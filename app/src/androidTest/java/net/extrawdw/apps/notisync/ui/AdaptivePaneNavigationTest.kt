package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.apps.notisync.navigation.AppNavigationShell
import net.extrawdw.apps.notisync.navigation.MenuConfiguration
import net.extrawdw.apps.notisync.navigation.NonBouncyModalNavigationDrawer
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run at the current narrow-tablet profile: 2364x1672 px, 440 dpi (about 860 dp wide). */
@RunWith(AndroidJUnit4::class)
class AdaptivePaneNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun contentSwipesExpandRestoreAndDismissBeforeDrawerGestures() {
        lateinit var drawer: DrawerState
        compose.setContent {
            NotiSyncTheme {
                drawer = rememberDrawerState(DrawerValue.Closed)
                NonBouncyModalNavigationDrawer(
                    enabled = true,
                    drawerState = drawer,
                    gesturesEnabled = true,
                    drawerContent = { modifier -> ModalDrawerSheet(modifier) { Text("Feature drawer") } },
                ) {
                    PaneFixture()
                }
            }
        }
        compose.onNodeWithText("Open detail").performClick()
        val split = compose.onNodeWithTag("detail-pane-divider").getUnclippedBoundsInRoot()
        val listText = compose.onNodeWithTag("list-text").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("pane-body").performTouchInput { swipeUp() }
        compose.onNodeWithText("Open detail").assertIsDisplayed()
        assertEquals(split, compose.onNodeWithTag("detail-pane-divider").getUnclippedBoundsInRoot())
        compose.runOnIdle { assertTrue(drawer.isClosed) }

        // Inspect the layout before lifting the finger: content must track each delta rather
        // than wait for release, and body drags must not put the handle into its pressed shape.
        compose.onNodeWithTag("pane-region").performTouchInput {
            down(Offset(width * .82f, height * .6f))
            moveTo(Offset(width * .70f, height * .6f), 200)
        }
        val firstMove = compose.onNodeWithTag("detail-pane-divider").getUnclippedBoundsInRoot()
        assertTrue(firstMove.left < split.left - 40.dp)
        assertTrue(firstMove.left > 0.dp)
        assertEquals(split.right - split.left, firstMove.right - firstMove.left)
        assertEquals(split.bottom - split.top, firstMove.bottom - firstMove.top)
        compose.onNodeWithTag("pane-region").performTouchInput {
            moveTo(Offset(width * .60f, height * .6f), 200)
        }
        val secondMove = compose.onNodeWithTag("detail-pane-divider").getUnclippedBoundsInRoot()
        val region = compose.onNodeWithTag("pane-region").getUnclippedBoundsInRoot()
        assertEquals((region.right - region.left).value * .1f, (firstMove.left - secondMove.left).value, 1f)
        compose.onNodeWithTag("pane-region").performTouchInput {
            moveTo(Offset(width * .24f, height * .6f), 400)
            advanceEventTime(200)
            moveTo(Offset(width * .24f, height * .6f))
            up()
        }
        compose.onNodeWithText("Open detail").assertIsNotDisplayed()
        compose.runOnIdle { assertTrue(drawer.isClosed) }
        dragPane(.2f, .7f)
        compose.onNodeWithText("Open detail").assertIsDisplayed()
        val restored = compose.onNodeWithTag("detail-pane-divider").getUnclippedBoundsInRoot()
        assertEquals(split, restored)
        assertEquals(listText, compose.onNodeWithTag("list-text").getUnclippedBoundsInRoot())
        assertEquals((region.left + region.right).value / 2, (restored.left + restored.right).value / 2, 1f)
        compose.runOnIdle { assertTrue(drawer.isClosed) }
        dragPane(.6f, .98f)
        compose.onNodeWithTag("pane-body").assertDoesNotExist()
        compose.runOnIdle { assertTrue(drawer.isClosed) }

        // Once the detail is gone, the drawer retains its ordinary swipe behavior.
        compose.onNodeWithTag("pane-region").performTouchInput {
            swipe(Offset(width * .2f, height * .5f), Offset(width * .8f, height * .5f), 500)
        }
        compose.runOnIdle { assertTrue(drawer.isOpen) }
    }

    @Test
    fun expandedRailOverlaysWithoutChangingTheDetailPresentation() {
        compose.setContent {
            NotiSyncTheme {
                AppNavigationShell(
                    menuConfiguration = MenuConfiguration(),
                    currentDestination = null,
                    openMenuDestination = null,
                    onNavigationRequestStarted = {},
                    onOpenMenuDestinationConsumed = {},
                    onNavigate = {},
                    openMenu = {},
                    openAbout = {},
                ) { _, _ -> PaneFixture() }
            }
        }
        compose.onNodeWithText("Open detail").performClick()
        compose.onNodeWithText("Inline detail").assertIsDisplayed()
        val before = compose.onNodeWithTag("pane-region").getUnclippedBoundsInRoot()
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithContentDescription("Close menu").assertIsDisplayed()
        compose.onNodeWithText("Sheet detail").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag("pane-region").getUnclippedBoundsInRoot())
        compose.onNodeWithContentDescription("Close menu").performClick()
        compose.onNodeWithText("Inline detail").assertIsDisplayed()
        compose.onNodeWithTag("detail-pane-divider").assertIsDisplayed()
    }

    private fun dragPane(start: Float, end: Float) {
        compose.onNodeWithTag("pane-region").performTouchInput {
            down(Offset(width * start, height * .6f))
            repeat(20) { step ->
                moveTo(Offset(width * (start + (end - start) * (step + 1) / 20), height * .6f), 25)
            }
            advanceEventTime(200)
            moveTo(Offset(width * end, height * .6f))
            up()
        }
    }

    @Composable
    private fun PaneFixture() {
        var selected by remember { mutableStateOf<String?>(null) }
        AdaptiveDetailLayout(
                    detailState = Unit,
            modifier = Modifier.fillMaxSize().testTag("pane-region"),
            selectedKey = selected,
            onDismiss = { selected = null },
            detail = {
                AdaptiveDetailSheet(onDismissRequest = { selected = null }) {
                    Text(if (LocalIsDetailPane.current) "Inline detail" else "Sheet detail")
                    Column(Modifier.fillMaxSize().testTag("pane-body").verticalScroll(rememberScrollState())) {
                        repeat(30) { Text("Detail row $it", Modifier.height(48.dp)) }
                    }
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                Button(onClick = { selected = "example" }) { Text("Open detail") }
                Text("Device names and verification details should wrap identically after restoring the split. ".repeat(4),
                    Modifier.testTag("list-text"))
            }
        }
    }
}
