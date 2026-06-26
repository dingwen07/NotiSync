package net.extrawdw.notisync.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RouteEnvironment
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.PrivateKey
import java.time.Duration

/**
 * APNs token-authenticated HTTP/2 adapter. Payloads are background pushes carrying NotiSync's opaque
 * inline envelope (`ct`) or relay pointer (`mid`); the iOS app decrypts and posts the local notification.
 */
class ApnsPushTransport internal constructor(
    private val topic: String,
    private val ttlMillis: Long,
    private val tokenProvider: ApnsTokenProvider,
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
                val endpoint = when (route.environment) {
                    RouteEnvironment.DEVELOPMENT -> "https://api.sandbox.push.apple.com"
                    RouteEnvironment.PRODUCTION -> "https://api.push.apple.com"
                }
                // Match the wake TTL to the relay TTL (as FcmPushTransport does) so a deferred wake can't
                // outlive the relay item it points to (else a wake delivered later is a dead pointer).
                val expiration = (System.currentTimeMillis() + ttlMillis) / 1000
                // This is a silent, end-to-end-encrypted background push: the client decrypts and posts the
                // local notification, so it MUST be apns-push-type=background at apns-priority=5 regardless of
                // urgency (an alert/priority-10 push needs plaintext the broker never holds). Urgency therefore
                // can't change the APNs delivery class here; see review note on iOS timeliness.
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("$endpoint/3/device/${route.routeRef}"))
                    .timeout(Duration.ofSeconds(20))
                    .header("authorization", "bearer ${tokenProvider.token()}")
                    .header("content-type", "application/json")
                    .header("apns-topic", topic)
                    .header("apns-push-type", "background")
                    .header("apns-priority", "5")
                    .header("apns-expiration", expiration.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(apnsPayload(data)))
                    .build()
                val response = client.send(request)
                if (response.statusCode in 200..299) return@withContext PushOutcome.DELIVERED
                // A 403 means the provider auth JWT was rejected (Expired/Invalid/MissingProviderToken).
                // Drop the cached token so the next send re-mints, instead of replaying the rejected token
                // for the rest of its ~50-minute cache window.
                if (response.statusCode == 403) tokenProvider.invalidate()
                val reason = apnsReason(response.body)
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

    private fun apnsPayload(data: Map<String, String>): String {
        val entries = data.entries.joinToString(",") { (k, v) -> "\"${jsonEscape(k)}\":\"${jsonEscape(v)}\"" }
        return if (entries.isEmpty()) {
            """{"aps":{"content-available":1}}"""
        } else {
            """{"aps":{"content-available":1},$entries}"""
        }
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

        fun createOrNull(config: ServerConfig): PushTransport? {
            if (!config.apnsEnabled) return null
            val missing = buildList {
                if (config.apnsTeamId.isBlank()) add("NOTISYNC_APNS_TEAM_ID")
                if (config.apnsKeyId.isBlank()) add("NOTISYNC_APNS_KEY_ID")
                if (config.apnsPrivateKeyPath.isBlank()) add("NOTISYNC_APNS_PRIVATE_KEY_PATH")
                if (config.apnsTopic.isBlank()) add("NOTISYNC_APNS_TOPIC")
            }
            if (missing.isNotEmpty()) {
                log.warn("APNs disabled; missing {}", missing.joinToString(","))
                return null
            }
            return try {
                val key = Es256.loadEcPrivateKeyPem(File(config.apnsPrivateKeyPath).readText())
                log.info("APNs transport enabled for topic {}", config.apnsTopic)
                ApnsPushTransport(
                    topic = config.apnsTopic,
                    ttlMillis = config.relayTtlMillis,
                    tokenProvider = ApnsJwtProvider(config.apnsTeamId, config.apnsKeyId, key),
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
