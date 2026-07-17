package net.extrawdw.apps.notisync.fcm

import android.annotation.SuppressLint
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.apps.notisync.work.WakeFetchWorker
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.protocol.Envelope
import net.extrawdw.notisync.protocol.ProtocolCodec
import java.util.Base64

/**
 * FCM data-message handler. The broker sends data-only messages: an inline encrypted envelope ("ct")
 * for small payloads, or a wake pointer ("mid") for large ones — which WorkManager pulls from the
 * broker's relay by id rather than waiting for the next foreground WebSocket flush.
 * Decryption happens locally in the [SecureChannel] — FCM never sees plaintext. [SecureChannel.deliver]
 * is non-suspend and runs inline here, preserving the synchronous-completion contract of this thread.
 */
// Routing uses the FCM installation id from the newer register()/onRegistered() flow (see
// AppGraph.registerFcmRoute), not the legacy registration token — so onNewToken() is intentionally
// absent. The MissingFirebaseInstanceTokenRefresh check only knows about the legacy onNewToken path.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class NotiSyncMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val ct = message.data["ct"]
        if (ct != null) {
            val envelope = runCatching {
                ProtocolCodec.decodeFromCbor<Envelope>(Base64.getDecoder().decode(ct))
            }.getOrNull() ?: return
            val graph = (applicationContext as NotiSyncApp)
                .awaitGraphReadyBlocking(INLINE_GRAPH_WAIT_MS)
                ?: return
            val channel = graph.secureChannel ?: return
            // Inline delivery never gets fetched, so the broker can't drop its relay copy on its own —
            // queue it for the worker's batch ack (skipped for a dropped-unhandled message).
            val outcome = channel.deliver(envelope, DeliveryMode.FCM_INLINE)
            graph.onInlineDelivered(envelope.messageId, outcome)
            return
        }
        // Wake-only message ("typ"="wake"): the payload was too large to inline. Hand off immediately
        // to WorkManager so this FCM callback never blocks on graph init, network, or relay fetch time.
        message.data["mid"]?.let { mid ->
            WakeFetchWorker.enqueue(applicationContext, mid)
        }
    }

    override fun onRegistered(installationId: String) {
        (applicationContext as NotiSyncApp).runWhenGraphReady { it.onFcmRegistered(installationId) }
    }

    private companion object {
        const val INLINE_GRAPH_WAIT_MS = 10_000L
    }
}
