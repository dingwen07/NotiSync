package net.extrawdw.apps.notisync.notification.capture

import net.extrawdw.apps.notisync.data.PerAppConfig
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.NotificationStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCapturePolicyTest {
    @Test
    fun mediaPlaybackUsesItsOwnIosSwitch_evenWhenNotOngoing() {
        val media = notif(style = NotificationStyle.MEDIA, isOngoing = false)

        assertTrue(shouldExcludeIosForCapture(media, PerAppConfig()))
        assertFalse(
            shouldExcludeIosForCapture(
                media,
                PerAppConfig(mirrorMediaPlaybackToIos = true),
            )
        )
    }

    @Test
    fun mediaPlaybackSwitchOwnsMedia_evenWhenSourceIsOngoing() {
        val media = notif(style = NotificationStyle.MEDIA, isOngoing = true)

        assertTrue(
            shouldExcludeIosForCapture(
                media,
                PerAppConfig(mirrorOngoingToIos = true, mirrorMediaPlaybackToIos = false),
            )
        )
        assertFalse(
            shouldExcludeIosForCapture(
                media,
                PerAppConfig(mirrorOngoingToIos = false, mirrorMediaPlaybackToIos = true),
            )
        )
    }

    @Test
    fun nonMediaOngoingStillUsesOngoingIosSwitch() {
        val ongoing = notif(isOngoing = true)

        assertTrue(shouldExcludeIosForCapture(ongoing, PerAppConfig()))
        assertFalse(shouldExcludeIosForCapture(ongoing, PerAppConfig(mirrorOngoingToIos = true)))
    }

    @Test
    fun nonMediaNonOngoingCanReachIos() {
        assertFalse(shouldExcludeIosForCapture(notif(), PerAppConfig()))
    }

    @Test
    fun nonOngoingMediaStillUsesMediaUpdatePathAfterFirstPost() {
        assertFalse(shouldUseUpdatePath(sourceIsOngoing = false, isMediaPlayback = true, firstSeen = true))
        assertTrue(shouldUseUpdatePath(sourceIsOngoing = false, isMediaPlayback = true, firstSeen = false))
        assertTrue(shouldUseUpdatePath(sourceIsOngoing = true, isMediaPlayback = false, firstSeen = false))
        assertFalse(shouldUseUpdatePath(sourceIsOngoing = false, isMediaPlayback = false, firstSeen = false))
    }

    @Test
    fun updatePostTimeIsMonotonicWhenSourcePostTimeIsReused() {
        assertEquals(1_000L, nextUpdatePostTime(sourcePostTime = 100L, now = 1_000L, previous = null))
        assertEquals(1_001L, nextUpdatePostTime(sourcePostTime = 100L, now = 900L, previous = 1_000L))
        assertEquals(2_000L, nextUpdatePostTime(sourcePostTime = 2_000L, now = 900L, previous = 1_000L))
    }

    @Test
    fun localVolumeBroadcastRecapturesOnlyLocalPlayback() {
        assertTrue(shouldRecaptureForLocalVolume(null))
        assertTrue(shouldRecaptureForLocalVolume(1)) // MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
        assertFalse(shouldRecaptureForLocalVolume(2)) // MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
    }

    private fun notif(
        style: NotificationStyle = NotificationStyle.DEFAULT,
        isOngoing: Boolean = false,
    ) = CapturedNotification(
        sourceClientId = ClientId("android"),
        sourceKey = "0|com.player|1|tag",
        packageName = "com.player",
        appLabel = "Player",
        title = "Track",
        text = "Artist",
        style = style,
        postTime = 1L,
        isOngoing = isOngoing,
    )
}
