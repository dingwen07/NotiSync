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
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import net.extrawdw.apps.notisync.navigation.AppNavigationState
import net.extrawdw.apps.notisync.navigation.rememberAppNavigationState
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import net.extrawdw.apps.notisync.run.RunKey
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun runRequestsReceivedAfterEntryCreationReachTheDestination() {
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
        lateinit var navigation: AppNavigationState
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            navigation = rememberAppNavigationState()
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            NavigationFixture(navigation)
        }
        compose.runOnIdle { navigation.navigateToTopLevel(AppDestination.APPS) }
        compose.onNodeWithText("App selection 0").performClick()
        compose.runOnIdle { navigation.navigate(Route.Menu()) }
        compose.runOnIdle { navigation.navigate(Route.About()) }
        compose.runOnIdle { navigation.navigateToTopLevel(AppDestination.DEVICES) }
        compose.onNodeWithText("Devices page").assertIsDisplayed()

        compose.runOnIdle { navigation.navigateToTopLevel(AppDestination.APPS) }
        compose.onNodeWithText("App selection 1").assertIsDisplayed()
        compose.runOnIdle {
            back.onBackPressed()
            assertEquals(Route.Devices, navigation.currentRoute)
        }
        compose.onNodeWithText("Devices page").assertIsDisplayed()
    }

    @Test
    fun inactiveTabAndUtilityHistorySurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        lateinit var navigation: AppNavigationState
        restoration.setContent {
            navigation = rememberAppNavigationState()
            NavigationFixture(navigation)
        }
        compose.runOnIdle { navigation.navigateToTopLevel(AppDestination.APPS) }
        compose.onNodeWithText("App selection 0").performClick()
        compose.runOnIdle { navigation.navigateToTopLevel(AppDestination.SETTINGS) }
        val menu = Route.Menu()
        val about = Route.About()
        compose.runOnIdle { navigation.navigate(menu) }
        compose.runOnIdle { navigation.navigate(about) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("About page").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(about, navigation.currentRoute)
            navigation.goBack()
            assertEquals(menu, navigation.currentRoute)
        }
        compose.onNodeWithText("Menu page").assertIsDisplayed()
        compose.runOnIdle { navigation.goBack() }
        compose.onNodeWithText("Settings page").assertIsDisplayed()
        compose.runOnIdle { navigation.navigateToTopLevel(AppDestination.APPS) }
        compose.onNodeWithText("App selection 1").assertIsDisplayed()
    }

    @Test
    fun repeatedUtilityVisitsHaveIndependentStateAndSingleTopBehavior() {
        lateinit var navigation: AppNavigationState
        compose.setContent {
            navigation = rememberAppNavigationState()
            NavigationFixture(navigation)
        }
        compose.runOnIdle { navigation.navigate(Route.Menu()) }
        compose.onNodeWithText("Menu selection 0").performClick()
        val firstMenu = navigation.currentRoute
        compose.runOnIdle { navigation.navigate(Route.About()) }
        compose.runOnIdle { navigation.navigate(Route.Menu()) }
        compose.onNodeWithText("Menu selection 0").assertIsDisplayed()
        compose.runOnIdle {
            val secondMenu = navigation.currentRoute
            navigation.navigate(Route.Menu())
            assertEquals(secondMenu, navigation.currentRoute)
            navigation.goBack()
        }
        compose.onNodeWithText("About page").assertIsDisplayed()
        compose.runOnIdle { navigation.goBack() }
        compose.onNodeWithText("Menu selection 1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(firstMenu, navigation.currentRoute) }
    }

    @Test
    fun viewModelsStayWithTheirEntriesAndClearWhenUtilityIsPopped() {
        lateinit var navigation: AppNavigationState
        val models = mutableMapOf<Route, EntryViewModel>()
        compose.setContent {
            navigation = rememberAppNavigationState()
            NavigationFixture(navigation, models)
        }
        compose.runOnIdle { navigation.navigateToTopLevel(AppDestination.APPS) }
        val appsModel = compose.runOnIdle { models.getValue(Route.Apps) }
        compose.runOnIdle { navigation.navigateToTopLevel(AppDestination.SETTINGS) }
        compose.runOnIdle {
            assertNotSame(appsModel, models.getValue(Route.Settings))
            assertFalse(appsModel.cleared)
            navigation.navigateToTopLevel(AppDestination.APPS)
        }
        compose.runOnIdle { assertSame(appsModel, models.getValue(Route.Apps)) }
        val menu = Route.Menu()
        compose.runOnIdle { navigation.navigate(menu) }
        val menuModel = compose.runOnIdle { models.getValue(menu) }
        compose.runOnIdle { navigation.goBack() }
        compose.runOnIdle {
            assertTrue(menuModel.cleared)
            assertFalse(appsModel.cleared)
        }
    }

    @Composable
    private fun NavigationFixture(
        navigation: AppNavigationState,
        models: MutableMap<Route, EntryViewModel> = mutableMapOf(),
    ) {
        NotiSyncTheme {
            NavDisplay(
                entries = navigation.toEntries(entryProvider {
                    // All tabs have entries even before they are selected.
                    AppDestination.entries.forEach { destination ->
                        entry(destination.route) {
                            models[destination.route] = viewModel { EntryViewModel() }
                            when (destination) {
                                AppDestination.DEVICES -> Text("Devices page")
                                AppDestination.APPS -> Counter("App selection")
                                AppDestination.SETTINGS -> Text("Settings page")
                                else -> Text(destination.name)
                            }
                        }
                    }
                    entry<Route.Menu> { route ->
                        models[route] = viewModel { EntryViewModel() }
                        androidx.compose.foundation.layout.Column {
                            Text("Menu page")
                            Counter("Menu selection")
                        }
                    }
                    entry<Route.About> { Text("About page") }
                }),
                onBack = navigation::goBack,
            )
        }
    }

    @Composable
    private fun Counter(label: String) {
        var count by rememberSaveable { mutableIntStateOf(0) }
        Button(onClick = { count++ }) { Text("$label $count") }
    }

    private class EntryViewModel : ViewModel() {
        var cleared = false
            private set

        override fun onCleared() { cleared = true }
    }
}
