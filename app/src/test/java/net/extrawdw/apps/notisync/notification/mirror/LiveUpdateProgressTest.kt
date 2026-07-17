package net.extrawdw.apps.notisync.notification.mirror

import net.extrawdw.notisync.protocol.NotificationProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateProgressTest {

    @Test
    fun determinateProgress_preservesAndClampsIntSizedValues() {
        assertEquals(
            NativeLiveProgress(current = 42, total = 100, indeterminate = false),
            normalizeLiveProgress(NotificationProgress(current = 42L, total = 100L)),
        )
        assertEquals(
            NativeLiveProgress(current = 100, total = 100, indeterminate = false),
            normalizeLiveProgress(NotificationProgress(current = 150L, total = 100L)),
        )
        assertEquals(
            NativeLiveProgress(current = 0, total = 100, indeterminate = false),
            normalizeLiveProgress(NotificationProgress(current = -1L, total = 100L)),
        )
    }

    @Test
    fun veryLargeProgress_scalesWithoutOverflow() {
        val normalized = normalizeLiveProgress(
            NotificationProgress(current = 3_000_000_000L, total = 4_000_000_000L),
        )

        assertFalse(normalized.indeterminate)
        assertEquals(7_500, normalized.current)
        assertEquals(10_000, normalized.total)
    }

    @Test
    fun indeterminateOrIncompleteProgress_ignoresNativeValues() {
        val explicit = normalizeLiveProgress(
            NotificationProgress(current = 20L, total = 100L, indeterminate = true),
        )
        val missingCurrent = normalizeLiveProgress(NotificationProgress(total = 100L))
        val invalidTotal = normalizeLiveProgress(NotificationProgress(current = 1L, total = 0L))

        assertTrue(explicit.indeterminate)
        assertTrue(missingCurrent.indeterminate)
        assertTrue(invalidTotal.indeterminate)
    }
}
