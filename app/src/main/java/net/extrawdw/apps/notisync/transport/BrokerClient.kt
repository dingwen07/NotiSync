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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.integrity.PlayIntegrityAttestor
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.HealthResponse
import net.extrawdw.notisync.protocol.PlayIntegrityVerificationRequest
import net.extrawdw.notisync.protocol.PlayIntegrityVerificationResponse
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.Transport
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.VerificationStatusResponse
import net.extrawdw.notisync.protocol.WsAuth
import net.extrawdw.notisync.protocol.WsChallenge
import net.extrawdw.notisync.protocol.WsKind
import net.extrawdw.notisync.protocol.WsMessage
import net.extrawdw.notisync.protocol.crypto.HttpRequestSigning
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.PlayIntegrityBinding
import net.extrawdw.notisync.protocol.crypto.ProofOfWork
import java.net.URI
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

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
    private val tokenStore: AuthTokenStore,
    private val scope: CoroutineScope,
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
    private val refreshInFlight = AtomicBoolean(false)

    init {
        // The JWT is just a cached proof of Play Integrity (valid for days), so reuse a still-valid one
        // across process death — a force-stop/relaunch shouldn't force a fresh attestation. Ignore a token
        // minted for a different identity (signing key regenerated); it would only earn a 401 and re-attest.
        cachedAuth = runCatching { tokenStore.load() }.getOrNull()?.takeIf { it.clientId == signer.clientId }
    }

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

    /**
     * Query the broker's auth posture and this client's current token validity. Unauthenticated; sends
     * the cached token (if any) so the broker can report `verified`, but never triggers attestation —
     * safe for a UI/status poll. Returns null if the broker is unreachable.
     */
    suspend fun fetchVerificationStatus(): VerificationStatusResponse? {
        val resp = runCatching {
            client.get("${httpBase()}/v1/status") {
                cachedBearerForRefresh()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
        }.getOrNull() ?: return null
        return if (resp.status.isSuccess()) {
            runCatching { ProtocolCodec.decodeFromJson<VerificationStatusResponse>(resp.bodyAsText()) }.getOrNull()
        } else {
            null
        }
    }

    /** Liveness probe: GET /healthz, or null if the broker is unreachable or unhealthy. */
    suspend fun fetchHealth(): HealthResponse? {
        val resp = runCatching { client.get("${httpBase()}/healthz") }.getOrNull() ?: return null
        return if (resp.status.isSuccess()) {
            runCatching { ProtocolCodec.decodeFromJson<HealthResponse>(resp.bodyAsText()) }.getOrNull()
        } else {
            null
        }
    }

    // Auth is lazy: send the signed request with a best-effort token (none if attestation is
    // unavailable — a broker with Play Integrity disabled still accepts it), and only attest-or-throw
    // on a real 401. So the client works against an attestation-disabled broker without Play Integrity.
    private suspend fun authedGet(url: String): HttpResponse {
        var resp = client.get(url) { signedHeaders("GET", url, ByteArray(0), bearerTokenOrNull()) }
        if (resp.status == HttpStatusCode.Unauthorized) {
            storeAuth(null)
            val token = bearerToken()
            resp = client.get(url) { signedHeaders("GET", url, ByteArray(0), token) }
        }
        return resp
    }

    private suspend fun authedPost(url: String, body: ByteArray, contentType: ContentType? = null): HttpResponse {
        var resp = client.post(url) {
            contentType?.let { contentType(it) }
            signedHeaders("POST", url, body, bearerTokenOrNull())
            setBody(body)
        }
        if (resp.status == HttpStatusCode.Unauthorized) {
            storeAuth(null)
            val token = bearerToken()
            resp = client.post(url) {
                contentType?.let { contentType(it) }
                signedHeaders("POST", url, body, token)
                setBody(body)
            }
        }
        return resp
    }

    /** Cached token if comfortably unexpired (else null). Never does network. */
    private fun cachedBearerOrNull(): String? {
        val now = System.currentTimeMillis()
        return cachedAuth?.takeIf { it.expiresAt - now > AUTH_REFRESH_SKEW_MS }?.token
    }

    /** Cached token while still valid at all (even inside the refresh window) — proves a token refresh. */
    private fun cachedBearerForRefresh(): String? {
        val now = System.currentTimeMillis()
        return cachedAuth?.takeIf { it.expiresAt - now > 0 }?.token
    }

    /** Best-effort bearer: cached, else attempt attestation but return null (not throw) on failure or
     *  cooldown, so the request still goes out signed and a security-disabled broker accepts it. A cached
     *  token nearing expiry is served as-is while a background refresh renews it (stale-while-revalidate). */
    private suspend fun bearerTokenOrNull(): String? =
        cachedBearerOrNull()?.also { maybeProactiveRefresh() }
            ?: runCatching { bearerToken() }.getOrNull()

    private suspend fun bearerToken(): String {
        cachedBearerOrNull()?.let { return it }
        throwIfCoolingDown()
        return authMutex.withLock {
            cachedBearerOrNull()?.let { return@withLock it }
            throwIfCoolingDown()
            reverifyLocked().token
        }
    }

    /**
     * Stale-while-revalidate: when the cached token is still usable but within [AUTH_PROACTIVE_REFRESH_MS]
     * of expiry, kick off a background re-attestation so the token is renewed well before it lapses and no
     * caller ever blocks on Play Integrity. Deduplicated via [refreshInFlight] and best-effort — a failure
     * just records the usual cooldown and the still-valid token keeps serving. Covers every authed path
     * (HTTP sends, the live socket, and FCM/notification background work) since they all fetch here.
     */
    private fun maybeProactiveRefresh() {
        val auth = cachedAuth ?: return
        if (auth.expiresAt - System.currentTimeMillis() > AUTH_PROACTIVE_REFRESH_MS) return
        if (isCoolingDown()) return
        if (!refreshInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                authMutex.withLock {
                    // Another path may have refreshed (or started cooling down) while this was queued.
                    val current = cachedAuth
                    if (current != null && current.expiresAt - System.currentTimeMillis() > AUTH_PROACTIVE_REFRESH_MS) return@withLock
                    if (isCoolingDown()) return@withLock
                    runCatching { reverifyLocked() }
                }
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    /** Attest, then atomically cache and persist the new token. Caller holds [authMutex]; records a
     *  cooldown and rethrows on failure. */
    private suspend fun reverifyLocked(): PlayIntegrityVerificationResponse =
        runCatching { verifyIntegrity() }
            .onSuccess { lastAuthFailure = null }
            .onFailure {
                // Transient failures (network, server 429/5xx) get a short cooldown so a blip
                // doesn't block all transport for a minute; only definitive rejects back off long.
                val retryable = (it as? IntegrityException)?.retryable ?: true
                val cooldown = if (retryable) AUTH_RETRYABLE_COOLDOWN_MS else AUTH_FAILURE_COOLDOWN_MS
                lastAuthFailure = AuthFailure(System.currentTimeMillis(), it.message ?: it.javaClass.simpleName, cooldown)
            }
            .getOrThrow()
            .also { storeAuth(it) }

    /** Write-through: update the in-memory token and mirror it to encrypted storage (best-effort). */
    private fun storeAuth(value: PlayIntegrityVerificationResponse?) {
        cachedAuth = value
        runCatching { tokenStore.save(value) }
    }

    private fun isCoolingDown(): Boolean {
        val failure = lastAuthFailure ?: return false
        return System.currentTimeMillis() - failure.atMillis < failure.cooldownMs
    }

    private fun throwIfCoolingDown() {
        if (isCoolingDown()) error("Play Integrity verification cooling down after ${lastAuthFailure?.message}")
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
        // A still-valid token lets the broker treat this as a refresh (re-attests, skips proof of work).
        val refreshToken = cachedBearerForRefresh()
        val signed = HttpRequestSigning.sign(signer, "POST", pathAndQuery(url), body)
        val resp = client.post(url) {
            contentType(ContentType.Application.Json)
            applySigned(signed, bearerToken = refreshToken)
            if (refreshToken == null) {
                // First contact: solve the proof of work, bound to this request's signature.
                val powTimestamp = System.currentTimeMillis()
                val powNonce = ProofOfWork.solve(signed.signature, powTimestamp)
                header(ProofOfWork.HEADER_NONCE, powNonce)
                header(ProofOfWork.HEADER_TIMESTAMP, powTimestamp.toString())
            }
            setBody(body)
        }
        if (!resp.status.isSuccess()) {
            val retryable = resp.status == HttpStatusCode.TooManyRequests || resp.status.value >= 500
            throw IntegrityException("Play Integrity verification failed: ${resp.status} ${resp.bodyAsText()}", retryable)
        }
        return ProtocolCodec.decodeFromJson(resp.bodyAsText())
    }

    private fun HttpRequestBuilder.applySigned(signed: HttpRequestSigning.Headers, bearerToken: String?) {
        bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        header(HttpRequestSigning.HEADER_CLIENT_ID, signed.clientId.value)
        header(HttpRequestSigning.HEADER_TIMESTAMP, signed.timestampMillis.toString())
        header(HttpRequestSigning.HEADER_NONCE, signed.nonce)
        header(HttpRequestSigning.HEADER_CONTENT_SHA256, signed.contentSha256)
        header(HttpRequestSigning.HEADER_SIGNATURE, signed.signature)
    }

    private fun HttpRequestBuilder.signedHeaders(method: String, url: String, body: ByteArray, bearerToken: String?) =
        applySigned(HttpRequestSigning.sign(signer, method, pathAndQuery(url), body), bearerToken)

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
    @OptIn(DelicateCoroutinesApi::class) // for isClosedForSend on the channelFlow producer scope
    override fun incoming(): Flow<Envelope> = channelFlow {
        var backoffMs = 1_000L
        var consecutiveFailures = 0
        while (!isClosedForSend) {
            try {
                val url = "${wsBase()}/v1/connect"
                val token = bearerTokenOrNull()
                client.webSocket(url, request = {
                    signedHeaders("GET", url, ByteArray(0), token)
                }) {
                    val challenge = ProtocolCodec.decodeFromJson<WsChallenge>((incoming.receive() as Frame.Text).readText())
                    val sig = Base64.getEncoder().encodeToString(signer.sign(challenge.nonce.toByteArray()))
                    send(Frame.Text(ProtocolCodec.encodeToJson(WsAuth(signer.clientId, challenge.nonce, sig))))
                    backoffMs = 1_000L
                    consecutiveFailures = 0
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
                // A run of failed handshakes while we still hold a cached token may mean the broker
                // rotated its JWT key; drop it so the next attempt re-attests. (HTTP calls self-heal on 401.)
                if (++consecutiveFailures >= WS_REAUTH_AFTER_FAILURES) {
                    storeAuth(null)
                    consecutiveFailures = 0
                }
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        awaitClose { }
    }

    fun close() = client.close()

    private companion object {
        const val AUTH_REFRESH_SKEW_MS = 60_000L
        // Refresh a still-valid token once it's within a day of expiry (stale-while-revalidate), so the
        // multi-day JWT is renewed in the background long before any request would have to block to re-attest.
        const val AUTH_PROACTIVE_REFRESH_MS = 24L * 60 * 60 * 1000
        const val AUTH_FAILURE_COOLDOWN_MS = 60_000L
        const val AUTH_RETRYABLE_COOLDOWN_MS = 5_000L
        const val WS_REAUTH_AFTER_FAILURES = 3
    }

    private data class AuthFailure(val atMillis: Long, val message: String, val cooldownMs: Long)

    private class IntegrityException(message: String, val retryable: Boolean) : Exception(message)
}
