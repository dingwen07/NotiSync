package net.extrawdw.apps.notisync.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.MainActivity
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.assets.AssetCache
import net.extrawdw.apps.notisync.domain.MirrorRenderer
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotifStyle

/**
 * Mirrors the SOURCE app's channel structure on the receiver: one NotificationChannelGroup per source
 * app, one NotificationChannel per source channel (importance/mute mirrored at creation), nested in
 * that group. If the source app uses channel groups, the group's display name is shown in the channel
 * name (e.g. "alice@gmail.com · Mail") — when available (requires a CompanionDeviceManager
 * association; v1 falls back to the channel name / category / app label).
 *
 * IDs are opaque ASCII keyed by (sourceClientId, package, sourceChannelId); the second ':'-segment is
 * always the source client id so [gc] can prune channels for unpaired devices.
 */
object MirrorChannels {
    private fun groupId(client: ClientId, pkg: String) = "notigrp:${client.value}:$pkg"
    private fun channelId(client: ClientId, pkg: String, src: String?) = "notich:${client.value}:$pkg:${src ?: "_default"}"
    private fun convChannelId(client: ClientId, pkg: String, conv: String) = "notichc:${client.value}:$pkg:$conv"

    /** Create the per-app group + per-source-channel channel; return the channel id to post on. */
    fun ensure(context: Context, notif: CapturedNotification): String {
        val mgr = context.getSystemService(NotificationManager::class.java)
        val gid = groupId(notif.sourceClientId, notif.packageName)
        // Group must exist before a channel references it; createNotificationChannel also updates
        // name/description and lowers importance on an existing channel (never raises — OS contract).
        mgr.createNotificationChannelGroup(NotificationChannelGroup(gid, notif.appLabel))
        val cid = channelId(notif.sourceClientId, notif.packageName, notif.channelId)
        mgr.createNotificationChannel(
            NotificationChannel(cid, channelName(context, notif), importanceOf(notif)).apply { group = gid }
        )
        return cid
    }

    /** Create a conversation child channel under [parentChannelId]; return its id. */
    fun ensureConversation(context: Context, notif: CapturedNotification, parentChannelId: String, shortcutId: String): String {
        val mgr = context.getSystemService(NotificationManager::class.java)
        val conv = notif.shortcutId ?: notif.sourceKey
        val cid = convChannelId(notif.sourceClientId, notif.packageName, conv)
        mgr.createNotificationChannel(
            NotificationChannel(cid, channelName(context, notif), importanceOf(notif)).apply {
                group = groupId(notif.sourceClientId, notif.packageName)
                setConversationId(parentChannelId, shortcutId)
            }
        )
        return cid
    }

    /** Prune mirrored groups/channels whose source client is no longer a trusted peer. */
    fun gc(context: Context, validClientIds: Set<String>) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        runCatching {
            mgr.notificationChannels.forEach { c ->
                if ((c.id.startsWith("notich:") || c.id.startsWith("notichc:")) && clientOf(c.id) !in validClientIds) {
                    mgr.deleteNotificationChannel(c.id)
                }
            }
            mgr.notificationChannelGroups.forEach { g ->
                if (g.id.startsWith("notigrp:") && clientOf(g.id) !in validClientIds) {
                    mgr.deleteNotificationChannelGroup(g.id)
                }
            }
        }
    }

    private fun clientOf(id: String): String = id.substringAfter(':').substringBefore(':')

    private fun channelName(context: Context, notif: CapturedNotification): String {
        val base = notif.channelName?.takeIf { it.isNotBlank() } ?: categoryLabel(context, notif.category) ?: notif.appLabel
        return notif.channelGroupName?.takeIf { it.isNotBlank() }?.let { "$it · $base" } ?: base
    }

    private fun importanceOf(notif: CapturedNotification): Int =
        mapImportance(notif.channelImportance ?: notif.importance)

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

/**
 * Posts mirrored notifications natively, reconstructing MessagingStyle / BigText and — for
 * conversation notifications — a long-lived shortcut + conversation channel so they file under the
 * Conversations section. A delete intent reports local swipes back for dismissal sync.
 */
