package net.extrawdw.apps.notisync.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.integrity.PlayIntegrityAttestor
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.PlayIntegrityVerificationRequest
import net.extrawdw.notisync.protocol.PlayIntegrityVerificationResponse
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
import net.extrawdw.notisync.protocol.crypto.HttpRequestSigning
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.PlayIntegrityBinding
import java.net.URI
import java.util.Base64

/**
 * Ktor-based client implementing the transport-neutral [Transport]. The control plane is plain HTTP
 * with CBOR bodies; live delivery uses an authenticated WebSocket (the dev push transport). FCM is
 * the production wake path and is handled separately by the messaging service + broker.
 */
class BrokerClient(
    private val signer: IdentitySigner,
    private val baseUrlProvider: () -> String,
    private val integrity: PlayIntegrityAttestor,
    private val clientCardProvider: () -> SignedBlob,
    private val debugKey: String,
) : Transport {

    override val type: TransportType = TransportType.WEBSOCKET

    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }
    private val authMutex = Mutex()

    @Volatile
    private var cachedAuth: PlayIntegrityVerificationResponse? = null
    @Volatile
    private var lastAuthFailure: AuthFailure? = null

    private fun wsBase(): String = baseUrlProvider().trimEnd('/')
    private fun httpBase(): String = wsBase().replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")

    override suspend fun publishCard(card: SignedBlob) {
        authedPost("${httpBase()}/v1/cards", ProtocolCodec.encodeToCbor(card))
    }

    override suspend fun publishRoutes(routes: List<SignedBlob>) {
        if (routes.isEmpty()) return
        authedPost("${httpBase()}/v1/routes", ProtocolCodec.encodeToCbor(routes))
    }

    override suspend fun fetchCard(clientId: ClientId): SignedBlob? {
        val resp = authedGet("${httpBase()}/v1/cards/${clientId.value}")
        return if (resp.status.isSuccess()) ProtocolCodec.decodeFromCbor<SignedBlob>(resp.readRawBytes()) else null
    }

    override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult {
        val resp = authedPost("${httpBase()}/v1/send", ProtocolCodec.encodeToCbor(envelope))
        return runCatching { ProtocolCodec.decodeFromJson<SendResult>(resp.bodyAsText()) }
            .getOrDefault(SendResult(accepted = false))
    }

    override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray): Boolean {
        val resp = authedPost("${httpBase()}/v1/assets/${sourceClientId.value}/$assetId", ciphertext)
        // 200 stored or 409 already-exists both mean the broker holds it.
        return resp.status.isSuccess() || resp.status == HttpStatusCode.Conflict
    }

    override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? {
        val resp = authedGet("${httpBase()}/v1/assets/${sourceClientId.value}/$assetId")
        return if (resp.status.isSuccess()) resp.readRawBytes() else null
    }

    private suspend fun authedGet(url: String): HttpResponse {
        var token = bearerToken()
        var resp = client.get(url) { signedHeaders("GET", url, ByteArray(0), token) }
        if (resp.status == HttpStatusCode.Unauthorized) {
            cachedAuth = null
            token = bearerToken()
            resp = client.get(url) { signedHeaders("GET", url, ByteArray(0), token) }
        }
        return resp
    }

    private suspend fun authedPost(url: String, body: ByteArray, contentType: ContentType? = null): HttpResponse {
        var token = bearerToken()
        var resp = client.post(url) {
            contentType?.let { contentType(it) }
            signedHeaders("POST", url, body, token)
            setBody(body)
        }
        if (resp.status == HttpStatusCode.Unauthorized) {
            cachedAuth = null
            token = bearerToken()
            resp = client.post(url) {
                contentType?.let { contentType(it) }
                signedHeaders("POST", url, body, token)
                setBody(body)
            }
        }
        return resp
    }

    private suspend fun bearerToken(): String {
        val now = System.currentTimeMillis()
        cachedAuth?.takeIf { it.expiresAt - now > AUTH_REFRESH_SKEW_MS }?.let { return it.token }
        lastAuthFailure?.takeIf { now - it.atMillis < AUTH_FAILURE_COOLDOWN_MS }?.let {
            error("Play Integrity verification cooling down after ${it.message}")
        }
        return authMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            cachedAuth?.takeIf { it.expiresAt - lockedNow > AUTH_REFRESH_SKEW_MS }?.let { return@withLock it.token }
            lastAuthFailure?.takeIf { lockedNow - it.atMillis < AUTH_FAILURE_COOLDOWN_MS }?.let {
                error("Play Integrity verification cooling down after ${it.message}")
            }
            runCatching { verifyIntegrity() }
                .onSuccess { lastAuthFailure = null }
                .onFailure { lastAuthFailure = AuthFailure(System.currentTimeMillis(), it.message ?: it.javaClass.simpleName) }
                .getOrThrow()
                .also { cachedAuth = it }
                .token
        }
    }

    private suspend fun verifyIntegrity(): PlayIntegrityVerificationResponse {
        val requestNonce = HttpRequestSigning.newNonce()
        val requestHash = PlayIntegrityBinding.requestHash(signer.clientId, requestNonce)
        val integrityToken = integrity.requestToken(requestHash)
        val request = PlayIntegrityVerificationRequest(
            clientId = signer.clientId,
            requestNonce = requestNonce,
            requestHash = requestHash,
            integrityToken = integrityToken,
            clientCard = clientCardProvider(),
            debugProof = PlayIntegrityBinding.debugProof(debugKey, signer.clientId, requestNonce, requestHash),
        )
        val body = ProtocolCodec.encodeToJson(request).toByteArray(Charsets.UTF_8)
        val url = "${httpBase()}/v1/integrity/verify"
        val resp = client.post(url) {
            contentType(ContentType.Application.Json)
            signedHeaders("POST", url, body, bearerToken = null)
            setBody(body)
        }
        if (!resp.status.isSuccess()) error("Play Integrity verification failed: ${resp.status} ${resp.bodyAsText()}")
        return ProtocolCodec.decodeFromJson(resp.bodyAsText())
    }

    private fun HttpRequestBuilder.signedHeaders(method: String, url: String, body: ByteArray, bearerToken: String?) {
        bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        val signed = HttpRequestSigning.sign(signer, method, pathAndQuery(url), body)
        header(HttpRequestSigning.HEADER_CLIENT_ID, signed.clientId.value)
        header(HttpRequestSigning.HEADER_TIMESTAMP, signed.timestampMillis.toString())
        header(HttpRequestSigning.HEADER_NONCE, signed.nonce)
        header(HttpRequestSigning.HEADER_CONTENT_SHA256, signed.contentSha256)
        header(HttpRequestSigning.HEADER_SIGNATURE, signed.signature)
    }

    private fun pathAndQuery(url: String): String =
        URI(url).let { uri ->
            buildString {
                append(uri.rawPath.ifBlank { "/" })
                if (!uri.rawQuery.isNullOrBlank()) append('?').append(uri.rawQuery)
            }
        }

    /**
     * Live envelope stream over an authenticated WebSocket. Reconnects with backoff. The broker
     * challenges with a nonce; we prove control of the identity key by signing it.
     */
    override fun incoming(): Flow<Envelope> = channelFlow {
        var backoffMs = 1_000L
        while (!isClosedForSend) {
            try {
                val url = "${wsBase()}/v1/connect"
                val token = bearerToken()
                client.webSocket(url, request = {
                    signedHeaders("GET", url, ByteArray(0), token)
                }) {
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

    private companion object {
        const val AUTH_REFRESH_SKEW_MS = 60_000L
        const val AUTH_FAILURE_COOLDOWN_MS = 60_000L
    }

    private data class AuthFailure(val atMillis: Long, val message: String)
}
