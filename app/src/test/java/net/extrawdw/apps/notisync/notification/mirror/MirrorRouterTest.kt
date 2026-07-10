package net.extrawdw.apps.notisync.notification.mirror

import net.extrawdw.notisync.protocol.ClientId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [MirrorRouter.chooseTarget] — which source holds the single named routing session (chip identity). */
class MirrorRouterTest {

    /** Actives lists are ordered least recently updated FIRST (mirrors MirrorRouter's LinkedHashMap). */
    private fun src(id: String, playing: Boolean) = MirrorRouter.Source(ClientId(id), "Device $id", playing)

    private fun elect(current: MirrorRouter.Source?, vararg actives: MirrorRouter.Source): String? =
        MirrorRouter.chooseTarget(current, actives.toList())?.clientId?.value

    @Test
    fun nothingActive_electsNull() {
        assertNull(elect(null))
        assertNull(elect(src("a", true))) // stale current with no live card doesn't survive
    }

    @Test
    fun mostRecentlyUpdatedPlayingSourceWins() {
        assertEquals("b", elect(null, src("a", true), src("b", true)))
    }

    @Test
    fun playingBeatsMoreRecentlyUpdatedPaused() {
        assertEquals("a", elect(null, src("a", true), src("b", false)))
    }

    @Test
    fun currentHolderIsStickyWhileStillPlaying() {
        // b updated more recently, but a (current) still plays: no name flip-flap between two live players.
        assertEquals("a", elect(src("a", true), src("a", true), src("b", true)))
    }

    @Test
    fun stickinessReadsLiveStateNotTheElectionSnapshot() {
        // The holder paused since it was elected (snapshot still says playing): hand over to the player.
        assertEquals("b", elect(src("a", true), src("a", false), src("b", true)))
    }

    @Test
    fun pausedCurrentHolderBeatsOtherPausedSources() {
        assertEquals("a", elect(src("a", false), src("a", false), src("b", false)))
    }

    @Test
    fun nonePlayingAndNoCurrent_mostRecentlyUpdatedWins() {
        assertEquals("b", elect(null, src("a", false), src("b", false)))
    }

    @Test
    fun currentHolderGone_playingSourceTakesOver() {
        assertEquals("b", elect(src("a", true), src("b", true)))
    }
}
