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
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
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
import net.extrawdw.notisync.protocol.VerificationStatusResponse
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.WsAuth
import net.extrawdw.notisync.protocol.WsChallenge
import net.extrawdw.notisync.protocol.WsKind
import net.extrawdw.notisync.protocol.WsMessage
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64

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

    val cards = CardStore(db)
    val routes = RouteStore(db)
    val relay = RelayStore(db)
    val assets = PrivateAssetStore(db)
    val hub = WebSocketHub()
    val push = FcmPushTransport.createOrNull(config) ?: DisabledPushTransport
    val broker = Broker(cards, routes, relay, assets, hub, push, config)
    val auth = ServerAuth(config, JwtIssuer.load(config))
    val integrity = if (decoder != null) PlayIntegrityVerifier(config, decoder) else PlayIntegrityVerifier(config)

    install(DefaultHeaders)
    install(CallLogging)
    install(WebSockets)
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
        get("/healthz") { call.respond(HealthResponse("ok", config.version)) }
        get("/readyz") { call.respond(HealthResponse("ready", config.version)) }
        get("/.well-known/jwks.json") { call.respondText(auth.jwksJson(), ContentType.Application.Json) }

        // Unauthenticated discovery: a client learns whether attestation is required, and — if it
        // presents a bearer — whether that token is currently valid (so it can decide to re-attest).
        get("/v1/status") {
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

        post("/v1/integrity/verify") {
            val body = call.receiveCapped(MAX_VERIFY_BODY_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
            val req = runCatching {
                ProtocolCodec.decodeFromJson<PlayIntegrityVerificationRequest>(body.toString(Charsets.UTF_8))
            }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_integrity_request"))

            val verifiedCard = req.clientCard?.let { Verification.verifyClientCard(it) }
            if (req.clientCard != null && verifiedCard == null) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_card"))
            }
            if (verifiedCard != null && verifiedCard.clientId != req.clientId) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("card_client_mismatch"))
            }
            val signerSpki = verifiedCard?.identityPublicKey ?: broker.clientSpki(req.clientId)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown_client"))
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
                    req.clientCard?.let { broker.uploadCard(it) }
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

        post("/v1/cards") {
            val body = call.receiveCapped(MAX_CONTROL_BODY_BYTES)
                ?: return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too_large"))
            val principal = auth.requireJwtSigned(call, body, broker) ?: return@post
            val blob = runCatching { ProtocolCodec.decodeFromCbor<SignedBlob>(body) }.getOrNull()
            if (config.playIntegrityEnabled && blob?.signerId != principal.clientId) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("client_mismatch"))
            }
            if (blob == null || !broker.uploadCard(blob)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_card"))
            } else {
                call.respond(HttpStatusCode.OK, HealthResponse("stored", config.version))
            }
        }

        get("/v1/cards/{clientId}") {
            auth.requireJwtSigned(call, ByteArray(0), broker) ?: return@get
            val id = ClientId(call.parameters["clientId"].orEmpty())
            val bytes = broker.getCardBlob(id)
            if (bytes == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown_client"))
            else call.respondBytes(bytes, CBOR)
        }

        post("/v1/routes") {
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

        post("/v1/send") {
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

        // Opaque private-asset blobs keyed by random (sourceClientId, assetId). The body is AEAD
        // ciphertext the broker cannot read; the id+key are E2E-delivered inside the notification.
        post("/v1/assets/{sourceClientId}/{assetId}") {
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

        get("/v1/assets/{sourceClientId}/{assetId}") {
            auth.requireJwtSigned(call, ByteArray(0), broker) ?: return@get
            val src = ClientId(call.parameters["sourceClientId"].orEmpty())
            val bytes = broker.fetchAsset(src, call.parameters["assetId"].orEmpty())
            if (bytes == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown_asset"))
            else call.respondBytes(bytes, ContentType.Application.OctetStream)
        }

        webSocket("/v1/connect") {
            val principal = when (val result = auth.authenticateJwtSigned(call, ByteArray(0), broker)) {
                is AuthResult.Accepted -> result.principal
                is AuthResult.Rejected -> return@webSocket close(
                    CloseReason(CloseReason.Codes.VIOLATED_POLICY, result.reason)
                )
            }
            // 1. Challenge the client to prove control of its identity key.
            val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also { random.nextBytes(it) })
            send(Frame.Text(ProtocolCodec.encodeToJson(WsChallenge(nonce))))

            val authFrame = incoming.receiveCatching().getOrNull() as? Frame.Text
            val auth = authFrame?.let { runCatching { ProtocolCodec.decodeFromJson<WsAuth>(it.readText()) }.getOrNull() }
            if (auth == null || auth.nonce != nonce || (config.playIntegrityEnabled && auth.clientId != principal.clientId)) {
                return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth_failed"))
            }
            val spki = broker.clientSpki(auth.clientId)
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
    }
}
