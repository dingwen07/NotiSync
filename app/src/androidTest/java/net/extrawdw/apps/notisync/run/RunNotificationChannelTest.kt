package net.extrawdw.apps.notisync.run

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.notisync.protocol.ClientId
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunNotificationChannelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val firstPeer = ClientId("run-channel-peer-one")
    private val secondPeer = ClientId("run-channel-peer-two")

    @Before
    @After
    fun cleanUp() {
        manager.deleteNotificationChannel(RunNotificationChannels.channelId(firstPeer))
        manager.deleteNotificationChannel(RunNotificationChannels.channelId(secondPeer))
        manager.deleteNotificationChannelGroup(RunNotificationChannels.GROUP_ID)
    }

    @Test
    fun createsOneHighDefaultAlertChannelPerPeerInsideExactRunGroup() {
        val firstId = RunNotificationChannels.ensure(context, firstPeer, "Build Mac")
        val repeatedId = RunNotificationChannels.ensure(context, firstPeer, "Renamed Build Mac")
        val secondId = RunNotificationChannels.ensure(context, secondPeer, "Linux Host")

        assertEquals(firstId, repeatedId)
        assertNotEquals(firstId, secondId)
        val group = manager.getNotificationChannelGroup(RunNotificationChannels.GROUP_ID)
        assertEquals("NotiSync Run", group.name.toString())

        val channel = manager.getNotificationChannel(firstId)
        assertEquals("Renamed Build Mac", channel.name.toString())
        assertEquals(RunNotificationChannels.GROUP_ID, channel.group)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertEquals(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            channel.sound,
        )
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_EVENT, channel.audioAttributes.usage)
        assertTrue(channel.shouldVibrate())
        assertArrayEquals(RunNotificationChannels.vibrationPattern(), channel.vibrationPattern)
    }
}
