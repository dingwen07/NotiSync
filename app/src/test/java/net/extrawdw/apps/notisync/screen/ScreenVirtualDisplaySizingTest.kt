package net.extrawdw.apps.notisync.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenVirtualDisplaySizingTest {
    @Test fun customDensitySurvivesWindowResizeAndResolutionScaling() {
        val split = ScreenVirtualDisplaySizing.forViewport(1200, 1000, 520, fixedDensityDpi = 320)
        val full = ScreenVirtualDisplaySizing.forViewport(1200, 2670, 520, fixedDensityDpi = 320)
        val large = ScreenVirtualDisplaySizing.forViewport(6000, 4000, 520, fixedDensityDpi = 320)
        assertEquals(1000, split.height)
        assertEquals(2670, full.height)
        for (size in listOf(split, full, large)) {
            assertEquals(320, size.densityDpi)
            assertTrue(size.isValid())
        }
    }

    @Test fun phoneUsesItsOwnAspectAndDensity() {
        val size = ScreenVirtualDisplaySizing.forViewport(1200, 2670, 520)
        assertEquals(1200, size.width)
        assertEquals(2670, size.height)
        assertEquals(520, size.densityDpi)
        assertTrue(size.isValid())
    }

    @Test fun landscapeAndMultiwindowUseAvailableViewport() {
        val landscape = ScreenVirtualDisplaySizing.forViewport(2670, 1200, 520)
        assertEquals(2670, landscape.width)
        assertEquals(1200, landscape.height)
        val window = ScreenVirtualDisplaySizing.forViewport(900, 1600, 320)
        assertEquals(900, window.width)
        assertEquals(1600, window.height)
    }

    @Test fun oversizedWindowPreservesAspectAndDpScaleWithinLimits() {
        val size = ScreenVirtualDisplaySizing.forViewport(6000, 4000, 480)
        assertTrue(size.isValid())
        assertEquals(1.5, size.width.toDouble() / size.height, 0.002)
        assertEquals(6000.0 / 480, size.width.toDouble() / size.densityDpi, 0.03)
    }
}
