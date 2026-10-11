package net.extrawdw.apps.notisync

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import net.extrawdw.apps.notisync.run.RunKey
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun runRequestsReceivedAfterGraphCreationReachTheDestination() {
        val app = ApplicationProvider.getApplicationContext<NotiSyncApp>()
        compose.waitUntil(15_000) { app.startupState.value.stage == AppStartupStage.READY }
        val request = mutableStateOf<RunKey?>(null)
        val consumed = CopyOnWriteArrayList<RunKey>()
        compose.setContent {
            NotiSyncTheme {
                NotiSyncRoot(
                    openRun = request.value,
                    onOpenRunConsumed = {
                        consumed += requireNotNull(request.value)
                        request.value = null
                    },
                )
            }
        }
        compose.waitForIdle()

        // Synthetic missing records exercise the handoff without executing or modifying a Run.
        val first = RunKey("navigation-test", "first")
        val second = RunKey("navigation-test", "second")
        compose.runOnIdle { request.value = first }
        compose.waitUntil(10_000) { consumed.size == 1 }
        compose.runOnIdle { request.value = second }
        compose.waitUntil(10_000) { consumed.size == 2 }
        compose.waitForIdle()
        assertEquals(listOf(first, second), consumed.toList())
    }

    @Test
    fun utilityPagesDoNotReplaceDevicesOrLoseSavedTabState() {
        lateinit var navController: NavHostController
        compose.setContent {
            navController = rememberNavController()
            NotiSyncTheme {
                NavHost(navController, startDestination = Route.Devices) {
                    composable<Route.Devices> { Text("Devices page") }
                    composable<Route.Apps> {
                        var count by rememberSaveable { mutableIntStateOf(0) }
                        Button(onClick = { count++ }) { Text("App selection $count") }
                    }
                    composable<Route.Menu> { Text("Menu page") }
                    composable<Route.About> { Text("About page") }
                }
            }
        }
        compose.runOnIdle { navController.navigateToTopLevel(AppDestination.APPS) }
        compose.onNodeWithText("App selection 0").performClick()
        compose.runOnIdle { navController.navigate(Route.Menu) }
        compose.runOnIdle { navController.navigate(Route.About) }
        compose.runOnIdle { navController.navigateToTopLevel(AppDestination.DEVICES) }
        compose.onNodeWithText("Devices page").assertIsDisplayed()

        compose.runOnIdle { navController.navigateToTopLevel(AppDestination.APPS) }
        compose.onNodeWithText("App selection 1").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(navController.popBackStack())
            assertTrue(navController.currentDestination?.hasRoute<Route.Devices>() == true)
        }
        compose.onNodeWithText("Devices page").assertIsDisplayed()
    }
}
