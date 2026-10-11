package net.extrawdw.apps.notisync.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneSwipeBehaviorTest {
    @Test fun shortModerateSwipesAdvanceButSmallSlowMovementsReturn() {
        for (width in listOf(860f, 1280f)) for (density in listOf(1f, 2f, 3.5f)) {
            val anchors = listOf(0f, width / 2, width).map { it * density }
            val gesture = PaneSwipeGesture(anchors, anchors[1])
            assertEquals(0, gesture.targetIndex(anchors[1] - 60 * density, -500 * density, density))
            assertEquals(1, gesture.targetIndex(anchors[1] - 60 * density, -100 * density, density))
            assertEquals(1, gesture.targetIndex(anchors[1] - 10 * density, -2000 * density, density))
        }
    }

    @Test fun expandedPaneCannotSkipTheSplitEvenWithFastOrLongSwipes() {
        val gesture = PaneSwipeGesture(listOf(0f, 500f, 1000f), 0f)
        assertEquals(500f, gesture.maximum)
        assertEquals(1, gesture.targetIndex(100f, 10000f, 1f))
        assertEquals(1, gesture.targetIndex(950f, 10000f, 1f))
        assertEquals(1, gesture.targetIndex(400f, 0f, 1f))
        assertEquals(0, gesture.targetIndex(80f, 0f, 1f))
    }

    @Test fun reversalReturnsToTheOriginAndSplitCanStillDismiss() {
        val gesture = PaneSwipeGesture(listOf(0f, 500f, 1000f), 500f)
        assertEquals(1, gesture.targetIndex(380f, 500f, 1f))
        assertEquals(1, gesture.targetIndex(620f, -500f, 1f))
        assertEquals(2, gesture.targetIndex(570f, 500f, 1f))
        assertEquals(2, gesture.targetIndex(700f, 0f, 1f))
        assertEquals(0, gesture.targetIndex(300f, 0f, 1f))
    }

    @Test fun releaseVelocityIsBoundedToAvoidOvershootOrReversal() {
        assertEquals(500f, paneSwipeSettlingVelocity(500f, 200f))
        assertEquals(0f, paneSwipeSettlingVelocity(-500f, 200f))
        assertEquals(0f, paneSwipeSettlingVelocity(500f, -200f))
        val forward = paneSwipeSettlingVelocity(10000f, 10f)
        assertTrue(forward > 0 && forward < 400)
        assertEquals(-forward, paneSwipeSettlingVelocity(-10000f, -10f))
    }
}
