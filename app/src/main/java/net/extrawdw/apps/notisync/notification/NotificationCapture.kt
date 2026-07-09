package net.extrawdw.apps.notisync.notification

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
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
import net.extrawdw.apps.notisync.data.PerAppConfig
import net.extrawdw.apps.notisync.domain.OriginalActionPerformer
import net.extrawdw.apps.notisync.domain.OriginalCanceler
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.CallType
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ConversationMessage
import net.extrawdw.notisync.protocol.GroupAlertBehavior
import net.extrawdw.notisync.protocol.MediaCommand
import net.extrawdw.notisync.protocol.MediaCustomAction
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotificationStyle
import net.extrawdw.notisync.protocol.NotificationAction
import java.util.concurrent.ConcurrentHashMap

/** The framework template class name for an Android CallStyle notification (`Notification.EXTRA_TEMPLATE`),
 *  shared by capture-time style detection and the listener's ongoing-gate exemption for calls. */
private const val CALL_STYLE_TEMPLATE = "android.app.Notification\$CallStyle"

/** A snapshot of a source MediaSession's playback state, read at capture time (values as understood by
 *  `PlaybackState`/`MediaMetadata`). Lets the consumer rebuild a real media session — system-drawn transport
 *  buttons, play/pause state, seekbar — rather than a plain notification. */
data class MediaPlaybackInfo(
    val isPlaying: Boolean,
    val positionMs: Long?,
    val durationMs: Long?,
    val actions: Long?,
    val customActions: List<MediaCustomAction>,
    /** `PlaybackInfo` volume snapshot — stream volume for local playback, the app's VolumeProvider when the
     *  source itself casts. Control is a raw `VolumeProvider.VOLUME_CONTROL_*` value. */
    val volumeControl: Int? = null,
    val volumeMax: Int? = null,
    val volumeCurrent: Int? = null,
)

/**
 * Reads the live MediaSession behind a media notification, via the `EXTRA_MEDIA_SESSION` token the source
 * app publishes. A notification listener may build a [MediaController] from that token and read its playback
 * state + metadata. Best-effort: null when the notification carries no session token or it can't be read.
 */
object MediaCapture {
    fun read(context: Context, n: Notification): MediaPlaybackInfo? {
        val token = runCatching {
            n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        }.getOrNull() ?: return null
        return runCatching {
            val controller = MediaController(context, token)
            val state = controller.playbackState
            val duration = controller.metadata
                ?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
            val customActions = state?.customActions?.mapNotNull { ca ->
                val act = ca?.action ?: return@mapNotNull null
                MediaCustomAction(action = act, name = ca.name?.toString().orEmpty())
            }.orEmpty()
            // Volume snapshot: PlaybackInfo abstracts local (stream) vs remote (VolumeProvider) uniformly.
            val volume = runCatching { controller.playbackInfo }.getOrNull()
            MediaPlaybackInfo(
                isPlaying = state?.state == PlaybackState.STATE_PLAYING,
                positionMs = state?.position?.takeIf { it >= 0 },
                durationMs = duration,
                actions = state?.actions?.takeIf { it != 0L },
                customActions = customActions,
                volumeControl = volume?.volumeControl,
                volumeMax = volume?.maxVolume?.takeIf { it > 0 },
                volumeCurrent = volume?.currentVolume?.coerceAtLeast(0),
            )
        }.getOrNull()
    }
}

/** Turns a platform [StatusBarNotification] into the normalized, transport-neutral form. */
class NotificationNormalizer(private val pm: PackageManager) {
    private val labelCache = ConcurrentHashMap<String, String>()

