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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R

class SshAgentNotificationPresenter(private val context: Context) {
    fun post(stored: StoredSshProviderRequest, requesterName: String): Boolean {
        ensureChannel()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val title = context.getString(
            if (stored.kind == SshProviderRequestKind.SIGN) {
                R.string.ssh_agent_notification_sign_title
            } else {
                R.string.ssh_agent_notification_import_title
            },
        )
        val detail = when (stored.kind) {
            SshProviderRequestKind.SIGN -> {
                val request = requireNotNull(stored.signRequest)
                val host = request.destinationContext.hostAliases.firstOrNull()?.value
                val destination = host?.let { request.destinationContext.username?.let { user -> "$user@$it" } ?: it }
                destination ?: request.processContext.processLineage.mainCallerLabel()
                    ?: context.getString(R.string.ssh_agent_notification_unknown_destination)
            }
            SshProviderRequestKind.IMPORT -> stored.importRequest?.suggestedName
                ?: context.getString(R.string.ssh_agent_imported_key_default)
        }.take(160)
        val review = PendingIntent.getActivity(
            context,
            notificationId(stored.requestId),
            SshAgentReviewActivity.intent(context, stored.requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Approval is deliberately not encoded in a PendingIntent. Android ignores extras when comparing
        // PendingIntent identity, so a same-identity "approve" intent can rewrite the notification tap intent.
        val reject = PendingIntent.getBroadcast(
            context,
            notificationId(stored.requestId),
            SshAgentActionReceiver.rejectIntent(context, stored.requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.ssh_agent_notification_content, requesterName, detail))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(
                        R.string.ssh_agent_notification_details,
                        requesterName,
                        detail,
                        stored.requestId.take(8),
                    ),
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(review)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .addAction(0, context.getString(R.string.action_reject), reject)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    context.getString(R.string.action_approve),
                    review,
                ).build(),
            )
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(stored.requestId), notification)
        return true
    }

    fun dismiss(requestId: String) = NotificationManagerCompat.from(context).cancel(notificationId(requestId))

    private fun ensureChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.ssh_agent_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.ssh_agent_notification_channel_description)
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
