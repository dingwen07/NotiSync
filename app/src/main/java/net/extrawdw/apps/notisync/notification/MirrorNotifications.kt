package net.extrawdw.apps.notisync.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotifStyle
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
) : MirrorRenderer {

    // iOS bundle ids with an App Store icon fetch in flight, so concurrent renders don't pile up duplicates.
    private val iconUpgradesInFlight = ConcurrentHashMap.newKeySet<String>()

    /** [silent] posts just this one notification without sound/heads-up — the connect-time ANCS backlog replay,
     *  or a re-render that only attaches a now-downloaded graphic to an already-posted notification — while
     *  leaving the channel's importance untouched, so the next live notification on the same (HIGH) channel
     *  still alerts. Never lower a channel's importance to mute one post (see MirrorChannels.ensure). The
     *  cross-update alerting cadence (e.g. a messaging app enriching a message it already alerted) is carried by
     *  the source's own [CapturedNotification.onlyAlertOnce], applied on the builder below. */
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

        val builder = NotificationCompat.Builder(context, postChannelId)
            .setSmallIcon(smallIconForPackage(notif.packageName))
            .setSubText(
                context.getString(
                    R.string.mirror_via,
                    notif.appLabel
                )
            ) // marks the notification as mirrored
            .setAutoCancel(true)
            // Mirror the source's own per-post intent: when the app marked this post "only alert once" (an
            // enrichment update — e.g. attaching an inline image to a message it already alerted), the OS
            // refreshes the already-showing mirror without re-alerting. A new message is a separate post the
            // app leaves alerting, so it still heads-up.
            .setOnlyAlertOnce(notif.onlyAlertOnce)
            .setWhen(notif.postTime)
            .setShowWhen(true)
            .setPriority(toPriority(notif.importance))
            .setGroup(receiverGroupKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setDeleteIntent(deleteIntent(notif.sourceClientId, notif.sourceKey, id))
            .addExtras(mirrorExtras(notif, receiverGroupKey, receiverGroupTitle))
        if (shortcutId != null) builder.setShortcutId(shortcutId)
        // Quiet this one post (ANCS backlog replay, or an asset-arrival re-render) without lowering the channel
        // — the channel stays HIGH so the next live notification still heads-up. MessagingStyle below may also
        // silence its own self-reply case.
        if (silent) builder.setSilent(true)

        when (notif.style) {
            NotifStyle.MESSAGING -> {
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

            NotifStyle.BIG_PICTURE -> {
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

            NotifStyle.BIG_TEXT -> {
                builder.setContentTitle(notif.title ?: notif.appLabel).setContentText(notif.text)
                builder.setStyle(
                    NotificationCompat.BigTextStyle().bigText(notif.bigText ?: notif.text)
                )
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
        runCatching { NotificationManagerCompat.from(context).notify(tag, id, builder.build()) }
        span.metric("notify_ms", (System.nanoTime() - notifyStartNanos) / 1_000_000)
        if (notif.style == NotifStyle.MESSAGING) {
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
    ) {
        val compat = NotificationManagerCompat.from(context)
        val summaryTag = summaryTagOf(groupKey)
        val children = activeGroupChildren(groupKey)
        if (children.size < 2) {
            compat.cancel(summaryTag, summaryTag.hashCode())
            return
        }
        val latest = children.maxByOrNull { it.postTime }
        val extras = latest?.notification?.extras
        val title = extras?.getString(MirrorNotificationExtras.GROUP_TITLE) ?: fallbackTitle
        val packageName = extras?.getString(MirrorNotificationExtras.PACKAGE) ?: fallbackPackage
        val iosBundleId =
            extras?.getString(MirrorNotificationExtras.IOS_BUNDLE_ID) ?: fallbackIosBundleId
        val appIconHash =
            extras?.getString(MirrorNotificationExtras.APP_ICON_HASH) ?: fallbackAppIconHash
        val channelId = latest?.notification?.channelId ?: fallbackChannelId
        val text = context.resources.getQuantityString(
            R.plurals.mirror_group_summary_count,
            children.size,
            children.size
        )
        val largeIcon = iconResolver.colorIcon(packageName, iosBundleId, appIconHash)
        val summary = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIconForPackage(packageName))
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(context.getString(R.string.mirror_via, title))
            .setWhen(latest?.postTime ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setDeleteIntent(groupDeleteIntent(groupKey, summaryTag.hashCode()))
            .apply { largeIcon?.let { setLargeIcon(it) } }
            .build()
        runCatching { compat.notify(summaryTag, summaryTag.hashCode(), summary) }
    }

    private fun activeGroupChildren(groupKey: String): List<StatusBarNotification> {
        val mgr = context.getSystemService(NotificationManager::class.java)
        return runCatching {
            mgr.activeNotifications.filter { sbn ->
                sbn.packageName == context.packageName &&
                        sbn.notification.group == groupKey &&
                        (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }
        }.getOrDefault(emptyList())
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

    private fun smallIconForPackage(packageName: String): Int = when (packageName) {
        "com.google.android.apps.messaging" -> R.drawable.ic_google_messages_notification
        "com.whatsapp" -> R.drawable.ic_whatsapp_notification
        "com.tencent.mm" -> R.drawable.ic_wechat_notification
        else -> R.drawable.ic_notisync_mirror
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

    private fun deleteIntent(sourceClientId: ClientId, sourceKey: String, id: Int): PendingIntent {
        val intent = Intent(context, DismissReceiver::class.java).apply {
            action = "net.extrawdw.apps.notisync.DISMISS"
            putExtra(DismissReceiver.EXTRA_SOURCE_CLIENT, sourceClientId.value)
            putExtra(DismissReceiver.EXTRA_SOURCE_KEY, sourceKey)
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

    private fun activeNotification(tag: String, id: Int): StatusBarNotification? {
        val mgr = context.getSystemService(NotificationManager::class.java)
        return runCatching { mgr.activeNotifications.firstOrNull { it.tag == tag && it.id == id } }.getOrNull()
    }

    private fun toPriority(importance: MirrorImportance) = when (importance) {
        MirrorImportance.HIGH -> NotificationCompat.PRIORITY_HIGH
        MirrorImportance.DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
        MirrorImportance.LOW -> NotificationCompat.PRIORITY_LOW
        MirrorImportance.MIN, MirrorImportance.NONE -> NotificationCompat.PRIORITY_MIN
    }

    companion object {
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
                    engine.dismissLocal(ClientId(sourceClient), sourceKey)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun dismissGroup(context: Context, engine: MirrorEngine, groupKey: String) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        val compat = NotificationManagerCompat.from(context)
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
            compat.cancel(tag, tag.hashCode())
        }
        val summaryTag = RemoteNotificationPoster.summaryTagOf(groupKey)
        compat.cancel(summaryTag, summaryTag.hashCode())
    }

    companion object {
        const val EXTRA_SOURCE_CLIENT = "source_client"
        const val EXTRA_SOURCE_KEY = "source_key"
        const val EXTRA_GROUP_KEY = "group_key"
    }
}