    fun normalize(
        sbn: StatusBarNotification,
        ranking: NotificationListenerService.Ranking?,
        sourceClientId: ClientId,
        media: MediaPlaybackInfo? = null,
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
        val inboxLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            .orEmpty()
        // Rich-style detection keys off the source template (with a MediaSession fallback for media). CALL and
        // MEDIA win over the text/picture styles, since a call/media post can also carry a large icon or text.
        val template = runCatching { extras.getString(Notification.EXTRA_TEMPLATE) }.getOrNull()
        val isMedia = template == TEMPLATE_MEDIA || extras.containsKey(KEY_MEDIA_SESSION)
        val callType = if (template == CALL_STYLE_TEMPLATE) callTypeOf(extras) else null
        val call = if (callType != null) captureCall(n, extras) else null
        val style = when {
            callType != null -> NotificationStyle.CALL
            template == TEMPLATE_DECORATED_MEDIA_CUSTOM -> NotificationStyle.DECORATED_MEDIA_CUSTOM_VIEW
            isMedia -> NotificationStyle.MEDIA
            template == TEMPLATE_DECORATED_CUSTOM -> NotificationStyle.DECORATED_CUSTOM_VIEW
            messages.isNotEmpty() -> NotificationStyle.MESSAGING
            hasBigPicture -> NotificationStyle.BIG_PICTURE
            inboxLines.isNotEmpty() -> NotificationStyle.INBOX
            !bigText.isNullOrBlank() -> NotificationStyle.BIG_TEXT
            else -> NotificationStyle.DEFAULT
        }
        // MediaStyle and its decorated variant surface up to five transport buttons; others cap at three.
        val isMediaLike =
            style == NotificationStyle.MEDIA || style == NotificationStyle.DECORATED_MEDIA_CUSTOM_VIEW
        val maxActions = if (isMediaLike) MAX_MEDIA_ACTIONS else MAX_MIRRORED_ACTIONS

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
            importance = if (n.category == Notification.CATEGORY_MESSAGE ||
                n.category == Notification.CATEGORY_CALL ||
                callType == CallType.INCOMING || callType == CallType.SCREENING
            ) MirrorImportance.HIGH else MirrorImportance.DEFAULT,
            postTime = sbn.postTime,
            groupKey = n.group ?: runCatching { sbn.overrideGroupKey }.getOrNull(),
            // A call row may set FLAG_GROUP_SUMMARY on itself (WhatsApp); never treat a call as a group summary
            // — it renders as the standalone call notification, not a receiver-built summary.
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0 &&
                n.category != Notification.CATEGORY_CALL,
            groupAlertBehavior = groupAlertBehaviorOf(n.groupAlertBehavior),
            isOngoing = sbn.isOngoing,
            isClearable = sbn.isClearable,
            isForegroundService = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            // Raw source flag. Do not infer full alert cadence from this alone: apps may keep child rows
            // ONLY_ALERT_ONCE while alerting through an unsilenced group summary.
            onlyAlertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            channelId = channel?.id,
            channelName = runCatching { channel?.name?.toString() }.getOrNull(),
            channelGroupId = runCatching { channel?.group }.getOrNull(),
            channelGroupName = null, // group display name requires a CompanionDeviceManager association (v1: omit)
            channelImportance = channelImportance,
            shouldVibrate = runCatching { channel?.shouldVibrate() == true }.getOrDefault(false),
            // Source channel's sound state (None vs has-a-sound); mirrored so a silent-but-HIGH channel (a
            // dialer's incoming-call channel) recreates silent instead of getting the default sound. Null = no
            // channel / unreadable → receiver keeps its default.
            channelSilent = channel?.let { ch -> runCatching { ch.sound == null }.getOrNull() },
            isConversation = conversation || (shortcutId != null && messages.isNotEmpty()),
            shortcutId = shortcutId,
            conversationId = runCatching { channel?.conversationId }.getOrNull(),
            parentChannelId = runCatching { channel?.parentChannelId }.getOrNull(),
            actions = mirrorableActions(n, maxActions),
            hasContentIntent = n.contentIntent != null,
            inboxLines = inboxLines,
            isColorized = extras.getBoolean(Notification.EXTRA_COLORIZED, false),
            mediaCompactActionIndices =
                if (isMediaLike) extras.getIntArray(KEY_COMPACT_ACTIONS)?.toList().orEmpty() else emptyList(),
            callType = callType,
            callerName = call?.callerName,
            callVerificationText = call?.verificationText,
            callAnswerIndex = call?.answerIndex,
            callDeclineIndex = call?.declineIndex,
            callHangUpIndex = call?.hangUpIndex,
            accentColor = n.color.takeIf { it != Notification.COLOR_DEFAULT },
            mediaIsPlaying = media?.isPlaying,
            mediaPositionMs = media?.positionMs,
            mediaDurationMs = media?.durationMs,
            mediaActions = media?.actions,
            mediaCustomActions = media?.customActions.orEmpty(),
            mediaVolumeCurrent = media?.volumeCurrent,
            mediaVolumeMax = media?.volumeMax,
            mediaVolumeControl = media?.volumeControl,
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
    private fun mirrorableActions(n: Notification, max: Int): List<NotificationAction> {
        val actions = n.actions ?: return emptyList()
        val out = ArrayList<NotificationAction>(minOf(actions.size, max))
        for ((index, action) in actions.withIndex()) {
            if (out.size == max) break
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

    /** Android CallStyle call type from its (framework-internal) extra; null when absent/unknown. */
    private fun callTypeOf(extras: Bundle): CallType? = when (extras.getInt(KEY_CALL_TYPE, 0)) {
        CALL_TYPE_INCOMING -> CallType.INCOMING
        CALL_TYPE_ONGOING -> CallType.ONGOING
        CALL_TYPE_SCREENING -> CallType.SCREENING
        else -> null
    }

    private data class CallCapture(
        val callerName: String?,
        val verificationText: String?,
        val answerIndex: Int?,
        val declineIndex: Int?,
        val hangUpIndex: Int?,
    )

    /**
     * CallStyle detail: the caller name/verification, and which raw action indices are answer / decline /
     * hang-up — resolved by matching the framework's answer/decline/hang-up PendingIntents (held in the
     * notification's extras) against the generated action row, so the consumer can rebuild the CallStyle
     * buttons and relay a press back to the origin. The caller photo, when present, rides the large icon.
     */
    private fun captureCall(n: Notification, extras: Bundle): CallCapture {
        val person = runCatching {
            extras.getParcelable(KEY_CALL_PERSON, android.app.Person::class.java)
        }.getOrNull()
        val actions = n.actions.orEmpty()
        fun indexOfIntent(key: String): Int? {
            val pi = runCatching { extras.getParcelable(key, PendingIntent::class.java) }.getOrNull()
                ?: return null
            return actions.indexOfFirst { it?.actionIntent == pi }.takeIf { it >= 0 }
        }
        return CallCapture(
            callerName = person?.name?.toString(),
            verificationText = extras.getCharSequence(KEY_CALL_VERIFICATION_TEXT)?.toString(),
            answerIndex = indexOfIntent(KEY_CALL_ANSWER_INTENT),
            declineIndex = indexOfIntent(KEY_CALL_DECLINE_INTENT),
            hangUpIndex = indexOfIntent(KEY_CALL_HANG_UP_INTENT),
        )
    }

    private companion object {
        /** Mirrors the platform shade's visible-action cap; also bounds the E2E payload. */
        const val MAX_MIRRORED_ACTIONS = 3

        /** MediaStyle can surface up to five transport buttons (three in the compact view). */
        const val MAX_MEDIA_ACTIONS = 5

        // Style templates + CallStyle/MediaStyle extras. Several keys are framework-internal (@hidden), so we
        // read them by their stable literal name rather than a hidden constant — these are plain Bundle reads.
        const val TEMPLATE_MEDIA = "android.app.Notification\$MediaStyle"
        const val TEMPLATE_DECORATED_CUSTOM = "android.app.Notification\$DecoratedCustomViewStyle"
        const val TEMPLATE_DECORATED_MEDIA_CUSTOM = "android.app.Notification\$DecoratedMediaCustomViewStyle"
        const val KEY_MEDIA_SESSION = "android.mediaSession"
        const val KEY_COMPACT_ACTIONS = "android.compactActions"
        const val KEY_CALL_TYPE = "android.callType"
        const val KEY_CALL_PERSON = "android.callPerson"
        const val KEY_CALL_VERIFICATION_TEXT = "android.verificationText"
        const val KEY_CALL_ANSWER_INTENT = "android.answerIntent"
        const val KEY_CALL_DECLINE_INTENT = "android.declineIntent"
        const val KEY_CALL_HANG_UP_INTENT = "android.hangUpIntent"
        const val CALL_TYPE_INCOMING = 1
        const val CALL_TYPE_ONGOING = 2
        const val CALL_TYPE_SCREENING = 3

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

    /** One pending volume-echo re-capture per source key, latest wins (see [scheduleVolumeEcho]). */
    private val volumeEchoes = ConcurrentHashMap<String, Runnable>()
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /**
     * Per-key throttle state for ongoing-notification quiet updates ([throttleOngoingUpdate]). [ongoingLastSentAt]
     * is the wall-clock time of the last quiet send for a source key; [ongoingPending] holds the LATEST coalesced
     * send while within the interval; [ongoingFlushJobs] is the single trailing-flush job scheduled at the
     * interval boundary. The check-and-schedule runs only on the single-threaded listener callback; the flush
     * jobs run on the graph scope and touch only these concurrent maps. Entries are dropped when the source
     * notification is removed (see [onNotificationRemoved]).
     */
    private val ongoingLastSentAt = ConcurrentHashMap<String, Long>()
    private val ongoingPending = ConcurrentHashMap<String, suspend () -> Unit>()
    private val ongoingFlushJobs = ConcurrentHashMap<String, Job>()

    /**
     * Last media playback signature per media key, to classify an ongoing-media UPDATE as a DRAMATIC change
     * (track change / play↔pause) — delivered PROMPTLY as an alert-priority NOTIFICATION so a peer's media card
     * flips at once — versus a minor tick (position / buffering) that coasts on the quiet DATA_SYNC channel. Set
     * on the single-threaded listener callback; dropped when the source notification is removed, like the
     * throttle state above.
     */
    private val lastMediaSignature = ConcurrentHashMap<String, MediaSignature>()

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
            // Ongoing-update throttle state is per source key; drop it and cancel any pending trailing flush so
            // a stale quiet update can't fire after the notification is gone.
            ongoingLastSentAt.remove(sbn.key)
            ongoingPending.remove(sbn.key)
            ongoingFlushJobs.remove(sbn.key)?.cancel()
            lastMediaSignature.remove(sbn.key)
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

        /** Leading-edge throttle for a media notification's playback updates: a play/pause/track change fires
         *  at once when idle, while rapid re-posts (some apps re-post the position) collapse to this cadence. */
        const val MEDIA_UPDATE_THROTTLE_MS = 1_000L

        /** Settle time before a relayed volume change is echoed back via re-capture (an async VolumeProvider
         *  on a casting source needs a beat; local stream volume is immediate either way). */
        const val VOLUME_ECHO_DELAY_MS = 600L
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
                ActionKind.MEDIA -> performMediaCommand(sbn, event)
            }
        }.onFailure { Log.w(TAG, "perform action failed", it) }
    }

    /**
     * Replay a mirrored media transport press on the real source MediaSession. Targets the source app's
     * active session (matched by package) rather than the notification's action row, so play/pause/skip/seek
     * work without ever mapping transport controls onto notification-action indices.
     */
    private fun performMediaCommand(sbn: StatusBarNotification, event: ActionEvent) {
        val command = event.mediaCommand ?: return
        val controller = activeMediaController(sbn.packageName) ?: run {
            Log.d(TAG, "skip media command: no active session pkg=${sbn.packageName}")
            return
        }
        val tc = controller.transportControls
        when (command) {
            MediaCommand.PLAY -> tc.play()
            MediaCommand.PAUSE -> tc.pause()
            MediaCommand.PLAY_PAUSE ->
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            MediaCommand.NEXT -> tc.skipToNext()
            MediaCommand.PREVIOUS -> tc.skipToPrevious()
            MediaCommand.STOP -> tc.stop()
            MediaCommand.SEEK -> event.mediaSeekMs?.let { tc.seekTo(it) }
            MediaCommand.CUSTOM -> event.mediaCustomAction?.let { tc.sendCustomAction(it, null) }
            // Volume goes through the controller, which routes to the right backend by itself: AudioManager
            // stream volume for PLAYBACK_TYPE_LOCAL sessions, the app's VolumeProvider for PLAYBACK_TYPE_REMOTE
            // (the source is itself casting). Out-of-range values clamp downstream; flags=0 shows no volume UI
            // here. The delayed echo re-capture then pushes the level that actually landed back to peers.
            MediaCommand.SET_VOLUME -> event.mediaVolume?.let { v ->
                controller.setVolumeTo(v.coerceAtLeast(0), 0)
                scheduleVolumeEcho(sbn.key)
            }
            MediaCommand.ADJUST_VOLUME -> event.mediaVolume?.takeIf { it != 0 }?.let { d ->
                controller.adjustVolume(d.coerceIn(-1, 1), 0)
                scheduleVolumeEcho(sbn.key)
            }
        }
    }

    /**
     * After a relayed volume change, re-capture the notification so peers converge on the volume that
     * actually landed (clamping, provider rounding) — apps never re-post for a volume change, so without
     * this the only sync point would be the next track/state re-post. Delayed so an async VolumeProvider
     * settles, coalesced per key (latest wins), and run on the main thread like every listener callback
     * (handlePosted's ordering assumption). The normal quiet media-update machinery does the rest
     * (signature dedup, 1s leading-edge throttle, DATA_SYNC delivery) — and drops it if nothing changed.
     */
    private fun scheduleVolumeEcho(key: String) {
        val echo = Runnable {
            volumeEchoes.remove(key)
            val graph = app.graphIfReady ?: return@Runnable
            runCatching { getActiveNotifications(arrayOf(key))?.firstOrNull() }.getOrNull()
                ?.let { handlePosted(it, backfillCutoff = null, graph) }
        }
        volumeEchoes.put(key, echo)?.let(mainHandler::removeCallbacks)
        mainHandler.postDelayed(echo, VOLUME_ECHO_DELAY_MS)
    }

    /** The source app's currently-active [MediaController], via MediaSessionManager (permitted because this is
     *  the enabled notification listener). Null when the app has no active session. */
    private fun activeMediaController(packageName: String): MediaController? {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return null
        val component = ComponentName(this, NotiSyncListenerService::class.java)
        return runCatching {
            msm.getActiveSessions(component).firstOrNull { it.packageName == packageName }
        }.getOrNull()
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
        // A call notification — the CallStyle template, OR any CATEGORY_CALL. WhatsApp (and other VoIP apps)
        // post the incoming call on a voip_* channel with category=call, ONGOING, and FLAG_GROUP_SUMMARY on
        // the call row itself. A call is exempt from the group-summary drop below (its "summary" row IS the
        // call, not a duplicate the receiver rebuilds), but NOT from the ongoing opt-in: an ongoing call
        // honors the per-app switch like any other ongoing post; a non-ongoing call isn't gated there anyway.
        val isCall = n.category == Notification.CATEGORY_CALL ||
            n.extras.getString(Notification.EXTRA_TEMPLATE) == CALL_STYLE_TEMPLATE
        // Most source summaries are duplicate rows; the receiver builds its own local summary from mirrored
        // children. Keep only explicit summary-alert carriers: some apps silence child rows and route the
        // audible alert through GROUP_ALERT_SUMMARY. GROUP_ALERT_ALL is Android's default, so forwarding it
        // would broaden mirroring to nearly every grouping app and can double-alert.
        if (isGroupSummary && !isCall && n.groupAlertBehavior != Notification.GROUP_ALERT_SUMMARY) return
        // Learn which apps post notifications (for the picker + recency sort), even if not enabled.
        graph.appSelection?.recordSeen(sbn.packageName, sbn.postTime)
        if (graph.appSelection?.isEnabled(sbn.packageName) != true) return // opt-in: default OFF
        val cfg = graph.appConfig?.configFor(sbn.packageName) ?: PerAppConfig()
        // Ongoing (media / transport / foreground-service / ongoing call) notifications are mirrored only when
        // the app opts in. isClearable is false whenever the ongoing flag is set, so this keys purely on the
        // ongoing flag — ongoing calls included: per the product rule, an ongoing call honors the opt-in just
        // like any other ongoing post. A call that is NOT ongoing falls through here untouched (the gate only
        // fires for ongoing posts) and mirrors as a normal alerting notification.
        if (sbn.isOngoing && !cfg.mirrorOngoing) return
        val eng = graph.mirrorEngine ?: return
        val clientId = graph.clientId ?: return
        val ranking = NotificationListenerService.Ranking()
        val haveRanking =
            runCatching { currentRanking?.getRanking(sbn.key, ranking) == true }.getOrDefault(false)
        // Normalize + dedup synchronously on the (single-threaded) listener callback so per-key ordering
        // is preserved: rapid same-key updates can't race or invert on graph.scope's multi-threaded
        // dispatcher. Only the graphics attach + send is offloaded below.
        val normalizeStartNanos = System.nanoTime()
        // Read the source MediaSession (media notifications only — cheap null for the rest) so the mirror can
        // rebuild a real media session. `this` (the listener service) is a Context able to build a controller.
        val mediaInfo = MediaCapture.read(this, n)
        val captured = normalizer.normalize(sbn, if (haveRanking) ranking else null, clientId, mediaInfo)
        val normalizeNanos = System.nanoTime() - normalizeStartNanos
        if (captured.title.isNullOrBlank() && captured.text.isNullOrBlank() && captured.messages.isEmpty()) return

        // Source-side channel controls: learn this app's channels for the config sheet, and drop a capture whose
        // channel or channel-group the user disabled. Before registration (below), so a suppressed capture is
        // never tracked — otherwise its later removal would propagate a phantom dismissal for something peers
        // never saw. Default is all-channels-ON, so an app with no config suppresses nothing.
        graph.appConfig?.let { ac ->
            ac.recordSeenChannel(
                sbn.packageName, captured.channelId, captured.channelName,
                captured.channelGroupId, captured.channelGroupName,
            )
            if (ac.isChannelSuppressed(sbn.packageName, captured.channelId, captured.channelGroupId)) return
        }

        val signature = captureSignature(captured)
        // Register only once content is non-blank (a blank capture is never mirrored, so it must not be
        // registered — otherwise its later removal would propagate a dismissal for something peers never
        // saw). Always register even when we won't re-send, so dismissals still sync and live dedup works.
        // First-seen vs update, decided BEFORE registering the key below. An ongoing key's FIRST post takes the
        // normal alerting NOTIFICATION path; its later updates are throttled + delivered quietly (see below).
        val firstSeen = sbn.key !in mirroredKeys && sbn.key !in mirroredSummaryKeys
        if (captured.isGroupSummary) mirroredSummaryKeys.add(sbn.key) else mirroredKeys.add(sbn.key)
        val unchanged = lastSentSignature.put(sbn.key, signature) == signature
        // Skip the SEND for identical re-posts, and for backfilled notifications we already mirrored
        // before a restart (postTime at/under the persisted high-water mark).
        if (unchanged) return
        if (backfillCutoff != null && sbn.postTime <= backfillCutoff) return
        graph.settings.updateLastSeenPostTime(sbn.postTime)
        val isMediaPlayback = captured.isMediaPlaybackStyle()

        // Ongoing-notification UPDATE: deliver quietly (DATA_SYNC / FCM NORMAL) and throttled, so a rapidly
        // updating progress/media notification never wakes peers like an alert push. Only reached for ongoing
        // keys the app opted into (the gate above); the FIRST post fell through as a normal NOTIFICATION.
        // updateIntervalSec == 0 means "mirror only the initial post" — drop updates entirely.
        if (sbn.isOngoing && !firstSeen) {
            // Calls and media are exceptions to the user's update-interval: a call's transitions (ringing →
            // answered → hung-up) and a media notification's playback changes are discrete, meaningful updates,
            // not progress-tick spam, so they sync PROMPTLY rather than being dropped by "Initial only" (0) or
            // delayed. Content-dedup above already drops identical re-posts.
            if (isMediaPlayback) {
                // Split a media update by how much the playback changed. A DRAMATIC change — a track change or a
                // play↔pause — is delivered at once as a HIGH-priority NOTIFICATION (flagged silentUpdate, so the
                // peer renders it silently in place, not as a fresh alert) so its media-controls card flips
                // immediately instead of coasting on the NORMAL-priority quiet channel. A minor tick (position /
                // buffering) still rides the quiet DATA_SYNC channel under a short leading-edge throttle. iOS peers
                // get the prompt NOTIFICATION only when the app opted media playback into iOS mirroring (an iPhone
                // lacking the notification-filtering entitlement can't render it silently and would re-alert on
                // every change); the quiet channel already excludes iOS regardless.
                val cur = mediaSignatureOf(captured)
                val dramatic = lastMediaSignature.put(sbn.key, cur).let { it == null || it != cur }
                if (dramatic) {
                    // Send now, bypassing the throttle, and clear any pending quiet trailing-flush for this key so
                    // a stale minor tick can't fire afterwards and clobber the card (the receiver's
                    // last-writer-wins on postTime is the ultimate backstop). Advance the throttle clock so the
                    // next minor tick still spaces itself from this send.
                    ongoingPending.remove(sbn.key)
                    ongoingFlushJobs.remove(sbn.key)?.cancel()
                    ongoingLastSentAt[sbn.key] = System.currentTimeMillis()
                    val allowIos = cfg.mirrorMediaPlaybackToIos
                    graph.scope.launch {
                        runCatching {
                            val withGraphics = graph.graphicsPipeline?.attach(sbn, captured) ?: captured
                            eng.sendOngoingUpdatePrompt(withGraphics, allowIos)
                        }
                    }
                    return
                }
                throttleOngoingUpdate(sbn.key, MEDIA_UPDATE_THROTTLE_MS, graph.scope) {
                    val withGraphics = graph.graphicsPipeline?.attach(sbn, captured) ?: captured
                    eng.sendNotificationQuiet(withGraphics)
                }
                return
            }
            // Non-media ongoing update (progress / foreground-service / ongoing call): the quiet channel under the
            // user's per-app interval. A call uses no throttle so its transitions land at once.
            val intervalMs: Long = when {
                isCall -> 0L
                cfg.updateIntervalSec == 0 -> return                      // initial-only: don't mirror updates
                cfg.updateIntervalSec < 0 -> 0L                          // every update, no throttle
                else -> cfg.updateIntervalSec * 1000L
            }
            throttleOngoingUpdate(sbn.key, intervalMs, graph.scope) {
                val withGraphics = graph.graphicsPipeline?.attach(sbn, captured) ?: captured
                eng.sendNotificationQuiet(withGraphics)
            }
            return
        }
        // A media notification's first post (or any non-ongoing capture that fell through the update branch):
        // record its playback baseline so the FIRST ongoing update is classified against real prior state rather
        // than defaulting to a dramatic prompt-send.
        if (isMediaPlayback) lastMediaSignature[sbn.key] = mediaSignatureOf(captured)
        // Media playback is kept off iOS unless its own per-app opt-in is on — Android media rows are not always
        // platform-ongoing. Other ongoing notifications use the separate ongoing-to-iOS opt-in.
        val excludeIos = shouldExcludeIosForCapture(captured, cfg)
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
                    eng.captureLocal(withGraphics, excludeIos = excludeIos, span = captureSpan)
                }
            } finally {
                pendingChildKey?.let { pendingGroupChildSends.remove(it, coroutineContext.job) }
                captureSpan.stop()
            }
        }
        pendingChildKey?.let { pendingGroupChildSends[it] = sendJob }
        sendJob.start()
    }

    /**
     * Leading + trailing throttle for one ongoing key's quiet updates: fire [send] immediately when at least
     * [intervalMs] has elapsed since the last quiet send for [key]; otherwise keep only the LATEST [send] and
     * run it once at the interval boundary, so the newest state always lands but at most one send goes out per
     * interval. Called only from the single-threaded listener callback (safe check-and-schedule).
     */
    private fun throttleOngoingUpdate(
        key: String,
        intervalMs: Long,
        scope: CoroutineScope,
        send: suspend () -> Unit,
    ) {
        val now = System.currentTimeMillis()
        val elapsed = now - (ongoingLastSentAt[key] ?: 0L)
        if (elapsed >= intervalMs) {
            ongoingLastSentAt[key] = now
            scope.launch { runCatching { send() } }
            return
        }
        // Within the interval: remember the latest state and ensure ONE trailing flush at the boundary.
        ongoingPending[key] = send
        if (ongoingFlushJobs[key]?.isActive != true) {
            ongoingFlushJobs[key] = scope.launch {
                delay(intervalMs - elapsed)
                ongoingLastSentAt[key] = System.currentTimeMillis()
                ongoingPending.remove(key)?.let { runCatching { it() } }
            }
        }
    }

    /** The playback identity of a media notification: the parts that change on a track change or a play↔pause,
     *  but NOT on a mere position / buffering tick. [handlePosted] treats a change in either field as a DRAMATIC
     *  update worth a prompt HIGH-priority send. [playing] is nullable to mirror [CapturedNotification.mediaIsPlaying]
     *  (a media notification whose session couldn't be read has no play state). */
    private data class MediaSignature(val playing: Boolean?, val track: Int)

    private fun mediaSignatureOf(c: CapturedNotification): MediaSignature = MediaSignature(
        playing = c.mediaIsPlaying,
        // Title = track, text = artist, subText = album, plus the track duration: together these turn over on a
        // track change while staying put as the position advances.
        track = listOf(c.title, c.text, c.subText, c.mediaDurationMs).hashCode(),
    )

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

internal fun CapturedNotification.isMediaPlaybackStyle(): Boolean =
    style == NotificationStyle.MEDIA || style == NotificationStyle.DECORATED_MEDIA_CUSTOM_VIEW

internal fun shouldExcludeIosForCapture(captured: CapturedNotification, cfg: PerAppConfig): Boolean = when {
    // Media has its own iOS gate because real media players may post clearable/non-ongoing rows.
    captured.isMediaPlaybackStyle() -> !cfg.mirrorMediaPlaybackToIos
    captured.isOngoing -> !cfg.mirrorOngoingToIos
    else -> false
}
