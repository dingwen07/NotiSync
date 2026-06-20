package net.extrawdw.apps.notisync.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.WsAuth
import net.extrawdw.notisync.protocol.WsChallenge
import net.extrawdw.notisync.protocol.WsKind
import net.extrawdw.notisync.protocol.WsMessage
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import java.util.Base64

/**
 * Ktor-based client implementing the transport-neutral [Transport]. The control plane is plain HTTP
 * with CBOR bodies; live delivery uses an authenticated WebSocket (the dev push transport). FCM is
 * the production wake path and is handled separately by the messaging service + broker.
 */
class BrokerClient(
    private val signer: IdentitySigner,
    private val baseUrlProvider: () -> String,
) : Transport {

    override val type: TransportType = TransportType.WEBSOCKET

    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }

    private fun wsBase(): String = baseUrlProvider().trimEnd('/')
    private fun httpBase(): String = wsBase().replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")

    override suspend fun publishCard(card: SignedBlob) {
        client.post("${httpBase()}/v1/cards") { setBody(ProtocolCodec.encodeToCbor(card)) }
    }

    override suspend fun publishRoutes(routes: List<SignedBlob>) {
        if (routes.isEmpty()) return
        client.post("${httpBase()}/v1/routes") { setBody(ProtocolCodec.encodeToCbor(routes)) }
    }

    override suspend fun fetchCard(clientId: ClientId): SignedBlob? {
        val resp = client.get("${httpBase()}/v1/cards/${clientId.value}")
        return if (resp.status.isSuccess()) ProtocolCodec.decodeFromCbor<SignedBlob>(resp.readRawBytes()) else null
    }

    override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult {
        val resp = client.post("${httpBase()}/v1/send") { setBody(ProtocolCodec.encodeToCbor(envelope)) }
        return runCatching { ProtocolCodec.decodeFromJson<SendResult>(resp.bodyAsText()) }
            .getOrDefault(SendResult(accepted = false))
    }

    override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray): Boolean {
        val resp = client.post("${httpBase()}/v1/assets/${sourceClientId.value}/$assetId") { setBody(ciphertext) }
        // 200 stored or 409 already-exists both mean the broker holds it.
        return resp.status.isSuccess() || resp.status == HttpStatusCode.Conflict
    }

    override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? {
        val resp = client.get("${httpBase()}/v1/assets/${sourceClientId.value}/$assetId")
        return if (resp.status.isSuccess()) resp.readRawBytes() else null
    }

    /**
     * Live envelope stream over an authenticated WebSocket. Reconnects with backoff. The broker
     * challenges with a nonce; we prove control of the identity key by signing it.
     */
    override fun incoming(): Flow<Envelope> = channelFlow {
        var backoffMs = 1_000L
        while (!isClosedForSend) {
            try {
                client.webSocket("${wsBase()}/v1/connect") {
                    val challenge = ProtocolCodec.decodeFromJson<WsChallenge>((incoming.receive() as Frame.Text).readText())
                    val sig = Base64.getEncoder().encodeToString(signer.sign(challenge.nonce.toByteArray()))
                    send(Frame.Text(ProtocolCodec.encodeToJson(WsAuth(signer.clientId, challenge.nonce, sig))))
                    backoffMs = 1_000L
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val msg = runCatching { ProtocolCodec.decodeFromJson<WsMessage>(frame.readText()) }.getOrNull() ?: continue
                        if (msg.kind == WsKind.DELIVER && msg.envelopeB64 != null) {
                            val env = runCatching {
                                ProtocolCodec.decodeFromCbor<Envelope>(Base64.getDecoder().decode(msg.envelopeB64))
                            }.getOrNull() ?: continue
                            trySend(env)
                            send(Frame.Text(ProtocolCodec.encodeToJson(WsMessage(kind = WsKind.ACK, messageId = env.messageId))))
                        }
                    }
                }
            } catch (_: Exception) {
                // fall through to backoff/reconnect
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        awaitClose { }
    }

    fun close() = client.close()
}
