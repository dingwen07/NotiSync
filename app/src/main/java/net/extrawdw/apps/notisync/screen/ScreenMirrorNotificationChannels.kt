package net.extrawdw.apps.notisync.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import net.extrawdw.apps.notisync.R

/**
 * Foreground-service channels for the different phases and sides of a screen session.
 *
 * The source channel intentionally uses a new permanent id: Android does not allow an app to raise
 * the importance or alerting behavior of an existing channel, and previous builds created the old
 * `notisync.screen.session` channel at LOW importance. Preparing and requester notifications are
 * deliberately separate and quiet; neither must inherit the source connection alert.
 */
internal object ScreenMirrorNotificationChannels {
    const val SOURCE_CHANNEL_ID = "notisync.screen.source.active.v2"
    const val SOURCE_CONNECTING_CHANNEL_ID = "notisync.screen.source.connecting.v1"
    const val REQUESTER_CHANNEL_ID = "notisync.screen.requester.active.v1"

    fun connectionVibrationPattern(): LongArray = longArrayOf(0, 250, 120, 250)

    fun ensureSource(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SOURCE_CHANNEL_ID,
                context.getString(R.string.screen_mirror_source_service_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.screen_mirror_source_service_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
                enableVibration(true)
                vibrationPattern = connectionVibrationPattern()
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .build(),
                )
            },
        )
        return SOURCE_CHANNEL_ID
    }

    /** Satisfies the immediate FGS requirement without announcing a connection before TLS is ready. */
    fun ensureSourceConnecting(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SOURCE_CONNECTING_CHANNEL_ID,
                context.getString(R.string.screen_mirror_source_connecting_service_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(
                    R.string.screen_mirror_source_connecting_service_channel_description,
                )
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        return SOURCE_CONNECTING_CHANNEL_ID
    }

    /** Reserve a distinct, non-alerting channel for the Android viewer's connectedDevice FGS. */
    fun ensureRequester(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                REQUESTER_CHANNEL_ID,
                context.getString(R.string.screen_mirror_requester_service_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.screen_mirror_requester_service_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        return REQUESTER_CHANNEL_ID
    }
}
