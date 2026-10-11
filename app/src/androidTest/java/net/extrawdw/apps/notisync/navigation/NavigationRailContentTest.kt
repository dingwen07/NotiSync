package net.extrawdw.apps.notisync.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationRailContentTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun hiddenItemAnimatesLabelFromBelowIconOnEveryExpansion() {
        val expanded = mutableStateOf(false)
        var iconCenter = Offset.Zero
        var labelCenter = Offset.Zero
        compose.setContent {
            NotiSyncTheme {
                Box(Modifier.size(360.dp, 500.dp)) {
                    AnimatedExpandedRailEntries(expanded.value) { itemExpanded ->
                        WideNavigationRailItem(
                            selected = true,
                            onClick = {},
                            railExpanded = itemExpanded,
                            icon = {
                                Box(Modifier.size(24.dp).onGloballyPositioned {
                                    iconCenter = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)
                                })
                            },
                            label = {
                                Text("Menu", Modifier.onGloballyPositioned {
                                    labelCenter = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)
                                })
                            },
                        )
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        repeat(2) {
            var initialIconX = 0f
            compose.runOnIdle { expanded.value = true }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertTrue("Newly revealed label must start below its icon", labelCenter.y > iconCenter.y + 8f)
                initialIconX = iconCenter.x
            }
            repeat(20) {
                compose.mainClock.advanceTimeBy(16)
                compose.runOnIdle {
                    assertEquals("Reveal must keep the icon anchored horizontally", initialIconX, iconCenter.x, 1f)
                }
            }
            compose.mainClock.advanceTimeBy(480)
            compose.runOnIdle {
                assertEquals("Expanded label aligns beside its icon", iconCenter.y, labelCenter.y, 1f)
                assertTrue(labelCenter.x > iconCenter.x)
                expanded.value = false
            }
            compose.mainClock.advanceTimeBy(800)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun openingFadeDoesNotClipContentBeyondTheAnimatedRowWidth() {
        val expanded = mutableStateOf(false)
        compose.setContent {
            NotiSyncTheme {
                Box(Modifier.size(220.dp, 100.dp).background(Color.White).testTag("canvas")) {
                    AnimatedRailEntry(visible = expanded.value) {
                        // Native labels and selected indicators can draw beyond the row's
                        // animated width. Only the outer rail should clip that overflow.
                        Box(Modifier.size(96.dp, 56.dp).drawBehind {
                            drawRect(Color.Red, size = Size(160.dp.toPx(), 48.dp.toPx()))
                        })
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { expanded.value = true }
        compose.mainClock.advanceTimeBy(96)
        val pixels = compose.onNodeWithTag("canvas").captureToImage().toPixelMap()
        val y = with(compose.density) { 20.dp.roundToPx() }
        val inside = pixels[with(compose.density) { 40.dp.roundToPx() }, y]
        val overflow = pixels[with(compose.density) { 130.dp.roundToPx() }, y]
        assertTrue("Capture must be during the opening fade", inside.green > 0f && inside.green < 0.95f)
        assertEquals("Opening fade must preserve the right side of the indicator", inside.green, overflow.green, 0.02f)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun scrollViewportReachesBottomButLastItemCanClearNavigationBar() {
        compose.setContent {
            NotiSyncTheme {
                Box(Modifier.size(220.dp, 360.dp).testTag("rail")) {
                    NavigationRailContent(
                        bottomInsets = WindowInsets(bottom = 32.dp),
                        header = { Box(Modifier.height(48.dp).testTag("header")) },
                    ) {
                        repeat(10) { index ->
                            Box(Modifier.size(180.dp, 48.dp).testTag("item-$index"))
                        }
                    }
                }
            }
        }
        val rail = compose.onNodeWithTag("rail").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        assertEquals("Scrolling must draw to the rail bottom", rail.bottom, viewport.bottom, 1f)
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        val last = compose.onNodeWithTag("item-9").fetchSemanticsNode().boundsInRoot
        val inset = with(compose.density) { 32.dp.toPx() }
        assertEquals("Last item must clear the system bar at the end", rail.bottom - inset, last.bottom, 1f)
        val header = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        assertEquals("Header stays fixed while scrolling", rail.top, header.top, 1f)
    }
}
