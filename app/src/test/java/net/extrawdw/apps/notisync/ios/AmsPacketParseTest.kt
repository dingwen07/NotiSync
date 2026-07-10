package net.extrawdw.apps.notisync.ios

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmsPacketParseTest {

    @Test
    fun parseEntityUpdate_decodesHeaderAndUtf8Value() {
        val packet = byteArrayOf(
            Ams.ENTITY_TRACK.toByte(), Ams.TRACK_ATTR_TITLE.toByte(), 0,
        ) + "Blank Space".toByteArray(Charsets.UTF_8)
        val u = Ams.parseEntityUpdate(packet)!!
        assertEquals(Ams.ENTITY_TRACK, u.entityId)
        assertEquals(Ams.TRACK_ATTR_TITLE, u.attributeId)
        assertEquals("Blank Space", u.value)
        assertFalse(u.truncated)
    }

    @Test
    fun parseEntityUpdate_flagsTruncation() {
        val packet = byteArrayOf(
            Ams.ENTITY_TRACK.toByte(), Ams.TRACK_ATTR_ARTIST.toByte(), Ams.FLAG_TRUNCATED.toByte(),
        ) + "A very long artist na".toByteArray(Charsets.UTF_8)
        assertTrue(Ams.parseEntityUpdate(packet)!!.truncated)
    }

    @Test
    fun parseEntityUpdate_emptyValueIsValid() {
        // An attribute that emptied (the player closed) arrives as a bare header — value must be "".
        val u = Ams.parseEntityUpdate(
            byteArrayOf(Ams.ENTITY_PLAYER.toByte(), Ams.PLAYER_ATTR_NAME.toByte(), 0)
        )!!
        assertEquals("", u.value)
    }

    @Test
    fun parseEntityUpdate_rejectsShortPacket() {
        assertNull(Ams.parseEntityUpdate(byteArrayOf(0, 1)))
    }

    @Test
    fun parsePlaybackInfo_decodesStateRateElapsed() {
        val info = Ams.parsePlaybackInfo("1,1.0,45.5")!!
        assertEquals(Ams.PLAYBACK_PLAYING, info.state)
        assertEquals(1.0f, info.rate, 0.0001f)
        assertEquals(45.5, info.elapsedSec, 0.0001)
        assertTrue(info.isAdvancing)
    }

    @Test
    fun parsePlaybackInfo_pausedIsNotAdvancing() {
        assertFalse(Ams.parsePlaybackInfo("0,0.0,12.0")!!.isAdvancing)
        // Rewinding / fast-forwarding still count as advancing (the card shows the pause affordance).
        assertTrue(Ams.parsePlaybackInfo("2,-2.0,12.0")!!.isAdvancing)
        assertTrue(Ams.parsePlaybackInfo("3,2.0,12.0")!!.isAdvancing)
    }

    @Test
    fun parsePlaybackInfo_rejectsEmptyAndMalformed() {
        assertNull(Ams.parsePlaybackInfo(""))         // no active player
        assertNull(Ams.parsePlaybackInfo("1,1.0"))     // missing elapsed
        assertNull(Ams.parsePlaybackInfo("x,1.0,2.0")) // non-numeric state
    }

    @Test
    fun parseSupportedCommands_oneCommandPerByte() {
        val cmds = Ams.parseSupportedCommands(
            byteArrayOf(
                Ams.CMD_PLAY.toByte(),
                Ams.CMD_PAUSE.toByte(),
                Ams.CMD_NEXT_TRACK.toByte(),
                Ams.CMD_LIKE_TRACK.toByte(),
            )
        )
        assertEquals(
            listOf(Ams.CMD_PLAY, Ams.CMD_PAUSE, Ams.CMD_NEXT_TRACK, Ams.CMD_LIKE_TRACK),
            cmds,
        )
        assertTrue(Ams.parseSupportedCommands(byteArrayOf()).isEmpty())
    }

    @Test
    fun buildEntityUpdateRegistration_entityThenAttributes() {
        assertArrayEquals(
            byteArrayOf(
                Ams.ENTITY_TRACK.toByte(),
                Ams.TRACK_ATTR_ARTIST.toByte(),
                Ams.TRACK_ATTR_ALBUM.toByte(),
                Ams.TRACK_ATTR_TITLE.toByte(),
                Ams.TRACK_ATTR_DURATION.toByte(),
            ),
            Ams.buildEntityUpdateRegistration(Ams.ENTITY_TRACK, Ams.TRACK_ATTRS),
        )
    }

    @Test
    fun buildEntityAttributeRequest_isEntityAttributePair() {
        assertArrayEquals(
            byteArrayOf(Ams.ENTITY_TRACK.toByte(), Ams.TRACK_ATTR_TITLE.toByte()),
            Ams.buildEntityAttributeRequest(Ams.ENTITY_TRACK, Ams.TRACK_ATTR_TITLE),
        )
    }

    @Test
    fun buildRemoteCommand_isSingleCommandByte() {
        assertArrayEquals(
            byteArrayOf(Ams.CMD_TOGGLE_PLAY_PAUSE.toByte()),
            Ams.buildRemoteCommand(Ams.CMD_TOGGLE_PLAY_PAUSE),
        )
    }
}
