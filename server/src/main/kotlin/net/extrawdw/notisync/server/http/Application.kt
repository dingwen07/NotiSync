package net.extrawdw.notisync.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.ErrorResponse
import net.extrawdw.notisync.protocol.HealthResponse
import net.extrawdw.notisync.protocol.IntegrityVerificationRequest
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RelayAck
import net.extrawdw.notisync.protocol.RelayPending
import net.extrawdw.notisync.protocol.RouteClaim
import net.extrawdw.notisync.protocol.VerificationStatusResponse
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.WsAuth
import net.extrawdw.notisync.protocol.WsChallenge
import net.extrawdw.notisync.protocol.WsKind
import net.extrawdw.notisync.protocol.WsMessage
import net.extrawdw.notisync.server.ServerConfig
import net.extrawdw.notisync.server.auth.AuthResult
import net.extrawdw.notisync.server.auth.JwtIssuer
import net.extrawdw.notisync.server.auth.ServerAuth
import net.extrawdw.notisync.server.auth.SignatureCheck
import net.extrawdw.notisync.server.broker.Broker
import net.extrawdw.notisync.server.crypto.Verification
import net.extrawdw.notisync.server.delivery.WebSocketHub
import net.extrawdw.notisync.server.delivery.WsConnection
import net.extrawdw.notisync.server.delivery.push.CompositePushTransport
import net.extrawdw.notisync.server.integrity.AppCheckJwks
import net.extrawdw.notisync.server.integrity.AppCheckVerifier
import net.extrawdw.notisync.server.integrity.AttestationMetrics
import net.extrawdw.notisync.server.integrity.AttestationService
import net.extrawdw.notisync.server.integrity.IntegrityDecision
import net.extrawdw.notisync.server.data.EpochStore
import net.extrawdw.notisync.server.data.NotiSyncDb
import net.extrawdw.notisync.server.data.PrivateAssetStore
import net.extrawdw.notisync.server.data.RelayStore
import net.extrawdw.notisync.server.data.RouteStore
import net.extrawdw.notisync.server.demo.DemoExperience
import net.extrawdw.notisync.server.demo.DemoStartRequest
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

private val CBOR = ContentType("application", "cbor")
private val random = SecureRandom()

private const val MAX_VERIFY_BODY_BYTES = 64 * 1024
private const val MAX_CONTROL_BODY_BYTES = 1024 * 1024

/**
 * Read the request body, refusing (returning null) if it exceeds [maxBytes] — bounds pre-auth memory
 * use. Checks the declared Content-Length first, then hard-caps the actual read so a lying or chunked
 * client can't exceed the cap either.
 */
private suspend fun ApplicationCall.receiveCapped(maxBytes: Int): ByteArray? {
    val declared = request.headers["Content-Length"]?.toLongOrNull()
    if (declared != null && declared > maxBytes) return null
    val bytes = receiveStream().readNBytes(maxBytes + 1)
    return if (bytes.size > maxBytes) null else bytes
}

fun Application.module() = brokerModule()

/**
 * The broker application. [appCheckJwks] is a test seam for the App Check JWKS source; production passes
 * null and uses the live JWKS. Kept off [module] so Ktor's reflective module loader (application.yaml)
 * binds the no-arg entry point.
 */
