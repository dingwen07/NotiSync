package net.extrawdw.apps.notisync.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.domain.MirrorRenderer
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotifStyle

/** A channel per source device keeps mirrored notifications grouped and dodges auto-bundling. */
object MirrorChannels {
    private fun channelId(sourceClientId: ClientId) = "mirror_${sourceClientId.value}"

    fun ensure(context: Context, sourceClientId: ClientId, deviceLabel: String): String {
        val id = channelId(sourceClientId)
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(id) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(id, "Mirrored from $deviceLabel", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Notifications mirrored from $deviceLabel via NotiSync"
                }
            )
        }
        return id
    }
}

/**
 * Posts mirrored notifications natively (reconstructing MessagingStyle / BigText as declared by the
 * producer) and clears them on remote dismissal. A delete intent reports local swipes back to the
 * [MirrorEngine] so the dismissal syncs to peers.
 */
class RemoteNotificationPoster(private val context: Context) : MirrorRenderer {

    override fun render(notif: CapturedNotification) {
        val channelId = MirrorChannels.ensure(context, notif.sourceClientId, notif.appLabel)
        val tag = tagOf(notif.sourceClientId, notif.sourceKey)
        val id = tag.hashCode()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notisync_mirror)
            .setContentTitle(notif.title ?: notif.appLabel)
            .setContentText(notif.text)
            .setSubText("via ${notif.appLabel}") // marks the notification as mirrored
            .setAutoCancel(true)
            .setWhen(notif.postTime)
            .setShowWhen(true)
            .setPriority(toPriority(notif.importance))
            .setDeleteIntent(deleteIntent(notif.sourceClientId, notif.sourceKey, id))

        when (notif.style) {
            NotifStyle.MESSAGING -> {
                val self = Person.Builder().setName("You").build()
                val style = NotificationCompat.MessagingStyle(self)
                    .setConversationTitle(notif.conversationTitle)
                    .setGroupConversation(notif.isGroupConversation)
                notif.messages.forEach { m ->
                    val person = m.sender?.let { Person.Builder().setName(it).build() }
                    style.addMessage(NotificationCompat.MessagingStyle.Message(m.text, m.timestamp, person))
                }
                builder.setStyle(style)
            }
            NotifStyle.BIG_TEXT -> builder.setStyle(NotificationCompat.BigTextStyle().bigText(notif.bigText ?: notif.text))
            else -> notif.bigText?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        }

        runCatching { NotificationManagerCompat.from(context).notify(tag, id, builder.build()) }
    }

    override fun clear(sourceClientId: ClientId, sourceKey: String) {
        val tag = tagOf(sourceClientId, sourceKey)
        NotificationManagerCompat.from(context).cancel(tag, tag.hashCode())
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
        MirrorImportance.MIN -> NotificationCompat.PRIORITY_MIN
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
