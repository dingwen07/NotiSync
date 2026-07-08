package net.extrawdw.notisync.server.delivery.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RouteEnvironment
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.server.ServerConfig
import net.extrawdw.notisync.server.crypto.Es256
import net.extrawdw.notisync.server.delivery.PushOutcome
import net.extrawdw.notisync.server.delivery.PushTransport
import net.extrawdw.notisync.server.data.StoredRoute
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.PrivateKey
import java.time.Duration

/**
 * APNs token-authenticated HTTP/2 adapter. NOTIFICATION envelopes are alert pushes carrying NotiSync's
 * opaque inline envelope (`ct`) or relay pointer (`mid`) for the NSE to decrypt and display; other message
 * types are priority-5 background pushes handled by the app process.
 */
class ApnsPushTransport internal constructor(
    private val topic: String,
    private val ttlMillis: Long,
    /** APNs provider-token signer per environment — Apple scopes keys to sandbox vs production. */
    private val tokenProviders: Map<RouteEnvironment, ApnsTokenProvider>,
    private val client: ApnsClient = JavaNetApnsClient(),
) : PushTransport {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun wake(route: StoredRoute, data: Map<String, String>, urgency: Urgency): PushOutcome =
        withContext(Dispatchers.IO) {
            if (route.transport != TransportType.APNS) return@withContext PushOutcome.DISABLED
            if (!route.routeRef.isApnsDeviceToken()) {
                log.info("APNs rejected malformed route token client={}", route.clientId.shortForm())
                return@withContext PushOutcome.ROUTE_INVALID
            }
            try {
                // Pick the provider token for the route's environment; if no key is configured for it
                // (e.g. only a sandbox key is installed), the push can't be signed — leave it for relay/WS.
                val provider = tokenProviders[route.environment]
                    ?: return@withContext PushOutcome.DISABLED
                val endpoint = when (route.environment) {
                    RouteEnvironment.DEVELOPMENT -> "https://api.sandbox.push.apple.com"
                    RouteEnvironment.PRODUCTION -> "https://api.push.apple.com"
                }
                // Match the wake TTL to the relay TTL (as FcmPushTransport does) so a deferred wake can't
                // outlive the relay item it points to (else a wake delivered later is a dead pointer).
                val expiration = (System.currentTimeMillis() + ttlMillis) / 1000
                // Type-aware delivery class. A NOTIFICATION is delivered as an alert push with
                // mutable-content so the iOS Notification Service Extension wakes, decrypts the inline
                // ciphertext (or fetches the relay pointer) on-device, and replaces the placeholder before
                // display — the broker still holds only ciphertext. DISMISSAL/DATA_SYNC stay silent
                // background pushes handled by the app process. (FCM/Android is unaffected.)
                val isAlert = data["mtyp"] == MessageType.NOTIFICATION.name
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$endpoint/3/device/${route.routeRef}"))
                    .timeout(Duration.ofSeconds(20))
                    .header("authorization", "bearer ${provider.token()}")
                    .header("content-type", "application/json")
                    .header("apns-topic", topic)
                    .header("apns-push-type", if (isAlert) "alert" else "background")
                    .header("apns-priority", if (isAlert) "10" else "5")
                    .header("apns-expiration", expiration.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(apnsPayload(data, isAlert)))
                    .build()
                val response = client.send(request)
                if (response.statusCode in 200..299) return@withContext PushOutcome.DELIVERED
                val reason = apnsReason(response.body)
                // Re-mint the provider token ONLY when re-minting can actually help — an Expired or Missing
                // token. An InvalidProviderToken is a config error (wrong key/kid/team): re-minting yields the
                // same rejected token on every send and quickly trips APNs's 429 TooManyProviderTokenUpdates
                // rate limit, so leave the cached token in place and surface the (steady) InvalidProviderToken.
                if (response.statusCode == 403 && reason in REMINTABLE_TOKEN_REASONS) provider.invalidate()
                val outcome = apnsOutcome(response.statusCode, reason)
                log.info(
                    "APNs rejected push client={} status={} reason={} outcome={}",
                    route.clientId.shortForm(),
                    response.statusCode,
                    reason ?: "unknown",
                    outcome,
                )
                outcome
            } catch (e: Exception) {
                log.warn("APNs send failed: {}", e.message)
                PushOutcome.TRANSIENT_FAILURE
            }
        }

    private fun apnsPayload(data: Map<String, String>, alert: Boolean): String {
        val entries = data.entries.joinToString(",") { (k, v) -> "\"${jsonEscape(k)}\":\"${jsonEscape(v)}\"" }
        val aps = if (alert) {
            // mutable-content lets the NSE intercept + decrypt; the alert is a generic placeholder the NSE
            // replaces (the broker never holds plaintext). The category is static because the broker cannot
            // inspect encrypted actions, but it lets iOS select the generic content extension before the NSE
            // rewrites the final dynamic action category.
            """"aps":{"alert":{"title":"NotiSync","body":"New notification"},"mutable-content":1,"sound":"default","category":"notisync.mirror"}"""
        } else {
            """"aps":{"content-available":1}"""
        }
        return if (entries.isEmpty()) "{$aps}" else "{$aps,$entries}"
    }

    private fun jsonEscape(value: String): String = buildString(value.length + 8) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private fun String.isApnsDeviceToken(): Boolean =
        isNotEmpty() && length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** Map a non-2xx APNs response to a route-state outcome. [reason] is APNs's JSON `reason`, if present. */
    private fun apnsOutcome(statusCode: Int, reason: String?): PushOutcome = when {
        // Token/route-specific: this device token is dead for this topic — retire the route.
        statusCode == 410 -> PushOutcome.ROUTE_INVALID
        reason != null && reason in ROUTE_INVALID_REASONS -> PushOutcome.ROUTE_INVALID
        // Provider auth (403, token already re-minted above) and server/throttling (429/5xx): retry later.
        statusCode == 403 -> PushOutcome.TRANSIENT_FAILURE
        statusCode == 429 || statusCode in 500..599 -> PushOutcome.TRANSIENT_FAILURE
        // Other 4xx (BadTopic, PayloadTooLarge, BadPriority, ...): a broker payload/config error that
        // retrying as-sent can't fix and that does NOT imply the device token is invalid.
        else -> PushOutcome.PERMANENT_FAILURE
    }

    private fun apnsReason(body: String): String? =
        runCatching { ProtocolCodec.decodeFromJson<ApnsErrorResponse>(body).reason }
            .getOrNull()

    companion object {
        private val log = LoggerFactory.getLogger(ApnsPushTransport::class.java)
        private val ROUTE_INVALID_REASONS = setOf("BadDeviceToken", "DeviceTokenNotForTopic", "Unregistered")
        // 403 reasons where a fresh provider token actually fixes the rejection. NOT InvalidProviderToken
        // (a key/kid/team misconfig — re-minting it just storms APNs into 429 TooManyProviderTokenUpdates).
        private val REMINTABLE_TOKEN_REASONS = setOf("ExpiredProviderToken", "MissingProviderToken")

        fun createOrNull(config: ServerConfig): PushTransport? {
            if (!config.apnsEnabled) return null
            if (config.apnsTeamId.isBlank() || config.apnsTopic.isBlank()) {
                log.warn("APNs disabled; missing NOTISYNC_APNS_TEAM_ID / NOTISYNC_APNS_TOPIC")
                return null
            }
            return try {
                val providers = mutableMapOf<RouteEnvironment, ApnsTokenProvider>()
                // Production (api.push.apple.com) — also the fallback key for an unscoped key.
                if (config.apnsKeyId.isNotBlank() && config.apnsPrivateKeyPath.isNotBlank()) {
                    val key = Es256.loadEcPrivateKeyPem(File(config.apnsPrivateKeyPath).readText())
                    providers[RouteEnvironment.PRODUCTION] = ApnsJwtProvider(config.apnsTeamId, config.apnsKeyId, key)
                }
                // Sandbox (api.sandbox.push.apple.com) — its own env-scoped key, else reuse the (unscoped) one.
                val sandboxKeyId = config.apnsKeyIdSandbox.ifBlank { config.apnsKeyId }
                val sandboxPath = config.apnsPrivateKeyPathSandbox.ifBlank { config.apnsPrivateKeyPath }
                if (sandboxKeyId.isNotBlank() && sandboxPath.isNotBlank()) {
                    val key = Es256.loadEcPrivateKeyPem(File(sandboxPath).readText())
                    providers[RouteEnvironment.DEVELOPMENT] = ApnsJwtProvider(config.apnsTeamId, sandboxKeyId, key)
                }
                if (providers.isEmpty()) {
                    log.warn("APNs disabled; no key configured (NOTISYNC_APNS_KEY_ID and/or _KEY_ID_SANDBOX)")
                    return null
                }
                log.info("APNs transport enabled for topic {} environments={}", config.apnsTopic, providers.keys)
                ApnsPushTransport(
                    topic = config.apnsTopic,
                    ttlMillis = config.relayTtlMillis,
                    tokenProviders = providers,
                )
            } catch (e: Exception) {
                log.warn("APNs credentials unavailable ({}); APNs push disabled.", e.message)
                null
            }
        }
    }
}

