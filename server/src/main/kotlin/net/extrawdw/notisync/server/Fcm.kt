package net.extrawdw.notisync.server

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64

/** Outcome of a wake/inline push, mapped to NotiSync route-state semantics. */
enum class PushOutcome { DELIVERED, ROUTE_INVALID, TRANSIENT_FAILURE, DISABLED }

/** A wake + small-message transport. Trusted only to wake and carry opaque ciphertext, never plaintext. */
interface PushTransport {
    suspend fun wake(route: StoredRoute, data: Map<String, String>, urgency: Urgency): PushOutcome
}

/** No-op transport used when provider credentials are absent (dev runs rely on the WebSocket path). */
object DisabledPushTransport : PushTransport {
    override suspend fun wake(route: StoredRoute, data: Map<String, String>, urgency: Urgency) = PushOutcome.DISABLED
}

class CompositePushTransport(private val delegates: Map<TransportType, PushTransport>) : PushTransport {
    override suspend fun wake(route: StoredRoute, data: Map<String, String>, urgency: Urgency): PushOutcome =
        delegates[route.transport]?.wake(route, data, urgency) ?: PushOutcome.DISABLED

    companion object {
        fun create(config: ServerConfig): CompositePushTransport {
            val delegates = buildMap {
                FcmPushTransport.createOrNull(config)?.let { put(TransportType.FCM, it) }
                ApnsPushTransport.createOrNull(config)?.let { put(TransportType.APNS, it) }
            }
            return CompositePushTransport(delegates)
        }
    }
}

/**
 * FCM HTTP v1 adapter via firebase-admin. Sends DATA-ONLY messages (the client decides how to
 * surface them) at HIGH priority for user-visible mirrors and NORMAL for background sync/repair.
 */
class FcmPushTransport private constructor(
    private val app: FirebaseApp,
    /** FCM message TTL, matched to the relay TTL so a deferred wake can't outlive the relay item it points to. */
    private val ttlMillis: Long,
) : PushTransport {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun wake(route: StoredRoute, data: Map<String, String>, urgency: Urgency): PushOutcome =
        withContext(Dispatchers.IO) {
            if (route.transport != TransportType.FCM) return@withContext PushOutcome.DISABLED
            val android = AndroidConfig.builder()
                .setPriority(if (urgency == Urgency.HIGH) AndroidConfig.Priority.HIGH else AndroidConfig.Priority.NORMAL)
                .setTtl(ttlMillis.coerceIn(0, MAX_FCM_TTL_MILLIS))
                .build()
            val message = Message.builder()
                .setToken(route.routeRef)
                .putAllData(data)
                .setAndroidConfig(android)
                .build()
            try {
                FirebaseMessaging.getInstance(app).send(message)
                PushOutcome.DELIVERED
            } catch (e: FirebaseMessagingException) {
                when (e.messagingErrorCode) {
                    MessagingErrorCode.UNREGISTERED,
                    MessagingErrorCode.SENDER_ID_MISMATCH,
                    MessagingErrorCode.INVALID_ARGUMENT -> PushOutcome.ROUTE_INVALID

                    MessagingErrorCode.UNAVAILABLE,
                    MessagingErrorCode.INTERNAL,
                    MessagingErrorCode.QUOTA_EXCEEDED -> PushOutcome.TRANSIENT_FAILURE

                    else -> PushOutcome.TRANSIENT_FAILURE
                }
            } catch (e: Exception) {
                log.warn("FCM send failed: {}", e.message)
                PushOutcome.TRANSIENT_FAILURE
            }
        }

    companion object {
        private val log = LoggerFactory.getLogger(FcmPushTransport::class.java)

        /** FCM's hard ceiling on AndroidConfig TTL: 28 days. */
        private const val MAX_FCM_TTL_MILLIS = 28L * 24 * 60 * 60 * 1000

        /**
         * Initialize FCM from Application Default Credentials (GOOGLE_APPLICATION_CREDENTIALS, gcloud
         * ADC, or workload identity). Returns null if credentials are unavailable.
         */
        fun createOrNull(config: ServerConfig): PushTransport? {
            if (!config.fcmEnabled) return null
            return try {
                val credentials = GoogleCredentials.getApplicationDefault()
                val app = if (FirebaseApp.getApps().isEmpty()) {
                    FirebaseApp.initializeApp(
                        FirebaseOptions.builder()
                            .setCredentials(credentials)
                            .setProjectId(config.fcmProjectId)
                            .build()
                    )
                } else {
                    FirebaseApp.getInstance()
                }
                log.info("FCM transport enabled for project {}", config.fcmProjectId)
                FcmPushTransport(app, config.relayTtlMillis)
            } catch (e: Exception) {
                log.warn("FCM credentials unavailable ({}); FCM push disabled.", e.message)
                null
            }
        }
    }
}

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
                val expiration = (System.currentTimeMillis() + ttlMillis).coerceAtLeast(0) / 1000
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
                apnsOutcome(response.statusCode, response.body).also { outcome ->
                    if (outcome != PushOutcome.DELIVERED) {
                        log.info(
                            "APNs rejected push client={} status={} reason={} outcome={}",
                            route.clientId.shortForm(),
                            response.statusCode,
                            apnsReason(response.body) ?: "unknown",
                            outcome,
                        )
                    }
                }
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

    private fun apnsOutcome(statusCode: Int, body: String): PushOutcome {
        if (statusCode in 200..299) return PushOutcome.DELIVERED
        val reason = apnsReason(body)
        return when {
            statusCode == 410 -> PushOutcome.ROUTE_INVALID
            reason != null && reason in ROUTE_INVALID_REASONS -> PushOutcome.ROUTE_INVALID
            statusCode == 429 || statusCode in 500..599 -> PushOutcome.TRANSIENT_FAILURE
            else -> PushOutcome.TRANSIENT_FAILURE
        }
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
                val key = ApnsPrivateKey.load(config.apnsPrivateKeyPath)
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
}

