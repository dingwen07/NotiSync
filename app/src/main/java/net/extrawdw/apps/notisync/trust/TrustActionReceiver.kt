package net.extrawdw.apps.notisync.trust

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.notisync.protocol.ClientId

/**
 * Handles the Approve / Reject actions on a pending-trust notification without opening the app:
 * applies the decision to the trust store, propagates an overturn, and dismisses the notification.
 */
class TrustActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? NotiSyncApp ?: return
        val clientId = ClientId(intent.getStringExtra(EXTRA_CLIENT_ID) ?: return)
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        val now = System.currentTimeMillis()
        when (intent.action) {
            ACTION_APPROVE -> app.graph.trust.approveTrust(clientId, now) // agree -> not re-broadcast
            ACTION_REJECT -> if (app.graph.trust.rejectTrust(clientId, now)) app.graph.broadcastTrust() // overturn -> propagate
        }
        if (notifId != 0) NotificationManagerCompat.from(context).cancel(notifId)
    }

    companion object {
        const val ACTION_APPROVE = "net.extrawdw.apps.notisync.action.TRUST_APPROVE"
        const val ACTION_REJECT = "net.extrawdw.apps.notisync.action.TRUST_REJECT"
        const val EXTRA_CLIENT_ID = "clientId"
        const val EXTRA_NOTIF_ID = "notifId"
    }
}
