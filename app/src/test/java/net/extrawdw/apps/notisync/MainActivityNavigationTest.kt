package net.extrawdw.apps.notisync

import net.extrawdw.apps.notisync.data.run.RunKey
import net.extrawdw.notisync.protocol.ClientId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityNavigationTest {
    @Test
    fun openingRunAlwaysClosesPairingOverlay() {
        val requestedRun = RunKey(ClientId("host"), "run-1")

        assertFalse(pairingOverlayAfterRunOpenRequest(currentlyVisible = true, requestedRun))
        assertFalse(pairingOverlayAfterRunOpenRequest(currentlyVisible = false, requestedRun))
    }

    @Test
    fun noRunRequestLeavesPairingVisibilityAlone() {
        assertTrue(pairingOverlayAfterRunOpenRequest(currentlyVisible = true, openRun = null))
        assertFalse(pairingOverlayAfterRunOpenRequest(currentlyVisible = false, openRun = null))
    }
}
