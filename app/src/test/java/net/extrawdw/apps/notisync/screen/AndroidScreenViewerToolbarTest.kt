package net.extrawdw.apps.notisync.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidScreenViewerToolbarTest {
    @Test
    fun `toolbar anchors span the usable viewport and remain distinct`() {
        assertEquals(1720f, screenViewerToolbarTravelDistance(1800, 80), 0f)
        assertEquals(1f, screenViewerToolbarTravelDistance(80, 120), 0f)
    }

    @Test
    fun `direct control slots preserve title space and grow with width`() {
        assertEquals(1, screenViewerDirectControlSlots(240f))
        assertEquals(2, screenViewerDirectControlSlots(320f))
        assertEquals(3, screenViewerDirectControlSlots(360f))
        assertEquals(4, screenViewerDirectControlSlots(425f))
        assertEquals(5, screenViewerDirectControlSlots(600f))
    }

    @Test
    fun `pinned controls that no longer fit remain available in overflow`() {
        val layout = screenViewerControlLayout(
            selectedControls = ScreenViewerControl.entries.toSet(),
            directControlSlots = 3,
        )

        assertEquals(
            listOf(
                ScreenViewerControl.BACK,
                ScreenViewerControl.HOME,
                ScreenViewerControl.RECENTS,
            ),
            layout.direct,
        )
        assertEquals(
            listOf(ScreenViewerControl.KEYBOARD, ScreenViewerControl.POWER),
            layout.overflow,
        )
    }
}
