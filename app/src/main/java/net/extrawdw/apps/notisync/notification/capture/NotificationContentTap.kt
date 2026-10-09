package net.extrawdw.apps.notisync.notification.capture

import android.app.Notification
import java.util.concurrent.atomic.AtomicBoolean

internal data class NotificationTapSnapshot<T>(val postTime: Long, val contentIntent: T, val flags: Int)

/** Completes a content tap once, after sending succeeds, without dismissing a newer notification. */
internal class NotificationContentTap<T>(
    private val snapshot: NotificationTapSnapshot<T>,
    private val current: () -> NotificationTapSnapshot<T>?,
    private val dismiss: () -> Unit,
) {
    val contentIntent: T get() = snapshot.contentIntent
    private val completed = AtomicBoolean()

    fun onSent() {
        if (!completed.compareAndSet(false, true) || !autoCancels(snapshot.flags)) return
        val latest = current() ?: return
        if (latest.postTime != snapshot.postTime || latest.contentIntent != snapshot.contentIntent ||
            !autoCancels(latest.flags)
        ) return
        dismiss()
    }

    private fun autoCancels(flags: Int): Boolean =
        flags and Notification.FLAG_AUTO_CANCEL != 0 && flags and Notification.FLAG_FOREGROUND_SERVICE == 0
}
