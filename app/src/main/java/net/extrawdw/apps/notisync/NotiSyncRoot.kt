package net.extrawdw.apps.notisync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.navigation.AppMenuShortcuts
import net.extrawdw.apps.notisync.navigation.AppNavigationHost
import net.extrawdw.apps.notisync.navigation.AppNavigationShell
import net.extrawdw.apps.notisync.navigation.rememberAppNavigationState
import net.extrawdw.apps.notisync.navigation.MenuDestination
import net.extrawdw.apps.notisync.pairing.PairingHost
import net.extrawdw.apps.notisync.run.RunKey
import net.extrawdw.apps.notisync.ui.rememberDevicesPairingDirective
import net.extrawdw.apps.notisync.ui.rememberGraph

@Composable
fun NotiSyncRoot(
    openMenuDestination: MenuDestination? = null,
    onOpenMenuDestinationConsumed: () -> Unit = {},
    pendingPairingPayload: String? = null,
    onPendingPairingPayloadConsumed: () -> Unit = {},
    pendingHcePairingPayload: String? = null,
    onPendingHcePairingPayloadConsumed: (String) -> Unit = {},
    openDevices: Boolean = false,
    onOpenDevicesConsumed: () -> Unit = {},
    openDeviceDetails: String? = null,
    onOpenDeviceDetailsConsumed: () -> Unit = {},
    openRun: RunKey? = null,
    onOpenRunConsumed: () -> Unit = {},
    openSshHistoryRequestId: String? = null,
    onOpenSshHistoryConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val graph = rememberGraph()
    val menuConfiguration by graph.settings.menuConfiguration.collectAsStateWithLifecycle()
    val navigation = rememberAppNavigationState()
    val currentDestination = navigation.currentRoute
    val pairingPaneDirective = rememberDevicesPairingDirective()
    val usePairingPane = pairingPaneDirective.maxHorizontalPartitions > 1
    val configuration = LocalConfiguration.current
    var foregroundResumeGeneration by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        foregroundResumeGeneration += 1
        onPauseOrDispose { }
    }

    // Refresh labels after locale changes and retry any background rate-limited update on foregrounding.
    LaunchedEffect(configuration.locales.toLanguageTags(), foregroundResumeGeneration) {
        withContext(Dispatchers.IO) {
            runCatching { AppMenuShortcuts.update(context, graph.settings.menuConfiguration.value) }
                .onFailure { android.util.Log.w("MainActivity", "Failed to refresh launcher shortcuts", it) }
        }
    }
    PairingHost(
        graph = graph,
        pendingPairingPayload = pendingPairingPayload,
        onPendingPairingPayloadConsumed = onPendingPairingPayloadConsumed,
        pendingHcePairingPayload = pendingHcePairingPayload,
        onPendingHcePairingPayloadConsumed = onPendingHcePairingPayloadConsumed,
        foregroundResumeGeneration = foregroundResumeGeneration,
        usePairingPane = usePairingPane,
        onOpenDevices = { navigation.navigateToTopLevel(AppDestination.DEVICES) },
    ) { pairing ->
        LaunchedEffect(openDevices) {
            if (openDevices) {
                navigation.navigateToTopLevel(AppDestination.DEVICES)
                onOpenDevicesConsumed()
            }
        }

        LaunchedEffect(openDeviceDetails) {
            if (openDeviceDetails != null) {
                pairing.close()
                navigation.navigateToTopLevel(AppDestination.DEVICES)
            }
        }

        LaunchedEffect(openRun) {
            if (openRun != null) {
                // Pairing is not a navigation destination, so changing tabs alone leaves it drawn above Run.
                // A notification open is explicit navigation: dismiss the overlay before selecting the Run tab.
                pairing.isOpen = pairingOverlayAfterRunOpenRequest(pairing.isOpen, openRun)
                navigation.navigateToTopLevel(AppDestination.RUN)
            }
        }

        LaunchedEffect(openSshHistoryRequestId) {
            if (openSshHistoryRequestId != null) {
                pairing.close()
                navigation.navigateToTopLevel(AppDestination.SSH_AGENT)
            }
        }

        LaunchedEffect(currentDestination, usePairingPane) {
            if (usePairingPane && !currentDestination.isOn(AppDestination.DEVICES)) {
                pairing.close()
            }
        }
        AppNavigationShell(
            menuConfiguration = menuConfiguration,
            currentDestination = currentDestination,
            openMenuDestination = openMenuDestination,
            onNavigationRequestStarted = pairing::close,
            onOpenMenuDestinationConsumed = onOpenMenuDestinationConsumed,
            onNavigate = navigation::navigateToTopLevel,
            openMenu = { navigation.navigate(Route.Menu()) },
            openAbout = { navigation.navigate(Route.About()) },
        ) { isRail, navigationLimit ->
            AppNavigationHost(
                navigation = navigation,
                pairing = pairing,
                pairingPaneDirective = pairingPaneDirective,
                isRail = isRail,
                navigationLimit = navigationLimit,
                openDeviceDetails = openDeviceDetails,
                onOpenDeviceDetailsConsumed = onOpenDeviceDetailsConsumed,
                openRun = openRun,
                onOpenRunConsumed = onOpenRunConsumed,
                openSshHistoryRequestId = openSshHistoryRequestId,
                onOpenSshHistoryConsumed = onOpenSshHistoryConsumed,
            )
        }
    }
}

internal fun pairingOverlayAfterRunOpenRequest(currentlyVisible: Boolean, openRun: RunKey?): Boolean =
    currentlyVisible && openRun == null
