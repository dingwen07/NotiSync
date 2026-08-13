package net.extrawdw.apps.notisync.seal

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import net.extrawdw.apps.notisync.R

/** Private, deliberately generic lock-screen presentation for a pending signing decision. */
class OpenPgpSignNotificationPresenter(private val context: Context) {
    fun post(requestId: String, requesterName: String): Boolean {
        ensureChannel()
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        val intent = OpenPgpSignReviewActivity.intent(context, requestId)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(requestId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notisync_mirror)
            .setContentTitle(context.getString(R.string.seal_notification_title))
            .setContentText(context.getString(R.string.seal_notification_body, requesterName))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(requestId), notification)
        return true
    }

    fun dismiss(requestId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(requestId))
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.seal_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.seal_notification_channel_description)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }
        )
    }

    private companion object {
        const val CHANNEL_ID = "openpgp_sign_requests"

        fun notificationId(requestId: String): Int = requestId.hashCode() and 0x7fffffff
    }
}
