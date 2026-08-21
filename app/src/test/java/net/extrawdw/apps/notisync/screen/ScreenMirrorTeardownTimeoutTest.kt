package net.extrawdw.apps.notisync.screen

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMirrorTeardownTimeoutTest {
    @Test
    fun stuckPrivilegedStopCannotHoldLogicalSessionForever() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val stopped = runScreenTeardownWithTimeout(50) {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
            }

            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertFalse(stopped)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun stuckRecoveryOperationCannotHoldRelayWriterForever() {
        val release = CountDownLatch(1)
        try {
            val result = runScreenOperationWithTimeout(50) {
                release.await(2, TimeUnit.SECONDS)
                3
            }

            assertNull(result)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun readyBackendProbePublishesOneCompleteSnapshot() {
        val executor = probeExecutor()
        try {
            val snapshot = probeScreenBackendWithTimeout(
                executor = executor,
                timeoutMillis = 1_000,
                backendStatus = { ScreenMirrorBackendStatus.READY },
                probeHardwareCodecs = { ScreenMirrorCodecBits.H264 or ScreenMirrorCodecBits.H265 },
                probeCapabilities = {
                    ScreenMirrorProbeBits.DISPLAY_CAPTURE or ScreenMirrorProbeBits.INPUT_INJECTION
                },
            )

            assertNotNull(snapshot)
            assertEquals(ScreenMirrorBackendStatus.READY, snapshot?.backendStatus)
            assertEquals(ScreenMirrorCodecBits.H264 or ScreenMirrorCodecBits.H265, snapshot?.availableCodecBits)
            assertEquals(
                ScreenMirrorProbeBits.DISPLAY_CAPTURE or ScreenMirrorProbeBits.INPUT_INJECTION,
                snapshot?.probeBits,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun nonReadyBackendProbeSkipsCapabilityCalls() {
        val executor = probeExecutor()
        val capabilityCalled = AtomicBoolean(false)
        try {
            val snapshot = probeScreenBackendWithTimeout(
                executor = executor,
                timeoutMillis = 1_000,
                backendStatus = { ScreenMirrorBackendStatus.BACKEND_UNAVAILABLE },
                probeHardwareCodecs = { capabilityCalled.set(true); 1 },
                probeCapabilities = { capabilityCalled.set(true); 1 },
            )

            assertNotNull(snapshot)
            assertEquals(ScreenMirrorBackendStatus.BACKEND_UNAVAILABLE, snapshot?.backendStatus)
            assertEquals(0, snapshot?.availableCodecBits)
            assertEquals(0, snapshot?.probeBits)
            assertFalse(capabilityCalled.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun stuckBackendProbeReturnsAtDeadline() {
        val executor = probeExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val snapshot = probeScreenBackendWithTimeout(
                executor = executor,
                timeoutMillis = 50,
                backendStatus = {
                    started.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    ScreenMirrorBackendStatus.READY
                },
                probeHardwareCodecs = { 1 },
                probeCapabilities = { 1 },
            )

            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertNull(snapshot)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun probeExecutor() = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "screen-probe-test").apply { isDaemon = true }
    }.apply {
        setRemoveOnCancelPolicy(true)
    }
}
