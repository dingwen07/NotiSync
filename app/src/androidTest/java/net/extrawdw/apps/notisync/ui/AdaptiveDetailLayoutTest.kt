package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a tablet: exercise the actual Material/Nav3 scene and modal windows. */
@RunWith(AndroidJUnit4::class)
class AdaptiveDetailLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val wide = mutableStateOf(true)
    private val listIndex = java.util.concurrent.atomic.AtomicInteger()

    @Test
    fun resizingKeepsSelectionDraftAndListPositionAndCloseReturnsToList() {
        compose.setContent { Fixture() }
        compose.onNodeWithTag("list").performScrollToIndex(25)
        compose.onNodeWithText("Item 25").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("after opening", 25, listIndex.get()) }
        compose.onNodeWithTag("draft").performTextInput("unsent text")
        compose.onNodeWithText("pane").assertIsDisplayed()

        compose.runOnIdle { wide.value = false }
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "adaptive-resize.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("sheet").assertIsDisplayed()
        compose.onNodeWithTag("draft").assertTextEquals("unsent text")
        compose.runOnIdle { org.junit.Assert.assertEquals("after narrowing", 25, listIndex.get()) }

        compose.runOnIdle { wide.value = true }
        compose.onNodeWithText("pane").assertIsDisplayed()
        compose.onNodeWithTag("draft").assertTextEquals("unsent text")
        compose.runOnIdle { org.junit.Assert.assertEquals("after widening", 25, listIndex.get()) }
        compose.onNodeWithTag("detail-pane-back").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("after closing", 25, listIndex.get()) }
        compose.waitUntil(5_000) { compose.onNodeWithText("Item 25").isDisplayed() }
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "adaptive-closed.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Item 25").assertIsDisplayed()
        compose.onNodeWithTag("draft").assertDoesNotExist()
    }

    @Test
    fun nativeDividerExpandsDetailRestoresSplitAndClosesDetail() {
        compose.setContent { Fixture() }
        compose.onNodeWithText("Item 0").performClick()
        compose.onNodeWithTag("detail-pane-divider").performTouchInput {
            swipe(center, Offset(center.x - 1100f, center.y), 700)
        }
        compose.onNodeWithText("Item 0").assertIsNotDisplayed()
        compose.onNodeWithTag("draft").assertIsDisplayed()
        compose.onNodeWithTag("detail-pane-divider").performTouchInput {
            swipe(center, Offset(center.x + 1100f, center.y), 700)
        }
        compose.onNodeWithText("Item 0").assertIsDisplayed()
        compose.onNodeWithTag("detail-pane-divider").performTouchInput {
            swipe(center, Offset(center.x + 1100f, center.y), 700)
        }
        compose.waitUntil(5_000) { compose.onNodeWithTag("draft").isDisplayed().not() }
        compose.onNodeWithText("Item 0").assertIsDisplayed()
        compose.onNodeWithTag("draft").assertDoesNotExist()
    }

    @Test
    fun draggingCoversTheListAndSlidesDetailAwayWithoutRewrappingOutgoingText() {
        val listText = java.util.concurrent.atomic.AtomicReference<TextLayoutResult>()
        val detailText = java.util.concurrent.atomic.AtomicReference<TextLayoutResult>()
        val paragraph = "Pane content should stay readable while the divider moves. ".repeat(12)
        compose.setContent {
            var selected by rememberSaveable { mutableStateOf<String?>(null) }
            MaterialTheme {
                AdaptiveDetailLayout(
                    selectedKey = selected,
                    onDismiss = { selected = null },
                    detail = {
                        AdaptiveDetailSheet(onDismissRequest = { selected = null }) {
                            Text(paragraph, onTextLayout = { detailText.set(it) })
                        }
                    },
                ) {
                    androidx.compose.foundation.layout.Column {
                        Button(onClick = { selected = "selected" }) { Text("Open detail") }
                        Text(paragraph, onTextLayout = { listText.set(it) })
                    }
                }
            }
        }
        compose.onNodeWithText("Open detail").performClick()
        compose.waitForIdle()
        val listBefore = listText.get()
        val detailBefore = detailText.get()
        val divider = compose.onNodeWithTag("detail-pane-divider").fetchSemanticsNode().boundsInRoot.center
        val step = listBefore.size.width / 5f
        compose.onRoot().performTouchInput { down(divider) }
        repeat(2) {
            compose.onRoot().performTouchInput { moveBy(Offset(-step, 0f), delayMillis = 100) }
            compose.runOnIdle {
                org.junit.Assert.assertEquals("covered list width", listBefore.size.width, listText.get().size.width)
                org.junit.Assert.assertEquals("covered list lines", listBefore.lineCount, listText.get().lineCount)
                org.junit.Assert.assertTrue("detail expands", detailText.get().size.width > detailBefore.size.width)
            }
        }
        capture("adaptive-drag-covering.png")
        val expandedDetail = detailText.get()
        repeat(4) {
            compose.onRoot().performTouchInput { moveBy(Offset(step, 0f), delayMillis = 100) }
            compose.runOnIdle {
                org.junit.Assert.assertEquals("outgoing detail width", expandedDetail.size.width, detailText.get().size.width)
                org.junit.Assert.assertEquals("outgoing detail lines", expandedDetail.lineCount, detailText.get().lineCount)
            }
        }
        capture("adaptive-drag-outgoing.png")
        val widenedList = listText.get()
        compose.onRoot().performTouchInput { moveBy(Offset(-step, 0f), delayMillis = 100) }
        compose.runOnIdle {
            org.junit.Assert.assertTrue("list follows reversal toward its split", listText.get().size.width < widenedList.size.width)
            org.junit.Assert.assertTrue("list has not passed its split", listText.get().size.width >= listBefore.size.width)
        }
        compose.onRoot().performTouchInput { moveBy(Offset(-step * 3, 0f), delayMillis = 300) }
        repeat(2) {
            compose.runOnIdle {
                org.junit.Assert.assertEquals("reversed list stops at default split", listBefore.size.width, listText.get().size.width)
                org.junit.Assert.assertEquals("reversed list restores original lines", listBefore.lineCount, listText.get().lineCount)
            }
            compose.onRoot().performTouchInput { moveBy(Offset(-step / 2, 0f), delayMillis = 100) }
        }
        capture("adaptive-drag-reversed.png")
        compose.onRoot().performTouchInput { moveTo(Offset(width.toFloat(), divider.y), delayMillis = 700); up() }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("detail-pane-back")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("detail-pane-back").assertDoesNotExist()
        compose.onNodeWithText("Open detail").assertIsDisplayed()

        // From expanded detail, returning to the middle anchor must restore a usable split.
        compose.onNodeWithText("Open detail").performClick()
        compose.waitForIdle()
        val listBeforeHiding = listText.get()
        compose.onNodeWithTag("detail-pane-divider").performTouchInput {
            swipe(center, Offset(center.x - 1500f, center.y), 700)
        }
        compose.onNodeWithText("Open detail").assertIsNotDisplayed()
        val fullDetail = detailText.get()
        val edgeDivider = compose.onNodeWithTag("detail-pane-divider").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(edgeDivider) }
        repeat(4) {
            compose.onRoot().performTouchInput { moveBy(Offset(step, 0f), delayMillis = 100) }
            compose.runOnIdle {
                org.junit.Assert.assertEquals("uncovered list width", listBeforeHiding.size.width, listText.get().size.width)
                org.junit.Assert.assertEquals("uncovered list lines", listBeforeHiding.lineCount, listText.get().lineCount)
                org.junit.Assert.assertEquals("sliding expanded detail width", fullDetail.size.width, detailText.get().size.width)
            }
        }
        capture("adaptive-drag-uncovering.png")
        compose.onRoot().performTouchInput {
            moveTo(Offset(divider.x, edgeDivider.y), delayMillis = 400)
            advanceEventTime(400)
            up()
        }
        compose.runOnIdle {
            org.junit.Assert.assertTrue("settled detail fits its pane", detailText.get().size.width < expandedDetail.size.width)
            org.junit.Assert.assertEquals("settled split has equal text widths", listText.get().size.width, detailText.get().size.width)
        }
    }

    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun restoringSavedStateRestoresSelectionAndFormInEitherPresentation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Fixture() }
        compose.onNodeWithText("Item 0").performClick()
        compose.onNodeWithTag("draft").performTextInput("retained preference")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("draft").assertTextEquals("retained preference")
        compose.runOnIdle { wide.value = false }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("sheet").assertIsDisplayed()
        compose.onNodeWithTag("draft").assertTextEquals("retained preference")
    }

    @Composable
    private fun Fixture() {
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        val directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2()).copy(
            maxHorizontalPartitions = if (wide.value) 2 else 1,
        )
        MaterialTheme {
            Box(Modifier.requiredWidth(if (wide.value) 1200.dp else 420.dp).fillMaxHeight()) {
                AdaptiveDetailLayout(
                    selectedKey = selected,
                    directive = directive,
                    onDismiss = { selected = null },
                    detail = {
                        var draft by rememberSaveable { mutableStateOf("") }
                        AdaptiveDetailSheet(onDismissRequest = { selected = null }) {
                            Text(if (LocalIsDetailPane.current) "pane" else "sheet")
                            OutlinedTextField(draft, { draft = it }, Modifier.testTag("draft"))
                            Button(onClick = { selected = null }) { Text("Dismiss detail") }
                        }
                    },
                ) {
                    val state = rememberLazyListState()
                    SideEffect { listIndex.set(state.firstVisibleItemIndex) }
                    LazyColumn(Modifier.testTag("list"), state = state) {
                        items((0..80).toList(), key = { it }) { index ->
                            Button(
                                onClick = { selected = index.toString() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Item $index") }
                        }
                    }
                }
            }
        }
    }
}
