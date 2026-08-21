package net.extrawdw.apps.notisync.sshagent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.MainActivity
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.notification.requestPagePendingIntentOptions
import net.extrawdw.apps.notisync.notification.tryOpenRequestPageWhileUnlocked
import net.extrawdw.notisync.protocol.SshImportSourceType

class SshAgentNotificationPresenter(
    private val context: Context,
    private val store: SshKeyProviderStore,
    private val openRequestPageAutomatically: () -> Boolean = { false },
) {
    fun post(
        stored: StoredSshProviderRequest,
        requesterName: String,
        openImmediately: Boolean = false,
    ): Boolean {
        ensureChannel()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val safeRequesterName = requesterName.take(MAX_CONTEXT_CHARS)
        val knownHostname = stored.signRequest?.destinationContext?.let(store::knownHostHostname)
        val destination = stored.destinationLabel(knownHostname)?.take(MAX_CONTEXT_CHARS)
        val process = stored.signRequest?.processContext?.processLineage?.mainCallerLabel()
        val keyName = when (stored.kind) {
            SshProviderRequestKind.SIGN -> stored.history.keyName
                ?: context.getString(R.string.ssh_agent_notification_unknown_key)
            SshProviderRequestKind.IMPORT -> stored.importRequest?.suggestedName
                ?: stored.history.suggestedName
                ?: context.getString(R.string.ssh_agent_imported_key_default)
        }.take(MAX_CONTEXT_CHARS)
        val title = when (stored.kind) {
            SshProviderRequestKind.SIGN -> destination?.let {
                context.getString(R.string.ssh_agent_notification_sign_title_with_destination, it)
            } ?: context.getString(R.string.ssh_agent_notification_sign_title)
            SshProviderRequestKind.IMPORT -> context.getString(
                R.string.ssh_agent_notification_import_title,
                keyName,
            )
        }
        val content = when (stored.kind) {
            SshProviderRequestKind.SIGN -> context.getString(
                R.string.ssh_agent_notification_sign_content,
                safeRequesterName,
                keyName,
            )
            SshProviderRequestKind.IMPORT -> context.getString(
                R.string.ssh_agent_notification_import_content,
                safeRequesterName,
                keyName,
            )
        }
        val expandedDetails = when (stored.kind) {
            SshProviderRequestKind.SIGN -> {
                listOfNotNull(
                    destination?.let {
                        context.getString(R.string.ssh_agent_notification_destination, it)
                    },
                    process?.take(MAX_CONTEXT_CHARS)?.let {
                        context.getString(R.string.ssh_agent_notification_process, it)
                    },
                    context.getString(R.string.ssh_agent_notification_key, keyName),
                )
            }
            SshProviderRequestKind.IMPORT -> listOfNotNull(
                when (stored.history.importSourceType ?: stored.importRequest?.sourceType) {
                    SshImportSourceType.AGENT_IDENTITY ->
                        context.getString(R.string.ssh_agent_import_source_agent)
                    SshImportSourceType.PRIVATE_KEY_FILE ->
                        context.getString(R.string.ssh_agent_import_source_file)
                    null -> null
                }?.let { context.getString(R.string.ssh_agent_notification_source, it) },
            )
        }
        val expandedText = buildList {
            add(content)
            addAll(expandedDetails)
            add(context.getString(R.string.ssh_agent_notification_request, stored.requestId.take(8)))
        }.joinToString("\n")
        val autoOpenEnabled = openRequestPageAutomatically()
        val review = PendingIntent.getActivity(
            context,
            notificationId(stored.requestId),
            SshAgentReviewActivity.intent(context, stored.requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            requestPagePendingIntentOptions(),
        )
        val automaticReview = PendingIntent.getActivity(
            context,
            notificationId(stored.requestId),
            SshAgentReviewActivity.autoOpenIntent(context, stored.requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            requestPagePendingIntentOptions(),
        )
        val approve = PendingIntent.getActivity(
            context,
            notificationId(stored.requestId),
            SshAgentReviewActivity.approveIntent(context, stored.requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reject = PendingIntent.getBroadcast(
            context,
            notificationId(stored.requestId),
            SshAgentActionReceiver.rejectIntent(context, stored.requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Keep lock-screen content useful without exposing requester, key, destination, process, or request ID.
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentTitle(context.getString(R.string.ssh_agent_name))
            .setContentText(context.getString(R.string.ssh_agent_notification_public_content))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setSubText(context.getString(R.string.ssh_agent_name))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(review)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .addAction(0, context.getString(R.string.action_reject), reject)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    context.getString(R.string.action_approve),
                    approve,
                ).setAuthenticationRequired(true).build(),
            )
            .apply {
                if (autoOpenEnabled) {
                    setFullScreenIntent(automaticReview, true)
                }
            }
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(stored.requestId), notification)
        if (openImmediately && autoOpenEnabled) {
            tryOpenRequestPageWhileUnlocked(context, automaticReview)
        }
        return true
    }

    fun postAutoApproved(stored: StoredSshProviderRequest, requesterName: String): Boolean {
        ensureChannel()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val authorizationId = stored.history.rememberedAuthorizationId ?: return false
        if (stored.history.approvalKind != SshRequestApprovalKind.REMEMBERED_AUTHORIZATION ||
            stored.outcome != SshProviderRequestOutcome.SIGNED
        ) return false
        val safeRequesterName = requesterName.take(MAX_CONTEXT_CHARS)
        val knownHostname = stored.signRequest?.destinationContext?.let(store::knownHostHostname)
            ?: stored.history.destinationHostKeyFingerprint?.let { fingerprint ->
                store.knownHosts().firstOrNull { it.fingerprint() == fingerprint }?.hostname
            }
        val destination = stored.destinationLabel(knownHostname)?.take(MAX_CONTEXT_CHARS)
        val process = stored.history.processLineage.mainCallerLabel()?.take(MAX_CONTEXT_CHARS)
        val keyName = (stored.history.keyName
            ?: context.getString(R.string.ssh_agent_notification_unknown_key)).take(MAX_CONTEXT_CHARS)
        val content = context.getString(
            R.string.ssh_agent_notification_auto_approved_content,
            safeRequesterName,
            keyName,
        )
        val expandedText = buildList {
            add(content)
            destination?.let { add(context.getString(R.string.ssh_agent_notification_destination, it)) }
            process?.let { add(context.getString(R.string.ssh_agent_notification_process, it)) }
            add(context.getString(R.string.ssh_agent_notification_key, keyName))
            add(context.getString(R.string.ssh_agent_notification_request, stored.requestId.take(8)))
        }.joinToString("\n")
        val reviewIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_SSH_HISTORY
            data = Uri.parse("notisync://ssh-history/${stored.requestId}")
            putExtra(MainActivity.EXTRA_SSH_REQUEST_ID, stored.requestId)
        }
        val review = PendingIntent.getActivity(
            context,
            notificationId(stored.requestId),
            reviewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val forget = PendingIntent.getBroadcast(
            context,
            notificationId(stored.requestId),
            SshAgentActionReceiver.forgetAuthorizationIntent(
                context,
                stored.requestId,
                authorizationId,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentTitle(context.getString(R.string.ssh_agent_name))
            .setContentText(context.getString(R.string.ssh_agent_notification_auto_approved_public_content))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal_notification)
            .setContentTitle(context.getString(R.string.ssh_agent_notification_auto_approved_title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setSubText(context.getString(R.string.ssh_agent_name))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(review)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.ssh_agent_remembered_delete_confirm), forget)
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
                enableVibration(true)
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "ssh_agent_requests"
        const val MAX_CONTEXT_CHARS = 160
        fun notificationId(requestId: String) = requestId.hashCode() and 0x7fffffff
    }
}

class SshAgentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ACTION_REJECT, ACTION_FORGET_AUTHORIZATION)) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val app = context.applicationContext as? NotiSyncApp ?: return
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = app.awaitGraphReady() ?: return@launch
                when (intent.action) {
                    ACTION_REJECT -> graph.sshAgentProviderEngine?.reject(requestId)
                    ACTION_FORGET_AUTHORIZATION -> {
                        val authorizationId = intent.getStringExtra(EXTRA_AUTHORIZATION_ID) ?: return@launch
                        if (graph.sshKeyProviderStore.deleteRememberedAuthorization(authorizationId)) {
                            graph.sshAgentProviderEngine?.publishInventory()
                        }
                        graph.sshAgentNotifications.dismiss(requestId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_REJECT = "net.extrawdw.apps.notisync.action.SSH_AGENT_REJECT"
        private const val ACTION_FORGET_AUTHORIZATION =
            "net.extrawdw.apps.notisync.action.SSH_AGENT_FORGET_AUTHORIZATION"
        private const val EXTRA_REQUEST_ID = "ssh_agent_request_id"
        private const val EXTRA_AUTHORIZATION_ID = "ssh_agent_authorization_id"
        fun rejectIntent(context: Context, requestId: String) = Intent(context, SshAgentActionReceiver::class.java)
            .setAction(ACTION_REJECT)
            .putExtra(EXTRA_REQUEST_ID, requestId)

        fun forgetAuthorizationIntent(
            context: Context,
            requestId: String,
            authorizationId: String,
        ) = Intent(context, SshAgentActionReceiver::class.java)
            .setAction(ACTION_FORGET_AUTHORIZATION)
            .setData(Uri.parse("notisync://ssh-authorization/$authorizationId"))
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_AUTHORIZATION_ID, authorizationId)
    }
}
