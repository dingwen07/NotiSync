package net.extrawdw.apps.notisync.notification

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.MainActivity
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.analytics.PerfSpan
import net.extrawdw.apps.notisync.analytics.crashGuard
import net.extrawdw.apps.notisync.analytics.perfTrace
import net.extrawdw.apps.notisync.appicon.AppStoreIconProvider
import net.extrawdw.apps.notisync.appicon.IconResolver
import net.extrawdw.apps.notisync.assets.AssetCache
import net.extrawdw.apps.notisync.domain.MirrorEngine
import net.extrawdw.apps.notisync.domain.MirrorRenderer
import net.extrawdw.apps.notisync.domain.RenderPhase
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.GroupAlertBehavior
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.CallType
import net.extrawdw.notisync.protocol.NotificationStyle
import net.extrawdw.notisync.protocol.NotificationAction
import net.extrawdw.notisync.protocol.OriginPlatform
import net.extrawdw.notisync.protocol.PrivateAssetRef
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors the SOURCE app's channel structure on the receiver. Android-origin captures keep the source's
 * own shape: one NotificationChannelGroup per source app, one NotificationChannel per source channel
 * (importance/mute mirrored at creation), nested in that group. If the source app uses channel groups,
 * the group's display name is shown in the channel name (e.g. "alice@gmail.com · Mail") — when available
 * (requires a CompanionDeviceManager association; v1 falls back to the channel name / category / app label).
 *
 * iOS/ANCS-origin captures have no source channels, so their settings mirror the iOS mental model
 * instead: ONE group per bridged iPhone (named after the iPhone, e.g. "Dingwen's iPhone") holding ONE
 * channel per iOS app (named after the app, e.g. "WhatsApp") — the channel is the per-app switch, the
 * group the per-device bucket. Shade bundling is a separate mechanism and stays per app (see
 * RemoteNotificationPoster.receiverGroupKey).
 *
 * IDs are "{type}:{sourceClientId}:…" with type = group / channel / conversation; the second
 * ':'-segment is always the source client id so [gc] can prune by peer. Android-origin ids are
 * "{type}:{client}:{package}[:{source}]", and the per-app group's display name carries the source
 * device — e.g. "WhatsApp (Pixel 10)" — so two devices running the same app stay distinct in system
 * settings. iOS-origin ids are "group:{client}:{originId}:_device" and
 * "channel:{client}:{originId}:{package}:_ios". Legacy per-app iOS groups/channels (Android-shaped,
 * ending ":{package}" / ":_default") are neither migrated nor pruned — they linger, no longer posted
 * to, until the user runs the reset-channels diagnostic ([deleteAll], which matches them by prefix).
 */
object MirrorChannels {
    // Sentinel id segments for the iOS/ANCS scheme, in the "_default" convention: a real package /
    // bundle id is always dotted and an ANCS capture has no source-channel id, so an underscore-only
    // segment can never collide with a real value in that slot.
    private const val IOS_DEVICE_GROUP = "_device"
    private const val IOS_APP_CHANNEL = "_ios"

    /** Matches AncsBleManager.iphoneId()'s own fallback, for a bridged capture missing its origin id. */
    private const val IOS_FALLBACK_ORIGIN = "iphone"
    // The [originId] segment (a stable origin-device id, e.g. a hashed iPhone id) separates notifications a
    // bridging client relays for *different* origin devices (its own Android vs a paired iPhone) — otherwise
    // a same-app notification from each collides into one group and its name flip-flops. Empty for a local
    // capture, which keeps the legacy id format so existing groups/channels aren't churned.
    private fun groupId(client: ClientId, originId: String, pkg: String) =
        if (originId.isEmpty()) "group:${client.value}:$pkg" else "group:${client.value}:$originId:$pkg"

    private fun channelId(client: ClientId, originId: String, pkg: String, src: String?) =
        if (originId.isEmpty()) "channel:${client.value}:$pkg:${src ?: "_default"}" else "channel:${client.value}:$originId:$pkg:${src ?: "_default"}"

    private fun convChannelId(client: ClientId, originId: String, pkg: String, conv: String) =
        if (originId.isEmpty()) "conversation:${client.value}:$pkg:$conv" else "conversation:${client.value}:$originId:$pkg:$conv"

    /** One settings group per bridged iPhone. The trailing sentinel keeps the shape mechanically distinct
     *  from the legacy per-app "group:{client}:{originId}:{package}" it replaces. */
    private fun iosDeviceGroupId(client: ClientId, originId: String) =
        "group:${client.value}:$originId:$IOS_DEVICE_GROUP"

    /** One channel per iOS app, under the device group. Deliberately a FRESH id (legacy ANCS channels end
     *  in ":_default"): the OS never re-groups an existing channel and resurrects a deleted id with its old
     *  settings (including the dead per-app group), so only a new id moves upgraded installs to the
     *  device-group layout — no migration pass needed. */
    private fun iosAppChannelId(client: ClientId, originId: String, pkg: String) =
        "channel:${client.value}:$originId:$pkg:$IOS_APP_CHANNEL"

    /** Stable origin-device discriminator for ids; empty for a local capture (keeps the legacy id format). */
    private fun originId(notif: CapturedNotification): String =
        notif.originDeviceId?.takeIf { it.isNotBlank() }.orEmpty()

    private fun isCallCapture(notif: CapturedNotification): Boolean =
        notif.category == MirrorCategory.CALL || notif.style == NotificationStyle.CALL || notif.callType != null

    /**
     * Whether the mirror channel is created with NO sound. Two reasons: (1) the source channel itself has no
     * sound ([CapturedNotification.channelSilent] — a dialer's HIGH-importance-but-silent incoming-call
     * channel, whose ringtone the app plays itself; we mirror that so the channel still pops up but stays
     * silent rather than getting the default notification sound), and (2) any call, whose ring is owned by
     * [CallRinger] on the receiver — so the channel must never also sound and double the ring.
     */
    private fun mirrorChannelSilent(notif: CapturedNotification): Boolean =
        notif.channelSilent == true

    private fun isIosOrigin(notif: CapturedNotification) =
        notif.originPlatform == OriginPlatform.IOS_ANCS

    /** ANCS origin id for id-building; never empty (the mapper always sets one, but a foreign producer
     *  might not). */
    private fun ancsOriginId(notif: CapturedNotification) =
        originId(notif).ifEmpty { IOS_FALLBACK_ORIGIN }

    /** The settings group this capture files under — shared by [ensure] and [ensureConversation]. */
    private fun groupIdFor(notif: CapturedNotification): String =
        if (isIosOrigin(notif)) iosDeviceGroupId(notif.sourceClientId, ancsOriginId(notif))
        else groupId(notif.sourceClientId, originId(notif), notif.packageName)

    /** Group label carries the source device so two devices running the same app stay distinct. */
    fun groupName(appLabel: String, deviceName: String?): String =
        deviceName?.takeIf { it.isNotBlank() }?.let { "$appLabel ($it)" } ?: appLabel

