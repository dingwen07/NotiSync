package net.extrawdw.apps.notisync.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenEndpointRetryPolicyTest {
    @Test
    fun videoIdentityConsumptionMakesLaterFailureTerminal() {
        assertTrue(ScreenEndpointRetryPolicy.canRetry(videoAuthenticated = false))
        assertFalse(ScreenEndpointRetryPolicy.canRetry(videoAuthenticated = true))
    }
}
