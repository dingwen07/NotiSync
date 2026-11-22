package net.extrawdw.apps.notisync.ios

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisingLossTrackerTest {
    private val tracker = AdvertisingLossTracker()

    private fun loseAdapter(start: Long, delay: Long = 100): Boolean {
        tracker.advertisingRequested(start)
        return tracker.adapterWentDown(start + delay)
    }

    @Test fun threeDistinctLossesTripOnceUntilBridgeIsStopped() {
        assertFalse(loseAdapter(0))
        assertFalse(tracker.adapterWentDown(200)) // OFF after TURNING_OFF
        assertFalse(loseAdapter(12_000))
        assertTrue(loseAdapter(60_000))
        assertTrue(tracker.tripped)
        assertFalse(tracker.adapterWentDown(60_200))
        tracker.reset()
        assertFalse(tracker.tripped)
        assertFalse(loseAdapter(61_000))
    }

    @Test fun rollingWindowExpiresOnlyOldLosses() {
        assertFalse(loseAdapter(0))
        assertFalse(loseAdapter(59_000))
        assertFalse(loseAdapter(61_000))
        assertTrue(loseAdapter(65_000))
    }

    @Test fun onlyLossesImmediatelyFollowingAnActiveRequestCount() {
        assertFalse(tracker.adapterWentDown(0))
        tracker.advertisingRequested(100)
        tracker.advertisingStopped()
        assertFalse(tracker.adapterWentDown(200))
        assertFalse(loseAdapter(300, 5_001))
        assertFalse(loseAdapter(10_000, 5_000))
        assertFalse(loseAdapter(20_000))
        assertTrue(loseAdapter(30_000))
    }
}
