package net.extrawdw.apps.notisync.sshagent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R

class SshAgentNotificationPresenter(private val context: Context) {
    fun post(stored: StoredSshProviderRequest, requesterName: String): Boolean {
        ensureChannel()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val title = if (stored.kind == SshProviderRequestKind.SIGN) "Approve SSH signature" else "Import SSH private key"
        val detail = when (stored.kind) {
            SshProviderRequestKind.SIGN -> {
                val request = requireNotNull(stored.signRequest)
                val destination = request.destinationContext.username?.let { user ->
                    request.destinationContext.hostAliases.firstOrNull()?.value?.let { "$user@$it" }
                }
                destination ?: request.processContext.directParent?.displayName ?: "Unknown destination"
            }
            SshProviderRequestKind.IMPORT -> stored.importRequest?.suggestedName ?: "Imported SSH key"
        }.take(160)
        val review = PendingIntent.getActivity(
            context,
            notificationId(stored.requestId),
            SshAgentReviewActivity.intent(context, stored.requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reject = PendingIntent.getBroadcast(
            context,
            notificationId(stored.requestId),
            SshAgentActionReceiver.rejectIntent(context, stored.requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val approve = PendingIntent.getActivity(
            context,
            notificationId(stored.requestId),
            SshAgentReviewActivity.approveIntent(context, stored.requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentTitle(title)
            .setContentText("$requesterName · $detail")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Requested by $requesterName\n$detail\n${stored.requestId.take(8)}"))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(review)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .addAction(0, "Reject", reject)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    if (stored.kind == SshProviderRequestKind.IMPORT) "Choose storage" else "Approve",
                    approve,
                ).setAuthenticationRequired(true).build(),
            )
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(stored.requestId), notification)
        return true
    }

    fun dismiss(requestId: String) = NotificationManagerCompat.from(context).cancel(notificationId(requestId))

    private fun ensureChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SSH Agent approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Private SSH key import and signature approval requests"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "ssh_agent_requests"
        fun notificationId(requestId: String) = requestId.hashCode() and 0x7fffffff
    }
}

class SshAgentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REJECT) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val app = context.applicationContext as? NotiSyncApp ?: return
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                app.awaitGraphReady()?.sshAgentProviderEngine?.reject(requestId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_REJECT = "net.extrawdw.apps.notisync.action.SSH_AGENT_REJECT"
        private const val EXTRA_REQUEST_ID = "ssh_agent_request_id"
        fun rejectIntent(context: Context, requestId: String) = Intent(context, SshAgentActionReceiver::class.java)
            .setAction(ACTION_REJECT)
            .putExtra(EXTRA_REQUEST_ID, requestId)
    }
}
