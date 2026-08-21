package net.extrawdw.notisync.peer.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.notisync.peer.ports.IntegrityEvidenceProvider
import net.extrawdw.notisync.peer.ports.PeerTelemetry
import net.extrawdw.notisync.peer.ports.trace
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.HealthResponse
import net.extrawdw.notisync.protocol.IntegrityVerificationRequest
import net.extrawdw.notisync.protocol.IntegrityVerificationResponse
import net.extrawdw.notisync.protocol.LiveDeliveryDisposition
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RelayAck
import net.extrawdw.notisync.protocol.RelayBatchFrame
import net.extrawdw.notisync.protocol.RelayBatchKind
import net.extrawdw.notisync.protocol.RelayPending
import net.extrawdw.notisync.protocol.RelayWire
import net.extrawdw.notisync.protocol.SendRequest
import net.extrawdw.notisync.protocol.SendResult
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.ScreenRelayJoin
import net.extrawdw.notisync.protocol.ScreenRelaySignal
import net.extrawdw.notisync.protocol.ScreenRelaySignalKind
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
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

data class RelayDelivery(
    val envelope: Envelope,
    val acceptedAt: Long?,
)

data class RelayBatchDownload(
    val snapshotAt: Long,
    val cutoff: Long,
    val itemCount: Long,
)

sealed interface RelayBatchFetchResult {
    data class Complete(val download: RelayBatchDownload) : RelayBatchFetchResult
    data object Unsupported : RelayBatchFetchResult
    data object Failed : RelayBatchFetchResult
}

/**
 * Ktor-based client implementing the transport-neutral [Transport]. The control plane is signed HTTP
 * with CBOR bodies; live delivery uses an authenticated foreground WebSocket. FCM is the Android
 * background wake path and is handled separately by the messaging service + broker.
 */
