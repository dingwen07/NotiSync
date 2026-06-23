package net.extrawdw.notisync.server

import io.ktor.http.ContentType
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
import net.extrawdw.notisync.protocol.PlayIntegrityVerificationRequest
import net.extrawdw.notisync.protocol.PlayIntegrityVerificationResponse
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RelayAck
import net.extrawdw.notisync.protocol.RelayPending
import net.extrawdw.notisync.protocol.VerificationStatusResponse
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.WsAuth
import net.extrawdw.notisync.protocol.WsChallenge
import net.extrawdw.notisync.protocol.WsKind
import net.extrawdw.notisync.protocol.WsMessage
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
 * The broker application. [decoder] is a test seam for the Play Integrity decoder; production passes
 * null and uses the real Google-backed decoder. It is kept off [module] so Ktor's reflective module
 * loader (application.yaml) binds the no-arg entry point.
 */
fun Application.brokerModule(decoder: PlayIntegrityDecoder? = null) {
    val log = LoggerFactory.getLogger("NotiSyncBroker")
    val config = ServerConfig.fromEnv()
    val db = NotiSyncDb.connect(config)

    val routes = RouteStore(db)
    val relay = RelayStore(db)
    val assets = PrivateAssetStore(db)
    val epochs = EpochStore(db)
    val hub = WebSocketHub()
    val push = FcmPushTransport.createOrNull(config) ?: DisabledPushTransport
    val broker = Broker(routes, relay, assets, epochs, hub, push, config)
    val auth = ServerAuth(config, JwtIssuer.load(config))
    val integrity = if (decoder != null) PlayIntegrityVerifier(config, decoder) else PlayIntegrityVerifier(config)

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
        "NotiSync broker {} starting (db={}, fcm={}, playIntegrity={})",
        config.version,
        config.dbPath,
        push !is DisabledPushTransport,
        config.playIntegrityEnabled,
    )

    routing {
        // Liveness/readiness stay UNVERSIONED at the root: load balancers, container probes, uptime
        // monitors, and BrokerClient hit /healthz and /readyz directly (see docker-compose.yml, README).
        // They report process health, not the wire contract, so they must not move when the API version does.
        get("/healthz") { call.respond(HealthResponse("ok", config.version)) }
        get("/readyz") { call.respond(HealthResponse("ready", config.version)) }

        // Clean NS2 API — everything version-specific (its own JWT key, the NS2 wire contract) is served
        // under /v2 (the legacy NS1 JAR owns /v1). No NS1 code path.
        route("/v2") {
        get("/.well-known/jwks.json") { call.respondText(auth.jwksJson(), ContentType.Application.Json) }

        // Unauthenticated discovery: a client learns whether attestation is required, and — if it
        // presents a bearer — whether that token is currently valid (so it can decide to re-attest).
        get("/status") {
            val principal = auth.bearerPrincipal(call)
            call.respond(
                VerificationStatusResponse(
                    version = config.version,
                    playIntegrityRequired = config.playIntegrityEnabled,
                    verified = principal != null,
                    clientId = principal?.clientId,
                    expiresAt = principal?.expiresAtMillis,
                )
            )
        }

        post("/integrity/verify") {
            val body = call.receiveCapped(MAX_VERIFY_BODY_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
            val req = runCatching {
                ProtocolCodec.decodeFromJson<PlayIntegrityVerificationRequest>(body.toString(Charsets.UTF_8))
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

            // First-contact verification must carry a proof of work; this gates the billed Play
            // Integrity decode against unauthenticated floods. A refresh that presents a still-valid
            // bearer for this client skips PoW but is still re-attested below.
            val refreshing = auth.bearerPrincipal(call)?.clientId == req.clientId
            if (!refreshing) {
                auth.checkProofOfWork(call)?.let {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(it))
                }
            }

            when (val decision = integrity.verify(req)) {
                is IntegrityDecision.Rejected -> return@post call.respond(
                    if (decision.retryable) HttpStatusCode.TooManyRequests else HttpStatusCode.Forbidden,
                    ErrorResponse(decision.reason),
                )
                is IntegrityDecision.Accepted -> {
                    // Persist the carried key-epoch BEFORE minting a token. A non-null blob that fails to
                    // store (stale/below-floor, or an identity-pin mismatch) must not silently yield a
                    // bearer the client would then use with a signer epoch the broker never recorded.
                    val ke = req.clientKeyEpoch
                    if (ke != null && !broker.uploadKeyEpoch(ke)) {
                        return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("stale_key_epoch"))
                    }
                    val issued = auth.issue(req.clientId)
                    call.respond(
                        HttpStatusCode.OK,
                        PlayIntegrityVerificationResponse(
                            token = issued.token,
                            clientId = req.clientId,
                            expiresAt = issued.expiresAtMillis,
                        ),
                    )
                }
            }
        }

        // Publish/rotate a self-contained key-epoch (the server's identity + operational-key source).
        post("/keyepoch") {
            val body = call.receiveCapped(MAX_CONTROL_BODY_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
            val principal = auth.requireJwtSigned(call, body, broker) ?: return@post
            val blob = runCatching { ProtocolCodec.decodeFromCbor<SignedBlob>(body) }.getOrNull()
            if (config.playIntegrityEnabled && blob?.signerId != principal.clientId) {
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
            val principal = auth.requireJwtSigned(call, body, broker) ?: return@post
            val list = runCatching {
                ProtocolCodec.decodeFromCbor<List<SignedBlob>>(body)
            }.getOrNull()
            if (list == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_routes"))
            } else if (config.playIntegrityEnabled && list.any { it.signerId != principal.clientId }) {
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
            } else if (config.playIntegrityEnabled && envelope.signerId != principal.clientId) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("client_mismatch"))
            } else {
                call.respond(HttpStatusCode.OK, broker.send(bytes, envelope))
            }
        }

        // List the caller's queued message ids (signature-only, no JWT). The WorkManager drain backstop
        // pulls this, then fetches + acks each via GET /v2/relay/{id} below. A cheap, low-frequency
        // catch-all for wakes that FCM deferred (normal priority) or whose foreground fetch failed.
        get("/relay") {
            val principal = auth.requireSigned(call, ByteArray(0), broker) ?: return@get
            call.respond(RelayPending(broker.pendingMessageIds(principal.clientId)))
        }

        // Single-message relay pull for the FCM background-wake path. When a notification is too large
        // to inline in the FCM data message, the broker sends a wake pointer ("mid") and the client
        // fetches exactly that one envelope here, then the broker drops it. Authenticated by request
        // SIGNATURE ALONE (no JWT bearer) so a background wake still works when the client's attestation
        // token has lapsed — it can always sign with its identity key. The body is opaque ciphertext.
        get("/relay/{messageId}") {
            val messageId = call.parameters["messageId"].orEmpty()
            val principal = auth.requireSigned(call, ByteArray(0), broker) ?: return@get
            val envelope = broker.relayMessage(principal.clientId, messageId)
            if (envelope == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown_message"))
            } else {
                call.respondBytes(envelope, CBOR)
                // Ack only after the bytes are flushed; a failed send leaves the item for the next WS
                // flush. Best-effort — a failed ack just re-delivers later (the client dedups by id).
                runCatching { broker.ack(principal.clientId, messageId) }
            }
        }

        // Batch-ack: drop many of the caller's queued messages in one signed request. Signature-only
        // (no JWT) like the GET paths, so a background WorkManager run can ack even while attestation is
        // cooling down. The body is the signed JSON RelayAck — the signature commits to those exact
        // bytes. Used for deliveries the broker can't observe being consumed: FCM-inline pushes (the
        // envelope rides in the push, so it's never fetched here) and a local mirror dismissal.
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
            if (config.playIntegrityEnabled && src != principal.clientId) {
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
            if (auth == null || auth.nonce != nonce || (config.playIntegrityEnabled && auth.clientId != principal.clientId)) {
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