fun Application.brokerModule(appCheckJwks: AppCheckJwks? = null) {
    val log = LoggerFactory.getLogger("NotiSyncBroker")
    val config = ServerConfig.fromEnv()
    val db = NotiSyncDb.connect(config)

    val routes = RouteStore(db)
    val relay = RelayStore(db)
    val assets = PrivateAssetStore(db)
    val epochs = EpochStore(db)
    val hub = WebSocketHub()
    val push = CompositePushTransport.create(config)
    val broker = Broker(routes, relay, assets, epochs, hub, push, config)
    val auth = ServerAuth(config, JwtIssuer.load(config))
    val demo = DemoExperience(broker, this, config.demoConfigPath)
    // Pluggable attestation: App Check is the live "way", added when configured. New methods (e.g. Apple App
    // Attest) join here without touching the endpoint or downstream. With no verifier configured, attestation
    // can't pass — fine when NOTISYNC_INTEGRITY_REQUIRED is off (a bearer is still issued to signed clients).
    val metrics = AttestationMetrics()
    val attestation = AttestationService(
        config,
        buildList {
            if (config.appCheckEnabled) {
                add(if (appCheckJwks != null) AppCheckVerifier(config, appCheckJwks) else AppCheckVerifier(config))
            }
        },
        metrics,
    )

    install(DefaultHeaders)
    install(CallLogging)
    install(WebSockets) {
        // Keepalive: ping idle clients so half-open connections are detected and the hub frees the
        // slot (and NAT paths stay open). The client also pings; either side noticing a dead peer
        // tears the socket down and the client reconnects.
        pingPeriod = 30.seconds
        timeout = 60.seconds
    }
    install(ContentNegotiation) { json(ProtocolCodec.json) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.warn("request failed: {}", cause.message)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error"))
        }
    }

    // Background GC of expired recoverable cache.
    launch {
        while (isActive) {
            delay(60L * 60 * 1000)
            runCatching { broker.purgeExpired() }
        }
    }

    log.info(
        "NotiSync broker {} starting (db={}, fcm={}, apns={}, security={}, integrityRequired={}, appCheck={})",
        config.version,
        config.dbPath,
        config.fcmEnabled,
        config.apnsEnabled,
        config.securityEnabled,
        config.integrityRequired,
        config.appCheckEnabled,
    )
    // Contradictory posture: the master switch wins. With security off, signed/JWT auth AND attestation are all
    // bypassed, so a requested integrity requirement can't be honored — surface it loudly rather than silently.
    if (!config.securityEnabled && config.integrityRequired) {
        log.warn(
            "NOTISYNC_INTEGRITY_REQUIRED=true is IGNORED because NOTISYNC_SECURITY_ENABLED=false — the master " +
                "switch bypasses all signed/JWT auth and attestation. Set NOTISYNC_SECURITY_ENABLED=true to enforce integrity.",
        )
    }

    routing {
        // Liveness/readiness stay UNVERSIONED at the root: load balancers, container probes, uptime
        // monitors, and BrokerClient hit /healthz and /readyz directly (see docker-compose.yml, README).
        // They report process health, not the wire contract, so they must not move when the API version does.
        get("/healthz") { call.respond(HealthResponse("ok", config.version)) }
        get("/readyz") { call.respond(HealthResponse("ready", config.version)) }

        // Experience Mode: an iOS client POSTs its pairing URL, the server trusts that public card for this
        // ephemeral session, returns a synthetic demo client's pairing URL, then sends a short burst of
        // regular E2E-encrypted notifications through the broker.
        post("/demo") {
            val body = call.receiveCapped(MAX_VERIFY_BODY_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
            val req = runCatching {
                ProtocolCodec.decodeFromJson<DemoStartRequest>(body.toString(Charsets.UTF_8))
            }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_demo_request"))
            val response = runCatching { demo.start(req.pairingUrl) }.getOrElse {
                log.info("demo start rejected: {}", it.message)
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_pairing"))
            }
            call.respond(response)
        }

        // Clean NS2 API — everything version-specific (its own JWT key, the NS2 wire contract) is served
        // under /v2 (the legacy NS1 JAR owns /v1). No NS1 code path.
        route("/v2") {
        get("/.well-known/jwks.json") { call.respondText(auth.jwksJson(), ContentType.Application.Json) }

        // Attestation metrics (HTTP Basic Auth; 404 when NOTISYNC_METRICS_PASSWORD is unset). Grouped under
        // /integrity alongside /integrity/verify. Per-30-min buckets + a recent-events ring — diagnostics, and
        // watching App Check accepts climb before requiring integrity. At <host>/v2/integrity/metrics.
        get("/integrity/metrics") {
            if (config.metricsPassword.isBlank()) return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
            if (!auth.metricsAuthorized(call)) {
                call.response.headers.append(HttpHeaders.WWWAuthenticate, "Basic realm=\"notisync-metrics\"")
                return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
            }
            call.respond(metrics.snapshot())
        }

        // Unauthenticated discovery: a client learns whether the broker is secured (`securityEnabled`) and
        // whether a passing integrity attestation is required (`integrityRequired`), and — if it presents a
        // bearer — whether that token is currently valid (so it can decide to re-attest). `playIntegrityRequired`
        // is the legacy alias for `securityEnabled`, emitted unchanged for older clients (pending removal).
        get("/status") {
            val principal = auth.bearerPrincipal(call)
            call.respond(
                VerificationStatusResponse(
                    version = config.version,
                    playIntegrityRequired = config.securityEnabled,
                    verified = principal != null,
                    clientId = principal?.clientId,
                    expiresAt = principal?.expiresAtMillis,
                    powDifficulty = config.powDifficulty,
                    acceptedAttestationMethods = attestation.acceptedMethods,
                    integrityRequired = config.integrityRequired,
                    securityEnabled = config.securityEnabled,
                )
            )
        }

        post("/integrity/verify") {
            val body = call.receiveCapped(MAX_VERIFY_BODY_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
            val req = runCatching {
                ProtocolCodec.decodeFromJson<IntegrityVerificationRequest>(body.toString(Charsets.UTF_8))
            }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_integrity_request"))

            // Identity is learned from the self-authenticating key-epoch (first contact / key refresh).
            val keyEpoch = req.clientKeyEpoch?.let { Verification.verifyKeyEpoch(it) }
            if (req.clientKeyEpoch != null && keyEpoch == null) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_key_epoch"))
            }
            if (keyEpoch != null && keyEpoch.clientId != req.clientId) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("key_epoch_client_mismatch"))
            }
            // A verified key-epoch carries the identity (a stripped one was already rejected above); fall back
            // to the stored identity otherwise. takeIf guards against an empty anchor → unknown_client, never empty.
            val signerSpki = keyEpoch?.identityPublicKey?.takeIf { it.isNotEmpty() } ?: broker.clientSpki(req.clientId)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown_client"))
            // The attestation request is signed by the IDENTITY key (signerEpoch 0).
            when (val sig = auth.verifySignedRequest(call, body, req.clientId, signerSpki)) {
                SignatureCheck.Accepted -> Unit
                is SignatureCheck.Rejected -> return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse(sig.reason))
            }

            // First-contact verification must carry a proof of work; this gates attestation + the bearer mint
            // against unauthenticated floods. A refresh that presents a still-valid bearer for this client
            // skips PoW but is still re-attested below.
            val refreshing = auth.bearerPrincipal(call)?.clientId == req.clientId
            if (!refreshing) {
                auth.checkProofOfWork(call)?.let {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(it))
                }
            }

            // The verifier's real verdict is always recorded (metrics); whether a rejection BLOCKS the bearer
            // is policy. Only NOTISYNC_INTEGRITY_REQUIRED makes a failed/missing attestation fatal; otherwise a
            // validly-signed client still gets a bearer (safe posture while a method like App Check is rolled
            // out, or where some clients can't attest). Request signing + PoW still gated this endpoint above.
            val decision = attestation.verify(req)
            if (decision is IntegrityDecision.Rejected && config.integrityRequired) {
                return@post call.respond(
                    if (decision.retryable) HttpStatusCode.TooManyRequests else HttpStatusCode.Forbidden,
                    ErrorResponse(decision.reason),
                )
            }

            // Persist the carried key-epoch BEFORE minting a token. A non-null blob that fails to store
            // (stale/below-floor, or an identity-pin mismatch) must not silently yield a bearer the client
            // would then use with a signer epoch the broker never recorded.
            val ke = req.clientKeyEpoch
            if (ke != null && !broker.uploadKeyEpoch(ke)) {
                return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("stale_key_epoch"))
            }
            val issued = auth.issue(req.clientId)
            call.respond(
                HttpStatusCode.OK,
                IntegrityVerificationResponse(
                    token = issued.token,
                    clientId = req.clientId,
                    expiresAt = issued.expiresAtMillis,
                ),
            )
        }

        // Publish/rotate a self-contained key-epoch (the server's identity + operational-key source).
        post("/keyepoch") {
            val body = call.receiveCapped(MAX_CONTROL_BODY_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
            val principal = auth.requireJwtSigned(call, body, broker) ?: return@post
            val blob = runCatching { ProtocolCodec.decodeFromCbor<SignedBlob>(body) }.getOrNull()
            if (config.securityEnabled && blob?.signerId != principal.clientId) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("client_mismatch"))
            }
            if (blob == null || !broker.uploadKeyEpoch(blob)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_key_epoch"))
            } else {
                call.respond(HttpStatusCode.OK, HealthResponse("stored", config.version))
            }
        }

        // Key-epoch pull: serve a client's current (or a specific ?epoch=N) operational key-epoch so peers
        // learn rotated keys. Self-verifying (carries the identity key), so the caller re-checks it.
        get("/keyepoch/{clientId}") {
            auth.requireJwtSigned(call, ByteArray(0), broker) ?: return@get
            val id = ClientId(call.parameters["clientId"].orEmpty())
            val epoch = call.request.queryParameters["epoch"]?.toIntOrNull()
            val bytes = broker.getKeyEpoch(id, epoch)
            if (bytes == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown_epoch"))
            else call.respondBytes(bytes, CBOR)
        }

        post("/routes") {
            val body = call.receiveCapped(MAX_CONTROL_BODY_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
            val list = runCatching {
                ProtocolCodec.decodeFromCbor<List<SignedBlob>>(body)
            }.getOrNull()
            if (list == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_routes"))
                return@post
            }
            val hasReset = list.any { blob ->
                blob.typ == SignedType.ROUTE_CLAIM &&
                    runCatching { blob.decode<RouteClaim>().routeRef.isEmpty() }.getOrDefault(false)
            }
            val principal = if (hasReset) {
                auth.requireJwtIdentitySigned(call, body, broker) ?: return@post
            } else {
                auth.requireJwtSigned(call, body, broker) ?: return@post
            }
            if (config.securityEnabled && list.any { it.signerId != principal.clientId }) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("client_mismatch"))
            } else {
                val accepted = broker.uploadRoutes(list)
                call.respond(HttpStatusCode.OK, HealthResponse("accepted:$accepted", config.version))
            }
        }

        post("/send") {
            val bytes = call.receiveCapped(MAX_CONTROL_BODY_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
            val principal = auth.requireJwtSigned(call, bytes, broker) ?: return@post
            val envelope = runCatching { ProtocolCodec.decodeFromCbor<Envelope>(bytes) }.getOrNull()
            if (envelope == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_envelope"))
            } else if (config.securityEnabled && envelope.signerId != principal.clientId) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("client_mismatch"))
            } else {
                call.respond(HttpStatusCode.OK, broker.send(bytes, envelope))
            }
        }

        // List the caller's queued message ids (signature-only, no JWT). Background drain backstops pull this,
        // then fetch each item below. A cheap, low-frequency catch-all for wakes that FCM/APNs deferred or whose
        // foreground WebSocket delivery failed. `?typ=DISMISSAL` narrows to queued dismissals — the iOS NSE's
        // piggyback drain pulls exactly those; DATA_SYNC and the notification backlog stay for the app's paths.
        get("/relay") {
            val principal = auth.requireSigned(call, ByteArray(0), broker) ?: return@get
            val typ = call.request.queryParameters["typ"]
                ?.let { value -> MessageType.entries.firstOrNull { it.name == value } }
            call.respond(RelayPending(broker.pendingMessageIds(principal.clientId, typ)))
        }

        // Single-message relay pull for background-wake paths. When a message is too large to inline, the
        // broker sends a wake pointer ("mid") and the client fetches exactly that one envelope here.
        // Default/legacy behavior is ack-on-fetch; clients that need ack-after-handle can send Peek: true and
        // explicitly POST /v2/relay/ack after durable handling. Authenticated by request SIGNATURE ALONE (no JWT
        // bearer) so a background wake still works when the client's attestation token has lapsed.
        get("/relay/{messageId}") {
            val messageId = call.parameters["messageId"].orEmpty()
            val ackOnFetch = call.request.headers["Peek"]?.equals("true", ignoreCase = true) != true
            val principal = auth.requireSigned(call, ByteArray(0), broker) ?: return@get
            val envelope = broker.relayMessage(principal.clientId, messageId)
            if (envelope == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown_message"))
            } else {
                call.respondBytes(envelope, CBOR)
                if (ackOnFetch) {
                    // Ack only after the bytes are flushed; a failed send leaves the item for the next WS
                    // flush. Best-effort — a failed ack just re-delivers later (the client dedups by id).
                    runCatching { broker.ack(principal.clientId, messageId) }
                }
            }
        }

        // Batch-ack: drop many of the caller's queued messages in one signed request. Signature-only
        // (no JWT) like the GET paths, so a background drain job can ack even while attestation is
        // cooling down. The body is the signed JSON RelayAck — the signature commits to those exact
        // bytes. Used for deliveries the broker can't observe being consumed: inline pushes (the envelope
        // rides in the push, so it's never fetched here), ack-after-handle relay fetches, and local mirror
        // dismissal.
        post("/relay/ack") {
            val bytes = call.receiveCapped(MAX_CONTROL_BODY_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
            val principal = auth.requireSigned(call, bytes, broker) ?: return@post
            val ack = runCatching { ProtocolCodec.decodeFromJson<RelayAck>(bytes.decodeToString()) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_ack"))
            val removed = broker.ackMany(principal.clientId, ack.messageIds)
            log.info("relay ack client={} requested={} removed={}", principal.clientId.shortForm(), ack.messageIds.size, removed)
            call.respondText("ok", status = HttpStatusCode.OK)
        }

        // Opaque private-asset blobs keyed by random (sourceClientId, assetId). The body is AEAD
        // ciphertext the broker cannot read; the id+key are E2E-delivered inside the notification.
        post("/assets/{sourceClientId}/{assetId}") {
            val src = ClientId(call.parameters["sourceClientId"].orEmpty())
            val assetId = call.parameters["assetId"].orEmpty()
            // Reject oversized uploads before buffering the whole body.
            val declared = call.request.headers["Content-Length"]?.toLongOrNull()
            if (declared != null && declared > config.maxPrivateAssetBytes) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
            }
            val bytes = call.receiveStream().readBytes()
            val principal = auth.requireJwtSigned(call, bytes, broker) ?: return@post
            if (config.securityEnabled && src != principal.clientId) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("client_mismatch"))
            }
            if (bytes.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("empty_asset"))
            when (broker.storeAsset(src, assetId, bytes)) {
                Broker.AssetStoreOutcome.STORED -> call.respond(HttpStatusCode.OK, HealthResponse("stored", config.version))
                Broker.AssetStoreOutcome.EXISTS -> call.respond(HttpStatusCode.Conflict, ErrorResponse("asset_exists"))
                Broker.AssetStoreOutcome.TOO_LARGE -> call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
                Broker.AssetStoreOutcome.BAD_ID -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_asset_id"))
            }
        }

        get("/assets/{sourceClientId}/{assetId}") {
            auth.requireJwtSigned(call, ByteArray(0), broker) ?: return@get
            val src = ClientId(call.parameters["sourceClientId"].orEmpty())
            val bytes = broker.fetchAsset(src, call.parameters["assetId"].orEmpty())
            if (bytes == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown_asset"))
            else call.respondBytes(bytes, ContentType.Application.OctetStream)
        }

        webSocket("/connect") {
            val principal = when (val result = auth.authenticateJwtSigned(call, ByteArray(0), broker)) {
                is AuthResult.Accepted -> result.principal
                is AuthResult.Rejected -> return@webSocket close(
                    CloseReason(CloseReason.Codes.VIOLATED_POLICY, result.reason)
                )
            }
            // 1. Challenge the client to prove control of its signing key.
            val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also { random.nextBytes(it) })
            send(Frame.Text(ProtocolCodec.encodeToJson(WsChallenge(nonce))))

            val authFrame = incoming.receiveCatching().getOrNull() as? Frame.Text
            val auth = authFrame?.let { runCatching { ProtocolCodec.decodeFromJson<WsAuth>(it.readText()) }.getOrNull() }
            if (auth == null || auth.nonce != nonce || (config.securityEnabled && auth.clientId != principal.clientId)) {
                return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth_failed"))
            }
            // epoch 0 = identity (root) key, ≥1 = the operational key of that key-epoch.
            val spki = (if (auth.epoch == 0) broker.clientSpki(auth.clientId) else broker.operationalSpki(auth.clientId, auth.epoch))
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unknown_client"))
            val sig = runCatching { Base64.getDecoder().decode(auth.signatureB64) }.getOrNull()
            if (sig == null || !Verification.verifyDetached(spki, nonce.toByteArray(), sig)) {
                return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad_signature"))
            }

            val conn = WsConnection(auth.clientId, this)
            hub.register(conn)
            log.info("ws connected client={}", auth.clientId.shortForm())
            try {
                // Flush anything queued while this client was offline.
                broker.flushPending(auth.clientId) { frameJson -> send(Frame.Text(frameJson)) }
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val msg = runCatching { ProtocolCodec.decodeFromJson<WsMessage>(frame.readText()) }.getOrNull()
                        when (msg?.kind) {
                            WsKind.ACK -> msg.messageId?.let { broker.ack(auth.clientId, it) }
                            WsKind.PING -> send(Frame.Text(ProtocolCodec.encodeToJson(WsMessage(kind = WsKind.PONG))))
                        }
                    }
                }
            } finally {
                hub.unregister(conn)
                log.info("ws disconnected client={}", auth.clientId.shortForm())
            }
        }
    } }
}
