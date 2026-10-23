package net.extrawdw.apps.notisync.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Gesture timing is exercised on both the regular tablet and the Samsung landscape test activity. */
@RunWith(AndroidJUnit4::class)
class PaneSwipeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun shortModerateSwipeExpandsAndFastRightSwipeRestoresInsteadOfClosing() {
        fixture()
        val split = divider()
        val handle = compose.onNodeWithTag("detail-pane-divider").getUnclippedBoundsInRoot()
        repeat(4) {
            swipeBody(-80f, 120)
            assertTrue(divider() < split * .1f)
            swipeBody(if (it % 2 == 0) 220f else regionWidth() * .72f, 80)
            assertEquals(split, divider(), 1f)
            compose.onNodeWithTag("section-two").assertIsDisplayed()
            assertEquals(handle, compose.onNodeWithTag("detail-pane-divider").getUnclippedBoundsInRoot())
        }
        swipeBody(-30f, 350)
        assertEquals(split, divider(), 1f)
    }

    @Test fun quickSwipeKeepsGridRearrangementAnimatedThroughSettling() = checkRearrangement(request = false)
    @Test fun quickSwipeKeepsRequestRearrangementAnimatedThroughSettling() = checkRearrangement(request = true)

    @Test fun longFastSwipeKeepsGridRearrangementAnimated() = checkRearrangement(request = false, long = true)
    @Test fun longFastSwipeKeepsRequestRearrangementAnimated() = checkRearrangement(request = true, long = true)

    @Test fun rightSwipeCanInterruptExpansionAndStillReturnToSplit() {
        fixture()
        val split = divider()
        compose.mainClock.autoAdvance = false
        swipeBody(-100f, 100)
        compose.mainClock.advanceTimeBy(80)
        swipeBody(200f, 80)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(split, divider(), 1f)
        compose.onNodeWithTag("section-two").assertIsDisplayed()
    }

    @Test fun gridRearrangesToOneColumnBeforeRestoringGestureEnds() = checkRestoreRearrangement(request = false)
    @Test fun requestRearrangesToOneColumnBeforeRestoringGestureEnds() = checkRestoreRearrangement(request = true)

    private fun checkRestoreRearrangement(request: Boolean) {
        fixture(request)
        val split = divider()
        swipeBody(-80f, 120)
        val regionWidthDp = regionWidth()
        // Cross the content breakpoint while keeping the divider visibly short of the split.
        val targetPaneWidth = (regionWidthDp / 2 + 704f) / 2
        val distance = (regionWidthDp - targetPaneWidth) * compose.activity.resources.displayMetrics.density
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("swipe-region").performTouchInput {
            down(Offset(width * .15f, height * .7f))
            moveTo(Offset(width * .15f + distance, height * .7f), 200)
        }
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        val first = compose.onNodeWithTag("section-one").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("section-two").getUnclippedBoundsInRoot()
        assertTrue("Still dragging before the split", divider() < split - 8)
        assertTrue("One-column rearrangement already started: $first, $second", second.top > first.top + 10.dp)
        compose.onNodeWithTag("swipe-region").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(split, divider(), 1f)
        assertEquals(
            compose.onNodeWithTag("section-one").getUnclippedBoundsInRoot().left,
            compose.onNodeWithTag("section-two").getUnclippedBoundsInRoot().left,
        )
    }

    private fun checkRearrangement(request: Boolean, long: Boolean = false) {
        fixture(request)
        val split = divider()
        compose.mainClock.autoAdvance = false
        swipeBody(if (long) -regionWidth() * .6f else -100f, 100)
        val samples = mutableListOf<Pair<Float, Float>>()
        repeat(40) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            val first = compose.onNodeWithTag("section-one").getUnclippedBoundsInRoot()
            val second = compose.onNodeWithTag("section-two").getUnclippedBoundsInRoot()
            samples += divider() to (second.left - first.left).value
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val finish = samples.last().second
        assertTrue("Two columns fit after expansion: $samples", finish > 100)
        val moving = samples.zipWithNext().map { (a, b) -> b.second - a.second }
        assertTrue("Reflow has multiple intermediate frames: $samples", moving.count { abs(it) > .5f } >= 6)
        assertTrue("No terminal jump in the reflow: $samples", abs(moving.last { abs(it) > .5f }) < 8f)
        assertTrue("Rearrangement completes: $samples", moving.takeLast(5).all { abs(it) < 2f })
        assertTrue("The pane settles directly toward expansion: $samples", samples.zipWithNext().all { (a, b) -> b.first <= a.first + 1 })
        assertTrue(divider() < split * .1f)
        println("PaneSwipeTest ${if (request) "request" else "grid"}: $samples")
    }

    private fun fixture(request: Boolean = false) {
        compose.setContent {
            NotiSyncTheme {
                var selected by remember { mutableStateOf<String?>("fixture") }
                AdaptiveDetailLayout(
                    selectedKey = selected, detailState = Unit, onDismiss = { selected = null },
                    modifier = Modifier.fillMaxSize().testTag("swipe-region"),
                    detail = {
                        AdaptiveDetailSheet(onDismissRequest = { selected = null }) {
                            if (request) {
                                RequestDetailLayout(
                                    contentPadding = PaddingValues(20.dp), useWideLayout = true,
                                    summary = { Box(Modifier.fillMaxWidth().height(120.dp).testTag("section-one")) },
                                    details = { Box(Modifier.fillMaxWidth().height(120.dp).testTag("section-two")) },
                                )
                            } else {
                                AdaptiveDetailGrid(contentPadding = PaddingValues(20.dp), verticalSpacing = 18.dp) {
                                    detailSection("section-one") { Text("Identity", Modifier.height(120.dp)) }
                                    detailSection("section-two") { Text("Controls", Modifier.height(120.dp)) }
                                }
                            }
                        }
                    },
                ) { Text("List") }
            }
        }
        compose.waitForIdle()
    }

    private fun swipeBody(distanceDp: Float, durationMillis: Long) {
        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("swipe-region").performTouchInput {
            val x = if (distanceDp < 0) width * .85f else width * .15f
            swipe(Offset(x, height * .7f), Offset(x + distanceDp * density, height * .7f), durationMillis)
        }
    }

    private fun regionWidth(): Float {
        val bounds = compose.onNodeWithTag("swipe-region").getUnclippedBoundsInRoot()
        return (bounds.right - bounds.left).value
    }

    private fun divider(): Float {
        val bounds = compose.onNodeWithTag("detail-pane-divider").getUnclippedBoundsInRoot()
        return ((bounds.left + bounds.right) / 2).value
    }
}
