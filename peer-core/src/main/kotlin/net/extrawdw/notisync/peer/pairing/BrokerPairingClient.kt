package net.extrawdw.notisync.peer.pairing

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.extrawdw.notisync.protocol.BrokerPairing
import net.extrawdw.notisync.protocol.PairingRelayReady
import net.extrawdw.notisync.protocol.PairingRelayRequest
import net.extrawdw.notisync.protocol.ProtocolCodec

/** A short-lived connection owned by the pairing UI/CLI, separate from regular broker delivery. */
class BrokerPairingClient : AutoCloseable {
    private val http = HttpClient(OkHttp) {
        followRedirects = false
        engine { config { followRedirects(false); followSslRedirects(false) } }
        // OkHttp does not implement Ktor's maxFrameSize setter; validate every received message below.
        install(WebSockets)
    }

    suspend fun host(
        brokerUrl: String,
        ownPayload: String,
        onReady: (BrokerPairingLink) -> Unit,
    ): String {
        val broker = BrokerPairingLink.normalizeBroker(brokerUrl)
        val hostId = PairingPayloadCodec.verifyPayload(ownPayload).card.clientId.value
        val secret = BrokerPairingLink.generateSecret()
        return connect(broker, null) { ready ->
            val link = BrokerPairingLink(ready.sessionId, secret, hostId, broker)
            onReady(link)
            exchange(link, PairingRole.HOST, ownPayload)
        }
    }

    suspend fun join(link: BrokerPairingLink, ownPayload: String): String {
        PairingPayloadCodec.verifyPayload(ownPayload)
        return connect(link.brokerUrl, link.sessionId) { exchange(link, PairingRole.CLIENT, ownPayload) }
    }

    private suspend fun connect(
        broker: String,
        sessionId: String?,
        block: suspend WebSocketSession.(PairingRelayReady) -> String,
    ): String = withContext(Dispatchers.IO) {
        withTimeout(BrokerPairing.SESSION_MILLIS) {
            var result: String? = null
            val ws = broker.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            http.webSocket(urlString = ws + BrokerPairing.PATH) {
                send(Frame.Binary(true, ProtocolCodec.encodeToCbor(PairingRelayRequest(sessionId = sessionId))))
                val ready = withTimeout(10_000) { ProtocolCodec.decodeFromCbor<PairingRelayReady>(receiveBinary()) }
                require(ready.version == BrokerPairing.VERSION && BrokerPairing.validSessionId(ready.sessionId))
                require(sessionId == null || sessionId == ready.sessionId) { "Pairing session mismatch" }
                result = block(ready)
            }
            checkNotNull(result) { "Pairing ended without a CARD" }
        }
    }

    private suspend fun WebSocketSession.exchange(
        link: BrokerPairingLink,
        role: PairingRole,
        payload: String,
    ): String = PairingPake(link, role).use { pake ->
        send(Frame.Binary(true, pake.round1()))
        // The host may wait for a scan; once the peer starts, the entire exchange has a short deadline.
        val first = receiveBinary()
        withTimeout(BrokerPairing.EXCHANGE_MILLIS) {
            send(Frame.Binary(true, pake.round2(first)))
            send(Frame.Binary(true, pake.round3(receiveBinary())))
            pake.confirm(receiveBinary())
            send(Frame.Binary(true, pake.encryptCard(payload)))
            val received = pake.decryptCard(receiveBinary())
            val ownId = PairingPayloadCodec.verifyPayload(payload).card.clientId
            val peer = PairingPayloadCodec(ownId).decode(received).getOrThrow()
            if (role == PairingRole.CLIENT) {
                require(peer.card.clientId.value == link.hostId) { "Host CARD does not match the scanned identity" }
            }
            received
        }
    }

    private suspend fun WebSocketSession.receiveBinary(): ByteArray {
        val frame = incoming.receiveCatching().getOrNull()
            ?: throw IOException("Pairing closed or expired. Start a new session on the device showing the QR code.")
        require(frame is Frame.Binary && frame.fin && frame.data.size in 1..BrokerPairing.MAX_FRAME_BYTES) {
            "Invalid pairing frame"
        }
        return frame.data
    }

    override fun close() = http.close()
}
