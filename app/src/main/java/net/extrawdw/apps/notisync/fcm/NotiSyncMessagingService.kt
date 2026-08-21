package net.extrawdw.apps.notisync.fcm

import android.annotation.SuppressLint
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.messaging.MAX_AUTHENTICATED_MESSAGE_ID_CHARS

/**
 * FCM data-message handler. The broker sends data-only messages: an inline encrypted envelope ("ct")
 * for small payloads, or a wake pointer ("mid") for large ones. Both include the durable broker locator.
 * FCM never sees plaintext. This callback exact-fetches and processes the broker-owned message during FCM's
 * execution window instead of routing prompt delivery through WorkManager.
 * The broker relay copy is authoritative recovery state, so the inline ciphertext is never the sole copy.
 */
// Routing uses the FCM installation id from the newer register()/onRegistered() flow (see
// AppGraph.registerFcmRoute), not the legacy registration token — so onNewToken() is intentionally
// absent. The MissingFirebaseInstanceTokenRefresh check only knows about the legacy onNewToken path.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class NotiSyncMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val messageId = relayWakeMessageId(message.data) ?: return
        (applicationContext as? NotiSyncApp)?.processFcmWakeBlocking(messageId)
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
