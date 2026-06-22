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
import net.extrawdw.notisync.protocol.Urgency
import org.slf4j.LoggerFactory

/** Outcome of a wake/inline push, mapped to NotiSync route-state semantics. */
enum class PushOutcome { DELIVERED, ROUTE_INVALID, TRANSIENT_FAILURE, DISABLED }

/** A wake + small-message transport. Trusted only to wake and carry opaque ciphertext, never plaintext. */
interface PushTransport {
    suspend fun wake(routeRef: String, data: Map<String, String>, urgency: Urgency): PushOutcome
}

/** No-op transport used when FCM credentials are absent (dev runs rely on the WebSocket path). */
object DisabledPushTransport : PushTransport {
    override suspend fun wake(routeRef: String, data: Map<String, String>, urgency: Urgency) = PushOutcome.DISABLED
}

/**
 * FCM HTTP v1 adapter via firebase-admin. Sends DATA-ONLY messages (the client decides how to
 * surface them) at HIGH priority for user-visible mirrors and NORMAL for background sync/repair.
 * Maps provider error codes so the broker can retire invalid routes and back off on transient
 * failures.
 *
 * routeRef is the FCM direct-send target produced by FirebaseMessagingService.onRegistered().
 * firebase-admin 9.9.0 has not grown a first-class FID setter yet, so use setToken(routeRef) while
 * FCM keeps that transition bridge. When Admin exposes a FID target, switch this call over.
 */
class FcmPushTransport private constructor(
    private val app: FirebaseApp,
    /** FCM message TTL, matched to the relay TTL so a deferred wake can't outlive the relay item it
     *  points to (else a wake delivered after deep Doze would be a dead pointer → fetch 404). */
    private val ttlMillis: Long,
) : PushTransport {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun wake(routeRef: String, data: Map<String, String>, urgency: Urgency): PushOutcome =
        withContext(Dispatchers.IO) {
            val android = AndroidConfig.builder()
                .setPriority(if (urgency == Urgency.HIGH) AndroidConfig.Priority.HIGH else AndroidConfig.Priority.NORMAL)
                .setTtl(ttlMillis.coerceIn(0, MAX_FCM_TTL_MILLIS))
                .build()
            val message = Message.builder()
                .setToken(routeRef)
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
         * ADC, or workload identity). Returns null if credentials are unavailable so the broker still
         * boots and serves the WebSocket transport.
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
                log.warn("FCM credentials unavailable ({}); push disabled, WebSocket transport only.", e.message)
                null
            }
        }
    }
}