class BrokerClient(
    private val signer: IdentitySigner,
    /** Provider for the current operational signer — signs the hot-path data plane (`send`, asset I/O) with
     *  signerEpoch ≥1; a provider so a rotation swaps the active epoch without rebuilding the client. */
    private val operationalSigner: () -> OperationalSigner,
    private val baseUrlProvider: () -> String,
    private val integrity: IntegrityEvidenceProvider,
    /** This device's current self-contained, identity-signed `ClientKeyEpoch` ([SignedType.KEY_EPOCH]) —
     *  carried on attestation so the broker learns our keys, and (re)published via [publishKeyEpoch]. */
    private val clientKeyEpochProvider: () -> SignedBlob,
    private val tokenStore: AuthTokenStore,
    private val scope: CoroutineScope,
    private val telemetry: PeerTelemetry = PeerTelemetry.None,
    /** Applied when this client is built; callers should recreate it after changing this setting. */
    private val webSocketPingSeconds: Long = DEFAULT_WS_PING_SECONDS,
    /** Authenticated WebSocket lifecycle signal used by foreground peers for truthful local status. */
    private val onWebSocketConnectionChanged: (Boolean) -> Unit = {},
) : Transport {

    override val type: TransportType = TransportType.WEBSOCKET

    private val client = HttpClient(OkHttp) {
        engine {
            // Keepalive at the OkHttp layer: ping the broker periodically so a half-open socket
            // (NAT/proxy idle drop) is detected — a missed pong fails the socket instead of the read
            // loop blocking forever, surfacing as the exception that drives reconnect in runLiveDelivery().
            // Network request timing comes from Firebase's automatic OkHttp monitoring (it captures the Ktor
            // engine's calls) plus custom URL patterns in the console — no manual HttpMetric, which would
            // double-count against the automatic instrumentation.
            require(webSocketPingSeconds > 0) { "webSocketPingSeconds must be positive" }
            config { pingInterval(webSocketPingSeconds, TimeUnit.SECONDS) }
        }
        // Explicit, deterministic HTTP timeouts (without this plugin the engine defaults applied invisibly —
        // Crashlytics reported "socket_timeout=unknown"). Verified safe for the live socket on this stack
        // (Ktor 3.5 / OkHttp 5.3): Ktor never applies the request timeout to WebSocket upgrades, and OkHttp's
        // WebSocketReader clears the read timeout while idling between frames — so for the WS these values
        // only bound the connect and a mid-frame stall (both desirable), while half-open detection stays
        // with the ping/pong keepalive above.
        install(HttpTimeout) {
            connectTimeoutMillis = HTTP_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = HTTP_SOCKET_TIMEOUT_MS
            requestTimeoutMillis = HTTP_REQUEST_TIMEOUT_MS
        }
        install(WebSockets)
    }
    private val relayWebSocketClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(HTTP_SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .pingInterval(webSocketPingSeconds, TimeUnit.SECONDS)
        // Relay control messages are tiny (a touch is one 32-byte scrcpy frame inside TLS).
        // OkHttp otherwise leaves TCP_NODELAY at the platform default, so Nagle plus delayed ACKs
        // can pace MOVE events into visibly slow bursts on both Android relay clients.
        .socketFactory(TcpNoDelaySocketFactory())
        // Relay VIDEO is already AES-GCM ciphertext. Deflate cannot reduce it and only adds CPU and
        // another staging buffer on the latency-sensitive path.
        .minWebSocketMessageToCompress(Long.MAX_VALUE)
        .build()
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
        cachedAuth =
            runCatching { tokenStore.load() }.getOrNull()?.takeIf { it.clientId == signer.clientId }
    }

    private fun wsBase(): String = brokerWebSocketBase(baseUrlProvider())
    private fun httpBase(): String = brokerHttpBase(baseUrlProvider())

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
        return if (resp.status.isSuccess()) runCatching {
            ProtocolCodec.decodeFromCbor<SignedBlob>(
                resp.readRawBytes()
            )
        }.getOrNull() else null
    }

    override suspend fun send(envelope: Envelope, urgency: Urgency): SendResult {
        // Operational-signed request (hot path): the envelope inside is already operational-signed; the
        // broker accepts an operational request signature (floor + window + REQUEST_AUTH) and self-heals to
        // identity attestation on a 401 if our epoch isn't yet known there. Urgency rides inside the signed
        // request body so the broker can honor it without trusting a mutable HTTP header.
        val resp = authedPost(
            "${httpBase()}/v2/send",
            ProtocolCodec.encodeToCbor(SendRequest(envelope, urgency)),
            operational = true
        )
        return runCatching { ProtocolCodec.decodeFromJson<SendResult>(resp.bodyAsText()) }
            .getOrDefault(SendResult(accepted = false))
    }

    override suspend fun uploadPrivateAsset(
        sourceClientId: ClientId,
        assetId: String,
        ciphertext: ByteArray
    ): Boolean {
        val resp = authedPost(
            "${httpBase()}/v2/assets/${sourceClientId.value}/$assetId",
            ciphertext,
            operational = true
        )
        // 200 stored or 409 already-exists both mean the broker holds it.
        return resp.status.isSuccess() || resp.status == HttpStatusCode.Conflict
    }

    override suspend fun fetchPrivateAsset(sourceClientId: ClientId, assetId: String): ByteArray? {
        val resp = authedGet(
            "${httpBase()}/v2/assets/${sourceClientId.value}/$assetId",
            operational = true
        )
        return if (resp.status.isSuccess()) resp.readRawBytes() else null
    }

    /**
     * Pull a single relay message by id (the FCM-wake path: the broker stored a too-large notification
     * and pushed only a "mid" pointer). Authenticated by request SIGNATURE alone — it sends a cached
     * bearer if one happens to be valid but never triggers attestation, so a background wake delivers
     * even while Play Integrity is cooling down. Signs with the operational key (TEE, fast) to keep the
     * slow StrongBox identity root off this per-message wake/drain path, falling back ONCE to the identity
     * root on a 401 if the broker hasn't ingested our current epoch yet (a rotation/convergence race).
     * `Peek: true` keeps the relay item queued until the caller durably handles it and explicitly acks it.
     */
    suspend fun fetchRelayDelivery(messageId: String): RelayDelivery? {
        val url = "${httpBase()}/v2/relay/$messageId"
        var resp = runCatching {
            client.get(url) {
                signedHeaders(
                    "GET",
                    url,
                    ByteArray(0),
                    cachedBearerOrNull(),
                    operational = true
                )
                header("Peek", "true")
            }
        }.getOrNull() ?: return null
        // unknown_epoch → 401: the broker doesn't yet know our operational epoch; retry once on the
        // always-valid identity root (still signature-only — never attests).
        if (resp.status == HttpStatusCode.Unauthorized) {
            resp = runCatching {
                client.get(url) {
                    signedHeaders("GET", url, ByteArray(0), cachedBearerOrNull())
                    header("Peek", "true")
                }
            }.getOrNull() ?: return null
        }
        if (!resp.status.isSuccess()) return null
        val envelope = runCatching { ProtocolCodec.decodeFromCbor<Envelope>(resp.readRawBytes()) }.getOrNull()
            ?: return null
        return RelayDelivery(
            envelope = envelope,
            acceptedAt = resp.headers[RelayWire.ACCEPTED_AT_HEADER]?.toLongOrNull(),
        )
    }

    /** Compatibility helper for callers that do not need broker acceptance metadata. */
    suspend fun fetchRelayMessage(messageId: String): Envelope? = fetchRelayDelivery(messageId)?.envelope

    /**
     * List the message ids the broker currently has queued for us (signed-only GET, never triggers
     * attestation). Null distinguishes a failed legacy fallback request from a genuinely empty relay.
     */
    suspend fun fetchPendingRelayIdsOrNull(): List<String>? {
        val url = "${httpBase()}/v2/relay"
        val resp = runCatching {
            client.get(url) { signedHeaders("GET", url, ByteArray(0), cachedBearerOrNull()) }
        }.getOrNull() ?: return null
        if (!resp.status.isSuccess()) return null
        return runCatching { ProtocolCodec.decodeFromJson<RelayPending>(resp.bodyAsText()).messageIds }.getOrNull()
    }

    suspend fun fetchPendingRelayIds(): List<String> = fetchPendingRelayIdsOrNull().orEmpty()

    /**
     * Stream one finite relay snapshot. [onItem] is invoked once per validated ITEM frame, allowing
     * Android to persist each envelope without buffering the whole 48-hour backlog in memory. Only a
     * 404 is `Unsupported`; rejected, malformed, truncated, and network-failed responses are `Failed`.
     */
    suspend fun fetchRelayBatch(
        before: Long? = null,
        onItem: (RelayBatchFrame) -> Unit,
    ): RelayBatchFetchResult {
        val suffix = before?.let { "?before=$it" }.orEmpty()
        val url = "${httpBase()}/v2/relay/batch$suffix"

        suspend fun request(operational: Boolean): HttpResponse? = runCatching {
            client.get(url) {
                signedHeaders(
                    "GET",
                    url,
                    ByteArray(0),
                    cachedBearerOrNull(),
                    operational = operational,
                )
                // A large backlog is bounded by socket-idle timeout, not by the ordinary whole-request cap.
                timeout { requestTimeoutMillis = Long.MAX_VALUE }
            }
        }.getOrNull()

        var response = request(operational = true) ?: return RelayBatchFetchResult.Failed
        if (response.status == HttpStatusCode.Unauthorized) {
            response = request(operational = false) ?: return RelayBatchFetchResult.Failed
        }
        if (response.status == HttpStatusCode.NotFound) return RelayBatchFetchResult.Unsupported
        if (!response.status.isSuccess()) return RelayBatchFetchResult.Failed

        return runCatching<RelayBatchFetchResult> {
            val channel = response.bodyAsChannel()
            var snapshotAt: Long? = null
            var cutoff: Long? = null
            var seen = 0L
            while (true) {
                val size = channel.readInt()
                require(size in 1..RelayWire.MAX_BATCH_FRAME_BYTES) { "invalid relay batch frame size" }
                val bytes = ByteArray(size)
                channel.readFully(bytes)
                val frame = ProtocolCodec.decodeFromCbor<RelayBatchFrame>(bytes)
                when (frame.kind) {
                    RelayBatchKind.START -> {
                        require(snapshotAt == null && seen == 0L)
                        snapshotAt = requireNotNull(frame.snapshotAt)
                        cutoff = requireNotNull(frame.cutoff)
                    }
                    RelayBatchKind.ITEM -> {
                        require(snapshotAt != null)
                        require(!frame.messageId.isNullOrBlank())
                        require(frame.acceptedAt != null && frame.messageType != null && frame.envelope != null)
                        onItem(frame)
                        seen++
                    }
                    RelayBatchKind.END -> {
                        require(snapshotAt != null)
                        require(frame.itemCount == seen)
                        return@runCatching RelayBatchFetchResult.Complete(
                            RelayBatchDownload(
                                snapshotAt = snapshotAt!!,
                                cutoff = requireNotNull(cutoff),
                                itemCount = seen,
                            )
                        )
                    }
                    else -> error("unknown relay batch frame kind")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("relay batch ended without END frame")
        }.getOrDefault(RelayBatchFetchResult.Failed)
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
        var resp = client.get(url) {
            signedHeaders(
                "GET",
                url,
                ByteArray(0),
                bearerTokenOrNull(),
                operational
            )
        }
        if (resp.status == HttpStatusCode.Unauthorized) {
            storeAuth(null)
            val token = bearerToken()
            resp = client.get(url) { signedHeaders("GET", url, ByteArray(0), token, operational) }
        }
        return resp
    }

    private suspend fun authedPost(
        url: String,
        body: ByteArray,
        contentType: ContentType? = null,
        operational: Boolean = false
    ): HttpResponse {
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
                lastAuthFailure = AuthFailure(
                    System.currentTimeMillis(),
                    it.message ?: it.javaClass.simpleName,
                    cooldown,
                    attempt
                )
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
        // The slowest security operation on any request path: the App Check token round-trip (Play Integrity
        // under the hood) plus the broker verify POST. `token_ms` isolates the Firebase/Play Integrity cost
        // from the broker leg; `result` distinguishes a token-fetch failure from a broker reject/transient.
        val span = telemetry.trace("integrity_attestation")
        var result = "token_failed"
        try {
            // App Check proves this is a genuine, unmodified app on a genuine device (Play Integrity under the
            // hood, handled by Firebase). The token is bound to this clientId/nonce/timestamp by the identity
            // request signature below, and the broker verifies it locally against the App Check JWKS.
            val tokenStartNanos = System.nanoTime()
            val evidence = integrity.evidence()
            span.metric("evidence_ms", (System.nanoTime() - tokenStartNanos) / 1_000_000)
            result = "post_failed"
            val request = IntegrityVerificationRequest(
                clientId = signer.clientId,
                attestationType = evidence.type,
                attestationToken = evidence.token,
                attestationKeyId = evidence.keyId,
                // NS2: carry the self-contained key-epoch so the broker learns our identity + operational keys
                // and stores them BEFORE issuing the bearer (so a subsequent operational-signed request resolves).
                clientKeyEpoch = clientKeyEpochProvider(),
            )
            val body = ProtocolCodec.encodeToJson(request).toByteArray(Charsets.UTF_8)
            val url = "${httpBase()}/v2/integrity/verify"
            // A still-valid token lets the broker treat this as a refresh (re-attests, skips proof of work).
            val refreshToken = cachedBearerForRefresh()
            span.attr("had_pow", (refreshToken == null).toString())
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
                val retryable =
                    resp.status == HttpStatusCode.TooManyRequests || resp.status.value >= 500
                result = if (retryable) "retryable" else "rejected"
                throw IntegrityException(
                    "Attestation verification failed: ${resp.status} ${resp.bodyAsText()}",
                    retryable
                )
            }
            val response = ProtocolCodec.decodeFromJson<IntegrityVerificationResponse>(resp.bodyAsText())
            result = "ok"
            return response
        } finally {
            span.attr("result", result)
            span.stop()
        }
    }

    /** The PoW difficulty the broker currently requires (advertised on /v2/status); falls back to the
     *  protocol default only if the broker is unreachable. A difficulty of 0 means PoW is disabled. */
    private suspend fun powDifficulty(): Int =
        fetchVerificationStatus()?.powDifficulty ?: ProofOfWork.DEFAULT_DIFFICULTY

    private fun HttpRequestBuilder.applySigned(
        signed: HttpRequestSigning.Headers,
        bearerToken: String?
    ) {
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
    private fun HttpRequestBuilder.signedHeaders(
        method: String,
        url: String,
        body: ByteArray,
        bearerToken: String?,
        operational: Boolean = false
    ) {
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
    override suspend fun runLiveDelivery(onEnvelope: (Envelope) -> LiveDeliveryDisposition) =
        runLiveDeliveryWithMetadata { envelope, _ -> onEnvelope(envelope) }

    /** Android receive path with broker acceptance metadata; the transport-neutral API remains compatible. */
    suspend fun runLiveDeliveryWithMetadata(
        onEnvelope: (Envelope, acceptedAt: Long?) -> LiveDeliveryDisposition,
    ) {
        var backoffMs = 1_000L
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            val span = telemetry.trace("ws_connect")
            span.metric("attempt", (consecutiveFailures + 1).toLong())
            var connected = false
            try {
                val url = "${wsBase()}/v2/connect"
                val token = bearerTokenOrNull()
                client.webSocket(url, request = {
                    signedHeaders("GET", url, ByteArray(0), token)
                }) {
                    val challenge =
                        ProtocolCodec.decodeFromJson<WsChallenge>((incoming.receive() as Frame.Text).readText())
                    // Sign the handshake nonce with the identity root (epoch 0): always valid, never floored —
                    // the live socket connects regardless of operational-epoch convergence (§3.4).
                    val sig = Base64.getEncoder()
                        .encodeToString(signer.sign(challenge.nonce.toByteArray()))
                    send(
                        Frame.Text(
                            ProtocolCodec.encodeToJson(
                                WsAuth(
                                    signer.clientId,
                                    challenge.nonce,
                                    sig,
                                    epoch = 0
                                )
                            )
                        )
                    )
                    // Handshake + auth complete. Stop the connect trace here — before the long-lived receive
                    // loop — so `ws_connect` measures connect+auth latency, not the whole session length.
                    connected = true
                    runCatching { onWebSocketConnectionChanged(true) }
                    span.attr("result", "ok")
                    span.stop()
                    backoffMs = 1_000L
                    consecutiveFailures = 0
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val msg =
                            runCatching { ProtocolCodec.decodeFromJson<WsMessage>(frame.readText()) }.getOrNull()
                                ?: continue
                        if (msg.kind == WsKind.DELIVER && msg.envelopeB64 != null) {
                            val env = runCatching {
                                ProtocolCodec.decodeFromCbor<Envelope>(
                                    Base64.getDecoder().decode(msg.envelopeB64)
                                )
                            }.getOrNull() ?: continue
                            // A dropped/in-flight message stays queued: trust/key convergence or the winning
                            // concurrent handler may make a later redelivery succeed.
                            if (onEnvelope(env, msg.acceptedAt) == LiveDeliveryDisposition.ACK) {
                                send(
                                    Frame.Text(
                                        ProtocolCodec.encodeToJson(
                                            WsMessage(
                                                kind = WsKind.ACK,
                                                messageId = env.messageId
                                            )
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Cooperative cancellation (app backgrounded / scope cancelled): not a reconnectable failure.
                if (!connected) { span.attr("result", "cancelled"); span.stop() }
                throw e
            } catch (_: Exception) {
                // A run of failed handshakes while we still hold a cached token may mean the broker
                // rotated its JWT key; drop it so the next attempt re-attests. (HTTP calls self-heal on 401.)
                if (!connected) { span.attr("result", "failed"); span.stop() }
                if (++consecutiveFailures >= WS_REAUTH_AFTER_FAILURES) {
                    storeAuth(null)
                    consecutiveFailures = 0
                }
            }
            runCatching { onWebSocketConnectionChanged(false) }
            // Additive jitter [x, 1.5x] (mirrors [backoffCooldownMs]): a fleet of devices dropped by the
            // same broker blip would otherwise reconnect in lockstep and re-synchronize load spikes. Jitter
            // only ever delays a retry, never advances it; the base keeps its clean exponential growth + cap.
            delay(backoffMs + Random.nextLong(backoffMs / 2 + 1))
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    /** Registers one broker-authenticated v1 screen Relay channel. */
    suspend fun openScreenRelay(join: ScreenRelayJoin): BrokerRelayConnection {
        require(join.relayId.matches(Regex("[A-Za-z0-9_-]{32}"))) { "invalid screen relay id" }
        require(join.expiresAt > System.currentTimeMillis()) { "screen relay request expired" }
        val connection = BrokerRelayConnection(join.channel, join.role)
        val registered = CompletableDeferred<Unit>()
        val url = "${wsBase()}/v1/screen-relay"
        val token = bearerTokenOrNull()
        val signed = HttpRequestSigning.sign(signer, "GET", pathAndQuery(url), ByteArray(0))
        val request = Request.Builder().url(url).apply {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            header(HttpRequestSigning.HEADER_CLIENT_ID, signed.clientId.value)
            header(HttpRequestSigning.HEADER_TIMESTAMP, signed.timestampMillis.toString())
            header(HttpRequestSigning.HEADER_NONCE, signed.nonce)
            header(HttpRequestSigning.HEADER_CONTENT_SHA256, signed.contentSha256)
            header(HttpRequestSigning.HEADER_SIGNATURE, signed.signature)
            header(HttpRequestSigning.HEADER_SIGNER_EPOCH, signed.signerEpoch.toString())
        }.build()
        val socket = relayWebSocketClient.newWebSocket(request, object : WebSocketListener() {
            private var challengeHandled = false
            private var registeredHandled = false

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (!challengeHandled) {
                        val challenge = ProtocolCodec.decodeFromJson<WsChallenge>(text)
                        val signature = Base64.getEncoder()
                            .encodeToString(signer.sign(challenge.nonce.toByteArray()))
                        check(
                            webSocket.send(
                                ProtocolCodec.encodeToJson(
                                    WsAuth(signer.clientId, challenge.nonce, signature, epoch = 0),
                                ),
                            ),
                        ) { "broker relay authentication send failed" }
                        check(webSocket.send(ProtocolCodec.encodeToJson(join))) {
                            "broker relay join send failed"
                        }
                        challengeHandled = true
                    } else if (!registeredHandled) {
                        val signal = ProtocolCodec.decodeFromJson<ScreenRelaySignal>(text)
                        check(signal.kind == ScreenRelaySignalKind.REGISTERED) {
                            signal.detail ?: "broker relay registration rejected"
                        }
                        registeredHandled = true
                        registered.complete(Unit)
                    } else {
                        connection.receiveSignal(ProtocolCodec.decodeFromJson(text))
                    }
                } catch (error: Throwable) {
                    registered.completeExceptionally(error)
                    webSocket.cancel()
                    connection.terminate()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    connection.receive(bytes.toByteArray())
                } catch (error: Throwable) {
                    registered.completeExceptionally(error)
                    webSocket.cancel()
                    connection.terminate()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                registered.completeExceptionally(IOException("broker relay closed ($code): $reason"))
                connection.terminate()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                registered.completeExceptionally(t)
                connection.terminate()
            }
        })
        connection.attach(socket)
        try {
            withTimeout(SCREEN_RELAY_REGISTER_TIMEOUT_MS) { registered.await() }
            return connection
        } catch (error: Throwable) {
            connection.abort()
            throw error
        }
    }

    fun close() {
        client.close()
        relayWebSocketClient.dispatcher.cancelAll()
        relayWebSocketClient.dispatcher.executorService.shutdown()
        relayWebSocketClient.connectionPool.evictAll()
    }

    private companion object {
        const val AUTH_REFRESH_SKEW_MS = 60_000L

        // Refresh a still-valid token once it's within a day of expiry (stale-while-revalidate), so the
        // multi-day JWT is renewed in the background long before any request would have to block to re-attest.
        const val AUTH_PROACTIVE_REFRESH_MS = 24L * 60 * 60 * 1000

        // Re-attestation backoff is exponential with jitter. Retryable errors start at 5s; a definitive
        // rejection starts at 60s. Both reset after a successful attestation.
        const val AUTH_RETRYABLE_COOLDOWN_BASE_MS = 5_000L
        const val AUTH_FAILURE_COOLDOWN_BASE_MS = 60_000L
        const val AUTH_COOLDOWN_MAX_SHIFT = 4
        const val AUTH_COOLDOWN_MAX_MS = 5L * 60 * 1000
        const val WS_REAUTH_AFTER_FAILURES = 3
        const val DEFAULT_WS_PING_SECONDS = 30L
        const val SCREEN_RELAY_REGISTER_TIMEOUT_MS = 30_000L

        // HTTP timeouts. Connect makes the platform default explicit; socket (per-read/write inactivity,
        // which is also the HTTP/2 per-stream response-header wait) gets headroom over the old implicit 10s
        // so a slow mobile hop fails less eagerly; request bounds the whole call including the body, sized
        // for private-asset up/downloads on a weak uplink. Every call site treats a failure as best-effort,
        // so a timeout costs one attempt — retried by the relay/anti-entropy backstops, never fatal.
        const val HTTP_CONNECT_TIMEOUT_MS = 10_000L
        const val HTTP_SOCKET_TIMEOUT_MS = 15_000L
        const val HTTP_REQUEST_TIMEOUT_MS = 30_000L
    }

    private data class AuthFailure(
        val atMillis: Long,
        val message: String,
        val cooldownMs: Long,
        val attempt: Int
    )

    private class IntegrityException(message: String, val retryable: Boolean) : Exception(message)
}

/**
 * A configured broker address is an HTTP base URL because most broker traffic is signed HTTP. Accept the
 * older ws/wss spelling as input, but canonicalize the scheme at the point where each transport is used.
 */
internal fun brokerHttpBase(value: String): String {
    val normalized = value.trim().trimEnd('/')
    return when {
        normalized.startsWith("wss://") -> "https://${normalized.removePrefix("wss://")}"
        normalized.startsWith("ws://") -> "http://${normalized.removePrefix("ws://")}"
        else -> normalized
    }
}

internal fun brokerWebSocketBase(value: String): String {
    val normalized = value.trim().trimEnd('/')
    return when {
        normalized.startsWith("https://") -> "wss://${normalized.removePrefix("https://")}"
        normalized.startsWith("http://") -> "ws://${normalized.removePrefix("http://")}"
        else -> normalized
    }
}
