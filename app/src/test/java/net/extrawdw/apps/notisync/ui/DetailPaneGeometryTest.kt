package net.extrawdw.apps.notisync.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailPaneGeometryTest {
    @Test
    fun openingDraggingAndRestoringUseTheSameSplit() {
        // Include odd widths and density-rounded gaps, not just whole-dp tablet dimensions.
        for ((width, gap, main, detail) in listOf(
            listOf(1000, 16, 492, 492),
            listOf(1001, 17, 493, 492),
            listOf(2560, 32, 1264, 1264),
            listOf(2364, 44, 1160, 1160),
        )) {
            val geometry = DetailPaneGeometry(width, gap)
            assertEquals(main, geometry.paneWidthAt(.5f, main = true))
            assertEquals(detail, geometry.splitDetailWidth)
            assertEquals(main, geometry.firstPaneWidthAt(width * .5f))
            assertEquals(main, geometry.firstPaneWidthDuringMotion(detail, 1f))
            assertEquals(width, main + detail + geometry.spacer)
        }
    }

    @Test
    fun collapsedPanesDoNotReserveAnEmptyGap() {
        val geometry = DetailPaneGeometry(1000, 16)
        assertEquals(0, geometry.paneWidthAt(0f, main = true))
        assertEquals(1000, geometry.paneWidthAt(0f, main = false))
        assertEquals(1000, geometry.paneWidthAt(1f, main = true))
        assertEquals(0, geometry.paneWidthAt(1f, main = false))
        assertEquals(0f, geometry.dragOffsetFromHandle(8f))
        assertEquals(1000f, geometry.dragOffsetFromHandle(992f))
    }

    @Test
    fun fullWidthDetailClosesWithinThePaneRegion() {
        val geometry = DetailPaneGeometry(1000, 16)
        assertEquals(1000, geometry.firstPaneWidthDuringMotion(1000, 0f))
        assertEquals(500, geometry.firstPaneWidthDuringMotion(1000, .5f))
        assertEquals(0, geometry.firstPaneWidthDuringMotion(1000, 1f))
    }
}
