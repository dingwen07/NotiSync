package net.extrawdw.apps.notisync.screen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMirrorNotificationChannelPolicyTest {
    @Test
    fun sourceUsesMigratedChannelAndRequesterCanNeverShareIt() {
        assertNotEquals(
            ScreenMirrorNotificationChannels.SOURCE_CHANNEL_ID,
            ScreenMirrorNotificationChannels.SOURCE_CONNECTING_CHANNEL_ID,
        )
        assertNotEquals(
            ScreenMirrorNotificationChannels.SOURCE_CHANNEL_ID,
            ScreenMirrorNotificationChannels.REQUESTER_CHANNEL_ID,
        )
        assertNotEquals(
            ScreenMirrorNotificationChannels.SOURCE_CONNECTING_CHANNEL_ID,
            ScreenMirrorNotificationChannels.REQUESTER_CHANNEL_ID,
        )
        assertNotEquals(
            "notisync.screen.session",
            ScreenMirrorNotificationChannels.SOURCE_CHANNEL_ID,
        )
        assertTrue(ScreenMirrorNotificationChannels.SOURCE_CHANNEL_ID.endsWith(".v2"))
        assertTrue(ScreenMirrorNotificationChannels.SOURCE_CONNECTING_CHANNEL_ID.endsWith(".v1"))
    }

    @Test
    fun sourceConnectionHasAnExplicitFiniteVibrationPattern() {
        assertArrayEquals(
            longArrayOf(0, 250, 120, 250),
            ScreenMirrorNotificationChannels.connectionVibrationPattern(),
        )
    }
}
