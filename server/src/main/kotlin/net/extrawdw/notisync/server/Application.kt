package net.extrawdw.notisync.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
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
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.WsAuth
import net.extrawdw.notisync.protocol.WsChallenge
import net.extrawdw.notisync.protocol.WsKind
import net.extrawdw.notisync.protocol.WsMessage
import net.extrawdw.notisync.protocol.BlobUploadResponse
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64

private val CBOR = ContentType("application", "cbor")
private val random = SecureRandom()

fun Application.module() {
    val log = LoggerFactory.getLogger("NotiSyncBroker")
    val config = ServerConfig.fromEnv()
    val db = NotiSyncDb.connect(config)

    val cards = CardStore(db)
    val routes = RouteStore(db)
    val relay = RelayStore(db)
    val blobs = BlobStore(db)
    val hub = WebSocketHub()
    val push = FcmPushTransport.createOrNull(config) ?: DisabledPushTransport
    val broker = Broker(cards, routes, relay, blobs, hub, push, config)

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

    log.info("NotiSync broker {} starting (db={}, fcm={})", config.version, config.dbPath, push !is DisabledPushTransport)

    routing {
        get("/healthz") { call.respond(HealthResponse("ok", config.version)) }
        get("/readyz") { call.respond(HealthResponse("ready", config.version)) }

        post("/v1/cards") {
            val blob = runCatching { ProtocolCodec.decodeFromCbor<SignedBlob>(call.receiveStream().readBytes()) }.getOrNull()
            if (blob == null || !broker.uploadCard(blob)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_card"))
            } else {
                call.respond(HttpStatusCode.OK, HealthResponse("stored", config.version))
            }
        }

        get("/v1/cards/{clientId}") {
            val id = ClientId(call.parameters["clientId"].orEmpty())
            val bytes = broker.getCardBlob(id)
            if (bytes == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown_client"))
            else call.respondBytes(bytes, CBOR)
        }

        post("/v1/routes") {
            val list = runCatching {
                ProtocolCodec.decodeFromCbor<List<SignedBlob>>(call.receiveStream().readBytes())
            }.getOrNull()
            if (list == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_routes"))
            } else {
                val accepted = broker.uploadRoutes(list)
                call.respond(HttpStatusCode.OK, HealthResponse("accepted:$accepted", config.version))
            }
        }

        post("/v1/send") {
            val bytes = call.receiveStream().readBytes()
            val envelope = runCatching { ProtocolCodec.decodeFromCbor<Envelope>(bytes) }.getOrNull()
            if (envelope == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_envelope"))
            } else {
                call.respond(HttpStatusCode.OK, broker.send(bytes, envelope))
            }
        }

        post("/v1/blobs") {
            val bytes = call.receiveStream().readBytes()
            if (bytes.isEmpty()) call.respond(HttpStatusCode.BadRequest, ErrorResponse("empty_blob"))
            else call.respond(HttpStatusCode.OK, BlobUploadResponse(broker.storeBlob(bytes)))
        }

        get("/v1/blobs/{id}") {
            val bytes = broker.fetchBlob(call.parameters["id"].orEmpty())
            if (bytes == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown_blob"))
            else call.respondBytes(bytes, CBOR)
        }

        webSocket("/v1/connect") {
            // 1. Challenge the client to prove control of its identity key.
            val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also { random.nextBytes(it) })
            send(Frame.Text(ProtocolCodec.encodeToJson(WsChallenge(nonce))))

            val authFrame = incoming.receiveCatching().getOrNull() as? Frame.Text
            val auth = authFrame?.let { runCatching { ProtocolCodec.decodeFromJson<WsAuth>(it.readText()) }.getOrNull() }
            if (auth == null || auth.nonce != nonce) {
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
