package net.extrawdw.apps.notisync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import net.extrawdw.apps.notisync.pairing.PairingDeepLinks
import net.extrawdw.apps.notisync.ui.ActivityScreen
import net.extrawdw.apps.notisync.ui.AppsScreen
import net.extrawdw.apps.notisync.ui.DevicesScreen
import net.extrawdw.apps.notisync.ui.PairingOverlay
import net.extrawdw.apps.notisync.ui.PermissionState
import net.extrawdw.apps.notisync.ui.SettingsScreen
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme

class MainActivity : ComponentActivity() {
    private val pendingPairingPayload = MutableStateFlow<String?>(null)
    private val pendingOpenDevices = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePendingPairingPayload(intent)
        consumeOpenDevices(intent)
        enableEdgeToEdge()
        setContent {
            val pairingPayload by pendingPairingPayload.collectAsStateWithLifecycle()
            val openDevices by pendingOpenDevices.collectAsStateWithLifecycle()
            NotiSyncTheme {
                NotiSyncRoot(
                    pendingPairingPayload = pairingPayload,
                    onPendingPairingPayloadConsumed = { pendingPairingPayload.value = null },
                    openDevices = openDevices,
                    onOpenDevicesConsumed = { pendingOpenDevices.value = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updatePendingPairingPayload(intent)
        consumeOpenDevices(intent)
    }

    /** A trust notification asked us to open the Devices tab. */
    private fun consumeOpenDevices(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_DEVICES, false) != true) return
        pendingOpenDevices.value = true
        intent.removeExtra(EXTRA_OPEN_DEVICES) // consume so a config change / Recents can't re-trigger it
    }

    companion object {
        const val EXTRA_OPEN_DEVICES = "net.extrawdw.apps.notisync.OPEN_DEVICES"
    }

    private fun updatePendingPairingPayload(intent: Intent?) {
        intent ?: return
        // Reopening the app from the Recents list re-delivers the task's base intent — for a
        // QR-launched task that's the original pairing deep link. Ignore it, otherwise every
        // return-from-Recents would surface the trust dialog again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val payload = PairingDeepLinks.payloadFrom(intent.data) ?: return
        pendingPairingPayload.value = payload
        // Consume the link so the same intent can't re-trigger pairing on a later recreation
        // (e.g. a configuration change, which restarts the activity with this same intent).
        intent.data = null
    }
}

/** Type-safe (serializable) navigation routes — the single source of truth for the back stack. */
private sealed interface Route {
    @Serializable data object Devices : Route
    @Serializable data object Apps : Route
    @Serializable data object Activity : Route
    @Serializable data object Settings : Route
}

/** The navigation-suite (bottom bar / rail / drawer) destinations, in display order. */
private enum class TopLevelDestination(val route: Route, @param:StringRes val label: Int, val icon: ImageVector) {
    DEVICES(Route.Devices, R.string.tab_devices, Icons.Outlined.Devices),
    APPS(Route.Apps, R.string.tab_apps, Icons.Outlined.Apps),
    ACTIVITY(Route.Activity, R.string.tab_activity, Icons.Outlined.History),
    SETTINGS(Route.Settings, R.string.tab_settings, Icons.Outlined.Settings),
}

@Composable
fun NotiSyncRoot(
    pendingPairingPayload: String? = null,
    onPendingPairingPayloadConsumed: () -> Unit = {},
    openDevices: Boolean = false,
    onOpenDevicesConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // Pairing is a state-driven overlay rather than a nav destination, so it can expand out of — and
    // collapse back into — the "Pair a device" stripe with a predictive-back-driven container transform
    // (see PairingOverlay). The stripe reports its live position here; the overlay renders above the
    // whole navigation suite so the Devices tab (and bar) stay visible as the page folds away.
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var pairButtonBounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(pendingPairingPayload) {
        if (pendingPairingPayload != null) showPairing = true
    }

    LaunchedEffect(openDevices) {
        if (openDevices) {
            navController.navigateToTopLevel(TopLevelDestination.DEVICES)
            onOpenDevicesConsumed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavigationSuiteScaffold(
            layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo()),
            navigationSuiteItems = {
                TopLevelDestination.entries.forEach { dest ->
                    item(
                        selected = currentDestination.isOn(dest),
                        onClick = { navController.navigateToTopLevel(dest) },
                        icon = { Icon(dest.icon, contentDescription = stringResource(dest.label)) },
                        label = { Text(stringResource(dest.label)) },
                    )
                }
            },
        ) {
            NavHost(
                navController = navController,
                startDestination = Route.Devices,
                modifier = Modifier.fillMaxSize(),
                // Top-level tabs swap instantly. Selecting a non-start tab is a push (enter/exit);
                // selecting the start destination (Devices) is a pop, so the pop transitions must be
                // None as well — otherwise Devices alone would slide while the others cut.
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable<Route.Devices> {
                    DevicesDestination(
                        onPair = { showPairing = true },
                        // Sample the stripe's bounds (root coordinates, shared with the overlay) so the
                        // container transform knows where to grow from / fold back into. It moves as the
                        // list scrolls; the last value before opening is what the collapse animates to.
                        pairButtonModifier = Modifier.onGloballyPositioned {
                            pairButtonBounds = it.boundsInRoot()
                        },
                    )
                }
                composable<Route.Apps> { AppsScreen() }
                composable<Route.Activity> { ActivityScreen() }
                composable<Route.Settings> { SettingsScreen() }
            }
        }

        if (showPairing) {
            PairingOverlay(
                pairButtonBounds = pairButtonBounds,
                onClose = { showPairing = false },
                initialPairingPayload = pendingPairingPayload,
                onInitialPairingPayloadConsumed = onPendingPairingPayloadConsumed,
            )
        }
    }
}

/**
 * Switch tabs the Now-in-Android way: save the outgoing tab's nested state, restore the incoming
 * tab's, and keep a single copy on the back stack so System Back from any tab returns to the start
 * destination (and from the start destination, exits).
 */
private fun NavController.navigateToTopLevel(dest: TopLevelDestination) {
    navigate(dest.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavDestination?.isOn(dest: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(dest.route::class) } == true

/** Hosts the permission/launcher plumbing the Devices screen needs, scoped to that destination. */
@Composable
private fun DevicesDestination(onPair: () -> Unit, pairButtonModifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Re-check permissions whenever Devices returns to the foreground (e.g. back from system settings).
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val permissions = remember(refresh) { readPermissions(context) }

    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    DevicesScreen(
        permissions = permissions,
        onPair = onPair,
        pairButtonModifier = pairButtonModifier,
        onRequestPostNotifications = {
            postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onOpenListenerSettings = {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
    )
}

private fun readPermissions(context: Context): PermissionState {
    val listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    val postGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    return PermissionState(listenerEnabled = listenerEnabled, postNotificationsGranted = postGranted)
}
