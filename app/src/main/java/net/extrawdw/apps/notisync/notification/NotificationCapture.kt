package net.extrawdw.apps.notisync.notification

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.analytics.PerfSpan
import net.extrawdw.apps.notisync.analytics.perfSpan
import net.extrawdw.apps.notisync.domain.OriginalActionPerformer
import net.extrawdw.apps.notisync.domain.OriginalCanceler
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ConversationMessage
import net.extrawdw.notisync.protocol.GroupAlertBehavior
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotifStyle
import net.extrawdw.notisync.protocol.NotificationAction
import java.util.concurrent.ConcurrentHashMap

/** Turns a platform [StatusBarNotification] into the normalized, transport-neutral form. */
class NotificationNormalizer(private val pm: PackageManager) {
    private val labelCache = ConcurrentHashMap<String, String>()

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
        val channelImportance =
            runCatching { ranking?.importance }.getOrNull()?.let(::mapImportance)
        val shortcutId = runCatching { n.shortcutId }.getOrNull()
        val conversation = runCatching { ranking?.isConversation == true }.getOrDefault(false)

        val messaging = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        }.getOrNull()

        val messages = messaging?.messages?.map {
            ConversationMessage(
                sender = it.person?.name?.toString(),
                text = it.text?.toString() ?: "",
                timestamp = it.timestamp,
                dataMimeType = it.dataMimeType,
            )
        } ?: emptyList()

        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val hasBigPicture =
            extras.containsKey(Notification.EXTRA_PICTURE) || extras.containsKey(Notification.EXTRA_PICTURE_ICON)
        val style = when {
            messages.isNotEmpty() -> NotifStyle.MESSAGING
            hasBigPicture -> NotifStyle.BIG_PICTURE
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
            groupKey = n.group ?: runCatching { sbn.overrideGroupKey }.getOrNull(),
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            groupAlertBehavior = groupAlertBehaviorOf(n.groupAlertBehavior),
            isOngoing = sbn.isOngoing,
            isClearable = sbn.isClearable,
            // Raw source flag. Do not infer full alert cadence from this alone: apps may keep child rows
            // ONLY_ALERT_ONCE while alerting through an unsilenced group summary.
            onlyAlertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            channelId = channel?.id,
            channelName = runCatching { channel?.name?.toString() }.getOrNull(),
            channelGroupId = runCatching { channel?.group }.getOrNull(),
            channelGroupName = null, // group display name requires a CompanionDeviceManager association (v1: omit)
            channelImportance = channelImportance,
            shouldVibrate = runCatching { channel?.shouldVibrate() == true }.getOrDefault(false),
            isConversation = conversation || (shortcutId != null && messages.isNotEmpty()),
            shortcutId = shortcutId,
            conversationId = runCatching { channel?.conversationId }.getOrNull(),
            parentChannelId = runCatching { channel?.parentChannelId }.getOrNull(),
            actions = mirrorableActions(n),
            hasContentIntent = n.contentIntent != null,
        )
    }

    private fun groupAlertBehaviorOf(value: Int): GroupAlertBehavior = when (value) {
        Notification.GROUP_ALERT_ALL -> GroupAlertBehavior.ALL
        Notification.GROUP_ALERT_SUMMARY -> GroupAlertBehavior.SUMMARY
        Notification.GROUP_ALERT_CHILDREN -> GroupAlertBehavior.CHILDREN
        else -> GroupAlertBehavior.CHILDREN
    }

    /**
     * The source's action row as mirrors may show it: the visible buttons the origin can re-fire on a
     * peer's [ActionEvent]. Skipped: contextual suggestions (ephemeral, regenerated per content),
     * auth-gated actions (firing one remotely would bypass this device's lock), intent-less
     * placeholders, and choice-only remote inputs (firing without a free-form result would no-op).
     * [NotificationAction.index] is the position in the RAW array — the perform side indexes back
     * into `notification.actions` directly — so the exported list can be sparse. Capped at the
     * platform shade's own button limit.
     */
    private fun mirrorableActions(n: Notification): List<NotificationAction> {
        val actions = n.actions ?: return emptyList()
        val out = ArrayList<NotificationAction>(minOf(actions.size, MAX_MIRRORED_ACTIONS))
        for ((index, action) in actions.withIndex()) {
            if (out.size == MAX_MIRRORED_ACTIONS) break
            if (action?.actionIntent == null) continue
            val title = action.title?.toString()?.takeIf { it.isNotBlank() } ?: continue
            if (runCatching { action.isContextual }.getOrDefault(false)) continue
            if (runCatching { action.isAuthenticationRequired }.getOrDefault(false)) continue
            val remoteInputs = action.remoteInputs.orEmpty()
            val freeForm = remoteInputs.firstOrNull { it.allowFreeFormInput }
            if (remoteInputs.isNotEmpty() && freeForm == null) continue
            out.add(
                NotificationAction(
                    index = index,
                    title = title,
                    remoteInput = freeForm != null,
                    remoteInputLabel = freeForm?.label?.toString(),
                    semanticAction = action.semanticAction,
                    // Compat-only metadata (no framework getter): absent = no claim, so a mirror only
                    // shows "check on device" feedback when the source app explicitly declared UI.
                    showsUserInterface = action.extras.getBoolean(EXTRA_SHOWS_USER_INTERFACE, false),
                )
            )
        }
        return out
    }

    private companion object {
        /** Mirrors the platform shade's visible-action cap; also bounds the E2E payload. */
        const val MAX_MIRRORED_ACTIONS = 3

        /** NotificationCompat's `showsUserInterface` marker (androidx writes it into action extras). */
        const val EXTRA_SHOWS_USER_INTERFACE = "android.support.action.showsUserInterface"
    }

    private fun mapImportance(importance: Int): MirrorImportance? = when (importance) {
        NotificationManager.IMPORTANCE_NONE -> MirrorImportance.NONE
        NotificationManager.IMPORTANCE_MIN -> MirrorImportance.MIN
        NotificationManager.IMPORTANCE_LOW -> MirrorImportance.LOW
        NotificationManager.IMPORTANCE_DEFAULT -> MirrorImportance.DEFAULT
        NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_MAX -> MirrorImportance.HIGH
        else -> null // UNSPECIFIED / unknown -> consumer falls back
    }

    private fun appLabel(pkg: String): String =
        labelCache.getOrPut(pkg) {
            runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }

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
class NotiSyncListenerService : NotificationListenerService(), OriginalCanceler, OriginalActionPerformer {

