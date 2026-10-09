package net.extrawdw.apps.notisync.notification.capture

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationContentTapTest {
    private val original = NotificationTapSnapshot(10L, "content-intent", Notification.FLAG_AUTO_CANCEL)

    @Test fun `auto cancel runs only after send completion and only once`() {
        var dismissals = 0
        val tap = NotificationContentTap(original, { original }) { dismissals++ }
        assertEquals("content-intent", tap.contentIntent)
        assertEquals(0, dismissals)
        tap.onSent()
        tap.onSent()
        assertEquals(1, dismissals)
    }

    @Test fun `updated replaced or already removed notifications are preserved`() {
        listOf(null, original.copy(postTime = 11L), original.copy(contentIntent = "replacement"),
            original.copy(flags = 0), original.copy(flags = original.flags or Notification.FLAG_FOREGROUND_SERVICE)
        ).forEach { latest ->
            var dismissals = 0
            NotificationContentTap(original, { latest }) { dismissals++ }.onSent()
            assertEquals(0, dismissals)
        }
    }

    @Test fun `notifications without auto cancel and foreground service notifications stay posted`() {
        listOf(0, Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_AUTO_CANCEL).forEach { flags ->
            var dismissals = 0
            val snapshot = original.copy(flags = flags)
            NotificationContentTap(snapshot, { snapshot }) { dismissals++ }.onSent()
            assertEquals(0, dismissals)
        }
    }
}
