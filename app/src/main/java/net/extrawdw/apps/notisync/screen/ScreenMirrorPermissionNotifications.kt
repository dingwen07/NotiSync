package net.extrawdw.apps.notisync.screen

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.MainActivity
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.TrustStatus

internal fun canAuthorizeScreenControl(
    peer: RosterDevice?,
    enabled: Boolean,
    quarantined: Boolean,
): Boolean = enabled && !quarantined && peer != null &&
    peer.ownDevice && peer.verified && peer.status == TrustStatus.TRUSTED

/** One permission reminder per peer; contains no session secrets and never resumes a rejected request. */
internal class ScreenMirrorPermissionNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun post(peerId: ClientId, displayName: String?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel()
        manager.notify(TAG_PREFIX + peerId.value, NOTIFICATION_ID, buildNotification(peerId, displayName))
    }

    internal fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.screen_mirror_permission_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                enableVibration(true)
            },
        )
    }

    internal fun buildNotification(peerId: ClientId, displayName: String?): Notification {
        val details = PendingIntent.getActivity(
            context,
            0,
            detailsIntent(context, peerId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val allow = PendingIntent.getBroadcast(
            context,
            0,
            ScreenMirrorPermissionReceiver.allowIntent(context, peerId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = context.getString(
            R.string.screen_mirror_permission_body,
            ScreenControllerLabel.create(displayName, peerId.shortForm()),
        )
        val publicNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_share)
            .setContentTitle(context.getString(R.string.screen_mirror_permission_title))
            .setContentText(context.getString(R.string.screen_mirror_permission_public_body))
            .setContentIntent(details)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_share)
            .setContentTitle(context.getString(R.string.screen_mirror_permission_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(details)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    context.getString(R.string.screen_mirror_permission_allow),
                    allow,
                ).setAuthenticationRequired(true).build(),
            )
            .build()
    }

    fun dismiss(peerId: ClientId) {
        manager.cancel(TAG_PREFIX + peerId.value, NOTIFICATION_ID)
        PendingIntent.getBroadcast(
            context,
            0,
            ScreenMirrorPermissionReceiver.allowIntent(context, peerId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.cancel()
    }

    fun dismissIneligible(isEligible: (ClientId) -> Boolean) {
        // Query system notifications so cleanup also works after an app process restart.
        manager.activeNotifications.forEach { notification ->
            val tag = notification.tag ?: return@forEach
            if (tag.startsWith(TAG_PREFIX)) {
                val peerId = ClientId(tag.removePrefix(TAG_PREFIX))
                if (!isEligible(peerId)) dismiss(peerId)
            }
        }
    }

    companion object {
        internal const val CHANNEL_ID = "notisync.screen.authorization"
        private const val TAG_PREFIX = "screen-authorization:"
        private const val NOTIFICATION_ID = 1

        internal fun detailsIntent(context: Context, peerId: ClientId): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DEVICE_DETAILS)
                .setData(Uri.Builder().scheme("notisync").authority("device-details")
                    .appendPath(peerId.value).build())
                .putExtra(MainActivity.EXTRA_DEVICE_DETAILS_CLIENT_ID, peerId.value)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/** Only our immutable notification PendingIntent can invoke this non-exported receiver. */
class ScreenMirrorPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALLOW) return
        val peerId = intent.getStringExtra(EXTRA_PEER_ID)?.takeIf(String::isNotBlank)?.let(::ClientId)
            ?: return
        val app = context.applicationContext as? NotiSyncApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = app.awaitGraphReady() ?: return@launch
                val notifications = ScreenMirrorPermissionNotifications(context)
                val eligible = canAuthorizeScreenControl(
                    graph.trust.roster.value.firstOrNull { it.clientId == peerId },
                    graph.settings.screenMirroringEnabledNow(),
                    graph.trust.quarantined.value,
                )
                if (!eligible) {
                    notifications.dismiss(peerId)
                    return@launch
                }
                graph.screenMirrorAuthorizations.setAuthorized(peerId, true)
                // A failed durable write keeps the reminder available for another attempt.
                if (graph.screenMirrorAuthorizations.isAuthorized(peerId)) notifications.dismiss(peerId)
            } catch (error: Exception) {
                Log.w("ScreenMirrorPermission", "Could not allow screen control", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_ALLOW = "net.extrawdw.apps.notisync.ALLOW_SCREEN_CONTROL"
        private const val EXTRA_PEER_ID = "screen_control_peer_id"

        internal fun allowIntent(context: Context, peerId: ClientId): Intent =
            Intent(context, ScreenMirrorPermissionReceiver::class.java)
                .setAction(ACTION_ALLOW)
                .setData(Uri.Builder().scheme("notisync").authority("allow-screen-control")
                    .appendPath(peerId.value).build())
                .putExtra(EXTRA_PEER_ID, peerId.value)
    }
}
