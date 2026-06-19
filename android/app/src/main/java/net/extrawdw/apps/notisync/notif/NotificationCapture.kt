package net.extrawdw.apps.notisync.notif

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    fun normalize(
        sbn: StatusBarNotification,
        ranking: NotificationListenerService.Ranking?,
        sourceClientId: ClientId,
    ): CapturedNotification {
        val n = sbn.notification
        val extras = n.extras
        val pkg = sbn.packageName

        // Source channel + conversation metadata (best-effort; redacted/absent for a plain listener).
        val channel = runCatching { ranking?.channel }.getOrNull()
        val channelImportance = runCatching { ranking?.importance }.getOrNull()?.let(::mapImportance)
        val shortcutId = runCatching { n.shortcutId }.getOrNull()
        val conversation = runCatching { ranking?.isConversation == true }.getOrDefault(false)

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
            channelId = channel?.id,
            channelName = runCatching { channel?.name?.toString() }.getOrNull(),
            channelGroupId = runCatching { channel?.group }.getOrNull(),
            channelGroupName = null, // group display name requires a CompanionDeviceManager association (v1: omit)
            channelImportance = channelImportance,
            isConversation = conversation || (shortcutId != null && messages.isNotEmpty()),
            shortcutId = shortcutId,
            conversationId = runCatching { channel?.conversationId }.getOrNull(),
            parentChannelId = runCatching { channel?.parentChannelId }.getOrNull(),
        )
    }

    private fun mapImportance(importance: Int): MirrorImportance? = when (importance) {
        NotificationManager.IMPORTANCE_NONE -> MirrorImportance.NONE
        NotificationManager.IMPORTANCE_MIN -> MirrorImportance.MIN
        NotificationManager.IMPORTANCE_LOW -> MirrorImportance.LOW
        NotificationManager.IMPORTANCE_DEFAULT -> MirrorImportance.DEFAULT
        NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_MAX -> MirrorImportance.HIGH
        else -> null // UNSPECIFIED / unknown -> consumer falls back
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
     * Last content signature sent per source key. Lets us skip identical re-posts — listener-reconnect
     * backfill (onListenerConnected) and source-app updates/replaces both re-fire onNotificationPosted
     * with unchanged content. Bounded LRU. Cleared on a real user dismissal so a later identical
     * re-post still re-mirrors.
     */
    private val lastSentSignature: MutableMap<String, Int> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Int>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Int>): Boolean = size > 512
        }
    )

    /**
     * Reasons under which the source notification is genuinely gone / handled and the mirror should
     * clear: user swipe (CANCEL/CANCEL_ALL), each child of a dismissed group (GROUP_SUMMARY_CANCELED),
     * the user opening it (CLICK), the app clearing it because the user read it (APP_CANCEL[_ALL]),
     * and expiry (TIMEOUT). Explicitly EXCLUDES our own REASON_LISTENER_CANCEL echo, regroup
     * (GROUP_OPTIMIZATION/UNAUTOBUNDLED), snooze, and package/channel churn.
     */
    private val dismissReasons = setOf(
        REASON_CLICK,
        REASON_CANCEL,
        REASON_CANCEL_ALL,
        REASON_APP_CANCEL,
        REASON_APP_CANCEL_ALL,
        REASON_GROUP_SUMMARY_CANCELED,
        REASON_TIMEOUT,
    )

    /** Debounced dismissals in flight, keyed by source key — cancelled by a re-post (cancel+repost update). */
    private val pendingDismissals: MutableMap<String, Job> = java.util.Collections.synchronizedMap(HashMap())

    override fun onListenerConnected() {
        engine?.originalCanceler = this
        // Backfill on (re)connect, but don't RE-send notifications we already mirrored before a restart
        // (e.g. after a NotiSync update). They're still registered so their dismissals sync — only the
        // SEND is gated by the persisted high-water mark of mirrored post times.
        app.graph.scope.launch {
            val cutoff = runCatching { app.graph.settings.lastSeenPostTime() }.getOrDefault(0L)
            runCatching { activeNotifications }.getOrNull()?.forEach { handlePosted(it, cutoff) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // A (re)post cancels any pending debounced dismissal for this key — it's an update, not removal.
        pendingDismissals.remove(sbn.key)?.cancel()
        handlePosted(sbn, backfillCutoff = null)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        val wasMirrored = mirroredKeys.remove(sbn.key)
        if (reason !in dismissReasons) return // echo / regroup / snooze / package churn — leave the mirror
        if (!wasMirrored) {
            // Removal of something we didn't mirror: a not-enabled app, or a child transiently filtered
            // at post time. Logged to surface the latter during on-device testing.
            Log.d(TAG, "skip dismissal for unmirrored key reason=$reason pkg=${sbn.packageName}")
            return
        }
        val eng = engine ?: return
        val clientId = app.graph.clientId ?: return
        // Debounce: apps that update by cancel+repost re-post within the window, which cancels this
        // (see onNotificationPosted). Only a genuine removal commits the dismissal + clears the dedup
        // signature, so cancel+repost of identical content neither flickers nor leaves a stale mirror.
        val job = app.graph.scope.launch {
            delay(DISMISS_DEBOUNCE_MS)
            pendingDismissals.remove(sbn.key)
            lastSentSignature.remove(sbn.key)
            eng.dismissLocal(clientId, sbn.key)
        }
        pendingDismissals.put(sbn.key, job)?.cancel()
    }

    private companion object {
        const val TAG = "NotiSyncListener"
        const val DISMISS_DEBOUNCE_MS = 400L
    }

    override fun cancel(sourceKey: String) {
        runCatching { cancelNotification(sourceKey) }
    }

    private fun handlePosted(sbn: StatusBarNotification, backfillCutoff: Long?) {
        if (sbn.packageName == packageName) return                              // never mirror ourselves
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return            // group summaries
        // Learn which apps post notifications (for the picker + recency sort), even if not enabled.
        app.graph.appSelection?.recordSeen(sbn.packageName, sbn.postTime)
        if (sbn.isOngoing && !sbn.isClearable) return                          // media / transport / service
        if (app.graph.appSelection?.isEnabled(sbn.packageName) != true) return // opt-in: default OFF
        val eng = engine ?: return
        val clientId = app.graph.clientId ?: return
        val ranking = NotificationListenerService.Ranking()
        val haveRanking = runCatching { currentRanking?.getRanking(sbn.key, ranking) == true }.getOrDefault(false)
        val captured = normalizer.normalize(sbn, if (haveRanking) ranking else null, clientId)
        if (captured.title.isNullOrBlank() && captured.text.isNullOrBlank() && captured.messages.isEmpty()) return

        val signature = captured.copy(postTime = 0L).hashCode()
        // Always register (even when we won't re-send) so dismissals still sync and live dedup works.
        mirroredKeys.add(sbn.key)
        val unchanged = lastSentSignature.put(sbn.key, signature) == signature
        // Skip the SEND for identical re-posts, and for backfilled notifications we already mirrored
        // before a restart (postTime at/under the persisted high-water mark).
        if (unchanged) return
        if (backfillCutoff != null && sbn.postTime <= backfillCutoff) return
        app.graph.settings.updateLastSeenPostTime(sbn.postTime)
        // Off the listener thread: extract/upload private graphics (best-effort), then seal + send.
        val pipeline = app.graph.graphicsPipeline
        app.graph.scope.launch {
            val withGraphics = pipeline?.attach(sbn, captured) ?: captured
            eng.captureLocal(withGraphics)
        }
    }
}
