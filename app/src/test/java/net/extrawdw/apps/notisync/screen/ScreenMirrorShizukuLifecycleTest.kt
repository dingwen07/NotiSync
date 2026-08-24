package net.extrawdw.apps.notisync.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMirrorShizukuLifecycleTest {
    @Test
    fun bindingProbeDoesNotInvalidateAnAdmittedSession() {
        assertFalse(ShizukuScreenStatus.READY.invalidatesActiveScreenSession())
        assertFalse(ShizukuScreenStatus.BINDING.invalidatesActiveScreenSession())
    }

    @Test
    fun unusableShizukuStatesInvalidateAnAdmittedSession() {
        val unusable = ShizukuScreenStatus.entries - setOf(
            ShizukuScreenStatus.READY,
            ShizukuScreenStatus.BINDING,
        )

        assertTrue(unusable.isNotEmpty())
        unusable.forEach { status ->
            assertTrue("$status must invalidate the active screen session", status.invalidatesActiveScreenSession())
        }
    }

    @Test
    fun userServiceDestroyClosesBackendThenTerminatesProcessExactlyOnce() {
        val events = mutableListOf<String>()
        val lifecycle = ScreenMirrorUserServiceDestroyLifecycle(
            closeBackend = { events += "close" },
            terminateProcess = { events += "exit" },
        )

        lifecycle.destroy()
        lifecycle.destroy()

        assertTrue(lifecycle.isDestroyed)
        assertEquals(listOf("close", "exit"), events)
    }

    @Test
    fun userServiceDestroyTerminatesProcessWhenBackendCleanupFails() {
        var terminated = false
        val lifecycle = ScreenMirrorUserServiceDestroyLifecycle(
            closeBackend = { error("cleanup failed") },
            terminateProcess = { terminated = true },
        )

        lifecycle.destroy()

        assertTrue(lifecycle.isDestroyed)
        assertTrue(terminated)
    }
}
