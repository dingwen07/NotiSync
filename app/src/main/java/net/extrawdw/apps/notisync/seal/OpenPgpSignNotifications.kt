package net.extrawdw.apps.notisync.seal

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.analytics.crashGuard
import net.extrawdw.apps.notisync.notification.requestPagePendingIntentOptions
import net.extrawdw.apps.notisync.notification.tryOpenRequestPageWhileUnlocked
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpRejectReason

/** Private notification-shade presentation for a pending signing decision. */
class OpenPgpSignNotificationPresenter(
    private val context: Context,
    private val openRequestPageAutomatically: () -> Boolean = { false },
) {
    fun post(
        stored: StoredOpenPgpRequest,
        requesterName: String,
        openImmediately: Boolean = false,
    ): Boolean {
        ensureChannel()
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        val requestId = stored.request.requestId
        val objectTitle = when (stored.request.objectKind) {
            OpenPgpObjectKind.GIT_COMMIT -> stored.commit?.message?.commitSubject()
                ?.takeIf(String::isNotBlank)
                ?.take(MAX_TITLE_CHARS)
                ?: context.getString(R.string.seal_commit_untitled)
            OpenPgpObjectKind.GIT_TAG -> stored.tag?.tagName
                ?.takeIf(String::isNotBlank)
                ?.take(MAX_TITLE_CHARS)
                ?: context.getString(R.string.seal_tag_untitled)
        }
        val repositoryName = stored.request.workingDirectory
            ?.workingDirectoryName()
            ?.takeIf(String::isNotBlank)
            ?.take(MAX_CONTEXT_CHARS)
        val safeRequesterName = requesterName.take(MAX_CONTEXT_CHARS)
        val requestText = when (stored.request.objectKind) {
            OpenPgpObjectKind.GIT_COMMIT -> repositoryName?.let {
                context.getString(R.string.seal_notification_body_with_repository, safeRequesterName, it)
            } ?: context.getString(R.string.seal_notification_body, safeRequesterName)
            OpenPgpObjectKind.GIT_TAG -> repositoryName?.let {
                context.getString(
                    R.string.seal_tag_notification_body_with_repository,
                    safeRequesterName,
                    objectTitle,
                    it,
                )
            } ?: context.getString(R.string.seal_tag_notification_body, safeRequesterName, objectTitle)
        }
        val identifiersText = context.getString(
            R.string.seal_notification_identifiers,
            requestId.take(8),
            stored.request.payloadSha256.toHex().take(7),
        )
        val identityText = (stored.commit?.author ?: stored.tag?.tagger)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(MAX_CONTEXT_CHARS)
            ?.let {
                context.getString(
                    if (stored.request.objectKind == OpenPgpObjectKind.GIT_TAG) {
                        R.string.seal_notification_tagger
                    } else {
                        R.string.seal_notification_author
                    },
                    it,
                )
            }
        val expandedText = listOfNotNull(requestText, identityText, identifiersText).joinToString("\n")
        val contentTitle = context.getString(
            if (stored.request.objectKind == OpenPgpObjectKind.GIT_TAG) {
                R.string.seal_notification_title_with_tag
            } else {
                R.string.seal_notification_title_with_commit
            },
            objectTitle,
        )
        val autoOpenEnabled = openRequestPageAutomatically()
        val review = PendingIntent.getActivity(
            context,
            notificationId(requestId),
            OpenPgpSignReviewActivity.intent(context, requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            requestPagePendingIntentOptions(),
        )
        val automaticReview = PendingIntent.getActivity(
            context,
            notificationId(requestId),
            OpenPgpSignReviewActivity.autoOpenIntent(context, requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            requestPagePendingIntentOptions(),
        )
        val rejectIntent = PendingIntent.getBroadcast(
            context,
            notificationId(requestId),
            OpenPgpSignActionReceiver.rejectIntent(context, requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val approveIntent = PendingIntent.getActivity(
            context,
            notificationId(requestId),
            OpenPgpSignReviewActivity.approveIntent(context, requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val rejectAction = NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.action_reject),
            rejectIntent,
        ).build()
        val approveAction = NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.action_approve),
            approveIntent,
        ).setAuthenticationRequired(true).build()
        // Keep lock-screen content useful without exposing Git object, repository, requester, identity, or hash details.
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notisync_mirror)
            .setContentTitle(context.getString(R.string.seal_name))
            .setContentText(context.getString(R.string.seal_notification_public_content))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notisync_mirror)
            .setContentTitle(contentTitle)
            .setContentText(requestText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setSubText(context.getString(R.string.seal_name))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(review)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(rejectAction)
            .addAction(approveAction)
            .apply {
                if (autoOpenEnabled) {
                    setFullScreenIntent(automaticReview, true)
                }
            }
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(requestId), notification)
        if (openImmediately && autoOpenEnabled) {
            tryOpenRequestPageWhileUnlocked(context, automaticReview)
        }
        return true
    }

    fun dismiss(requestId: String) {
        PendingIntent.getActivity(
            context,
            notificationId(requestId),
            OpenPgpSignReviewActivity.autoOpenIntent(context, requestId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            requestPagePendingIntentOptions(),
        )?.cancel()
        NotificationManagerCompat.from(context).cancel(notificationId(requestId))
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.seal_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.seal_notification_channel_description)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                enableVibration(true)
            }
        )
    }

    private companion object {
        const val CHANNEL_ID = "openpgp_sign_requests"
        const val MAX_TITLE_CHARS = 160
        const val MAX_CONTEXT_CHARS = 80

        fun notificationId(requestId: String): Int = requestId.hashCode() and 0x7fffffff
    }
}

/** Handles the non-interactive Reject shade action without opening the review activity. */
class OpenPgpSignActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REJECT) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val app = context.applicationContext as? NotiSyncApp ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + crashGuard("OpenPgpSignActionReceiver")).launch {
            try {
                val graph = app.awaitGraphReady() ?: return@launch
                if (
                    graph.openPgpSignStore.storeReject(
                        requestId,
                        OpenPgpRejectReason.USER_REJECTED,
                        System.currentTimeMillis(),
                    )
                ) {
                    graph.openPgpSignNotifications.dismiss(requestId)
                    OpenPgpSignResponseWorker.enqueue(context.applicationContext, requestId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_REJECT = "net.extrawdw.apps.notisync.action.SEAL_REJECT"
        private const val EXTRA_REQUEST_ID = "openpgp_request_id"

        fun rejectIntent(context: Context, requestId: String): Intent =
            Intent(context, OpenPgpSignActionReceiver::class.java)
                .setAction(ACTION_REJECT)
                .putExtra(EXTRA_REQUEST_ID, requestId)
    }
}