    /** Create the group + channel this capture posts on; return the channel id.
     *  Android-origin: per-app group + per-source-channel channel. iOS/ANCS: per-iPhone group + per-app channel. */
    fun ensure(context: Context, notif: CapturedNotification, deviceName: String?): String {
        val mgr = context.getSystemService(NotificationManager::class.java)
        val ios = isIosOrigin(notif)
        val gid = groupIdFor(notif)
        // An iOS group is labelled with the iPhone itself — originDeviceName only, NOT [deviceName], whose
        // fallback is the bridging client's profile name and would title the iPhone's bucket after the
        // Android phone that relayed it. Placeholder until the name is learned; the re-apply below heals it.
        val groupLabel =
            if (ios) notif.originDeviceName?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.mirror_ios_device_fallback)
            else groupName(notif.appLabel, deviceName)
        // Group must exist before a channel references it; createNotificationChannel also updates
        // name/description and lowers importance on an existing channel (never raises — OS contract).
        // createNotificationChannelGroup re-applies the name every call, so a source-device rename
        // surfaces on its next mirrored notification — the only point where both the app label (from
        // the capture) and the current device name (from the trust store) are known together.
        mgr.createNotificationChannelGroup(NotificationChannelGroup(gid, groupLabel))
        val cid =
            if (ios) iosAppChannelId(notif.sourceClientId, ancsOriginId(notif), notif.packageName)
            else channelId(notif.sourceClientId, originId(notif), notif.packageName, notif.channelId)
        // The first notification's importance fixes the channel: createNotificationChannel can only LOWER an
        // existing channel's importance, never raise it (OS contract), and a delete+recreate to force a raise
        // doesn't work either — the OS resurrects a same-id channel with its old importance (and the delete
        // cancels any live posts on it). So the first message's importance is authoritative; the user can
        // raise it later in system settings.
        val importance = importanceOf(notif)
        mgr.createNotificationChannel(
            NotificationChannel(cid, channelName(context, notif), importance).apply {
                group = gid
                // Mirror the source channel's own vibration setting (Android: NotificationChannel.shouldVibrate()).
                // Vibration is a per-channel property the OS fixes at creation — importance alone never enables it.
                // iOS/ANCS has no source channel, so shouldVibrate is false there and the mirror just heads-up + sounds.
                enableVibration(notif.shouldVibrate)
                // A silent-but-important source channel (a dialer's incoming-call channel), or any call (rung by
                // CallRinger), is created with NO sound so it pops up on importance alone without a channel blip.
                if (mirrorChannelSilent(notif)) setSound(null, null)
            }
        )
        return cid
    }

    /** Create a conversation child channel under [parentChannelId]; return its id. */
    fun ensureConversation(
        context: Context,
        notif: CapturedNotification,
        parentChannelId: String,
        shortcutId: String
    ): String {
        val mgr = context.getSystemService(NotificationManager::class.java)
        val conv = notif.shortcutId ?: notif.sourceKey
        val cid = convChannelId(notif.sourceClientId, originId(notif), notif.packageName, conv)
        mgr.createNotificationChannel(
            NotificationChannel(cid, channelName(context, notif), importanceOf(notif)).apply {
                // ANCS captures are never conversations today; groupIdFor keeps the group right if that changes.
                group = groupIdFor(notif)
                setConversationId(parentChannelId, shortcutId)
                enableVibration(notif.shouldVibrate)
                if (mirrorChannelSilent(notif)) setSound(null, null)
            }
        )
        return cid
    }

    /** Prune mirrored groups/channels whose source client is no longer a trusted peer. */
    fun gc(context: Context, validClientIds: Set<String>) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        runCatching {
            mgr.notificationChannels.forEach { c ->
                if ((c.id.startsWith("channel:") || c.id.startsWith("conversation:")) && clientOf(c.id) !in validClientIds) {
                    mgr.deleteNotificationChannel(c.id)
                }
            }
            mgr.notificationChannelGroups.forEach { g ->
                if (g.id.startsWith("group:") && clientOf(g.id) !in validClientIds) {
                    mgr.deleteNotificationChannelGroup(g.id)
                }
            }
        }
    }

    /**
     * Delete every mirrored group + channel this app created (diagnostics recovery). The OS pins a channel's
     * importance at creation and only ever lowers it, so a channel stranded at Silent (e.g. minted by an older
     * build, or before the importance fix) can't be raised in code — deleting it is the only way to have it
     * recreated at the right importance. They come back at HIGH on the next mirrored iPhone notification. Leaves
     * the app's own service channels alone. Returns the number of channels removed.
     */
    fun deleteAll(context: Context): Int {
        val mgr = context.getSystemService(NotificationManager::class.java)
        return runCatching {
            val mirrored =
                mgr.notificationChannels.filter { it.id.startsWith("channel:") || it.id.startsWith("conversation:") }
            mirrored.forEach { mgr.deleteNotificationChannel(it.id) }
            mgr.notificationChannelGroups.forEach {
                if (it.id.startsWith("group:")) mgr.deleteNotificationChannelGroup(
                    it.id
                )
            }
            mirrored.size
        }.getOrDefault(0)
    }

    private fun clientOf(id: String): String = id.substringAfter(':').substringBefore(':')

    private fun channelName(context: Context, notif: CapturedNotification): String {
        // iOS/ANCS: the channel IS the app's switch, so it carries the app's name. The generic fallback
        // below would name it after the LATEST post's category ("Messages", then "Calls" after a missed
        // call) — generic and unstable, since createNotificationChannel re-applies the name on every call.
        if (isIosOrigin(notif)) return notif.appLabel
        val base =
            notif.channelName?.takeIf { it.isNotBlank() } ?: categoryLabel(context, notif.category)
            ?: notif.appLabel
        return notif.channelGroupName?.takeIf { it.isNotBlank() }?.let { "$it · $base" } ?: base
    }

    // iPhone (ANCS) notifications are banners on iOS, so their mirrored channel is always created HIGH — this is
    // the single enforcement point for that rule. It must NOT be derived from any one notification's importance:
    // the OS fixes a channel's importance at creation and only ever LOWERS it (see ensure()), so a single
    // transiently-quiet notification (connect-time backlog, or an iOS "silent" flag) would otherwise strand the
    // whole channel in the shade's Silent section. Per-notification quieting is done with setSilent() at post
    // time instead (RemoteNotificationPoster.render), which never touches the channel's importance.
    private fun importanceOf(notif: CapturedNotification): Int =
        if (notif.originPlatform == OriginPlatform.IOS_ANCS) NotificationManager.IMPORTANCE_HIGH
        else mapImportance(notif.channelImportance ?: notif.importance)

    private fun mapImportance(importance: MirrorImportance): Int = when (importance) {
        MirrorImportance.NONE -> NotificationManager.IMPORTANCE_NONE
        MirrorImportance.MIN -> NotificationManager.IMPORTANCE_MIN
        MirrorImportance.LOW -> NotificationManager.IMPORTANCE_LOW
        MirrorImportance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
        MirrorImportance.HIGH -> NotificationManager.IMPORTANCE_HIGH
    }

    private fun categoryLabel(context: Context, c: MirrorCategory): String? = when (c) {
        MirrorCategory.MESSAGE -> context.getString(R.string.category_messages)
        MirrorCategory.EMAIL -> context.getString(R.string.category_email)
        MirrorCategory.CALL -> context.getString(R.string.category_calls)
        MirrorCategory.ALARM -> context.getString(R.string.category_alarms)
        MirrorCategory.EVENT -> context.getString(R.string.category_events)
        MirrorCategory.REMINDER -> context.getString(R.string.category_reminders)
        MirrorCategory.SOCIAL -> context.getString(R.string.category_social)
        MirrorCategory.TRANSPORT -> context.getString(R.string.category_media)
        MirrorCategory.SERVICE -> context.getString(R.string.category_service)
        else -> null
    }
}

private object MirrorNotificationExtras {
    const val SOURCE_CLIENT = "net.extrawdw.apps.notisync.mirror.SOURCE_CLIENT"
    const val SOURCE_KEY = "net.extrawdw.apps.notisync.mirror.SOURCE_KEY"
    const val GROUP_KEY = "net.extrawdw.apps.notisync.mirror.GROUP_KEY"
    const val GROUP_TITLE = "net.extrawdw.apps.notisync.mirror.GROUP_TITLE"
    const val PACKAGE = "net.extrawdw.apps.notisync.mirror.PACKAGE"
    const val IOS_BUNDLE_ID = "net.extrawdw.apps.notisync.mirror.IOS_BUNDLE_ID"
    const val APP_ICON_HASH = "net.extrawdw.apps.notisync.mirror.APP_ICON_HASH"
    const val IS_SOURCE_SUMMARY = "net.extrawdw.apps.notisync.mirror.IS_SOURCE_SUMMARY"
}

/**
 * Posts mirrored notifications natively, reconstructing MessagingStyle / BigText and — for
 * conversation notifications — a long-lived shortcut + conversation channel so they file under the
 * Conversations section. A delete intent reports local swipes back for dismissal sync.
 */
