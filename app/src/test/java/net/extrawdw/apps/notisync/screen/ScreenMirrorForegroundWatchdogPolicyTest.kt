package net.extrawdw.apps.notisync.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenMirrorForegroundWatchdogPolicyTest {
    @Test
    fun watchdogOnlyOwnsUnacknowledgedForegroundSubmissions() {
        ScreenMirrorForegroundState.entries.forEach { state ->
            val expected = when (state) {
                ScreenMirrorForegroundState.SUBMITTING,
                ScreenMirrorForegroundState.REQUESTED,
                -> true

                ScreenMirrorForegroundState.NOT_REQUESTED,
                ScreenMirrorForegroundState.ACKNOWLEDGED,
                ScreenMirrorForegroundState.UNAVAILABLE,
                -> false
            }

            assertEquals(state.name, expected, state.awaitingAcknowledgement())
        }
    }
}
