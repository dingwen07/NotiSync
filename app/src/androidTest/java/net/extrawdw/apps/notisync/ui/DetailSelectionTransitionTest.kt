package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailSelectionTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectionAnimatesOnlyDetailsAndSameRecordUpdatesKeepState() {
        val selection = mutableStateOf("Device A")
        val revision = mutableStateOf(0)
        var compositions = 0
        compose.setContent {
            NotiSyncTheme {
                AdaptiveDetailLayout(
                    selectedKey = selection.value,
                    detailState = selection.value to revision.value,
                    paneTitle = selection.value,
                    onDismiss = {},
                    detail = { (name, version) ->
                        val instance = remember { ++compositions }
                        AdaptiveDetailSheet(onDismissRequest = {}) {
                            Box(Modifier.fillMaxSize().testTag("record-$name")) {
                                Text("$name version $version instance $instance", Modifier.size(200.dp))
                            }
                        }
                    },
                ) { Text("List stays still", Modifier.testTag("list")) }
            }
        }
        val listBounds = compose.onNodeWithTag("list").getUnclippedBoundsInRoot()
        val settledWidth = compose.onNodeWithTag("record-Device A").fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selection.value = "Device B" }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        // Both records and their own titles remain composed during fade-through.
        compose.onNodeWithText("Device A").assertExists()
        compose.onNodeWithText("Device B").assertExists()
        compose.onNodeWithText("Device A version 0 instance 1").assertExists()
        val incomingWidth = compose.onNodeWithTag("record-Device B").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Incoming detail is still scaling: $incomingWidth < $settledWidth", incomingWidth < settledWidth)
        assertEquals(listBounds, compose.onNodeWithTag("list").getUnclippedBoundsInRoot())
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("record-Device A").assertDoesNotExist()
        compose.onNodeWithText("Device B version 0 instance 2").assertIsDisplayed()
        // Async history loading or live device updates are not new selections.
        compose.runOnIdle { revision.value = 1 }
        compose.onNodeWithText("Device B version 1 instance 2").assertIsDisplayed()
        assertEquals(settledWidth, compose.onNodeWithTag("record-Device B").fetchSemanticsNode().boundsInRoot.width)
        // Interrupt a transition with another selection, then confirm old content is disposed.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selection.value = "Device C" }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { selection.value = "Device D" }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("record-Device B").assertDoesNotExist()
        compose.onNodeWithTag("record-Device C").assertDoesNotExist()
        compose.onNodeWithTag("record-Device D").assertIsDisplayed()
        assertEquals(listBounds, compose.onNodeWithTag("list").getUnclippedBoundsInRoot())
    }
}