internal data class ApnsResponse(val statusCode: Int, val body: String)

internal fun interface ApnsClient {
    fun send(request: HttpRequest): ApnsResponse
}

private class JavaNetApnsClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : ApnsClient {
    override fun send(request: HttpRequest): ApnsResponse {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return ApnsResponse(response.statusCode(), response.body())
    }
}

internal fun interface ApnsTokenProvider {
    fun token(): String

    /** Drop any cached token so the next [token] call re-mints. No-op for stateless providers. */
    fun invalidate() {}
}

@Serializable
private data class ApnsErrorResponse(val reason: String? = null)

private class ApnsJwtProvider(
    private val teamId: String,
    private val keyId: String,
    private val privateKey: PrivateKey,
) : ApnsTokenProvider {
    @Volatile
    private var cached: CachedToken? = null

    override fun token(): String = token(System.currentTimeMillis() / 1000)

    override fun invalidate() {
        cached = null
    }

    fun token(nowSeconds: Long): String {
        cached?.let { if (nowSeconds - it.issuedAtSeconds < TOKEN_TTL_SECONDS) return it.jwt }
        synchronized(this) {
            cached?.let { if (nowSeconds - it.issuedAtSeconds < TOKEN_TTL_SECONDS) return it.jwt }
            val header = Es256.b64Url.encodeToString("""{"alg":"ES256","kid":"$keyId"}""".toByteArray())
            val claims = Es256.b64Url.encodeToString("""{"iss":"$teamId","iat":$nowSeconds}""".toByteArray())
            val signingInput = "$header.$claims"
            val sig = Es256.b64Url.encodeToString(Es256.sign(privateKey, signingInput.toByteArray(Charsets.US_ASCII)))
            return "$signingInput.$sig".also { cached = CachedToken(it, nowSeconds) }
        }
    }

    private data class CachedToken(val jwt: String, val issuedAtSeconds: Long)

    private companion object {
        const val TOKEN_TTL_SECONDS = 50L * 60L
    }
}
