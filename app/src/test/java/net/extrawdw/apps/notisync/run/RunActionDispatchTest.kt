package net.extrawdw.apps.notisync.run

import net.extrawdw.notisync.protocol.RunControlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunActionDispatchTest {
    @Test
    fun immutableRouteRoundTripsAllPrivilegedFields() {
        val key = RunKey("host/with space+plus", "run/with punctuation?")
        val encoded = runActionRouteData(key, interactionGeneration = 7, control = "INPUT")

        val route = parseRunActionRoute(encoded)

        assertEquals(RunActionRoute(key, "INPUT", 7), route)
    }

    @Test
    fun remoteInputCannotTurnInputRouteIntoSignalOrRetargetIt() {
        val key = RunKey("trusted-host", "run-1")
        val route = parseRunActionRoute(runActionRouteData(key, 9, "INPUT"))!!

        val control = buildRunNotificationControl(
            route = route,
            // Treat signal-looking text as terminal text; mutable fill-in extras never supply routing fields.
            remoteInput = "TERM",
            requestId = "d8de5032-0c17-4ec4-8864-b1061c4579a5",
            requestedAt = 12_000,
        )!!

        assertEquals(key.hostClientId, control.hostClientId.value)
        assertEquals(key.runId, control.runId)
        assertEquals(RunControlKind.WRITE_INPUT, control.kind)
        assertEquals("TERM\n", control.inputText)
        assertNull(control.signal)
        assertEquals(9L, control.interactionGeneration)
    }

    @Test
    fun exactSignalAndRequestIdAreBuiltOnce() {
        val route = parseRunActionRoute(
            runActionRouteData(RunKey("host", "run-2"), 3, "INTERRUPT")
        )!!

        val control = buildRunNotificationControl(
            route = route,
            remoteInput = null,
            requestId = "fc276c64-4e65-4374-a5cf-4b44d93f3d9a",
            requestedAt = 44,
        )!!

        assertEquals("fc276c64-4e65-4374-a5cf-4b44d93f3d9a", control.requestId)
        assertEquals(RunControlKind.SIGNAL, control.kind)
        assertEquals("INT", control.signal)
        assertNull(control.inputText)
    }

    @Test
    fun malformedOrTamperedBaseRoutesAreRejected() {
        assertNull(parseRunActionRoute("notisync://run-control/kill/host/run/1"))
        assertNull(parseRunActionRoute("notisync://run-control/input/host/run/-1"))
        assertNull(parseRunActionRoute("notisync://other/input/host/run/1"))
        assertNull(parseRunActionRoute("notisync://run-control/input/host/run/1?control=terminate"))
        assertNull(parseRunActionRoute("https://run-control/input/host/run/1"))
    }

    @Test
    fun notificationActionGateAcceptsOnlyOnePressUntilANewRender() {
        val key = RunKey("host", "run-1")
        RunNotificationActionGate.release(key)

        assertTrue(RunNotificationActionGate.claim(key))
        assertFalse(RunNotificationActionGate.claim(key))

        RunNotificationActionGate.release(key)
        assertTrue(RunNotificationActionGate.claim(key))
        RunNotificationActionGate.release(key)
    }
}
