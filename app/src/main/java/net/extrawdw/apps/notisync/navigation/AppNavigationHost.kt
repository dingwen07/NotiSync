package net.extrawdw.apps.notisync.navigation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import net.extrawdw.apps.notisync.Route
import net.extrawdw.apps.notisync.pairing.PairingHostState
import net.extrawdw.apps.notisync.run.RunKey
import net.extrawdw.apps.notisync.screen.AndroidScreenMirrorActivity
import net.extrawdw.apps.notisync.ui.AboutScreen
import net.extrawdw.apps.notisync.ui.ActivityScreen
import net.extrawdw.apps.notisync.ui.AppsScreen
import net.extrawdw.apps.notisync.ui.DevicesPairingLayout
import net.extrawdw.apps.notisync.ui.DevicesScreen
import net.extrawdw.apps.notisync.ui.IosScreen
import net.extrawdw.apps.notisync.ui.MenuManagementScreen
import net.extrawdw.apps.notisync.ui.PermissionState
import net.extrawdw.apps.notisync.ui.RunScreen
import net.extrawdw.apps.notisync.ui.SealScreen
import net.extrawdw.apps.notisync.ui.SettingsScreen
import net.extrawdw.apps.notisync.ui.SshKeyProviderScreen

@Composable
internal fun AppNavigationHost(
    navController: NavHostController,
    pairing: PairingHostState,
    pairingPaneDirective: PaneScaffoldDirective,
    isRail: Boolean,
    navigationLimit: Int,
    openDeviceDetails: String?,
    onOpenDeviceDetailsConsumed: () -> Unit,
    openRun: RunKey?,
    onOpenRunConsumed: () -> Unit,
    openSshHistoryRequestId: String?,
    onOpenSshHistoryConsumed: () -> Unit,
) {
    // NavHost remembers the graph it builds. Keep the mutable notification handoff behind stable State
    // objects so the remembered Run destination observes the latest request instead of the value captured
    // when the graph was first created (normally null).
    val latestOpenRun = rememberUpdatedState(openRun)
    val latestOnOpenRunConsumed = rememberUpdatedState(onOpenRunConsumed)
    val latestOpenSshHistoryRequestId = rememberUpdatedState(openSshHistoryRequestId)
    val latestOnOpenSshHistoryConsumed = rememberUpdatedState(onOpenSshHistoryConsumed)
    val latestOpenDeviceDetails = rememberUpdatedState(openDeviceDetails)
    val latestOnOpenDeviceDetailsConsumed = rememberUpdatedState(onOpenDeviceDetailsConsumed)

    val latestPairing = rememberUpdatedState(pairing)
    val latestPairingPaneDirective = rememberUpdatedState(pairingPaneDirective)
    val latestIsRail = rememberUpdatedState(isRail)
    val latestNavigationLimit = rememberUpdatedState(navigationLimit)

    NavHost(
        navController = navController,
        startDestination = Route.Devices,
        modifier = Modifier.fillMaxSize(),
        // Normal pops inherit these; predictive back seeks the same fade-through motion.
        enterTransition = { AppNavigationTransitions.enter },
        exitTransition = { AppNavigationTransitions.exit },
        predictivePopEnterTransition = { AppNavigationTransitions.enter },
        predictivePopExitTransition = { AppNavigationTransitions.exit },
    ) {
        composable<Route.Devices> {
            val pairing = latestPairing.value
            DevicesPairingLayout(
                directive = latestPairingPaneDirective.value,
                showPairing = pairing.isOpen && !pairing.isQuarantined,
                onClosePairing = pairing::close,
                onPairingCandidate = pairing.onPairingCandidate,
                onBrokerPairingCandidate = pairing.onBrokerPairingCandidate,
                onBrokerPairing = pairing.onBrokerPairing,
            ) {
                DevicesDestination(
                    onPair = pairing::open,
                    pairingOpen = pairing.isOpen,
                    onDetailSelected = pairing::close,
                    openDeviceDetails = latestOpenDeviceDetails.value,
                    onOpenDeviceDetailsConsumed = latestOnOpenDeviceDetailsConsumed.value,
                    // Keep the compact overlay's animation anchored to the pairing button.
                    pairButtonModifier = Modifier.onGloballyPositioned {
                        pairing.pairButtonBounds = it.boundsInRoot()
                    },
                )
            }
        }
        composable<Route.Apps> { AppsDestination() }
        composable<Route.Ios> { IosScreen() }
        composable<Route.Run> {
            RunScreen(
                initialSelection = latestOpenRun.value,
                onInitialSelectionConsumed = latestOnOpenRunConsumed.value,
            )
        }
        composable<Route.Seal> { SealScreen() }
        composable<Route.SshAgent> {
            SshKeyProviderScreen(
                initialHistoryRequestId = latestOpenSshHistoryRequestId.value,
                onInitialHistoryRequestConsumed = latestOnOpenSshHistoryConsumed.value,
            )
        }
        composable<Route.Activity> { ActivityScreen() }
        composable<Route.Settings> {
            SettingsScreen(onOpenAbout = {
                navController.navigate(Route.About) { launchSingleTop = true }
            })
        }
        composable<Route.Menu> {
            MenuManagementScreen(
                navigationLimit = latestNavigationLimit.value,
                onBack = if (latestIsRail.value) null else ({ navController.popBackStack() }),
            )
        }
        composable<Route.About> {
            AboutScreen(onBack = if (latestIsRail.value) null else ({ navController.popBackStack() }))
        }
    }
}

/** Hosts the permission/launcher plumbing the Devices screen needs, scoped to that destination. */
// pairButtonModifier is threaded to the pair button specifically, not applied as the composable's root modifier.
@Suppress("ModifierParameter")
@Composable
private fun DevicesDestination(
    onPair: () -> Unit,
    pairingOpen: Boolean = false,
    onDetailSelected: () -> Unit = {},
    pairButtonModifier: Modifier = Modifier,
    openDeviceDetails: String? = null,
    onOpenDeviceDetailsConsumed: () -> Unit = {},
) {
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
        pairingOpen = pairingOpen,
        onDetailSelected = onDetailSelected,
        openDeviceDetails = openDeviceDetails,
        onOpenDeviceDetailsConsumed = onOpenDeviceDetailsConsumed,
        onPair = onPair,
        pairButtonModifier = pairButtonModifier,
        onRequestPostNotifications = {
            postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onStartScreenMirror = { sourceId ->
            context.startActivity(AndroidScreenMirrorActivity.intent(context, sourceId))
        },
    )
}

@Composable
private fun AppsDestination() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val permissions = remember(refresh) { readPermissions(context) }

    AppsScreen(
        permissions = permissions,
        onOpenListenerSettings = {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
    )
}

private fun readPermissions(context: Context): PermissionState {
    val listenerEnabled =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    val postGranted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    return PermissionState(
        listenerEnabled = listenerEnabled,
        postNotificationsGranted = postGranted
    )
}
