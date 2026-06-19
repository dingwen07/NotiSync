package net.extrawdw.apps.notisync.notif

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.domain.MirrorEngine
import net.extrawdw.apps.notisync.domain.OriginalCanceler
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ConversationMessage
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotifStyle

/** Turns a platform [StatusBarNotification] into the normalized, transport-neutral form. */
class NotificationNormalizer(private val pm: PackageManager) {

    fun normalize(sbn: StatusBarNotification, sourceClientId: ClientId): CapturedNotification {
        val n = sbn.notification
        val extras = n.extras
        val pkg = sbn.packageName

        val messaging = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        }.getOrNull()

        val messages = messaging?.messages?.map {
            ConversationMessage(sender = it.person?.name?.toString(), text = it.text?.toString() ?: "", timestamp = it.timestamp)
        } ?: emptyList()

        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val style = when {
            messages.isNotEmpty() -> NotifStyle.MESSAGING
            !bigText.isNullOrBlank() -> NotifStyle.BIG_TEXT
            else -> NotifStyle.DEFAULT
        }

        return CapturedNotification(
            sourceClientId = sourceClientId,
            sourceKey = sbn.key,
            packageName = pkg,
            appLabel = appLabel(pkg),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = bigText,
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            style = style,
            conversationTitle = messaging?.conversationTitle?.toString(),
            isGroupConversation = messaging?.isGroupConversation ?: false,
            messages = messages,
            category = categoryOf(n.category),
            importance = if (n.category == Notification.CATEGORY_MESSAGE) MirrorImportance.HIGH else MirrorImportance.DEFAULT,
            postTime = sbn.postTime,
            groupKey = n.group,
            isOngoing = sbn.isOngoing,
            isClearable = sbn.isClearable,
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun categoryOf(category: String?): MirrorCategory = when (category) {
        Notification.CATEGORY_MESSAGE -> MirrorCategory.MESSAGE
        Notification.CATEGORY_EMAIL -> MirrorCategory.EMAIL
        Notification.CATEGORY_CALL -> MirrorCategory.CALL
        Notification.CATEGORY_ALARM -> MirrorCategory.ALARM
        Notification.CATEGORY_EVENT -> MirrorCategory.EVENT
        Notification.CATEGORY_REMINDER -> MirrorCategory.REMINDER
        Notification.CATEGORY_SOCIAL -> MirrorCategory.SOCIAL
        Notification.CATEGORY_PROGRESS -> MirrorCategory.PROGRESS
        Notification.CATEGORY_TRANSPORT -> MirrorCategory.TRANSPORT
        Notification.CATEGORY_SERVICE -> MirrorCategory.SERVICE
        Notification.CATEGORY_STATUS -> MirrorCategory.STATUS
        Notification.CATEGORY_ERROR -> MirrorCategory.ERROR
        Notification.CATEGORY_NAVIGATION -> MirrorCategory.NAVIGATION
        else -> MirrorCategory.NONE
    }
}

/**
 * The provider half: captures local notifications and forwards them. A plain bound service (the
 * platform binds it automatically) — never a foreground service. Offloads all work to the app scope
 * and backfills active notifications when (re)connected so missed posts are recovered.
 */
class NotiSyncListenerService : NotificationListenerService(), OriginalCanceler {

    private val app get() = applicationContext as NotiSyncApp
    private val engine: MirrorEngine? get() = app.graph.mirrorEngine
    private val normalizer by lazy { NotificationNormalizer(packageManager) }

    /**
     * Keys of notifications we actually mirrored. We sync a dismissal only for these, so a
     * not-enabled app's dismissal is never sent, and grouped/child dismissals are caught regardless
     * of the exact removal reason. Listener callbacks run on the main thread; the set is in-memory
     * and repopulated from active notifications on (re)connect.
     */
    private val mirroredKeys = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Reasons that represent a genuine USER dismissal we should sync (allowlist). Per AOSP: swiping
     * one notification → REASON_CANCEL; "Clear all" → REASON_CANCEL_ALL; each child of a dismissed
     * group → REASON_GROUP_SUMMARY_CANCELED. Everything else (app cancel, timeout, package change,
     * regroup, snooze, and our own REASON_LISTENER_CANCEL echo) is intentionally NOT synced.
     */
    private val syncReasons = setOf(
        REASON_CANCEL,
        REASON_CANCEL_ALL,
        REASON_GROUP_SUMMARY_CANCELED,
    )

    override fun onListenerConnected() {
        engine?.originalCanceler = this
        // Backfill: mirror everything already showing that we'd capture, so reconnects don't miss state.
        runCatching { activeNotifications }.getOrNull()?.forEach { handlePosted(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handlePosted(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        val wasMirrored = mirroredKeys.remove(sbn.key)
        if (reason !in syncReasons) return // not a user dismissal (echo / app-cancel / timeout / regroup)
        if (!wasMirrored) {
            // A user dismissal of something we didn't mirror: a not-enabled app, or a child that was
            // transiently filtered at post time. Logged to surface the latter during on-device testing.
            Log.d(TAG, "skip dismissal for unmirrored key reason=$reason pkg=${sbn.packageName}")
            return
        }
        val eng = engine ?: return
        val clientId = app.graph.clientId ?: return
        app.graph.scope.launch { eng.dismissLocal(clientId, sbn.key) }
    }

    private companion object {
        const val TAG = "NotiSyncListener"
    }

    override fun cancel(sourceKey: String) {
        runCatching { cancelNotification(sourceKey) }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return                              // never mirror ourselves
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return            // group summaries
        // Learn which apps post notifications (for the picker + recency sort), even if not enabled.
        app.graph.appSelection?.recordSeen(sbn.packageName, sbn.postTime)
        if (sbn.isOngoing && !sbn.isClearable) return                          // media / transport / service
        if (app.graph.appSelection?.isEnabled(sbn.packageName) != true) return // opt-in: default OFF
        val eng = engine ?: return
        val clientId = app.graph.clientId ?: return
        val captured = normalizer.normalize(sbn, clientId)
        if (captured.title.isNullOrBlank() && captured.text.isNullOrBlank() && captured.messages.isEmpty()) return
        mirroredKeys.add(sbn.key) // remember it so we can sync its dismissal later
        app.graph.scope.launch { eng.captureLocal(captured) }
    }
}
