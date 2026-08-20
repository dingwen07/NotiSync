package net.extrawdw.apps.notisync.fcm

import android.annotation.SuppressLint
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.messaging.MAX_AUTHENTICATED_MESSAGE_ID_CHARS
import net.extrawdw.apps.notisync.work.WakeFetchWorker

/**
 * FCM data-message handler. The broker sends data-only messages: an inline encrypted envelope ("ct")
 * for small payloads, or a wake pointer ("mid") for large ones — which WorkManager pulls from the
 * broker's relay by id rather than waiting for the next foreground WebSocket flush.
 * FCM never sees plaintext. This callback extracts only the durable broker locator and immediately hands it to
 * WorkManager; authentication, decryption, storage, and feature dispatch happen under a valid worker lifecycle.
 * The broker relay copy is authoritative recovery state, so the inline ciphertext is never the sole copy.
 */
// Routing uses the FCM installation id from the newer register()/onRegistered() flow (see
// AppGraph.registerFcmRoute), not the legacy registration token — so onNewToken() is intentionally
// absent. The MissingFirebaseInstanceTokenRefresh check only knows about the legacy onNewToken path.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class NotiSyncMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val messageId = relayWakeMessageId(message.data) ?: return
        WakeFetchWorker.enqueue(
            context = applicationContext,
            messageId = messageId,
            expedited = message.priority == RemoteMessage.PRIORITY_HIGH,
        )
    }

    override fun onRegistered(installationId: String) {
        (applicationContext as? NotiSyncApp)?.runWhenReadyServices { services ->
            services.fcmRouteRegistration.onRegistered(installationId)
        }
    }
}

/** Bounded FCM locator parsing only; the broker supplies `mid` for both inline and wake pushes. */
internal fun relayWakeMessageId(data: Map<String, String>): String? =
    data["mid"]?.takeIf(::isValidRelayMessageId)

private fun isValidRelayMessageId(value: String): Boolean =
    value.isNotBlank() && value.length <= MAX_AUTHENTICATED_MESSAGE_ID_CHARS && value.none(Char::isISOControl)