class RemoteNotificationPoster(
    private val context: Context,
    private val assets: AssetCache,
) : MirrorRenderer {

    override fun render(notif: CapturedNotification) {
        // No POST_NOTIFICATIONS → notify() is a silent no-op (and throws SecurityException on some
        // OEMs); skip the channel/shortcut/asset work entirely rather than build a notification we
        // can't post. Mirrors the guard in AppGraph.onTrustPrompt.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val parentChannelId = MirrorChannels.ensure(context, notif)
        var postChannelId = parentChannelId
        var shortcutId: String? = null

        if (notif.isConversation) {
            val conv = notif.shortcutId ?: notif.sourceKey
            val mirroredShortcut = "noticonv:${notif.sourceClientId.value}:${notif.packageName}:$conv"
            val published = runCatching { publishConversationShortcut(notif, mirroredShortcut) }.getOrDefault(false)
            if (published) {
                shortcutId = mirroredShortcut
                postChannelId = runCatching {
                    MirrorChannels.ensureConversation(context, notif, parentChannelId, mirroredShortcut)
                }.getOrDefault(parentChannelId)
            }
        }

        val tag = tagOf(notif.sourceClientId, notif.sourceKey)
        val id = tag.hashCode()

        val builder = NotificationCompat.Builder(context, postChannelId)
            .setSmallIcon(smallIconForPackage(notif.packageName))
            .setSubText(context.getString(R.string.mirror_via, notif.appLabel)) // marks the notification as mirrored
            .setAutoCancel(true)
            .setWhen(notif.postTime)
            .setShowWhen(true)
            .setPriority(toPriority(notif.importance))
            .setDeleteIntent(deleteIntent(notif.sourceClientId, notif.sourceKey, id))
        if (shortcutId != null) builder.setShortcutId(shortcutId)

        when (notif.style) {
            NotifStyle.MESSAGING -> {
                // MessagingStyle owns the title + per-message rendering. Do NOT also setContentTitle —
                // that would draw a second, redundant bold title line. Set conversationTitle ONLY for
                // group chats (per Google guidance); for 1:1 the title derives from the sender.
                val self = Person.Builder().setName(context.getString(R.string.mirror_self_name)).build()
                val style = NotificationCompat.MessagingStyle(self)
                    .setGroupConversation(notif.isGroupConversation)
                if (notif.isGroupConversation) style.setConversationTitle(notif.conversationTitle)
                notif.messages.forEach { m ->
                    val person = m.sender?.let { name ->
                        Person.Builder().setName(name)
                            .apply { m.avatar?.let { cachedBitmap(it.assetHash) }?.let { setIcon(IconCompat.createWithBitmap(it)) } }
                            .build()
                    }
                    style.addMessage(NotificationCompat.MessagingStyle.Message(m.text, m.timestamp, person))
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
                if (picture != null) builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture))
                else notif.bigText?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
            }
            NotifStyle.BIG_TEXT -> {
                builder.setContentTitle(notif.title ?: notif.appLabel).setContentText(notif.text)
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(notif.bigText ?: notif.text))
            }
            else -> {
                builder.setContentTitle(notif.title ?: notif.appLabel).setContentText(notif.text)
                notif.bigText?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
            }
        }

        applyLargeIcon(builder, notif)

        runCatching { NotificationManagerCompat.from(context).notify(tag, id, builder.build()) }
    }

    /**
     * Large icon: the mirrored original (a private asset) once it's in the local cache; until then,
     * nothing (a later re-post fills it in). When the original had no large icon, fall back to the
     * source app's icon if that app is installed on this device.
     */
    private fun applyLargeIcon(builder: NotificationCompat.Builder, notif: CapturedNotification) {
        val ref = notif.largeIcon
        val bitmap = if (ref != null) cachedBitmap(ref.assetHash) else appIconBitmap(notif.packageName)
        bitmap?.let { builder.setLargeIcon(it) }
    }

    private fun smallIconForPackage(packageName: String): Int = when (packageName) {
        "com.google.android.apps.messaging" -> R.drawable.ic_google_messages_notification
        "com.whatsapp" -> R.drawable.ic_whatsapp_notification
        "com.tencent.mm" -> R.drawable.ic_wechat_notification
        else -> R.drawable.ic_notisync_mirror
    }

    private fun cachedBitmap(assetHash: String): Bitmap? =
        assets.read(assetHash)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    private fun appIconBitmap(pkg: String): Bitmap? =
        runCatching { drawableToBitmap(context.packageManager.getApplicationIcon(pkg)) }.getOrNull()

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) drawable.bitmap?.let { return it }
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
        val bitmap = createBitmap(w, h)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    override fun clear(sourceClientId: ClientId, sourceKey: String) {
        val tag = tagOf(sourceClientId, sourceKey)
        NotificationManagerCompat.from(context).cancel(tag, tag.hashCode())
    }

    /** Publish a long-lived Person shortcut so the mirrored conversation renders as a conversation. */
    private fun publishConversationShortcut(notif: CapturedNotification, shortcutId: String): Boolean {
        val label = notif.conversationTitle?.takeIf { it.isNotBlank() }
            ?: notif.messages.firstOrNull { it.sender != null }?.sender
            ?: notif.title
            ?: notif.appLabel
        val senders = notif.messages.mapNotNull { it.sender }.distinct()
        val persons = senders.ifEmpty { listOf(label) }
            .map { Person.Builder().setName(it).setKey(it).build() }
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
        val avatar = notif.messages.firstNotNullOfOrNull { m -> m.avatar?.let { cachedBitmap(it.assetHash) } }
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setLongLived(true)
            .setShortLabel(label)
            .setPersons(persons.toTypedArray())
            .setLocusId(LocusIdCompat(shortcutId))
            .setIntent(intent)
            .apply { avatar?.let { setIcon(IconCompat.createWithBitmap(it)) } }
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

    private fun toPriority(importance: MirrorImportance) = when (importance) {
        MirrorImportance.HIGH -> NotificationCompat.PRIORITY_HIGH
        MirrorImportance.DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
        MirrorImportance.LOW -> NotificationCompat.PRIORITY_LOW
        MirrorImportance.MIN, MirrorImportance.NONE -> NotificationCompat.PRIORITY_MIN
    }

    companion object {
        fun tagOf(sourceClientId: ClientId, sourceKey: String) = "${sourceClientId.value}|$sourceKey"
    }
}

/** Fires when the user swipes away a mirrored notification → propagate the dismissal to peers. */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sourceClient = intent.getStringExtra(EXTRA_SOURCE_CLIENT) ?: return
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY) ?: return
        val app = context.applicationContext as NotiSyncApp
        val engine = app.graph.mirrorEngine ?: return
        val pending = goAsync()
        app.graph.scope.launch {
            try {
                engine.dismissLocal(ClientId(sourceClient), sourceKey)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_SOURCE_CLIENT = "source_client"
        const val EXTRA_SOURCE_KEY = "source_key"
    }
}
