package net.extrawdw.apps.notisync.screen

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
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
}