class RemoteNotificationPoster(
    private val context: Context,
    private val assets: AssetCache,
    /** Resolves an app icon (shipped pack → App Store cache → delivered asset → installed → bundle-id map). */
    private val iconResolver: IconResolver,
    /** Source device's display name, for the group label; null until the peer's profile is known. */
    private val deviceNameOf: (ClientId) -> String? = { null },
    /** App Store icon fetcher for the async iOS-icon upgrade; null leaves icons on their immediate fallback. */
    private val appStoreIcons: AppStoreIconProvider? = null,
    /** Scope for the off-render-path App Store fetch + re-render; null disables the upgrade. */
    private val scope: CoroutineScope? = null,
    /** Plays the ringtone + vibration for a RINGING call mirror (whose channel is silent); null disables ringing. */
    private val callRinger: CallRinger? = null,
    /** Per-app "ring for calls" preference (receiver-side); false suppresses the ring but not the call mirror. */
    private val ringForCalls: (packageName: String) -> Boolean = { true },
) : MirrorRenderer {

    // iOS bundle ids with an App Store icon fetch in flight, so concurrent renders don't pile up duplicates.
    private val iconUpgradesInFlight = ConcurrentHashMap.newKeySet<String>()
    private val orphanSourceSummaryCleanupJobs = ConcurrentHashMap<String, Job>()

    /** [silent] posts just this one notification without sound/heads-up — the connect-time ANCS backlog replay,
     *  or a re-render that only attaches a now-downloaded graphic to an already-posted notification — while
     *  leaving the channel's importance untouched, so the next live notification on the same (HIGH) channel
     *  still alerts. Never lower a channel's importance to mute one post (see MirrorChannels.ensure). Source
     *  update alerting is carried by raw [CapturedNotification.onlyAlertOnce] plus group-summary alert behavior. */
    override fun render(notif: CapturedNotification, silent: Boolean, phase: RenderPhase) {
        // No POST_NOTIFICATIONS → notify() is a silent no-op (and throws SecurityException on some
        // OEMs); skip the channel/shortcut/asset work entirely rather than build a notification we
        // can't post. Mirrors the guard in AppGraph.onTrustPrompt.
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        perfTrace("mirror_render") { span ->
            span.attr("style", notif.style.name.lowercase())
            span.attr("phase", phase.name.lowercase())
            // Source-post → render wall-clock. For ENRICH/ICON_UPGRADE this includes the fetch/upgrade wait
            // (time-until-enriched), so it MUST be segmented by phase; cross-device clock skew makes it a
            // distribution signal, not an exact per-event value.
            span.metric("latency_ms", (System.currentTimeMillis() - notif.postTime).coerceAtLeast(0))
            renderGranted(notif, silent, span)
        }
    }

    /** The render body, after the POST_NOTIFICATIONS guard. Traced as `mirror_render` by [render]. */
    private fun renderGranted(notif: CapturedNotification, silent: Boolean, span: PerfSpan) {
        // Prefer the originating device's name (e.g. the bridged iPhone) over this sender's own name, so an
        // iOS mirror reads "WhatsApp (Dingwen's iPhone)", not the Android bridge phone's name.
        val groupDeviceName = notif.originDeviceName ?: deviceNameOf(notif.sourceClientId)
        val parentChannelId = MirrorChannels.ensure(context, notif, groupDeviceName)
        val receiverGroupKey = receiverGroupKey(notif)
        val receiverGroupTitle = MirrorChannels.groupName(notif.appLabel, groupDeviceName)
        if (notif.isGroupSummary) {
            span.attr("kind", "group_summary")
            val summaryStartNanos = System.nanoTime()
            updateGroupSummary(
                receiverGroupKey,
                parentChannelId,
                receiverGroupTitle,
                notif.packageName,
                notif.iosBundleId,
                notif.appIcon?.assetHash,
                sourceSummary = notif,
                silent = silent,
            )
            span.metric("group_summary_ms", (System.nanoTime() - summaryStartNanos) / 1_000_000)
            return
        }
        span.attr("kind", "notification")
        // Of this notification's private assets, how many are locally available at render time (hit) vs still
        // pending an async fetch (miss); asset_count = hit + miss. A high miss rate → users see text first,
        // graphics later.
        val assetRefs = listOfNotNull(notif.largeIcon, notif.bigPicture, notif.appIcon) +
            notif.messages.flatMap { listOfNotNull(it.avatar, it.data) }
        val assetHits = assetRefs.count { assets.has(it.assetHash) }
        span.metric("asset_count", assetRefs.size.toLong())
        span.metric("asset_hit_count", assetHits.toLong())
        span.metric("asset_miss_count", (assetRefs.size - assetHits).toLong())
        var postChannelId = parentChannelId
        var shortcutId: String? = null

        if (notif.isConversation) {
            val conv = notif.shortcutId ?: notif.sourceKey
            val mirroredShortcut =
                "noticonv:${notif.sourceClientId.value}:${notif.packageName}:$conv"
            val published =
                runCatching { publishConversationShortcut(notif, mirroredShortcut) }.getOrDefault(
                    false
                )
            if (published) {
                shortcutId = mirroredShortcut
                postChannelId = runCatching {
                    MirrorChannels.ensureConversation(
                        context,
                        notif,
                        parentChannelId,
                        mirroredShortcut
                    )
                }.getOrDefault(parentChannelId)
            }
        }

        val tag = tagOf(notif.sourceClientId, notif.sourceKey)
        val id = tag.hashCode()
        // Toast/feedback label for tap + UI-opening actions: where the user should look next.
        val originLabel = groupDeviceName ?: notif.sourceClientId.shortForm()

        val builder = NotificationCompat.Builder(context, postChannelId)
            .setSmallIcon(smallIconFor(notif.packageName, notif.channelId))
            .setSubText(
                context.getString(
                    R.string.mirror_via,
                    notif.appLabel
                )
            ) // marks the notification as mirrored
            // With a tap-to-open-on-origin intent attached, auto-cancel must stay OFF: an auto-cancel
            // tap would fire the deleteIntent (a mesh-wide dismissal racing the TAP event to the
            // origin, killing the content intent before it opens). The mirror clears when the origin
            // reports its own dismissal instead — the tap's effect happens over there. Without a
            // content intent a tap is inert, so auto-cancel keeps its historical value.
            .setAutoCancel(!notif.hasContentIntent)
            // Keep the source child row's raw ONLY_ALERT_ONCE flag. Apps that alert through their group
            // summary (for example WhatsApp) are mirrored by silencing this durable child row and letting the
            // forwarded source summary carry the alert, not by rewriting this flag.
            .setOnlyAlertOnce(notif.onlyAlertOnce)
            // Mirror the source's ongoing nature: on minSdk 34+ an ongoing notification is user-dismissible
            // (individual swipe) yet exempt from "Clear all" and sorted with other ongoing posts, matching how
            // the source app presents it. A local swipe of such a mirror does NOT sync back (see [deleteIntent]
            // / MirrorEngine.dismissLocal) since a non-clearable source can't be cleared on its origin.
            // A RINGING incoming call is deliberately NOT marked ongoing: that makes the mirror user-dismissible
            // and lets setTimeoutAfter (below) be honored, so a phantom ring can't get stuck. An answered/ongoing
            // call stays ongoing (sticky, live).
            .setOngoing(notif.isOngoing && !isRingingCall(notif))
            .setWhen(notif.postTime)
            .setShowWhen(true)
            .setPriority(toPriority(notif.importance))
            .setGroup(receiverGroupKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setDeleteIntent(deleteIntent(notif.sourceClientId, notif.sourceKey, id, notif.isClearable))
            .addExtras(mirrorExtras(notif, receiverGroupKey, receiverGroupTitle))
        if (notif.hasContentIntent) {
            builder.setContentIntent(tapIntent(notif, id, originLabel))
        }
        // CallStyle builds its own Answer/Decline/Hang-up buttons from the mirrored answer/decline/hang-up
        // actions, so the generic action row would duplicate them; the CALL branch adds actions itself only
        // when it can't assemble a CallStyle.
        if (notif.style != NotificationStyle.CALL) applyActions(builder, notif, tag, originLabel)
        if (shortcutId != null) builder.setShortcutId(shortcutId)
        // Quiet this one post (ANCS backlog replay, or an asset-arrival re-render) without lowering the channel
        // — the channel stays HIGH so the next live notification still heads-up. MessagingStyle below may also
        // silence its own self-reply case.
        if (silent) builder.setSilent(true)
        else if (notif.groupAlertBehavior == GroupAlertBehavior.SUMMARY && notif.groupKey != null) {
            builder.setSilent(true)
        }
        // NB: a ringing call is deliberately NOT setSilent here — that would suppress the heads-up popup. It
        // stays silent via its channel (mirrorChannelSilent → no channel sound) while still popping up on its
        // HIGH importance; CallRinger provides the ring.
        // Auto-clear a ringing incoming-call mirror after the ring window, in case the source's removal never
        // syncs (network/broker) and leaves a phantom call. The OS then fires the delete intent, which is
        // local-only for a non-clearable source — it never declines the real call. Honored because a ringing
        // call is not marked ongoing (setOngoing above). Answered/ongoing calls get no timeout.
        if (isRingingCall(notif)) {
            builder.setTimeoutAfter(CALL_RING_TIMEOUT_MS)
            // Posted silent (the ringtone is CallRinger's job), so attach a full-screen intent — the platform
            // still presents it as an incoming call (a heads-up when USE_FULL_SCREEN_INTENT isn't granted)
            // rather than a quiet shade row. A CallStyle call also sets this in the CALL branch; setting it
            // twice is harmless, and this covers non-CallStyle calls (WhatsApp, category=call) too.
            fullScreenIntentToApp(id)?.let { builder.setFullScreenIntent(it, true) }
        }

        when (notif.style) {
            NotificationStyle.MESSAGING -> {
                // MessagingStyle owns the title + per-message rendering. Do NOT also setContentTitle —
                // that would draw a second, redundant bold title line. Set conversationTitle ONLY for
                // group chats (per Google guidance); for 1:1 the title derives from the sender.
                val self =
                    Person.Builder().setName(context.getString(R.string.mirror_self_name)).build()
                val style = NotificationCompat.MessagingStyle(self)
                    .setGroupConversation(notif.isGroupConversation)
                if (notif.isGroupConversation) style.setConversationTitle(notif.conversationTitle)
                notif.messages.forEach { m ->
                    val person = m.sender?.let { name ->
                        Person.Builder().setName(name)
                            .apply {
                                m.avatar?.let { avatarIcon(it.assetHash) }?.let { setIcon(it) }
                            }
                            .build()
                    }
                    val message = NotificationCompat.MessagingStyle.Message(
                        m.text,
                        m.timestamp,
                        person
                    )
                    m.data?.let { dataRef ->
                        messageDataUri(dataRef)?.let { uri ->
                            message.setData(m.dataMimeType ?: dataRef.mimeType, uri)
                        }
                    }
                    style.addMessage(message)
                }
                builder.setStyle(style)
                // If the newest message is the user's own (sender == null — e.g. an inline reply sent
                // from the notification on the source device), post the mirror update silently: the
                // user sent it, so re-alerting them on this device is just noise.
                if (notif.messages.isNotEmpty() && notif.messages.last().sender == null) {
                    builder.setSilent(true)
                }
            }

            NotificationStyle.BIG_PICTURE -> {
                builder.setContentTitle(notif.title ?: notif.appLabel).setContentText(notif.text)
                val picture = notif.bigPicture?.let { cachedBitmap(it.assetHash) }
                if (picture != null) builder.setStyle(
                    NotificationCompat.BigPictureStyle().bigPicture(picture)
                )
                else notif.bigText?.let {
                    builder.setStyle(
                        NotificationCompat.BigTextStyle().bigText(it)
                    )
                }
            }

            NotificationStyle.BIG_TEXT -> {
                builder.setContentTitle(notif.title ?: notif.appLabel).setContentText(notif.text)
                builder.setStyle(
                    NotificationCompat.BigTextStyle().bigText(notif.bigText ?: notif.text)
                )
            }

            NotificationStyle.INBOX -> {
                builder.setContentTitle(notif.title ?: notif.appLabel).setContentText(notif.text)
                if (notif.inboxLines.isNotEmpty()) {
                    val style = NotificationCompat.InboxStyle()
                    notif.title?.let { style.setBigContentTitle(it) }
                    notif.inboxLines.forEach { style.addLine(it) }
                    builder.setStyle(style)
                } else notif.bigText?.let {
                    builder.setStyle(NotificationCompat.BigTextStyle().bigText(it))
                }
            }

            NotificationStyle.MEDIA, NotificationStyle.DECORATED_MEDIA_CUSTOM_VIEW -> {
                // The source's custom RemoteViews can't cross the device boundary, so a decorated-media
                // notification degrades to the same standard MediaStyle treatment as a plain media one.
                builder.setContentTitle(notif.title ?: notif.appLabel).setContentText(notif.text)
                notif.accentColor?.let { builder.setColor(it) }
                if (notif.isColorized) builder.setColorized(true)
                // Album art rides the large icon (attached by applyLargeIcon below). No MediaSession token is
                // available on the consumer, so the media template degrades to a rich notification whose
                // transport buttons relay a press back to the origin — which is what controls playback.
                val media = androidx.media.app.NotificationCompat.MediaStyle()
                // The source's compact-view selection is in raw source-action index space; map each to its
                // position in the exported action row (the order applyActions added them), dropping any not
                // exported. The compact view shows at most three.
                val compact = notif.mediaCompactActionIndices
                    .mapNotNull { src -> notif.actions.indexOfFirst { it.index == src }.takeIf { it >= 0 } }
                    .take(3)
                    .toIntArray()
                if (compact.isNotEmpty()) media.setShowActionsInCompactView(*compact)
                builder.setStyle(media)
            }

            NotificationStyle.CALL -> {
                builder.setContentTitle(notif.callerName ?: notif.title ?: notif.appLabel)
                    .setContentText(notif.text)
                notif.accentColor?.let { builder.setColor(it) }
                val person = Person.Builder()
                    .setName(notif.callerName ?: notif.title ?: notif.appLabel)
                    .apply { notif.largeIcon?.let { avatarIcon(it.assetHash) }?.let { setIcon(it) } }
                    .build()
                // Build the CallStyle buttons from the mirrored answer/decline/hang-up actions: pressing one
                // relays a PERFORM to the origin, which acts on the real call.
                fun intentFor(index: Int?): PendingIntent? = index
                    ?.let { idx -> notif.actions.firstOrNull { it.index == idx } }
                    ?.let { actionIntent(notif, it, tag, originLabel) }
                val answer = intentFor(notif.callAnswerIndex)
                val decline = intentFor(notif.callDeclineIndex)
                val hangUp = intentFor(notif.callHangUpIndex)
                val callStyle = when (notif.callType) {
                    CallType.INCOMING -> if (answer != null && decline != null)
                        NotificationCompat.CallStyle.forIncomingCall(person, decline, answer) else null

                    CallType.ONGOING -> if (hangUp != null)
                        NotificationCompat.CallStyle.forOngoingCall(person, hangUp) else null

                    CallType.SCREENING -> if (answer != null && hangUp != null)
                        NotificationCompat.CallStyle.forScreeningCall(person, hangUp, answer) else null

                    null -> null
                }
                if (callStyle != null) {
                    notif.callVerificationText?.let { callStyle.setVerificationText(it) }
                    builder.setStyle(callStyle).setCategory(NotificationCompat.CATEGORY_CALL)
                    // Android REQUIRES a CallStyle notification to be a foreground service OR carry a full-screen
                    // intent, else NotificationManager throws at post ("CallStyle notifications must either be for
                    // a foreground Service or use a fullScreenIntent"). A mirror is neither, so attach an FSI that
                    // opens NotiSync. Without the USE_FULL_SCREEN_INTENT special access (default on Android 14+)
                    // it shows as a heads-up instead of full screen, which still satisfies the requirement.
                    fullScreenIntentToApp(id)?.let { builder.setFullScreenIntent(it, true) }
                } else {
                    // Missing the intents CallStyle needs — fall back to a plain notification with the mirrored
                    // actions (the generic action row was skipped above for CALL).
                    applyActions(builder, notif, tag, originLabel)
                    notif.bigText?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
                }
            }

            else -> {
                builder.setContentTitle(notif.title ?: notif.appLabel).setContentText(notif.text)
                notif.bigText?.let {
                    builder.setStyle(
                        NotificationCompat.BigTextStyle().bigText(it)
                    )
                }
            }
        }

        applyLargeIcon(builder, notif)

        val notifyStartNanos = System.nanoTime()
        // Build + post together, because the CallStyle FGS/FSI requirement is enforced at POST, not build: the
        // platform throws IllegalArgumentException from notify() if a CallStyle notification is neither a
        // foreground service nor carries a full-screen intent (a mirror is neither — hence the FSI above). On
        // ANY failure, strip the style, restore the action row CALL skipped, and post a plain high-priority
        // notification so the call still shows with its Answer/Decline buttons. The old silent runCatching hid
        // exactly this failure mode; post failures are now logged.
        fun post(): Boolean = runCatching {
            NotificationManagerCompat.from(context).notify(tag, id, builder.build())
            true
        }.getOrElse {
            Log.w("MirrorNotifications", "post failed for style=${notif.style}: ${it.message}", it)
            false
        }
        if (!post()) {
            builder.setStyle(null)
            if (notif.style == NotificationStyle.CALL) applyActions(builder, notif, tag, originLabel)
            post()
        }
        span.metric("notify_ms", (System.nanoTime() - notifyStartNanos) / 1_000_000)
        // Incoming-call ringer: a ringing call plays the phone ringtone + vibration itself (the mirror above is
        // posted silent). Start only on the genuine alerting post — a silent asset/backlog re-render of the same
        // call is already ringing — and stop on any non-ringing render of the same call (answered → ongoing/FGS).
        // Answer/Decline press, swipe, source dismissal and timeout each stop it via their own paths.
        if (notif.style == NotificationStyle.CALL || notif.category == MirrorCategory.CALL) {
            if (isRingingCall(notif)) {
                if (!silent && ringForCalls(notif.packageName)) callRinger?.start(tag)
            } else callRinger?.stop(tag)
        }
        if (notif.style == NotificationStyle.MESSAGING) {
            span.metric("message_count", notif.messages.size.toLong())
        }
        val summaryStartNanos = System.nanoTime()
        updateGroupSummary(
            receiverGroupKey,
            postChannelId,
            receiverGroupTitle,
            notif.packageName,
            notif.iosBundleId,
            notif.appIcon?.assetHash,
            silent = true,
        )
        span.metric("group_summary_ms", (System.nanoTime() - summaryStartNanos) / 1_000_000)

        maybeUpgradeIcon(notif)
    }

    private fun mirrorExtras(
        notif: CapturedNotification,
        receiverGroupKey: String,
        receiverGroupTitle: String
    ) =
        Bundle().apply {
            putString(MirrorNotificationExtras.SOURCE_CLIENT, notif.sourceClientId.value)
            putString(MirrorNotificationExtras.SOURCE_KEY, notif.sourceKey)
            putString(MirrorNotificationExtras.GROUP_KEY, receiverGroupKey)
            putString(MirrorNotificationExtras.GROUP_TITLE, receiverGroupTitle)
            putString(MirrorNotificationExtras.PACKAGE, notif.packageName)
            putString(MirrorNotificationExtras.IOS_BUNDLE_ID, notif.iosBundleId)
            putString(MirrorNotificationExtras.APP_ICON_HASH, notif.appIcon?.assetHash)
        }

    /**
     * Receiver-local shade grouping. Same-app, same-channel mirrors group together by default, separated
     * by source client and bridged-origin device so two devices running the same app do not collapse into
     * one shade group. The source group key remains payload metadata; it is not used as the default
     * receiver bucket.
     */
    private fun receiverGroupKey(notif: CapturedNotification): String {
        val origin = notif.originDeviceId?.takeIf { it.isNotBlank() } ?: "_local"
        val channel = notif.parentChannelId?.takeIf { it.isNotBlank() }
            ?: notif.channelId?.takeIf { it.isNotBlank() }
            ?: "_default"
        return "notisync:${stableToken("${notif.sourceClientId.value}\u001F$origin\u001F${notif.packageName}\u001F$channel")}"
    }

    private fun stableToken(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(12))
    }

    private fun updateGroupSummary(
        groupKey: String,
        fallbackChannelId: String,
        fallbackTitle: String,
        fallbackPackage: String,
        fallbackIosBundleId: String? = null,
        fallbackAppIconHash: String? = null,
        sourceSummary: CapturedNotification? = null,
        silent: Boolean = true,
        // A child tag the caller has just cancelled (see [clear]). getActiveNotifications() reflects a cancel
        // asynchronously, so the caller names the row to treat as already gone — letting the last-child cleanup
        // below fire on this same pass instead of stranding the summary until a re-post that may never come.
        excludeChildTag: String? = null,
    ) {
        val compat = NotificationManagerCompat.from(context)
        val summaryTag = summaryTagOf(groupKey)
        // One getActiveNotifications() snapshot drives both the child set and the source-summary check below.
        val active = activeNotifications()
        val children = groupChildren(active, groupKey, excludeChildTag)
        if (sourceSummary == null) {
            cancelOrphanSourceSummaryCleanup(groupKey)
            // A forwarded source summary is the alerting post for SUMMARY-behavior groups. Do not let the quiet
            // synthetic-summary maintenance pass overwrite or cancel it while its child rows still exist.
            val hasSourceSummary = hasSourceSummary(active, summaryTag)
            if (hasSourceSummary) {
                if (children.isEmpty()) {
                    if (excludeChildTag != null) {
                        compat.cancel(summaryTag, summaryTag.hashCode())
                    } else {
                        scheduleOrphanSourceSummaryCleanup(groupKey)
                    }
                }
                return
            }
            if (children.size < 2) {
                compat.cancel(summaryTag, summaryTag.hashCode())
                return
            }
        } else if (children.isEmpty()) {
            scheduleOrphanSourceSummaryCleanup(groupKey)
        } else {
            cancelOrphanSourceSummaryCleanup(groupKey)
        }
        val latest = children.maxByOrNull { it.postTime }
        val extras = latest?.notification?.extras
        val title = sourceSummary?.title ?: extras?.getString(MirrorNotificationExtras.GROUP_TITLE) ?: fallbackTitle
        val packageName = sourceSummary?.packageName ?: extras?.getString(MirrorNotificationExtras.PACKAGE) ?: fallbackPackage
        val iosBundleId =
            sourceSummary?.iosBundleId ?: extras?.getString(MirrorNotificationExtras.IOS_BUNDLE_ID) ?: fallbackIosBundleId
        val appIconHash =
            sourceSummary?.appIcon?.assetHash ?: extras?.getString(MirrorNotificationExtras.APP_ICON_HASH) ?: fallbackAppIconHash
        val channelId = if (sourceSummary != null) fallbackChannelId else latest?.notification?.channelId ?: fallbackChannelId
        val text = sourceSummary?.text ?: context.resources.getQuantityString(
            R.plurals.mirror_group_summary_count,
            children.size,
            children.size
        )
        val largeIcon = iconResolver.colorIcon(packageName, iosBundleId, appIconHash)
        val summary = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIconFor(packageName, sourceSummary?.channelId))
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(context.getString(R.string.mirror_via, fallbackTitle))
            .setWhen(sourceSummary?.postTime ?: latest?.postTime ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setGroupAlertBehavior(toGroupAlertBehavior(sourceSummary?.groupAlertBehavior ?: GroupAlertBehavior.CHILDREN))
            .setOnlyAlertOnce(sourceSummary?.onlyAlertOnce ?: true)
            .apply { if (silent) setSilent(true) }
            .setDeleteIntent(groupDeleteIntent(groupKey, summaryTag.hashCode()))
            .addExtras(summaryExtras(sourceSummary))
            .apply { largeIcon?.let { setLargeIcon(it) } }
            .build()
        runCatching { compat.notify(summaryTag, summaryTag.hashCode(), summary) }
    }

    private fun groupChildren(
        active: List<StatusBarNotification>,
        groupKey: String,
        excludeChildTag: String? = null,
    ): List<StatusBarNotification> =
        active.filter { sbn ->
            sbn.packageName == context.packageName &&
                sbn.notification.group == groupKey &&
                (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                sbn.tag != excludeChildTag
        }

    private fun hasSourceSummary(active: List<StatusBarNotification>, summaryTag: String): Boolean =
        active.firstOrNull { it.tag == summaryTag && it.id == summaryTag.hashCode() }
            ?.notification?.extras?.getBoolean(MirrorNotificationExtras.IS_SOURCE_SUMMARY, false) == true

    private fun scheduleOrphanSourceSummaryCleanup(groupKey: String) {
        val cleanupScope = scope ?: return
        val summaryTag = summaryTagOf(groupKey)
        val job = cleanupScope.launch {
            try {
                delay(ORPHAN_SOURCE_SUMMARY_CLEANUP_MS)
                val active = activeNotifications()
                if (groupChildren(active, groupKey).isEmpty() && hasSourceSummary(active, summaryTag)) {
                    NotificationManagerCompat.from(context).cancel(summaryTag, summaryTag.hashCode())
                }
            } finally {
                orphanSourceSummaryCleanupJobs.remove(groupKey, coroutineContext.job)
            }
        }
        orphanSourceSummaryCleanupJobs.put(groupKey, job)?.cancel()
    }

    private fun cancelOrphanSourceSummaryCleanup(groupKey: String) {
        orphanSourceSummaryCleanupJobs.remove(groupKey)?.cancel()
    }

    private fun summaryExtras(sourceSummary: CapturedNotification?): Bundle =
        Bundle().apply {
            if (sourceSummary != null) {
                putBoolean(MirrorNotificationExtras.IS_SOURCE_SUMMARY, true)
                putString(MirrorNotificationExtras.SOURCE_CLIENT, sourceSummary.sourceClientId.value)
                putString(MirrorNotificationExtras.SOURCE_KEY, sourceSummary.sourceKey)
            }
        }

    /**
     * Large icon: the mirrored original (a contact-photo private asset) once it's in the local cache; until
     * then — and for any notification without one (including all iOS/ANCS mirrors) — the best recognizable
     * app icon, via [IconResolver.colorIcon]: shipped pack → App Store cache → delivered APP_ICON asset →
     * the app installed here → the iOS bundle-id → Android-package mapping → none.
     */
    private fun applyLargeIcon(builder: NotificationCompat.Builder, notif: CapturedNotification) {
        val bitmap = notif.largeIcon?.let { cachedBitmap(it.assetHash) }
            ?: iconResolver.colorIcon(
                notif.packageName,
                notif.iosBundleId,
                notif.appIcon?.assetHash
            )
        bitmap?.let { builder.setLargeIcon(it) }
    }

    /**
     * For an iOS/ANCS mirror whose icon isn't already in the shipped pack (and has no mirrored original),
     * fetch the real App Store icon once off the render path and re-post when it lands — the same
     * "render now, re-render when the graphic arrives" pattern the asset layer uses. The in-flight guard +
     * `ensureCached`'s newly-available result make the re-render fire at most once per app.
     */
    private fun maybeUpgradeIcon(notif: CapturedNotification) {
        val provider = appStoreIcons ?: return
        val scope = scope ?: return
        val bundleId = notif.iosBundleId ?: return
        if (notif.largeIcon != null) return            // a mirrored original is already the best icon
        if (iconResolver.shippedCovers(bundleId)) return // shipped icon already preferred
        if (!iconUpgradesInFlight.add(bundleId)) return  // a fetch for this app is already running
        scope.launch {
            try {
                // colorIcon now hits the App Store cache
                if (provider.ensureCached(bundleId)) render(notif, phase = RenderPhase.ICON_UPGRADE)
            } finally {
                iconUpgradesInFlight.remove(bundleId)
            }
        }
    }

    /**
     * Small icon for a mirrored notification, resolved most-specific first: a source (package, channelId)
     * pair for apps that vary their glyph by purpose (e.g. Phone by Google's missed-call vs voicemail vs
     * in-call channels), then the app's default brand icon, then the generic NotiSync mirror glyph.
     *
     * [channelId] is the *source* app's channel id (e.g. "phone_missed_call"), not the receiver's mirror
     * channel. It is best-effort — null for iOS/ANCS captures and whenever the source Ranking was
     * unavailable at capture — so every channel-keyed app must also define a package default below.
     */
    private fun smallIconFor(packageName: String, channelId: String?): Int {
        channelId?.let { CHANNEL_SMALL_ICONS[packageName]?.get(it) }?.let { return it }
        return PACKAGE_SMALL_ICONS[packageName] ?: R.drawable.ic_notisync_mirror
    }

    private fun cachedBitmap(assetHash: String): Bitmap? =
        assets.read(assetHash)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    private fun avatarIcon(assetHash: String): IconCompat? =
        cachedBitmap(assetHash)?.let { IconCompat.createWithBitmap(it.circularAvatar()) }

    private fun messageDataUri(ref: PrivateAssetRef?): Uri? {
        ref ?: return null
        val file = assets.fileForRead(ref.assetHash) ?: return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.assetprovider", file)
        }.getOrNull()
    }

    private fun Bitmap.circularAvatar(): Bitmap {
        if (width <= 0 || height <= 0) return this
        val size = minOf(width, height)
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(this, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setTranslate(-left, -top) })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
        }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    override fun clear(sourceClientId: ClientId, sourceKey: String) {
        val tag = tagOf(sourceClientId, sourceKey)
        callRinger?.stop(tag)   // the call's source removal/dismissal synced here → stop any ring for it
        val active = activeNotification(tag, tag.hashCode())
        val groupKey = active?.notification?.group
        val fallbackChannel = active?.notification?.channelId
        val fallbackTitle =
            active?.notification?.extras?.getString(MirrorNotificationExtras.GROUP_TITLE)
        val fallbackPackage =
            active?.notification?.extras?.getString(MirrorNotificationExtras.PACKAGE)
        val fallbackIosBundleId =
            active?.notification?.extras?.getString(MirrorNotificationExtras.IOS_BUNDLE_ID)
        val fallbackAppIconHash =
            active?.notification?.extras?.getString(MirrorNotificationExtras.APP_ICON_HASH)
        NotificationManagerCompat.from(context).cancel(tag, tag.hashCode())
        if (groupKey != null && fallbackChannel != null) {
            updateGroupSummary(
                groupKey,
                fallbackChannel,
                fallbackTitle ?: context.getString(R.string.app_name),
                fallbackPackage ?: context.packageName,
                fallbackIosBundleId,
                fallbackAppIconHash,
                excludeChildTag = tag,
            )
        }
    }

    /** Publish a long-lived Person shortcut so the mirrored conversation renders as a conversation. */
    private fun publishConversationShortcut(
        notif: CapturedNotification,
        shortcutId: String
    ): Boolean {
        val label = notif.conversationTitle?.takeIf { it.isNotBlank() }
            ?: notif.messages.firstOrNull { it.sender != null }?.sender
            ?: notif.title
            ?: notif.appLabel
        val senders = notif.messages.mapNotNull { it.sender }.distinct()
        val persons = senders.ifEmpty { listOf(label) }
            .map { Person.Builder().setName(it).setKey(it).build() }
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
        val avatar =
            notif.messages.firstNotNullOfOrNull { m -> m.avatar?.let { avatarIcon(it.assetHash) } }
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setLongLived(true)
            .setShortLabel(label)
            .setPersons(persons.toTypedArray())
            .setLocusId(LocusIdCompat(shortcutId))
            .setIntent(intent)
            .apply { avatar?.let { setIcon(it) } }
            .build()
        return ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    /**
     * Rebuild the source's action row on the mirror. A press broadcasts to [MirrorActionReceiver],
     * which unicasts a PERFORM [net.extrawdw.notisync.protocol.ActionEvent] to the origin; a
     * remote-input action carries a reply field whose text rides along. Mirrored actions never open
     * UI on THIS device (`setShowsUserInterface(false)`) — a UI-opening origin action instead
     * toasts "[originLabel]" feedback from the receiver.
     */
    private fun applyActions(
        builder: NotificationCompat.Builder,
        notif: CapturedNotification,
        tag: String,
        originLabel: String,
    ) {
        for (action in notif.actions) {
            val pi = actionIntent(notif, action, tag, originLabel)
            val ab = NotificationCompat.Action.Builder(0, action.title, pi)
                .setSemanticAction(action.semanticAction) // same constants as the framework's
                .setShowsUserInterface(false)
                .setAllowGeneratedReplies(false)
            if (action.remoteInput) {
                ab.addRemoteInput(
                    RemoteInput.Builder(MirrorActionReceiver.KEY_REMOTE_REPLY)
                        .setLabel(
                            action.remoteInputLabel?.takeIf { it.isNotBlank() }
                                ?: context.getString(R.string.mirror_reply_hint)
                        )
                        .build()
                )
            }
            builder.addAction(ab.build())
        }
    }

    /** A RINGING (as opposed to answered/ongoing) incoming call — non-dismissible and prone to going stale, so
     *  the render makes it dismissible + auto-clearing. CallStyle gives the call type directly; a non-CallStyle
     *  call (e.g. WhatsApp, `category=call`) is "ringing" when it is NOT a foreground service (the answered call
     *  is an FGS). */
    private fun isRingingCall(notif: CapturedNotification): Boolean =
        notif.callType == CallType.INCOMING || notif.callType == CallType.SCREENING ||
            (notif.callType == null && notif.category == MirrorCategory.CALL && !notif.isForegroundService)

    /** A full-screen PendingIntent that opens NotiSync — attached to a CallStyle mirror so the platform's
     *  "CallStyle must be an FGS or have a fullScreenIntent" check passes (shown as a heads-up when the
     *  USE_FULL_SCREEN_INTENT special access isn't granted). Null if the app has no launcher entry. */
    private fun fullScreenIntentToApp(id: Int): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return null
        return PendingIntent.getActivity(
            context, id, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Tap-to-open-on-origin: a no-UI trampoline activity (an activity is what collapses the shade)
     *  that toasts "Check on <origin>" and unicasts a TAP event. The mirror itself stays posted —
     *  see the setAutoCancel note in [renderGranted]. */
    private fun tapIntent(notif: CapturedNotification, id: Int, originLabel: String): PendingIntent {
        val intent = Intent(context, MirrorTapActivity::class.java).apply {
            action = MirrorTapActivity.ACTION_TAP
            // Distinct data per mirror: extras don't participate in PendingIntent identity.
            data = Uri.parse("notisync://tap/${Uri.encode(tagOf(notif.sourceClientId, notif.sourceKey))}")
            putExtra(MirrorTapActivity.EXTRA_SOURCE_CLIENT, notif.sourceClientId.value)
            putExtra(MirrorTapActivity.EXTRA_SOURCE_KEY, notif.sourceKey)
            putExtra(MirrorTapActivity.EXTRA_DEVICE_NAME, originLabel)
        }
        return PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionIntent(
        notif: CapturedNotification,
        action: NotificationAction,
        tag: String,
        originLabel: String,
    ): PendingIntent {
        val intent = Intent(context, MirrorActionReceiver::class.java).apply {
            this.action = MirrorActionReceiver.ACTION_PERFORM
            data = Uri.parse("notisync://action/${action.index}/${Uri.encode(tag)}")
            putExtra(MirrorActionReceiver.EXTRA_SOURCE_CLIENT, notif.sourceClientId.value)
            putExtra(MirrorActionReceiver.EXTRA_SOURCE_KEY, notif.sourceKey)
            putExtra(MirrorActionReceiver.EXTRA_ACTION_INDEX, action.index)
            putExtra(MirrorActionReceiver.EXTRA_ACTION_TITLE, action.title)
            putExtra(MirrorActionReceiver.EXTRA_REMOTE_INPUT, action.remoteInput)
            putExtra(MirrorActionReceiver.EXTRA_SHOWS_UI, action.showsUserInterface)
            putExtra(MirrorActionReceiver.EXTRA_DEVICE_NAME, originLabel)
        }
        // A remote-input action's PendingIntent must be MUTABLE so the OS can attach the typed text.
        val mutability =
            if (action.remoteInput) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context, tag.hashCode() * 31 + action.index, intent,
            mutability or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun deleteIntent(
        sourceClientId: ClientId,
        sourceKey: String,
        id: Int,
        clearable: Boolean,
    ): PendingIntent {
        val intent = Intent(context, DismissReceiver::class.java).apply {
            action = "net.extrawdw.apps.notisync.DISMISS"
            putExtra(DismissReceiver.EXTRA_SOURCE_CLIENT, sourceClientId.value)
            putExtra(DismissReceiver.EXTRA_SOURCE_KEY, sourceKey)
            // False for an ongoing/non-clearable source: a local swipe of the mirror must not sync back.
            putExtra(DismissReceiver.EXTRA_CLEARABLE, clearable)
        }
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun groupDeleteIntent(groupKey: String, id: Int): PendingIntent {
        val intent = Intent(context, DismissReceiver::class.java).apply {
            action = "net.extrawdw.apps.notisync.DISMISS_GROUP"
            putExtra(DismissReceiver.EXTRA_GROUP_KEY, groupKey)
        }
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun activeNotification(tag: String, id: Int): StatusBarNotification? =
        activeNotifications().firstOrNull { it.tag == tag && it.id == id }

    private fun activeNotifications(): List<StatusBarNotification> {
        val mgr = context.getSystemService(NotificationManager::class.java)
        return runCatching { mgr.activeNotifications.toList() }.getOrDefault(emptyList())
    }

    private fun toPriority(importance: MirrorImportance) = when (importance) {
        MirrorImportance.HIGH -> NotificationCompat.PRIORITY_HIGH
        MirrorImportance.DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
        MirrorImportance.LOW -> NotificationCompat.PRIORITY_LOW
        MirrorImportance.MIN, MirrorImportance.NONE -> NotificationCompat.PRIORITY_MIN
    }

    private fun toGroupAlertBehavior(behavior: GroupAlertBehavior) = when (behavior) {
        GroupAlertBehavior.ALL -> NotificationCompat.GROUP_ALERT_ALL
        GroupAlertBehavior.SUMMARY -> NotificationCompat.GROUP_ALERT_SUMMARY
        GroupAlertBehavior.CHILDREN -> NotificationCompat.GROUP_ALERT_CHILDREN
    }

    companion object {
        private const val ORPHAN_SOURCE_SUMMARY_CLEANUP_MS = 5_000L

        /** Auto-clear window for a ringing incoming-call mirror — a backstop against a stuck phantom call if the
         *  source's removal never syncs. Longer than a normal ring, so a real ring/answer/decline resolves (its
         *  removal syncs) well before this fires. */
        private const val CALL_RING_TIMEOUT_MS = 120_000L

        /** Per-app default mirror small icon, keyed by the (resolved) Android package. */
        private val PACKAGE_SMALL_ICONS: Map<String, Int> = mapOf(
            "com.google.android.apps.messaging" to R.drawable.ic_google_messages_notification,
            "com.whatsapp" to R.drawable.ic_whatsapp_notification,
            "com.tencent.mobileqq" to R.drawable.ic_qq_notification,
            "com.tencent.mm" to R.drawable.ic_wechat_notification,
            "com.google.android.dialer" to R.drawable.ic_phone_notification,
        )

        /**
         * Per-channel small-icon overrides for apps whose notification glyph depends on the source
         * channel. Outer key = package, inner key = source channel id; misses fall through to
         * [PACKAGE_SMALL_ICONS]. Channel ids verified against Phone by Google (com.google.android.dialer)
         * — its other channels (phone_incoming_call, phone_default, …) intentionally use the package default.
         */
        private val CHANNEL_SMALL_ICONS: Map<String, Map<String, Int>> = mapOf(
            "com.google.android.dialer" to mapOf(
                "phone_missed_call" to R.drawable.ic_phone_missed_notification,
                "phone_voicemail" to R.drawable.ic_voicemail_notification,
                "phone_ongoing_call" to R.drawable.ic_phone_in_talk_notification,
            ),
        )

        fun tagOf(sourceClientId: ClientId, sourceKey: String) =
            "${sourceClientId.value}|$sourceKey"

        fun summaryTagOf(groupKey: String) = "summary|$groupKey"
    }
}

/** Fires when the user swipes away a mirrored notification → propagate the dismissal to peers. */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? NotiSyncApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default + crashGuard("DismissReceiver")).launch {
            try {
                val graph = app.awaitGraphReady() ?: return@launch
                val engine = graph.mirrorEngine ?: return@launch
                val groupKey = intent.getStringExtra(EXTRA_GROUP_KEY)
                if (groupKey != null) {
                    dismissGroup(context, engine, groupKey)
                } else {
                    val sourceClient = intent.getStringExtra(EXTRA_SOURCE_CLIENT) ?: return@launch
                    val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY) ?: return@launch
                    // Swiping the call mirror away stops its ring at once.
                    graph.callRinger?.stop(RemoteNotificationPoster.tagOf(ClientId(sourceClient), sourceKey))
                    // A non-clearable (ongoing) source: local swipe removes the local copy only — no mesh
                    // DISMISSAL, no origin cancel (see MirrorEngine.dismissLocal). Defaults clearable so
                    // mirrors posted before this extra existed still sync.
                    val clearable = intent.getBooleanExtra(EXTRA_CLEARABLE, true)
                    engine.dismissLocal(ClientId(sourceClient), sourceKey, syncToMesh = clearable)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun dismissGroup(context: Context, engine: MirrorEngine, groupKey: String) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        val compat = NotificationManagerCompat.from(context)
        val ringer = (context.applicationContext as? NotiSyncApp)?.graphIfReady?.callRinger
        val children = runCatching {
            mgr.activeNotifications.filter { sbn ->
                sbn.packageName == context.packageName &&
                        sbn.notification.group == groupKey &&
                        (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }
        }.getOrDefault(emptyList())

        children.forEach { child ->
            val sourceClient =
                child.notification.extras.getString(MirrorNotificationExtras.SOURCE_CLIENT)
                    ?: return@forEach
            val sourceKey = child.notification.extras.getString(MirrorNotificationExtras.SOURCE_KEY)
                ?: return@forEach
            val clientId = ClientId(sourceClient)
            engine.dismissLocal(clientId, sourceKey)
            val tag = RemoteNotificationPoster.tagOf(clientId, sourceKey)
            ringer?.stop(tag)
            compat.cancel(tag, tag.hashCode())
        }
        val summaryTag = RemoteNotificationPoster.summaryTagOf(groupKey)
        compat.cancel(summaryTag, summaryTag.hashCode())
    }

    companion object {
        const val EXTRA_SOURCE_CLIENT = "source_client"
        const val EXTRA_SOURCE_KEY = "source_key"
        const val EXTRA_GROUP_KEY = "group_key"
        const val EXTRA_CLEARABLE = "clearable"
    }
}

/**
 * Fires when the user presses a mirrored action button → unicast a PERFORM
 * [net.extrawdw.notisync.protocol.ActionEvent] to the origin client. For a remote-input (reply)
 * action the typed text rides along, and the mirror is re-posted with the reply as remote-input
 * history — otherwise the shade's inline-reply spinner never resolves; the origin app's own update
 * then replaces it with the canonical state. A UI-opening action toasts "Check on <origin>", since
 * its visible effect happens on the origin device.
 */
class MirrorActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? NotiSyncApp ?: return
        val sourceClient = intent.getStringExtra(EXTRA_SOURCE_CLIENT) ?: return
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY) ?: return
        val actionTitle = intent.getStringExtra(EXTRA_ACTION_TITLE) ?: return
        val actionIndex = intent.getIntExtra(EXTRA_ACTION_INDEX, -1)
        if (actionIndex < 0) return
        val isRemoteInput = intent.getBooleanExtra(EXTRA_REMOTE_INPUT, false)
        val replyText =
            if (isRemoteInput) {
                RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REMOTE_REPLY)?.toString()?.takeIf { it.isNotBlank() }
                    ?: return // a reply action without text has nothing to perform
            } else null
        // Feedback first (onReceive runs on main): a UI-opening action behaves like a tap — the
        // result appears on the origin device, so say where to look.
        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
        if (intent.getBooleanExtra(EXTRA_SHOWS_UI, false) && !deviceName.isNullOrBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.mirror_check_on_device, deviceName),
                Toast.LENGTH_SHORT
            ).show()
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default + crashGuard("MirrorActionReceiver")).launch {
            try {
                val graph = app.awaitGraphReady() ?: return@launch
                // Pressing Answer / Decline / Hang-up on a call mirror stops the local ring immediately, without
                // waiting for the origin's resulting state change to round-trip back. No-op for non-call actions.
                graph.callRinger?.stop(RemoteNotificationPoster.tagOf(ClientId(sourceClient), sourceKey))
                val engine = graph.mirrorEngine ?: return@launch
                engine.performRemote(ClientId(sourceClient), sourceKey, actionIndex, actionTitle, replyText)
                if (replyText != null) confirmReply(context, ClientId(sourceClient), sourceKey, replyText)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Re-post the replied mirror with the typed text as remote-input history — the platform's own
     * "reply in flight" presentation — so the inline-reply progress spinner resolves immediately.
     * recoverBuilder keeps everything else (style, extras, intents, actions) intact; the origin
     * app's next update re-renders the mirror wholesale and drops the interim history line.
     */
    private fun confirmReply(context: Context, clientId: ClientId, sourceKey: String, replyText: String) {
        val tag = RemoteNotificationPoster.tagOf(clientId, sourceKey)
        val mgr = context.getSystemService(NotificationManager::class.java)
        val active = runCatching {
            mgr.activeNotifications.firstOrNull { it.tag == tag && it.id == tag.hashCode() }
        }.getOrNull() ?: return
        runCatching {
            val rebuilt = Notification.Builder.recoverBuilder(context, active.notification)
                .setRemoteInputHistory(arrayOf(replyText))
                .setOnlyAlertOnce(true)
                .build()
            NotificationManagerCompat.from(context).notify(tag, tag.hashCode(), rebuilt)
        }
    }

    companion object {
        const val ACTION_PERFORM = "net.extrawdw.apps.notisync.MIRROR_ACTION"
        const val KEY_REMOTE_REPLY = "notisync_remote_reply"
        const val EXTRA_SOURCE_CLIENT = "source_client"
        const val EXTRA_SOURCE_KEY = "source_key"
        const val EXTRA_ACTION_INDEX = "action_index"
        const val EXTRA_ACTION_TITLE = "action_title"
        const val EXTRA_REMOTE_INPUT = "remote_input"
        const val EXTRA_SHOWS_UI = "shows_ui"
        const val EXTRA_DEVICE_NAME = "device_name"
    }
}

/**
 * Tap-to-open-on-origin trampoline. A mirrored notification's content intent must be an activity —
 * that's what collapses the shade on tap — but a mirror has nothing to show locally: the tap's
 * effect is the ORIGIN device firing its content intent. So this no-UI activity (Theme.NoDisplay)
 * toasts "Check on <origin device>", unicasts the TAP event, and finishes inside onCreate.
 */
class MirrorTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val sourceClient = intent.getStringExtra(EXTRA_SOURCE_CLIENT)
            val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)
            if (sourceClient != null && sourceKey != null) {
                val label = intent.getStringExtra(EXTRA_DEVICE_NAME)
                if (!label.isNullOrBlank()) {
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.mirror_check_on_device, label),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                (applicationContext as? NotiSyncApp)?.runWhenGraphReady { graph ->
                    graph.scope.launch(crashGuard("MirrorTap")) {
                        runCatching { graph.mirrorEngine?.tapRemote(ClientId(sourceClient), sourceKey) }
                    }
                }
            }
        } finally {
            finish() // Theme.NoDisplay requires finishing before resume
        }
    }

    companion object {
        const val ACTION_TAP = "net.extrawdw.apps.notisync.MIRROR_TAP"
        const val EXTRA_SOURCE_CLIENT = "source_client"
        const val EXTRA_SOURCE_KEY = "source_key"
        const val EXTRA_DEVICE_NAME = "device_name"
    }
}
