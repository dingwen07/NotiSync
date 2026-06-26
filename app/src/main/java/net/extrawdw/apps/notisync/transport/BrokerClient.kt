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
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.integrity.AppCheckAttestor
import net.extrawdw.notisync.protocol.AttestationType
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.HealthResponse
import net.extrawdw.notisync.protocol.IntegrityVerificationRequest
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RelayAck
import net.extrawdw.notisync.protocol.RelayPending
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
import net.extrawdw.notisync.protocol.crypto.OperationalSigner
import net.extrawdw.notisync.protocol.crypto.ProofOfWork
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ktor-based client implementing the transport-neutral [Transport]. The control plane is plain HTTP
 * with CBOR bodies; live delivery uses an authenticated WebSocket (the dev push transport). FCM is
 * the production wake path and is handled separately by the messaging service + broker.
 */
class BrokerClient(
    private val signer: IdentitySigner,
    /** Provider for the current operational signer — signs the hot-path data plane (`send`, asset I/O) with
     *  signerEpoch ≥1; a provider so a rotation swaps the active epoch without rebuilding the client. */
    private val operationalSigner: () -> OperationalSigner,
    private val baseUrlProvider: () -> String,
    private val integrity: AppCheckAttestor,
    /** This device's current self-contained, identity-signed `ClientKeyEpoch` ([SignedType.KEY_EPOCH]) —
     *  carried on attestation so the broker learns our keys, and (re)published via [publishKeyEpoch]. */
    private val clientKeyEpochProvider: () -> SignedBlob,
    private val tokenStore: AuthTokenStore,
    private val scope: CoroutineScope,
) : Transport {

    override val type: TransportType = TransportType.WEBSOCKET

    private val client = HttpClient(OkHttp) {
        engine {
            // Keepalive at the OkHttp layer: ping the broker periodically so a half-open socket
            // (NAT/proxy idle drop) is detected — a missed pong fails the socket instead of the read
            // loop blocking forever, surfacing as the exception that drives reconnect in runLiveDelivery().
            config { pingInterval(WS_PING_SECONDS, TimeUnit.SECONDS) }
        }
        install(WebSockets)
    }
    private val authMutex = Mutex()

    @Volatile
    private var cachedAuth: IntegrityVerificationResponse? = null
    @Volatile
    private var lastAuthFailure: AuthFailure? = null
    private val refreshInFlight = AtomicBoolean(false)

    init {
        // The JWT is just a cached proof of a passing attestation (valid for days), so reuse a still-valid one
        // across process death — a force-stop/relaunch shouldn't force a fresh attestation. Ignore a token
        // minted for a different identity (signing key regenerated); it would only earn a 401 and re-attest.
        cachedAuth = runCatching { tokenStore.load() }.getOrNull()?.takeIf { it.clientId == signer.clientId }
    }

    private fun wsBase(): String = baseUrlProvider().trimEnd('/')
    private fun httpBase(): String = wsBase().replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")

    override suspend fun publishKeyEpoch(keyEpoch: SignedBlob) {
        // Identity-signed request (signerEpoch 0): the broker pins our identity from the first key-epoch and
        // verifies the blob's own identity signature. Always-valid root, so it works pre-convergence.
        authedPost("${httpBase()}/v2/keyepoch", ProtocolCodec.encodeToCbor(keyEpoch))
    }

    override suspend fun publishRoutes(routes: List<SignedBlob>) {
        if (routes.isEmpty()) return
        authedPost("${httpBase()}/v2/routes", ProtocolCodec.encodeToCbor(routes))
    }

    override suspend fun fetchKeyEpoch(clientId: ClientId, epoch: Int?): SignedBlob? {
        val q = if (epoch != null) "?epoch=$epoch" else ""
        val resp = authedGet("${httpBase()}/v2/keyepoch/${clientId.value}$q")
        return if (resp.status.isSuccess()) runCatching { ProtocolCodec.decodeFromCbor<SignedBlob>(resp.readRawBytes()) }.getOrNull() else null
    }

    override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult {
        // Operational-signed request (hot path): the envelope inside is already operational-signed; the
        // broker accepts an operational request signature (floor + window + REQUEST_AUTH) and self-heals to
        // identity attestation on a 401 if our epoch isn't yet known there.
        val resp = authedPost("${httpBase()}/v2/send", ProtocolCodec.encodeToCbor(envelope), operational = true)
        return runCatching { ProtocolCodec.decodeFromJson<SendResult>(resp.bodyAsText()) }
            .getOrDefault(SendResult(accepted = false))
    }

    override suspend fun uploadPrivateAsset(sourceClientId: ClientId, assetId: String, ciphertext: ByteArray): Boolean {
        val resp = authedPost("${httpBase()}/v2/assets/${sourceClientId.value}/$assetId", ciphertext, operational = true)
        // 200 stored or 409 already-exists both mean the broker holds it.
        return resp.status.isSuccess() || resp.status == HttpStatusCode.Conflict
    }

    override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? {
        val resp = authedGet("${httpBase()}/v2/assets/${sourceClientId.value}/$assetId", operational = true)
        return if (resp.status.isSuccess()) resp.readRawBytes() else null
    }

    /**
     * Pull a single relay message by id (the FCM-wake path: the broker stored a too-large notification
     * and pushed only a "mid" pointer). Authenticated by request SIGNATURE alone — it sends a cached
     * bearer if one happens to be valid but never triggers attestation, so a background wake delivers
     * even while Play Integrity is cooling down. The broker acks/drops the message once it responds;
     * returns null on any failure, leaving the item for the next foreground WebSocket flush.
     */
    suspend fun fetchRelayMessage(messageId: String): Envelope? {
        val url = "${httpBase()}/v2/relay/$messageId"
        val resp = runCatching {
            client.get(url) { signedHeaders("GET", url, ByteArray(0), cachedBearerOrNull()) }
        }.getOrNull() ?: return null
        if (!resp.status.isSuccess()) return null
        return runCatching { ProtocolCodec.decodeFromCbor<Envelope>(resp.readRawBytes()) }.getOrNull()
    }

    /**
     * List the message ids the broker currently has queued for us (signed-only GET, never triggers
     * attestation). The WorkManager drain backstop then pulls + acks each via [fetchRelayMessage].
     * Returns empty on any failure — the backstop simply tries again on its next run.
     */
    suspend fun fetchPendingRelayIds(): List<String> {
        val url = "${httpBase()}/v2/relay"
        val resp = runCatching {
            client.get(url) { signedHeaders("GET", url, ByteArray(0), cachedBearerOrNull()) }
        }.getOrNull() ?: return emptyList()
        if (!resp.status.isSuccess()) return emptyList()
        return runCatching { ProtocolCodec.decodeFromJson<RelayPending>(resp.bodyAsText()).messageIds }.getOrDefault(emptyList())
    }

    /**
     * Batch-ack [messageIds] so the broker drops them from our relay queue (signed-only POST, never
     * triggers attestation). The backstop for deliveries the broker can't see consumed: FCM-inline
     * pushes (delivered in the push, so never fetched) and locally-dismissed mirrors. Returns true only
     * on a 2xx — on any failure the caller KEEPS the ids and retries next run, so a dropped ack only
     * costs a later redelivery (the receiver dedups it), never a lost notification. No-op for an empty
     * list. The signature commits to the exact JSON bytes the server re-reads and verifies.
     */
    suspend fun ackRelayMessages(messageIds: List<String>): Boolean {
        if (messageIds.isEmpty()) return true
        val url = "${httpBase()}/v2/relay/ack"
        val body = ProtocolCodec.encodeToJson(RelayAck(messageIds)).toByteArray(Charsets.UTF_8)
        val resp = runCatching {
            client.post(url) {
                contentType(ContentType.Application.Json)
                signedHeaders("POST", url, body, cachedBearerOrNull())
                setBody(body)
            }
        }.getOrNull() ?: return false
        return resp.status.isSuccess()
    }

    /**
     * Query the broker's auth posture and this client's current token validity. Unauthenticated; sends
     * the cached token (if any) so the broker can report `verified`, but never triggers attestation —
     * safe for a UI/status poll. Returns null if the broker is unreachable.
     */
    suspend fun fetchVerificationStatus(): VerificationStatusResponse? {
        val resp = runCatching {
            client.get("${httpBase()}/v2/status") {
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
    private suspend fun authedGet(url: String, operational: Boolean = false): HttpResponse {
        var resp = client.get(url) { signedHeaders("GET", url, ByteArray(0), bearerTokenOrNull(), operational) }
        if (resp.status == HttpStatusCode.Unauthorized) {
            storeAuth(null)
            val token = bearerToken()
            resp = client.get(url) { signedHeaders("GET", url, ByteArray(0), token, operational) }
        }
        return resp
    }

    private suspend fun authedPost(url: String, body: ByteArray, contentType: ContentType? = null, operational: Boolean = false): HttpResponse {
        var resp = client.post(url) {
            contentType?.let { contentType(it) }
            signedHeaders("POST", url, body, bearerTokenOrNull(), operational)
            setBody(body)
        }
        if (resp.status == HttpStatusCode.Unauthorized) {
            storeAuth(null)
            val token = bearerToken()
            resp = client.post(url) {
                contentType?.let { contentType(it) }
                signedHeaders("POST", url, body, token, operational)
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
    private suspend fun reverifyLocked(): IntegrityVerificationResponse =
        runCatching { verifyIntegrity() }
            .onSuccess { lastAuthFailure = null }
            .onFailure {
                // Transient failures (network, server 429/5xx, stale token) start with a short cooldown so
                // a blip doesn't block all transport; a definitive reject starts long. Either way the
                // cooldown grows per consecutive failure (see [backoffCooldownMs]) so a sustained outage
                // backs off instead of re-attesting on a fixed interval. Runs under [authMutex], so the
                // read-modify-write of the attempt counter can't race.
                val retryable = (it as? IntegrityException)?.retryable ?: true
                val attempt = (lastAuthFailure?.attempt ?: 0) + 1
                val cooldown = backoffCooldownMs(retryable, attempt)
                lastAuthFailure = AuthFailure(System.currentTimeMillis(), it.message ?: it.javaClass.simpleName, cooldown, attempt)
            }
            .getOrThrow()
            .also { storeAuth(it) }

    /** Write-through: update the in-memory token and mirror it to encrypted storage (best-effort). */
    private fun storeAuth(value: IntegrityVerificationResponse?) {
        cachedAuth = value
        runCatching { tokenStore.save(value) }
    }

    /** Diagnostics: drop the cached broker bearer (memory + encrypted storage), forcing a fresh
     *  attestation on the next authenticated request. */
    fun clearCachedAuth() = storeAuth(null)

    /** Diagnostics: let the next attestation try immediately after a prior verification failure. */
    fun resetVerificationBackoff() {
        lastAuthFailure = null
    }

    /**
     * Exponential backoff with additive jitter for re-attestation. The nth consecutive failure waits
     * base·2^(n-1) — shift capped at [AUTH_COOLDOWN_MAX_SHIFT], total capped at [AUTH_COOLDOWN_MAX_MS] —
     * plus 0–50% jitter so a fleet of clients hit by the same broker blip don't re-attest in lockstep.
     * Jitter only ever DELAYS a retry, never advances it, so we never hammer Play Integrity or the broker
     * harder than the computed backoff. [retryable] selects the base.
     */
    private fun backoffCooldownMs(retryable: Boolean, attempt: Int): Long {
        val base = if (retryable) AUTH_RETRYABLE_COOLDOWN_BASE_MS else AUTH_FAILURE_COOLDOWN_BASE_MS
        val shift = (attempt - 1).coerceIn(0, AUTH_COOLDOWN_MAX_SHIFT)
        val capped = (base shl shift).coerceAtMost(AUTH_COOLDOWN_MAX_MS)
        return capped + Random.nextLong(capped / 2 + 1)
    }

    private fun isCoolingDown(): Boolean {
        val failure = lastAuthFailure ?: return false
        return System.currentTimeMillis() - failure.atMillis < failure.cooldownMs
    }

    private fun throwIfCoolingDown() {
        if (isCoolingDown()) error("Attestation cooling down after ${lastAuthFailure?.message}")
    }

    private suspend fun verifyIntegrity(): IntegrityVerificationResponse {
        // App Check proves this is a genuine, unmodified app on a genuine device (Play Integrity under the
        // hood, handled by Firebase). The token is bound to this clientId/nonce/timestamp by the identity
        // request signature below, and the broker verifies it locally against the App Check JWKS.
        val request = IntegrityVerificationRequest(
            clientId = signer.clientId,
            attestationType = AttestationType.FIREBASE_APP_CHECK,
            attestationToken = integrity.token(),
            // NS2: carry the self-contained key-epoch so the broker learns our identity + operational keys and
            // stores them BEFORE issuing the bearer (so a subsequent operational-signed request resolves).
            clientKeyEpoch = clientKeyEpochProvider(),
        )
        val body = ProtocolCodec.encodeToJson(request).toByteArray(Charsets.UTF_8)
        val url = "${httpBase()}/v2/integrity/verify"
        // A still-valid token lets the broker treat this as a refresh (re-attests, skips proof of work).
        val refreshToken = cachedBearerForRefresh()
        val signed = HttpRequestSigning.sign(signer, "POST", pathAndQuery(url), body)
        val resp = client.post(url) {
            contentType(ContentType.Application.Json)
            applySigned(signed, bearerToken = refreshToken)
            if (refreshToken == null) {
                // First contact: solve the proof of work at the broker's advertised difficulty, bound to
                // this request's signature so it can't be precomputed.
                val powTimestamp = System.currentTimeMillis()
                val powNonce = ProofOfWork.solve(signed.signature, powTimestamp, powDifficulty())
                header(ProofOfWork.HEADER_NONCE, powNonce)
                header(ProofOfWork.HEADER_TIMESTAMP, powTimestamp.toString())
            }
            setBody(body)
        }
        if (!resp.status.isSuccess()) {
            val retryable = resp.status == HttpStatusCode.TooManyRequests || resp.status.value >= 500
            throw IntegrityException("Attestation verification failed: ${resp.status} ${resp.bodyAsText()}", retryable)
        }
        return ProtocolCodec.decodeFromJson(resp.bodyAsText())
    }

    /** The PoW difficulty the broker currently requires (advertised on /v2/status); falls back to the
     *  protocol default only if the broker is unreachable. A difficulty of 0 means PoW is disabled. */
    private suspend fun powDifficulty(): Int =
        fetchVerificationStatus()?.powDifficulty ?: ProofOfWork.DEFAULT_DIFFICULTY

    private fun HttpRequestBuilder.applySigned(signed: HttpRequestSigning.Headers, bearerToken: String?) {
        bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        header(HttpRequestSigning.HEADER_CLIENT_ID, signed.clientId.value)
        header(HttpRequestSigning.HEADER_TIMESTAMP, signed.timestampMillis.toString())
        header(HttpRequestSigning.HEADER_NONCE, signed.nonce)
        header(HttpRequestSigning.HEADER_CONTENT_SHA256, signed.contentSha256)
        header(HttpRequestSigning.HEADER_SIGNATURE, signed.signature)
        // The signer-epoch is bound into the v2 canonical, so the broker MUST receive it to rebuild and
        // verify the signature (0 = identity root, ≥1 = operational epoch).
        header(HttpRequestSigning.HEADER_SIGNER_EPOCH, signed.signerEpoch.toString())
    }

    /**
     * Sign request headers with the identity root (signerEpoch 0) by default — the always-valid path for
     * control-plane and background-wake requests (key-epoch publish, route claims, key-epoch pull, relay,
     * WS). Pass [operational] = true for the hot data plane (`send`, asset I/O), which signs with the
     * current operational key so the StrongBox root stays off the burst path.
     */
    private fun HttpRequestBuilder.signedHeaders(method: String, url: String, body: ByteArray, bearerToken: String?, operational: Boolean = false) {
        val signed = if (operational) {
            HttpRequestSigning.sign(operationalSigner(), method, pathAndQuery(url), body)
        } else {
            HttpRequestSigning.sign(signer, method, pathAndQuery(url), body)
        }
        applySigned(signed, bearerToken)
    }

    private fun pathAndQuery(url: String): String =
        URI(url).let { uri ->
            buildString {
                append(uri.rawPath.ifBlank { "/" })
                if (!uri.rawQuery.isNullOrBlank()) append('?').append(uri.rawQuery)
            }
        }

    /**
     * Live delivery over an authenticated WebSocket. Reconnects with backoff. The broker challenges
     * with a nonce; we prove control of the identity key by signing it. Each DELIVER is handed to
     * [onEnvelope] inline and ACKed to the broker ONLY AFTER it returns — so the broker drops the relay
     * copy only once we've durably handled it (at-least-once; a crash mid-handle leaves it queued).
     * This replaces the old `Flow` that ACKed on enqueue, which could lose a message to a buffer
     * overflow or process death between buffering and handling.
     */
    override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> Unit) {
        var backoffMs = 1_000L
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            try {
                val url = "${wsBase()}/v2/connect"
                val token = bearerTokenOrNull()
                client.webSocket(url, request = {
                    signedHeaders("GET", url, ByteArray(0), token)
                }) {
                    val challenge = ProtocolCodec.decodeFromJson<WsChallenge>((incoming.receive() as Frame.Text).readText())
                    // Sign the handshake nonce with the identity root (epoch 0): always valid, never floored —
                    // the live socket connects regardless of operational-epoch convergence (§3.4).
                    val sig = Base64.getEncoder().encodeToString(signer.sign(challenge.nonce.toByteArray()))
                    send(Frame.Text(ProtocolCodec.encodeToJson(WsAuth(signer.clientId, challenge.nonce, sig, epoch = 0))))
                    backoffMs = 1_000L
                    consecutiveFailures = 0
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val msg = runCatching { ProtocolCodec.decodeFromJson<WsMessage>(frame.readText()) }.getOrNull() ?: continue
                        if (msg.kind == WsKind.DELIVER && msg.envelopeB64 != null) {
                            val env = runCatching {
                                ProtocolCodec.decodeFromCbor<Envelope>(Base64.getDecoder().decode(msg.envelopeB64))
                            }.getOrNull() ?: continue
                            onEnvelope(env) // verify + decrypt + post, inline (SecureChannel.deliver is non-suspend)
                            // ACK only now: the broker drops the relay item once we've durably handled it.
                            send(Frame.Text(ProtocolCodec.encodeToJson(WsMessage(kind = WsKind.ACK, messageId = env.messageId))))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e // cooperative cancellation (app backgrounded / scope cancelled): not a reconnectable failure
            } catch (_: Exception) {
                // A run of failed handshakes while we still hold a cached token may mean the broker
                // rotated its JWT key; drop it so the next attempt re-attests. (HTTP calls self-heal on 401.)
                if (++consecutiveFailures >= WS_REAUTH_AFTER_FAILURES) {
                    storeAuth(null)
                    consecutiveFailures = 0
                }
            }
            // Additive jitter [x, 1.5x] (mirrors [backoffCooldownMs]): a fleet of devices dropped by the
            // same broker blip would otherwise reconnect in lockstep and re-synchronize load spikes. Jitter
            // only ever delays a retry, never advances it; the base keeps its clean exponential growth + cap.
            delay(backoffMs + Random.nextLong(backoffMs / 2 + 1))
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    fun close() = client.close()

    private companion object {
        const val AUTH_REFRESH_SKEW_MS = 60_000L
        // Refresh a still-valid token once it's within a day of expiry (stale-while-revalidate), so the
        // multi-day JWT is renewed in the background long before any request would have to block to re-attest.
        const val AUTH_PROACTIVE_REFRESH_MS = 24L * 60 * 60 * 1000
        // Re-attestation backoff: the nth consecutive failure waits base·2^(n-1), shift- then total-capped,
        // plus jitter (see [backoffCooldownMs]). Retryable (429/5xx/stale) starts gentle at 5s; a definitive
        // reject (bad device/app/signature) starts at 60s. Both reset on the next successful attestation.
        const val AUTH_RETRYABLE_COOLDOWN_BASE_MS = 5_000L
        const val AUTH_FAILURE_COOLDOWN_BASE_MS = 60_000L
        const val AUTH_COOLDOWN_MAX_SHIFT = 4
        const val AUTH_COOLDOWN_MAX_MS = 5L * 60 * 1000
        const val WS_REAUTH_AFTER_FAILURES = 3
        const val WS_PING_SECONDS = 30L
    }

    private data class AuthFailure(val atMillis: Long, val message: String, val cooldownMs: Long, val attempt: Int)

    private class IntegrityException(message: String, val retryable: Boolean) : Exception(message)
}
