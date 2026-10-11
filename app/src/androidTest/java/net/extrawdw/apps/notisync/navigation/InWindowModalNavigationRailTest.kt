package net.extrawdw.apps.notisync.navigation

import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailState
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InWindowModalNavigationRailTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var state: WideNavigationRailState
    private lateinit var back: OnBackPressedDispatcher
    private lateinit var bodyView: View
    private lateinit var railView: View
    private lateinit var inputMode: InputModeManager
    private var attachments = 0
    private var disposals = 0
    private var bodyClicks = 0
    private var primaryIconX = 0f
    private val hiddenIconX = FloatArray(5) { Float.NaN }

    @Test
    fun railStaysInTheSameWindowAndBodyDoesNotResizeDuringMotionOrReversal() {
        setContent()
        val body = compose.onNodeWithTag("body").getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        repeat(2) {
            compose.onNodeWithTag("toggle").performClick()
            repeat(6) { checkFrame(body) }
            compose.onNodeWithTag("toggle").performClick() // Reverse an unfinished expansion.
            repeat(35) { checkFrame(body) }
        }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle {
            assertEquals(WideNavigationRailValue.Collapsed, state.targetValue)
            assertEquals(1, attachments)
            assertEquals(0, disposals)
        }
    }

    @Test
    fun outsideTapBackAndEscapeDismissWithoutClickingThePage() {
        setContent()
        compose.onNodeWithTag("toggle").performClick()
        compose.onNodeWithTag("body").performTouchInput { click(Offset(width - 4f, height / 2f)) }
        compose.runOnIdle {
            assertEquals(0, bodyClicks)
            assertEquals(WideNavigationRailValue.Collapsed, state.targetValue)
        }
        compose.onNodeWithTag("toggle").performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.runOnIdle { assertEquals(WideNavigationRailValue.Collapsed, state.targetValue) }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithTag("toggle").performClick()
        compose.onNodeWithTag("toggle").performKeyInput { pressKey(Key.Escape) }
        compose.runOnIdle { assertEquals(WideNavigationRailValue.Collapsed, state.targetValue) }
    }

    @Test
    fun rtlExpansionKeepsThePageFixedAndTheRailOnTheLeadingEdge() {
        setContent(LayoutDirection.Rtl)
        val body = compose.onNodeWithTag("body").getUnclippedBoundsInRoot()
        val toggle = compose.onNodeWithTag("toggle").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("toggle").performClick()
        assertEquals(body, compose.onNodeWithTag("body").getUnclippedBoundsInRoot())
        assertEquals(toggle, compose.onNodeWithTag("toggle").getUnclippedBoundsInRoot())
        compose.runOnIdle { assertSame(bodyView, railView) }
    }

    @Test
    fun destinationSpacingDoesNotReverseAsLabelsMoveBesideTheirIcons() {
        setContent()
        compose.mainClock.autoAdvance = false
        fun centers() = (0..4).map {
            compose.onNodeWithTag("icon-$it", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.center.y
        }
        // A label's changing measured height used to push every following row down mid-open.
        // Measure all adjacent pairs so the cumulative jump cannot hide behind group centering.
        repeat(2) {
            repeat(2) { direction ->
                var previous = centers()
                compose.onNodeWithTag("toggle").performClick()
                repeat(45) { frame ->
                    compose.mainClock.advanceTimeByFrame()
                    val current = centers()
                    for (index in 1..4) {
                        val before = previous[index] - previous[index - 1]
                        val after = current[index] - current[index - 1]
                        val reversal = if (direction == 0) after - before else before - after
                        assertTrue(
                            "Row $index reversed spacing by $reversal px at frame $frame",
                            reversal <= 1.01f, // Allow one pixel of placement rounding.
                        )
                    }
                    previous = current
                }
            }
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun hiddenIconsRemainLeadingAnchoredWhileOpeningAndClosing() {
        setContent()
        compose.mainClock.autoAdvance = false
        repeat(2) {
            repeat(2) {
                compose.onNodeWithTag("toggle").performClick()
                repeat(40) { frame ->
                    compose.mainClock.advanceTimeByFrame()
                    compose.runOnIdle {
                        hiddenIconX.forEachIndexed { index, x ->
                            if (x.isFinite()) assertEquals(
                                "Hidden icon $index moved sideways at frame $frame",
                                primaryIconX, x, 1f,
                            )
                        }
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(hiddenIconX.all { it.isFinite() }) }
        compose.mainClock.autoAdvance = true
    }

    private fun checkFrame(body: androidx.compose.ui.unit.DpRect) {
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("item-0").assertIsDisplayed()
        assertEquals(body, compose.onNodeWithTag("body").getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertSame("Rail content must never transfer to a Dialog's ComposeView", bodyView, railView)
            assertEquals(1, attachments)
            assertEquals(0, disposals)
        }
    }

    private fun setContent(direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                NotiSyncTheme {
                    inputMode = LocalInputModeManager.current
                    state = rememberWideNavigationRailState()
                    back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                    val scope = rememberCoroutineScope()
                    val expanded = state.targetValue == WideNavigationRailValue.Expanded
                    InWindowModalNavigationRail(
                        state = state,
                        windowInsets = WindowInsets(0),
                        railContent = {
                            railView = LocalView.current
                            DisposableEffect(Unit) {
                                attachments++
                                onDispose { disposals++ }
                            }
                            NavigationRailContent(WindowInsets(0), header = {
                                IconButton(
                                    modifier = Modifier.testTag("toggle"),
                                    onClick = { scope.launch { state.toggle() } },
                                ) { Text("Menu") }
                            }) {
                                repeat(5) { index ->
                                    AnimatedRailEntry(
                                        visible = true,
                                        spacingAfter = if (!expanded && index < 4) 8.dp else 0.dp,
                                        modifier = Modifier.animateRailItemPlacement(),
                                    ) {
                                        WideNavigationRailItem(
                                            selected = index == 0,
                                            onClick = {},
                                            icon = {
                                                Box(Modifier.size(24.dp).testTag("icon-$index")
                                                    .onGloballyPositioned {
                                                        if (index == 0) primaryIconX = it.positionInRoot().x
                                                    })
                                            },
                                            label = { Text("Item $index", maxLines = 1) },
                                            railExpanded = expanded,
                                            modifier = Modifier.railItemHeight(expanded).testTag("item-$index"),
                                        )
                                    }
                                }
                                AnimatedExpandedRailEntries(expanded) { itemExpanded ->
                                    repeat(5) { index ->
                                        RailItemPlacement(animate = true) {
                                            WideNavigationRailItem(
                                                selected = false, onClick = {},
                                                icon = {
                                                    Box(Modifier.size(24.dp).onGloballyPositioned {
                                                        hiddenIconX[index] = it.positionInRoot().x
                                                    })
                                                },
                                                label = { Text("Extra item $index", maxLines = 1) },
                                                modifier = Modifier.railItemHeight(itemExpanded),
                                                railExpanded = itemExpanded,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        bodyView = LocalView.current
                        Box(Modifier.fillMaxSize().testTag("body").clickable { bodyClicks++ })
                    }
                }
            }
        }
    }
}
