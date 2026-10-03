package net.extrawdw.apps.notisync.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.run.RunKey
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses synthetic history on a disposable emulator. Models a visible, unfocused window. */
@RunWith(AndroidJUnit4::class)
class VisibleRunLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun historyAndSelectedDetailUpdateWhileStarted() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NotiSyncApp
        compose.waitUntil(15_000) { app.startupState.value.stage.name == "READY" }
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        val now = System.currentTimeMillis()
        val state = RunState(
            hostClientId = ClientId("adaptive-test-host"),
            runId = "adaptive-test-$now",
            revision = 1,
            phase = RunPhase.COMPLETED,
            updateReason = RunUpdateReason.COMPLETED,
            startedAt = now,
            updatedAt = now,
            endedAt = now,
            exitCode = 0,
            argv = listOf("adaptive-before-$now"),
            cwd = "/test",
            usesPty = false,
            terminal = RunTerminalSnapshot("", false, 0),
        )
        app.graph.runStore.apply(state)
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    RunScreen(initialSelection = RunKey(state.hostClientId.value, state.runId))
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("adaptive-before-$now").fetchSemanticsNodes().size >= 2
        }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        app.graph.runStore.apply(state.copy(revision = 2, argv = listOf("adaptive-after-$now")))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("adaptive-after-$now").fetchSemanticsNodes().size >= 2
        }
        // The shared pane header keeps the revision selector functional and shows that snapshot's title.
        compose.onNodeWithContentDescription(app.getString(R.string.run_revision_history_action)).performClick()
        val firstRevision = app.getString(R.string.run_revision_item, 1)
        compose.waitUntil(5_000) { compose.onNodeWithText(firstRevision).isDisplayed() }
        compose.onNodeWithText(firstRevision).performClick()
        compose.waitUntil(5_000) {
            compose.onNode(hasTestTag("run-detail-title") and hasText("adaptive-before-$now")).isDisplayed()
        }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        java.io.File(app.getExternalFilesDir(null), "run-pane-header.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("detail-pane-back").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("adaptive-before-$now").fetchSemanticsNodes().isEmpty()
        }
    }
}
