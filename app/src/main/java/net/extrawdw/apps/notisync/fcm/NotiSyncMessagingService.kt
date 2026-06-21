package net.extrawdw.apps.notisync.fcm

import android.annotation.SuppressLint
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.channel.SecureChannel
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.ProtocolCodec
import java.util.Base64

/**
 * FCM data-message handler. The broker sends data-only messages: an inline encrypted envelope ("ct")
 * for small payloads, or a wake pointer ("mid") for large ones (pulled over the WebSocket transport).
 * Decryption happens locally in the [SecureChannel] — FCM never sees plaintext. [SecureChannel.deliver]
 * is non-suspend and runs inline here, preserving the synchronous-completion contract of this thread.
 */
// Routing uses the FCM installation id from the newer register()/onRegistered() flow (see
// AppGraph.registerFcmRoute), not the legacy registration token — so onNewToken() is intentionally
// absent. The MissingFirebaseInstanceTokenRefresh check only knows about the legacy onNewToken path.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class NotiSyncMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val channel = (applicationContext as NotiSyncApp).graph.secureChannel ?: return
        val ct = message.data["ct"]
        if (ct != null) {
            val envelope = runCatching {
                ProtocolCodec.decodeFromCbor<Envelope>(Base64.getDecoder().decode(ct))
            }.getOrNull() ?: return
            channel.deliver(envelope)
        }
        // Wake-only messages ("typ"="wake") are reconciled by the WebSocket transport's relay flush.
    }

    override fun onRegistered(installationId: String) {
        (applicationContext as NotiSyncApp).graph.onFcmRegistered(installationId)
    }
}
