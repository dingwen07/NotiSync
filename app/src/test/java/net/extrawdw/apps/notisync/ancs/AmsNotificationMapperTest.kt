package net.extrawdw.apps.notisync.ancs

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotificationStyle
import net.extrawdw.notisync.protocol.OriginPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmsNotificationMapperTest {

    private val client = ClientId("client-1")
    private val iphoneId = "ab12cd34ef56"

    private fun playing(
        supported: List<Int> = listOf(
            Ams.CMD_PLAY, Ams.CMD_PAUSE, Ams.CMD_TOGGLE_PLAY_PAUSE,
            Ams.CMD_NEXT_TRACK, Ams.CMD_PREVIOUS_TRACK,
        ),
    ) = AmsNowPlaying(
        playerName = "Music",
        playbackState = Ams.PLAYBACK_PLAYING,
        playbackRate = 1f,
        elapsedSec = 30.0,
        elapsedAtMs = 10_000L,
        artist = "Taylor Swift",
        album = "1989",
        title = "Blank Space",
        durationSec = 231.0,
        supportedCommands = supported,
    )

    @Test
    fun map_buildsAMediaCardThatNeverAlerts() {
        val notif = AmsNotificationMapper.map(client, playing(), iphoneId, "Dingwen's iPhone", now = 10_000L)

        assertEquals(NotificationStyle.MEDIA, notif.style)
        assertEquals(MirrorCategory.TRANSPORT, notif.category)
        // A now-playing card must never alert: LOW importance on a silent channel, update-in-place.
        assertEquals(MirrorImportance.LOW, notif.importance)
        assertEquals(true, notif.channelSilent)
        assertTrue(notif.onlyAlertOnce)
        assertEquals(OriginPlatform.IOS_ANCS, notif.originPlatform)
        assertEquals(iphoneId, notif.originDeviceId)
        assertEquals("Dingwen's iPhone", notif.originDeviceName)
        assertEquals(AmsNotificationMapper.MEDIA_PACKAGE, notif.packageName)
        assertEquals("ams|$iphoneId|nowplaying", notif.sourceKey)
        // Consumer mapping: title → media TITLE, text → ARTIST, subText → ALBUM.
        assertEquals("Blank Space", notif.title)
        assertEquals("Taylor Swift", notif.text)
        assertEquals("1989", notif.subText)
        assertEquals("Music", notif.appLabel)
        // No open-on-iPhone, no mirrored button row (the transport row is the media session's).
        assertFalse(notif.hasContentIntent)
        assertTrue(notif.actions.isEmpty())
    }

    @Test
    fun map_extrapolatesPositionWhileAdvancing() {
        // Elapsed 30s captured at t=10s; mapped at t=20s while playing at 1x → 40s into the track.
        val notif = AmsNotificationMapper.map(client, playing(), iphoneId, null, now = 20_000L)
        assertEquals(40_000L, notif.mediaPositionMs)
        assertEquals(231_000L, notif.mediaDurationMs)
        assertEquals(true, notif.mediaIsPlaying)
    }

    @Test
    fun map_holdsPositionWhilePaused() {
        val paused = playing().copy(playbackState = Ams.PLAYBACK_PAUSED)
        val notif = AmsNotificationMapper.map(client, paused, iphoneId, null, now = 20_000L)
        assertEquals(30_000L, notif.mediaPositionMs) // no wall-clock projection while paused
        assertEquals(false, notif.mediaIsPlaying)
    }

    @Test
    fun map_projectsAtPlaybackRate() {
        // 2x rate: 30s elapsed + 10s wall * 2 = 50s.
        val fast = playing().copy(playbackRate = 2f)
        assertEquals(
            50_000L,
            AmsNotificationMapper.map(client, fast, iphoneId, null, now = 20_000L).mediaPositionMs,
        )
    }

    @Test
    fun map_supportedCommandsBecomeTransportMask() {
        val notif = AmsNotificationMapper.map(client, playing(), iphoneId, null, now = 10_000L)
        // PLAY(0x4) | PAUSE(0x2) | PLAY_PAUSE(0x200) | SKIP_TO_NEXT(0x20) | SKIP_TO_PREVIOUS(0x10);
        // never ACTION_SEEK_TO — AMS has no absolute seek.
        assertEquals(0x236L, notif.mediaActions)
    }

    @Test
    fun map_noSupportedListYet_leavesActionsNullForConsumerDefaults() {
        val notif =
            AmsNotificationMapper.map(client, playing(supported = emptyList()), iphoneId, null, 10_000L)
        assertNull(notif.mediaActions)
        assertTrue(notif.mediaCustomActions.isEmpty())
    }

    @Test
    fun map_extraCommandsBecomeCustomActionsWithRelayableIds() {
        val notif = AmsNotificationMapper.map(
            client,
            playing(
                supported = listOf(
                    Ams.CMD_PLAY, Ams.CMD_ADVANCE_SHUFFLE_MODE, Ams.CMD_LIKE_TRACK,
                    Ams.CMD_VOLUME_UP, // never surfaced — a card has no volume affordance
                ),
            ),
            iphoneId, null, 10_000L,
        )
        assertEquals(listOf("Shuffle", "Like"), notif.mediaCustomActions.map { it.name })
        val shuffleId = notif.mediaCustomActions.first().action
        // The relayed CUSTOM press round-trips back to the RemoteCommandID.
        assertEquals(Ams.CMD_ADVANCE_SHUFFLE_MODE, AmsNotificationMapper.commandOfCustomAction(shuffleId))
    }

    @Test
    fun commandOfCustomAction_rejectsForeignIds() {
        assertNull(AmsNotificationMapper.commandOfCustomAction("com.spotify.custom.like"))
        assertNull(AmsNotificationMapper.commandOfCustomAction("ams:notanumber"))
    }

    @Test
    fun hasTrack_needsATitleOrArtist() {
        assertTrue(playing().hasTrack)
        assertTrue(playing().copy(title = null).hasTrack) // artist alone still renders
        assertFalse(AmsNowPlaying(playerName = "Music").hasTrack) // a bare player is no card
        assertFalse(playing().copy(title = " ", artist = "").hasTrack)
    }
}