@kotlinx.serialization.Serializable
private data class ApnsErrorResponse(val reason: String? = null)

private class ApnsJwtProvider(
    private val teamId: String,
    private val keyId: String,
    private val privateKey: PrivateKey,
) : ApnsTokenProvider {
    @Volatile
    private var cached: CachedToken? = null

    override fun token(): String = token(System.currentTimeMillis() / 1000)

    fun token(nowSeconds: Long): String {
        cached?.let { if (nowSeconds - it.issuedAtSeconds < TOKEN_TTL_SECONDS) return it.jwt }
        synchronized(this) {
            cached?.let { if (nowSeconds - it.issuedAtSeconds < TOKEN_TTL_SECONDS) return it.jwt }
            val header = b64Url("""{"alg":"ES256","kid":"$keyId"}""".toByteArray())
            val claims = b64Url("""{"iss":"$teamId","iat":$nowSeconds}""".toByteArray())
            val signingInput = "$header.$claims"
            val sig = b64Url(signEs256(signingInput.toByteArray(Charsets.US_ASCII)))
            return "$signingInput.$sig".also { cached = CachedToken(it, nowSeconds) }
        }
    }

    private fun signEs256(data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
        return derToJose(der)
    }

    private data class CachedToken(val jwt: String, val issuedAtSeconds: Long)

    private companion object {
        const val TOKEN_TTL_SECONDS = 50L * 60L
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        fun b64Url(bytes: ByteArray): String = encoder.encodeToString(bytes)
    }
}

private object ApnsPrivateKey {
    fun load(path: String): PrivateKey {
        val pem = File(path).readText()
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        val der = Base64.getDecoder().decode(pem)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    }
}

private fun derToJose(der: ByteArray): ByteArray {
    var index = 0
    fun readByte(): Int = der[index++].toInt() and 0xff
    fun readLength(): Int {
        val first = readByte()
        if (first and 0x80 == 0) return first
        val bytes = first and 0x7f
        var value = 0
        repeat(bytes) { value = (value shl 8) or readByte() }
        return value
    }
    fun readInteger(): ByteArray {
        require(readByte() == 0x02) { "bad ECDSA DER integer" }
        val length = readLength()
        return der.copyOfRange(index, index + length).also { index += length }
    }

    require(readByte() == 0x30) { "bad ECDSA DER sequence" }
    readLength()
    val r = readInteger().toJoseComponent()
    val s = readInteger().toJoseComponent()
    return r + s
}

private fun ByteArray.toJoseComponent(): ByteArray {
    var start = 0
    while (size - start > 32 && this[start] == 0.toByte()) start++
    val trimmed = copyOfRange(start, size)
    require(trimmed.size <= 32) { "ECDSA coordinate too large" }
    return ByteArray(32 - trimmed.size) + trimmed
}
