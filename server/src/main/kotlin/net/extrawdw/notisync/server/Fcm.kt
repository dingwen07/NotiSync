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
import net.extrawdw.notisync.protocol.TransportType
import net.extrawdw.notisync.protocol.Urgency
import org.slf4j.LoggerFactory

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
