package net.extrawdw.apps.notisync.trust

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.analytics.crashGuard
import net.extrawdw.notisync.protocol.ClientId

/**
 * Handles the inline actions on a pending-trust/revoke notification without opening the app: applies the
 * decision to the trust store, propagates an overturn, and dismisses the notification. (Agreements —
 * approve a trust, confirm a revoke — don't re-broadcast; overturns — reject, keep — do.)
 */
class TrustActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? NotiSyncApp ?: return
        val clientId = ClientId(intent.getStringExtra(EXTRA_CLIENT_ID) ?: return)
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default + crashGuard("TrustActionReceiver")).launch {
            try {
                val graph = app.awaitGraphReady() ?: return@launch
                val trust = graph.trust
                val now = System.currentTimeMillis()
                when (intent.action) {
                    ACTION_APPROVE -> trust.approveTrust(clientId, now)
                    ACTION_REJECT -> if (trust.rejectTrust(clientId, now)) graph.broadcastTrust()
                    ACTION_CONFIRM_REVOKE -> trust.confirmRevoke(clientId, now)
                    ACTION_KEEP -> if (trust.keepTrusted(clientId, now)) graph.broadcastTrust()
                }
                if (notifId != 0) NotificationManagerCompat.from(context).cancel(notifId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_APPROVE = "net.extrawdw.apps.notisync.action.TRUST_APPROVE"
        const val ACTION_REJECT = "net.extrawdw.apps.notisync.action.TRUST_REJECT"
        const val ACTION_CONFIRM_REVOKE = "net.extrawdw.apps.notisync.action.TRUST_CONFIRM_REVOKE"
        const val ACTION_KEEP = "net.extrawdw.apps.notisync.action.TRUST_KEEP"
        const val EXTRA_CLIENT_ID = "clientId"
        const val EXTRA_NOTIF_ID = "notifId"
    }
}
