package net.extrawdw.apps.notisync.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMirrorRequesterForegroundOwnershipTest {
    @Test
    fun duplicateStartAdvancesTheFinalStopSelfId() {
        val ownership = ScreenMirrorRequesterForegroundOwnership()

        assertEquals(
            ScreenMirrorRequesterForegroundOwnership.StartDecision.Acquired("lease-a"),
            ownership.onStart("source-a", startId = 10, requestedLeaseId = "lease-a"),
        )
        assertTrue(ownership.markForegroundOwned("source-a", "lease-a"))
        assertEquals(
            ScreenMirrorRequesterForegroundOwnership.StartDecision.Duplicate("lease-a"),
            ownership.onStart("source-a", startId = 11, requestedLeaseId = "lease-a"),
        )

        val finished = ownership.finish("source-a", "lease-a")
        assertEquals(11, finished?.latestStartId)
        assertEquals("lease-a", finished?.leaseId)
        assertTrue(finished?.foregroundOwned == true)
    }

    @Test
    fun sourceSwitchAtomicallyRevokesOldCallbacksAndOwnsTheNewStartId() {
        val ownership = ScreenMirrorRequesterForegroundOwnership()
        ownership.onStart("source-a", startId = 20, requestedLeaseId = "lease-a")
        ownership.markForegroundOwned("source-a", "lease-a")

        assertEquals(
            ScreenMirrorRequesterForegroundOwnership.StartDecision.Switched(
                previousSourceId = "source-a",
                previousLeaseId = "lease-a",
                leaseId = "lease-b",
            ),
            ownership.onStart("source-b", startId = 21, requestedLeaseId = "lease-b"),
        )
        assertFalse(ownership.isOwnedBy("source-a", "lease-a"))
        assertTrue(ownership.isOwnedBy("source-b", "lease-b"))
        assertNull(ownership.finish("source-a", "lease-a"))
        assertTrue(ownership.markForegroundOwned("source-b", "lease-b"))

        // Even a stale stop command is a delivered Service start id. It may not stop B, but B's
        // eventual stopSelf must include it so Android cannot retain this Service for that command.
        assertEquals("lease-b", ownership.noteCommand(startId = 22)?.leaseId)
        assertNull(ownership.finish("source-b", "lease-a"))
        val finished = ownership.finish("source-b", "lease-b")
        assertEquals(22, finished?.latestStartId)
        assertEquals("source-b", finished?.sourceId)
        assertTrue(finished?.foregroundOwned == true)
    }

    @Test
    fun abandonedOrCompletedLeaseCannotTearDownReplacement() {
        val ownership = ScreenMirrorRequesterForegroundOwnership()
        ownership.onStart("source-a", startId = 30, requestedLeaseId = "lease-a")
        val abandoned = ownership.abandon()
        assertEquals("source-a", abandoned?.sourceId)
        assertEquals("lease-a", abandoned?.leaseId)

        assertEquals(
            ScreenMirrorRequesterForegroundOwnership.StartDecision.Acquired("lease-b"),
            ownership.onStart("source-b", startId = 31, requestedLeaseId = "lease-b"),
        )
        assertNull(ownership.finish("source-a", "lease-a"))
        assertTrue(ownership.isOwnedBy("source-b", "lease-b"))
    }

    @Test
    fun sameSourceNewLeaseTransfersOwnershipWithoutBecomingADuplicate() {
        val ownership = ScreenMirrorRequesterForegroundOwnership()
        ownership.onStart("source-a", startId = 40, requestedLeaseId = "old-lease")
        assertTrue(ownership.markForegroundOwned("source-a", "old-lease"))
        assertEquals(
            ScreenMirrorRequesterForegroundOwnership.StartDecision.Transferred(
                sourceId = "source-a",
                previousLeaseId = "old-lease",
                leaseId = "new-lease",
            ),
            ownership.onStart(
                "source-a",
                startId = 41,
                requestedLeaseId = "new-lease",
            ),
        )
        assertFalse(ownership.isOwnedBy("source-a", "old-lease"))
        assertTrue(ownership.isOwnedBy("source-a", "new-lease"))
        assertTrue(ownership.isSourceOwned("source-a"))

        // Delivery of the old PendingIntent still advances Android's latest Service start id, but
        // its old lease has no authority over the replacement using the identical source id.
        assertEquals("new-lease", ownership.noteCommand(startId = 42)?.leaseId)
        assertNull(ownership.finish("source-a", "old-lease"))
        assertTrue(ownership.isOwnedBy("source-a", "new-lease"))
        val finished = ownership.finish("source-a", "new-lease")
        assertEquals(42, finished?.latestStartId)
        // A transfer must promote the replacement generation itself; it may not accidentally
        // inherit the old Activity's foreground marker.
        assertFalse(finished?.foregroundOwned == true)
    }

    @Test
    fun sameSourceSameLeaseIsTheOnlyDuplicate() {
        val ownership = ScreenMirrorRequesterForegroundOwnership()
        ownership.onStart("source-a", startId = 50, requestedLeaseId = "lease-a")

        assertEquals(
            ScreenMirrorRequesterForegroundOwnership.StartDecision.Duplicate("lease-a"),
            ownership.onStart("source-a", startId = 51, requestedLeaseId = "lease-a"),
        )
        assertEquals(
            ScreenMirrorRequesterForegroundOwnership.StartDecision.Transferred(
                sourceId = "source-a",
                previousLeaseId = "lease-a",
                leaseId = "lease-b",
            ),
            ownership.onStart("source-a", startId = 52, requestedLeaseId = "lease-b"),
        )
    }
}
