package net.extrawdw.apps.notisync.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.apps.notisync.MainActivity
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Activity recreation with production screens, using only synthetic emulator input. */
@RunWith(AndroidJUnit4::class)
class FoldableRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val testName = org.junit.rules.TestName()

    @After
    fun captureTestScreen() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), testName.methodName + ".png")
            .outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
    }

    @Before
    fun finishTestDeviceOnboarding() {
        val app = compose.activity.application as NotiSyncApp
        compose.waitUntil(15_000) { app.startupState.value.stage.name == "READY" }
        kotlinx.coroutines.runBlocking { app.graph.settings.setOnboardingCompleted() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.tab_apps))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun selectedAppSettingsSurviveActivityRecreation() {
        val selection = (compose.activity.application as NotiSyncApp).graph.appSelection!!
        val wasEnabled = selection.isEnabled("com.android.chrome")
        try {
            compose.runOnIdle { selection.setEnabled("com.android.chrome", true) }
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.tab_apps)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Chrome").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasSetTextAction()).assertIsNotFocused()
            compose.onAllNodesWithText("Chrome")[0].performClick()
            val setting = compose.activity.getString(R.string.app_config_ring_calls_title)
            compose.onNodeWithText(setting).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(setting).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(setting).assertIsDisplayed()
            if (compose.onAllNodesWithTag("detail-pane-back").fetchSemanticsNodes().isNotEmpty()) {
                // In a pane, the list must remain usable even when the permission notice is taller
                // than the available list viewport in a short landscape window.
                compose.onNodeWithTag("apps-list").performScrollToNode(hasText("Chrome"))
                compose.onAllNodes(hasText("Chrome") and hasAnyAncestor(hasTestTag("apps-list")))[0].assertIsDisplayed()
                // Regression for closing a real Material ListItem-based Apps pane: the old
                // list-detail -> single-scene transition crashed in LookaheadDelegate.
                repeat(3) {
                    compose.onNodeWithTag("detail-pane-back").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithTag("detail-pane-back").fetchSemanticsNodes().isEmpty() }
                    compose.onAllNodes(hasText("Chrome") and hasAnyAncestor(hasTestTag("apps-list")))[0].performClick()
                    compose.waitUntil(5_000) { compose.onNodeWithTag("detail-pane-back").isDisplayed() }
                }
            }
        } finally {
            selection.setEnabled("com.android.chrome", wasEnabled)
        }
    }

    @Test
    fun sensitiveImportDraftSurvivesRecreationInMemory() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.open_features)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.ssh_key_provider_tools_label)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.action_paste)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.action_paste)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("synthetic-private-key-draft")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { compose.onNodeWithText("synthetic-private-key-draft").isDisplayed() }
        compose.onNodeWithText("synthetic-private-key-draft").assertIsDisplayed()
        // Explicit dismissal clears the draft; reopening must start empty.
        compose.onNodeWithText(compose.activity.getString(R.string.action_cancel)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.action_paste)).performClick()
        compose.onNodeWithText("synthetic-private-key-draft").assertDoesNotExist()
    }

}
