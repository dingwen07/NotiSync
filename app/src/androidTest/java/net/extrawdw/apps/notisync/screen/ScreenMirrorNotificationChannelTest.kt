package net.extrawdw.apps.notisync.screen

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenMirrorNotificationChannelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    @After
    fun cleanUp() {
        manager.deleteNotificationChannel(ScreenMirrorNotificationChannels.SOURCE_CHANNEL_ID)
        manager.deleteNotificationChannel(ScreenMirrorNotificationChannels.SOURCE_CONNECTING_CHANNEL_ID)
        manager.deleteNotificationChannel(ScreenMirrorNotificationChannels.REQUESTER_CHANNEL_ID)
    }

    @Test
    fun sourceConnectionAlertsWhileRequesterSessionRemainsOnASeparateQuietChannel() {
        val sourceId = ScreenMirrorNotificationChannels.ensureSource(context)
        val connectingId = ScreenMirrorNotificationChannels.ensureSourceConnecting(context)
        val requesterId = ScreenMirrorNotificationChannels.ensureRequester(context)

        assertNotEquals(sourceId, connectingId)
        assertNotEquals(sourceId, requesterId)
        assertNotEquals(connectingId, requesterId)
        assertNotEquals("notisync.screen.session", sourceId)

        val source = manager.getNotificationChannel(sourceId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, source.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, source.lockscreenVisibility)
        assertEquals(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            source.sound,
        )
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_EVENT, source.audioAttributes.usage)
        assertTrue(source.shouldVibrate())
        assertArrayEquals(
            ScreenMirrorNotificationChannels.connectionVibrationPattern(),
            source.vibrationPattern,
        )

        val connecting = manager.getNotificationChannel(connectingId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, connecting.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, connecting.lockscreenVisibility)
        assertNull(connecting.sound)
        assertFalse(connecting.shouldVibrate())

        val requester = manager.getNotificationChannel(requesterId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, requester.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, requester.lockscreenVisibility)
        assertNull(requester.sound)
        assertFalse(requester.shouldVibrate())
    }
}