    private val app get() = applicationContext as NotiSyncApp
    private val normalizer by lazy { NotificationNormalizer(packageManager) }

    /**
     * Keys of notifications we actually mirrored. We sync a dismissal only for these, so a
     * not-enabled app's dismissal is never sent, and grouped/child dismissals are caught regardless
     * of the exact removal reason. Listener callbacks run on the main thread; the set is in-memory
     * and repopulated from active notifications on (re)connect.
     */
    private val mirroredKeys = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Alert-capable source group summaries we forwarded only to drive the receiver's local summary alert.
     *  Their removals clear dedup state but do not sync dismissals; child removals carry the user action. */
    private val mirroredSummaryKeys = java.util.Collections.synchronizedSet(HashSet<String>())

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
     * Source group child captures whose graphics/send work is still in flight. Alert-capable source summaries
     * must not overtake their child row, or the receiver heads-up can render the previous child contents and
     * then update a moment later. Scoped by package because Android group strings are app-owned, not global.
     */
    private val pendingGroupChildSends = ConcurrentHashMap<PendingGroupKey, Job>()

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
    private val pendingDismissals: MutableMap<String, Job> =
        java.util.Collections.synchronizedMap(HashMap())

    override fun onListenerConnected() {
        app.runWhenGraphReady { graph ->
            graph.mirrorEngine?.originalCanceler = this
            graph.mirrorEngine?.originalActionPerformer = this
            // Backfill on (re)connect, but don't RE-send notifications we already mirrored before a restart
            // (e.g. after a NotiSync update). They're still registered so their dismissals sync — only the
            // SEND is gated by the persisted high-water mark of mirrored post times.
            graph.scope.launch {
                val cutoff = runCatching { graph.settings.lastSeenPostTime() }.getOrDefault(0L)
                runCatching { activeNotifications }.getOrNull()
                    ?.forEach { handlePosted(it, cutoff, graph) }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // A (re)post cancels any pending debounced dismissal for this key — it's an update, not removal.
        pendingDismissals.remove(sbn.key)?.cancel()
        val graph = app.graphIfReady ?: return // onListenerConnected backfill covers posts during init.
        handlePosted(sbn, backfillCutoff = null, graph)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        val wasMirrored = mirroredKeys.remove(sbn.key)
        val wasMirroredSummary = mirroredSummaryKeys.remove(sbn.key)
        if (reason !in dismissReasons) return // echo / regroup / snooze / package churn — leave the mirror
        if (wasMirroredSummary) {
            lastSentSignature.remove(sbn.key)
            return
        }
        if (!wasMirrored) {
            // Removal of something we didn't mirror: a not-enabled app, or a child transiently filtered
            // at post time. Logged to surface the latter during on-device testing.
            Log.d(TAG, "skip dismissal for unmirrored key reason=$reason pkg=${sbn.packageName}")
            return
        }
        val graph = app.graphIfReady ?: return
        val eng = graph.mirrorEngine ?: return
        val clientId = graph.clientId ?: return
        // Debounce: apps that update by cancel+repost re-post within the window, which cancels this
        // (see onNotificationPosted). Only a genuine removal commits the dismissal + clears the dedup
        // signature, so cancel+repost of identical content neither flickers nor leaves a stale mirror.
        val job = graph.scope.launch {
            delay(DISMISS_DEBOUNCE_MS)
            pendingDismissals.remove(sbn.key)
            lastSentSignature.remove(sbn.key)
            // Guard only the send, not the debounce delay above (which must stay cancellable so a
            // cancel+repost aborts cleanly): a re-attestation in cooldown throws, and this bare launch
            // would otherwise surface it as an uncaught exception that crashes the scope.
            runCatching { eng.dismissLocal(clientId, sbn.key) }
        }
        pendingDismissals.put(sbn.key, job)?.cancel()
    }

    private companion object {
        const val TAG = "NotiSyncListener"
        const val DISMISS_DEBOUNCE_MS = 400L
        const val GROUP_SUMMARY_CHILD_WAIT_MS = 1_000L
    }

    override fun cancel(sourceKey: String) {
        runCatching { cancelNotification(sourceKey) }
    }

    /**
     * A peer acted on a mirror of one of our originals: replay it on the real notification. Runs on
     * the channel's delivery thread (binder + PendingIntent.send are safe off-main). Best-effort —
     * if the original is gone, its action row shifted, or the OS refuses the send, the event is
     * simply dropped (the peer's mirror stays in whatever state the origin app leaves it).
     */
    override fun perform(event: ActionEvent) {
        runCatching {
            val sbn = getActiveNotifications(arrayOf(event.sourceKey))?.firstOrNull() ?: run {
                Log.d(TAG, "skip action: original gone kind=${event.kind}")
                return
            }
            when (event.kind) {
                ActionKind.TAP -> openOriginal(sbn)
                ActionKind.PERFORM -> performOriginalAction(sbn, event)
            }
        }.onFailure { Log.w(TAG, "perform action failed", it) }
    }

    /**
     * Replays a shade tap: fire the content intent, then — exactly like SystemUI's click handling —
     * dismiss an auto-cancel notification, propagating the dismissal to peers (our own listener
     * cancel echoes as REASON_LISTENER_CANCEL, which the reason allowlist ignores, so dismissLocal
     * carries the sync instead).
     */
    private fun openOriginal(sbn: StatusBarNotification) {
        val n = sbn.notification
        val pi = n.contentIntent ?: return
        sendAllowingBackgroundStart(pi, fillIn = null)
        if (n.flags and Notification.FLAG_AUTO_CANCEL == 0) return
        val graph = app.graphIfReady ?: return
        val eng = graph.mirrorEngine ?: return
        val clientId = graph.clientId ?: return
        graph.scope.launch { runCatching { eng.dismissLocal(clientId, sbn.key) } }
        runCatching { cancelNotification(sbn.key) }
    }

    private fun performOriginalAction(sbn: StatusBarNotification, event: ActionEvent) {
        val action = sbn.notification.actions?.getOrNull(event.actionIndex) ?: return
        // The action row may have been rebuilt while the event was in flight (the app updated the
        // notification): the echoed title must still match, else drop rather than fire a different button.
        if (event.actionTitle != null && action.title?.toString() != event.actionTitle) {
            Log.d(TAG, "skip stale action idx=${event.actionIndex}")
            return
        }
        val pi = action.actionIntent ?: return
        val reply = event.remoteInputText
        if (reply.isNullOrEmpty()) {
            sendAllowingBackgroundStart(pi, fillIn = null)
            return
        }
        // Feed the reply into every free-form RemoteInput the action declares — the result key never
        // travels the mesh; it's re-derived from the live action, so it stays correct across updates.
        val remoteInputs = action.remoteInputs?.takeIf { it.isNotEmpty() } ?: return
        val results = Bundle()
        for (ri in remoteInputs) if (ri.allowFreeFormInput) results.putCharSequence(ri.resultKey, reply)
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
        RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        sendAllowingBackgroundStart(pi, fillIn)
    }

    /**
     * PendingIntent.send with the SENDER-side background-activity-start opt-in when the target is an
     * activity, so a content intent / UI action uses whatever BAL privilege we hold (e.g. a granted
     * "Display over other apps"). The creator-mode option must NOT be set here — it is reserved for
     * the app that CREATES a PendingIntent, and Android 16 hard-throws when a sender supplies it
     * (15 silently reset it). The OS may still suppress the launch when neither app holds a BAL
     * exemption — accepted: the send itself always goes through. Broadcast/service actions (the
     * common Reply / Mark as read) get no options — ActivityOptions on a non-activity send is
     * rejected on Android 14+.
     */
    private fun sendAllowingBackgroundStart(pi: PendingIntent, fillIn: Intent?) {
        val options = if (pi.isActivity) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(backgroundActivityStartMode())
                .toBundle()
        } else {
            null
        }
        pi.send(this, 0, fillIn, null, null, null, options)
    }

    @Suppress("DEPRECATION")
    private fun backgroundActivityStartMode(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }

    private fun handlePosted(sbn: StatusBarNotification, backfillCutoff: Long?, graph: AppGraph) {
        if (sbn.packageName == packageName) return                              // never mirror ourselves
        val n = sbn.notification
        val isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        // Most source summaries are duplicate rows; the receiver builds its own local summary from mirrored
        // children. Keep only explicit summary-alert carriers: some apps silence child rows and route the
        // audible alert through GROUP_ALERT_SUMMARY. GROUP_ALERT_ALL is Android's default, so forwarding it
        // would broaden mirroring to nearly every grouping app and can double-alert.
        if (isGroupSummary && n.groupAlertBehavior != Notification.GROUP_ALERT_SUMMARY) return
        // Learn which apps post notifications (for the picker + recency sort), even if not enabled.
        graph.appSelection?.recordSeen(sbn.packageName, sbn.postTime)
        if (sbn.isOngoing && !sbn.isClearable) return                          // media / transport / service
        if (graph.appSelection?.isEnabled(sbn.packageName) != true) return // opt-in: default OFF
        val eng = graph.mirrorEngine ?: return
        val clientId = graph.clientId ?: return
        val ranking = NotificationListenerService.Ranking()
        val haveRanking =
            runCatching { currentRanking?.getRanking(sbn.key, ranking) == true }.getOrDefault(false)
        // Normalize + dedup synchronously on the (single-threaded) listener callback so per-key ordering
        // is preserved: rapid same-key updates can't race or invert on graph.scope's multi-threaded
        // dispatcher. Only the graphics attach + send is offloaded below.
        val normalizeStartNanos = System.nanoTime()
        val captured = normalizer.normalize(sbn, if (haveRanking) ranking else null, clientId)
        val normalizeNanos = System.nanoTime() - normalizeStartNanos
        if (captured.title.isNullOrBlank() && captured.text.isNullOrBlank() && captured.messages.isEmpty()) return

        val signature = captureSignature(captured)
        // Register only once content is non-blank (a blank capture is never mirrored, so it must not be
        // registered — otherwise its later removal would propagate a dismissal for something peers never
        // saw). Always register even when we won't re-send, so dismissals still sync and live dedup works.
        if (captured.isGroupSummary) mirroredSummaryKeys.add(sbn.key) else mirroredKeys.add(sbn.key)
        val unchanged = lastSentSignature.put(sbn.key, signature) == signature
        // Skip the SEND for identical re-posts, and for backfilled notifications we already mirrored
        // before a restart (postTime at/under the persisted high-water mark).
        if (unchanged) return
        if (backfillCutoff != null && sbn.postTime <= backfillCutoff) return
        graph.settings.updateLastSeenPostTime(sbn.postTime)
        // Off the listener thread: extract/upload private graphics (best-effort), then seal + send. One
        // `mirror_capture` span covers both threads — opened here (recording the listener-thread normalize)
        // and closed after the async send — so it times the whole capture→sent path for a mirrored post.
        val pipeline = graph.graphicsPipeline
        val captureSpan = perfSpan("mirror_capture")
        captureSpan.metric("normalize_ms", normalizeNanos / 1_000_000)
        // The group this send registers under as a pending child (non-summary captures only), so an
        // alert-carrier summary for the same group can wait for it. Null for summaries and ungrouped posts.
        val pendingChildKey = if (captured.isGroupSummary) null else sourceGroupKey(captured)
        val sendJob = graph.scope.launch(start = CoroutineStart.LAZY) {
            // Guard the whole send: a re-attestation in cooldown throws (see BrokerClient.bearerToken),
            // and this bare launch would otherwise surface it as an uncaught exception that crashes the
            // scope. attach() can throw too (it uploads private graphics via the same authed path). A
            // dropped mirror is re-delivered by the live socket / relay drain backstop.
            try {
                runCatching {
                    waitForGroupChildIfSummary(captured, captureSpan)
                    val graphicsStartNanos = System.nanoTime()
                    val withGraphics = pipeline?.attach(sbn, captured) ?: captured
                    captureSpan.metric("graphics_ms", (System.nanoTime() - graphicsStartNanos) / 1_000_000)
                    eng.captureLocal(withGraphics, captureSpan)
                }
            } finally {
                pendingChildKey?.let { pendingGroupChildSends.remove(it, coroutineContext.job) }
                captureSpan.stop()
            }
        }
        pendingChildKey?.let { pendingGroupChildSends[it] = sendJob }
        sendJob.start()
    }

    private suspend fun waitForGroupChildIfSummary(captured: CapturedNotification, span: PerfSpan) {
        if (!captured.isGroupSummary || captured.groupAlertBehavior != GroupAlertBehavior.SUMMARY) return
        val groupKey = sourceGroupKey(captured) ?: return
        val childJob = pendingGroupChildSends[groupKey] ?: return
        val waitStartNanos = System.nanoTime()
        withTimeoutOrNull(GROUP_SUMMARY_CHILD_WAIT_MS) { childJob.join() }
        span.metric("group_child_wait_ms", (System.nanoTime() - waitStartNanos) / 1_000_000)
    }

    private fun captureSignature(captured: CapturedNotification): Int =
        if (captured.isGroupSummary && captured.groupAlertBehavior == GroupAlertBehavior.SUMMARY) {
            // SUMMARY-behavior apps may re-alert with byte-identical summary text/counts while the child row is
            // force-silent on receivers. Keep postTime in the summary signature so each source alert carrier is
            // forwarded even when its visible text did not change.
            captured.hashCode()
        } else {
            captured.copy(postTime = 0L).hashCode()
        }

    private fun sourceGroupKey(captured: CapturedNotification): PendingGroupKey? =
        captured.groupKey?.let { PendingGroupKey(captured.packageName, it) }

    private data class PendingGroupKey(val packageName: String, val groupKey: String)
}
