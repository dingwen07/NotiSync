package net.extrawdw.apps.notisync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import net.extrawdw.apps.notisync.ui.icons.material.outlined.apps as AppsIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.devices as DevicesIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.history as HistoryIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.key as KeyIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.phone_iphone as PhoneIphoneIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.settings as SettingsIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.terminal as TerminalIcon
import androidx.compose.material3.Icon
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.extrawdw.apps.notisync.pairing.PairingDeepLinks
import net.extrawdw.apps.notisync.pairing.PairingCandidate
import net.extrawdw.apps.notisync.pairing.PairingCardStore
import net.extrawdw.apps.notisync.pairing.PairingManager
import net.extrawdw.apps.notisync.pairing.PairingNfcController
import net.extrawdw.apps.notisync.pairing.PairingNfcInbox
import net.extrawdw.apps.notisync.run.RunKey
import net.extrawdw.apps.notisync.screen.AndroidScreenMirrorActivity
import net.extrawdw.apps.notisync.ui.ActivityScreen
import net.extrawdw.apps.notisync.ui.AppsScreen
import net.extrawdw.apps.notisync.ui.DevicesScreen
import net.extrawdw.apps.notisync.ui.IosScreen
import net.extrawdw.apps.notisync.ui.LocalFeatureDrawerOpener
import net.extrawdw.apps.notisync.ui.OnboardingScreen
import net.extrawdw.apps.notisync.ui.PairingOverlay
import net.extrawdw.apps.notisync.ui.PairingApprovalSheet
import net.extrawdw.apps.notisync.ui.PermissionState
import net.extrawdw.apps.notisync.ui.SettingsScreen
import net.extrawdw.apps.notisync.ui.SignatureIcon
import net.extrawdw.apps.notisync.ui.RunScreen
import net.extrawdw.apps.notisync.ui.SealScreen
import net.extrawdw.apps.notisync.ui.SshKeyProviderScreen
import net.extrawdw.apps.notisync.ui.rememberGraph
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import net.extrawdw.notisync.peer.trust.RosterDevice
import net.extrawdw.notisync.protocol.TrustStatus

class MainActivity : ComponentActivity() {
    private val pendingPairingPayload = MutableStateFlow<String?>(null)
    private val pendingOpenDevices = MutableStateFlow(false)
    private val pendingOpenRun = MutableStateFlow<RunKey?>(null)
    private val pendingOpenSshHistory = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        val app = applicationContext as NotiSyncApp
        splashScreen.setKeepOnScreenCondition {
            shouldKeepSystemSplash(app.startupState.value)
        }
        updatePendingPairingPayload(intent)
        consumeOpenDevices(intent)
        consumeOpenRun(intent)
        consumeOpenSshHistory(intent)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val startupState by app.startupState.collectAsStateWithLifecycle()
            val pairingPayload by pendingPairingPayload.collectAsStateWithLifecycle()
            val hcePairingPayload by PairingNfcInbox.pendingPayload.collectAsStateWithLifecycle()
            val openDevices by pendingOpenDevices.collectAsStateWithLifecycle()
            val openRun by pendingOpenRun.collectAsStateWithLifecycle()
            val openSshHistory by pendingOpenSshHistory.collectAsStateWithLifecycle()
            NotiSyncTheme {
                when {
                    startupState.stage == AppStartupStage.READY -> {
                        val graph = remember { app.graph }
                        val onboardingCompleted by
                            graph.settings.onboardingCompleted.collectAsStateWithLifecycle()
                        when (onboardingCompleted) {
                            // Persist on the graph scope: the composable (and any rememberCoroutineScope) is
                            // disposed when the flow updates, which would cancel the write mid-flight.
                            false -> OnboardingScreen(
                                onFinish = {
                                    graph.scope.launch { graph.settings.setOnboardingCompleted() }
                                },
                            )
                            // Finishing onboarding lands here with Devices as the NavHost start destination;
                            // a pairing deep link received during onboarding is still pending and opens now.
                            true -> NotiSyncRoot(
                                pendingPairingPayload = pairingPayload,
                                onPendingPairingPayloadConsumed = { pendingPairingPayload.value = null },
                                pendingHcePairingPayload = hcePairingPayload,
                                onPendingHcePairingPayloadConsumed = { payload ->
                                    PairingNfcInbox.consume(applicationContext, payload)
                                },
                                openDevices = openDevices,
                                onOpenDevicesConsumed = { pendingOpenDevices.value = false },
                                openRun = openRun,
                                onOpenRunConsumed = { pendingOpenRun.value = null },
                                openSshHistoryRequestId = openSshHistory,
                                onOpenSshHistoryConsumed = { pendingOpenSshHistory.value = null },
                            )
                        }
                    }
                    shouldShowCustomStartupScreen(startupState) ->
                        StartupScreen(stage = startupState.stage)
                    // Kept behind the system splash during ordinary checking and graph initialization.
                    else -> Box(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updatePendingPairingPayload(intent)
        consumeOpenDevices(intent)
        consumeOpenRun(intent)
        consumeOpenSshHistory(intent)
    }

    /** A trust notification asked us to open the Devices tab. */
    private fun consumeOpenDevices(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_DEVICES, false) != true) return
        pendingOpenDevices.value = true
        intent.removeExtra(EXTRA_OPEN_DEVICES) // consume so a config change / Recents can't re-trigger it
    }

    /** A locally-rendered Run notification asked us to open its durable detail record. */
    private fun consumeOpenRun(intent: Intent?) {
        intent ?: return
        if (intent.action != ACTION_OPEN_RUN && !intent.hasExtra(EXTRA_RUN_ID)) return
        val host = intent.getStringExtra(EXTRA_RUN_HOST_CLIENT_ID) ?: return
        val runId = intent.getStringExtra(EXTRA_RUN_ID) ?: return
        pendingOpenRun.value = RunKey(host, runId)
        intent.removeExtra(EXTRA_RUN_HOST_CLIENT_ID)
        intent.removeExtra(EXTRA_RUN_ID)
    }

    /** An auto-approval notification asked us to open one exact durable SSH history record. */
    private fun consumeOpenSshHistory(intent: Intent?) {
        intent ?: return
        if (intent.action != ACTION_OPEN_SSH_HISTORY && !intent.hasExtra(EXTRA_SSH_REQUEST_ID)) return
        val requestId = intent.getStringExtra(EXTRA_SSH_REQUEST_ID)?.takeIf(String::isNotBlank) ?: return
        pendingOpenSshHistory.value = requestId
        intent.removeExtra(EXTRA_SSH_REQUEST_ID)
    }

    companion object {
        const val EXTRA_OPEN_DEVICES = "net.extrawdw.apps.notisync.OPEN_DEVICES"
        const val ACTION_OPEN_RUN = "net.extrawdw.apps.notisync.OPEN_RUN"
        const val EXTRA_RUN_HOST_CLIENT_ID = "net.extrawdw.apps.notisync.RUN_HOST_CLIENT_ID"
        const val EXTRA_RUN_ID = "net.extrawdw.apps.notisync.RUN_ID"
        const val ACTION_OPEN_SSH_HISTORY = "net.extrawdw.apps.notisync.OPEN_SSH_HISTORY"
        const val EXTRA_SSH_REQUEST_ID = "net.extrawdw.apps.notisync.SSH_REQUEST_ID"
    }

    private fun updatePendingPairingPayload(intent: Intent?) {
        intent ?: return
        // Reopening the app from the Recents list re-delivers the task's base intent — for a
        // QR-launched task that's the original pairing deep link. Ignore it, otherwise every
        // return-from-Recents would surface the trust dialog again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val payload = PairingDeepLinks.payloadFrom(intent.dataString) ?: return
        pendingPairingPayload.value = payload
        // Consume the link so the same intent can't re-trigger pairing on a later recreation
        // (e.g. a configuration change, which restarts the activity with this same intent).
        intent.data = null
    }
}

/** Type-safe (serializable) navigation routes — the single source of truth for the back stack. */
private sealed interface Route {
    @Serializable
    data object Devices : Route

    @Serializable
    data object Apps : Route

    @Serializable
    data object Ios : Route

    @Serializable
    data object Run : Route

    @Serializable
    data object Seal : Route

    @Serializable
    data object SshAgent : Route

    @Serializable
    data object Activity : Route

    @Serializable
    data object Settings : Route
}

private interface AppDestination {
    val route: Route
    @get:StringRes val label: Int
    val icon: ImageVector
}

/** Stable bottom-bar/rail destinations. Feature entries deliberately stay out of compact navigation. */
private enum class TopLevelDestination(
    override val route: Route,
    @param:StringRes override val label: Int,
    override val icon: ImageVector,
) : AppDestination {
    DEVICES(Route.Devices, R.string.tab_devices, DevicesIcon),
    APPS(Route.Apps, R.string.tab_apps, AppsIcon),
    IOS(Route.Ios, R.string.tab_ios, PhoneIphoneIcon),
    ACTIVITY(Route.Activity, R.string.tab_activity, HistoryIcon),
    SETTINGS(Route.Settings, R.string.tab_settings, SettingsIcon),
}

private enum class FeatureDestination(
    override val route: Route,
    @param:StringRes override val label: Int,
    override val icon: ImageVector,
) : AppDestination {
    RUN(Route.Run, R.string.tab_run, TerminalIcon),
    SEAL(Route.Seal, R.string.tab_seal, SignatureIcon),
    SSH_AGENT(Route.SshAgent, R.string.ssh_key_provider_tools_label, KeyIcon),
}

private enum class PairingReviewSource {
    INTERACTIVE,
    DEEP_LINK,
    HCE,
}

private data class PairingReview(
    val candidate: PairingCandidate,
    val source: PairingReviewSource,
    val existingTrustedDevice: RosterDevice?,
)

// Every tab glyph is centered in a 24dp box, but PhoneIphone fills 22/24 of its viewBox (vs 16–20
// for the others), so its taller silhouette reads as raised. Render just the iOS glyph slightly
// smaller, inside the same 24dp box, so its visual height matches the rest of the set.
private val TopLevelNavIconSize = 24.dp
private val TopLevelNavIosIconSize = 20.dp

@Composable
fun NotiSyncRoot(
    pendingPairingPayload: String? = null,
    onPendingPairingPayloadConsumed: () -> Unit = {},
    pendingHcePairingPayload: String? = null,
    onPendingHcePairingPayloadConsumed: (String) -> Unit = {},
    openDevices: Boolean = false,
    onOpenDevicesConsumed: () -> Unit = {},
    openRun: RunKey? = null,
    onOpenRunConsumed: () -> Unit = {},
    openSshHistoryRequestId: String? = null,
    onOpenSshHistoryConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val graph = rememberGraph()
    val pairing = remember { PairingManager(graph) }
    val pairingScope = rememberCoroutineScope()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // NavHost remembers the graph it builds. Keep the mutable notification handoff behind stable State
    // objects so the remembered Run destination observes the latest request instead of the value captured
    // when the graph was first created (normally null).
    val latestOpenRun = rememberUpdatedState(openRun)
    val latestOnOpenRunConsumed = rememberUpdatedState(onOpenRunConsumed)
    val latestOpenSshHistoryRequestId = rememberUpdatedState(openSshHistoryRequestId)
    val latestOnOpenSshHistoryConsumed = rememberUpdatedState(onOpenSshHistoryConsumed)

    // Pairing is frozen during a trust-tamper quarantine — the stripe is disabled in DevicesScreen, and
    // this also blocks the deep-link path so a pairing link can't bypass the freeze.
    val quarantined by graph.trust.quarantined.collectAsStateWithLifecycle()

    // Pairing is a state-driven overlay rather than a nav destination, so it can expand out of — and
    // collapse back into — the "Pair a device" stripe with a predictive-back-driven container transform
    // (see PairingOverlay). The stripe reports its live position here; the overlay renders above the
    // whole navigation suite so the Devices tab (and bar) stay visible as the page folds away.
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var pairButtonBounds by remember { mutableStateOf<Rect?>(null) }
    var pairingReview by remember { mutableStateOf<PairingReview?>(null) }
    var pairingApprovalOwnDevice by remember { mutableStateOf<Boolean?>(null) }
    var pairingApprovalError by remember { mutableStateOf<String?>(null) }
    val deviceName by graph.settings.deviceName.collectAsStateWithLifecycle()
    var foregroundResumeGeneration by remember { mutableIntStateOf(0) }
    var foregroundPairingUrl by remember {
        mutableStateOf(PairingCardStore.current()?.let(PairingDeepLinks::create))
    }

    LifecycleResumeEffect(Unit) {
        foregroundResumeGeneration += 1
        onPauseOrDispose { }
    }

    // Refresh the signed public card on every foreground entry (and rename). The previously persisted card
    // remains immediately usable while StrongBox signing runs off-main.
    LaunchedEffect(showPairing, deviceName, foregroundResumeGeneration) {
        if (!showPairing) {
            withContext(Dispatchers.IO) { runCatching { pairing.myLink() } }
                .onSuccess { foregroundPairingUrl = it.url }
        }
    }

    // Compatibility path for Android NDEF readers and iPhone: add the Type 4 AID only while this Activity is
    // resumed outside pairing UI. While approval is pending, withhold NDEF to prevent another system tag
    // dispatch but keep the proprietary AID preferred; the sheet must not switch this device to reader mode.
    LifecycleResumeEffect(showPairing, pairingReview != null, foregroundPairingUrl) {
        when {
            showPairing -> Unit
            pairingReview != null -> PairingNfcController.enableForegroundCustomAidOnly(context)
            else -> foregroundPairingUrl?.let {
                PairingNfcController.enableForegroundNdef(context, it)
            }
        }
        onPauseOrDispose { PairingNfcController.disableForegroundNdef(context) }
    }

    fun openPairingCandidate(
        candidate: PairingCandidate,
        source: PairingReviewSource = PairingReviewSource.INTERACTIVE,
    ) {
        // Reader mode suppresses this device's HCE mode. Remove the pairing page first, then show approval
        // above Devices so the reciprocal peer can continue to address our always-on custom AID.
        showPairing = false
        navController.navigateToTopLevel(TopLevelDestination.DEVICES)
        pairingApprovalError = null
        val existingTrustedDevice = graph.trust.roster.value.firstOrNull {
            it.clientId == candidate.clientId && it.status == TrustStatus.TRUSTED
        }
        pairingReview = PairingReview(candidate, source, existingTrustedDevice)
    }

    fun approvePairing(review: PairingReview, ownDevice: Boolean) {
        if (pairingApprovalOwnDevice != null) return
        pairingApprovalOwnDevice = ownDevice
        pairingApprovalError = null
        pairingScope.launch {
            runCatching {
                graph.durableTrustMutations.run {
                    pairing.accept(review.candidate.payload, ownDevice).getOrThrow()
                }
            }.fold(
                onSuccess = { card ->
                    if (review.source == PairingReviewSource.HCE) {
                        onPendingHcePairingPayloadConsumed(review.candidate.payload)
                    }
                    pairingReview = null
                    Toast.makeText(
                        context,
                        context.getString(R.string.pair_paired_with, card.displayName),
                        Toast.LENGTH_LONG,
                    ).show()
                },
                onFailure = {
                    pairingApprovalError =
                        context.getString(R.string.pair_could_not_pair, it.message)
                },
            )
            pairingApprovalOwnDevice = null
        }
    }

    LaunchedEffect(pendingPairingPayload, pendingHcePairingPayload, quarantined) {
        if (quarantined) return@LaunchedEffect
        val fromDeepLink = pendingPairingPayload != null
        val payload = pendingPairingPayload ?: pendingHcePairingPayload ?: return@LaunchedEffect
        navController.navigateToTopLevel(TopLevelDestination.DEVICES)
        val inspection = withContext(Dispatchers.Default) { pairing.inspect(payload) }
        inspection.fold(
            onSuccess = { candidate ->
                val source = if (fromDeepLink) {
                    PairingReviewSource.DEEP_LINK
                } else {
                    PairingReviewSource.HCE
                }
                if (source == PairingReviewSource.HCE) {
                    PairingNfcInbox.dismissNotification(context)
                }
                openPairingCandidate(candidate, source)
            },
            onFailure = {
                val message = if (fromDeepLink) {
                    context.getString(R.string.pair_could_not_open_link, it.message)
                } else {
                    context.getString(R.string.pair_could_not_pair, it.message)
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            },
        )
        if (fromDeepLink) {
            onPendingPairingPayloadConsumed()
        } else if (inspection.isFailure) {
            // Keep a verified HCE card durable until the user approves or dismisses the review. Invalid
            // input is terminal and should not reopen on every future app launch.
            onPendingHcePairingPayloadConsumed(payload)
        }
    }

    LaunchedEffect(openDevices) {
        if (openDevices) {
            navController.navigateToTopLevel(TopLevelDestination.DEVICES)
            onOpenDevicesConsumed()
        }
    }

    LaunchedEffect(openRun) {
        if (openRun != null) {
            // Pairing is not a navigation destination, so changing tabs alone leaves it drawn above Run.
            // A notification open is explicit navigation: dismiss the overlay before selecting the Run tab.
            showPairing = pairingOverlayAfterRunOpenRequest(showPairing, openRun)
            navController.navigateToTopLevel(FeatureDestination.RUN)
        }
    }

    LaunchedEffect(openSshHistoryRequestId) {
        if (openSshHistoryRequestId != null) {
            showPairing = false
            navController.navigateToTopLevel(FeatureDestination.SSH_AGENT)
        }
    }

    val layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(
        currentWindowAdaptiveInfoV2()
    )
    val suiteIsDrawer = layoutType == NavigationSuiteType.NavigationDrawer
    val featureDrawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var pendingFeatureDestination by remember { mutableStateOf<FeatureDestination?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        NonBouncyModalNavigationDrawer(
            drawerState = featureDrawerState,
            gesturesEnabled = !suiteIsDrawer,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(296.dp)) {
                    Text(
                        stringResource(R.string.features_title),
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                    )
                    HorizontalDivider()
                    FeatureDestination.entries.forEach { dest ->
                        NavigationDrawerItem(
                            selected = pendingFeatureDestination?.let { it == dest }
                                ?: currentDestination.isOn(dest),
                            onClick = {
                                if (pendingFeatureDestination == null) {
                                    // Update the drawer selection immediately, but avoid composing the
                                    // destination on the UI thread while the sheet is still animating.
                                    pendingFeatureDestination = dest
                                    drawerScope.launch {
                                        try {
                                            featureDrawerState.close()
                                            navController.navigateToTopLevel(dest)
                                        } finally {
                                            pendingFeatureDestination = null
                                        }
                                    }
                                }
                            },
                            icon = { TopLevelNavIcon(dest) },
                            label = { TopLevelNavLabel(dest) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            },
        ) {
        CompositionLocalProvider(
            LocalFeatureDrawerOpener provides if (suiteIsDrawer) null else ({
                drawerScope.launch { featureDrawerState.open() }
            })
        ) {
        NavigationSuiteScaffold(
            layoutType = layoutType,
            navigationSuiteItems = {
                TopLevelDestination.entries.forEach { dest ->
                    item(
                        selected = currentDestination.isOn(dest),
                        onClick = { navController.navigateToTopLevel(dest) },
                        icon = { TopLevelNavIcon(dest) },
                        label = { TopLevelNavLabel(dest) },
                    )
                }
                if (suiteIsDrawer) {
                    item(
                        selected = false,
                        onClick = {},
                        icon = {},
                        label = { Text(stringResource(R.string.features_title)) },
                        enabled = false,
                    )
                    FeatureDestination.entries.forEach { dest ->
                        item(
                            selected = currentDestination.isOn(dest),
                            onClick = { navController.navigateToTopLevel(dest) },
                            icon = { TopLevelNavIcon(dest) },
                            label = { TopLevelNavLabel(dest) },
                        )
                    }
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
                        onPair = { if (!quarantined) showPairing = true },
                        // Sample the stripe's bounds (root coordinates, shared with the overlay) so the
                        // container transform knows where to grow from / fold back into. It moves as the
                        // list scrolls; the last value before opening is what the collapse animates to.
                        pairButtonModifier = Modifier.onGloballyPositioned {
                            pairButtonBounds = it.boundsInRoot()
                        },
                    )
                }
                composable<Route.Apps> { AppsScreen() }
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
                composable<Route.Settings> { SettingsScreen() }
            }
        }
        }
        }

        // Compose this after the drawer so it takes precedence over Material 3's internal predictive
        // handler. On affected devices that handler lets a completed Back escape to app navigation.
        BackHandler(enabled = !suiteIsDrawer && featureDrawerState.isOpen) {
            drawerScope.launch { featureDrawerState.close() }
        }

        if (showPairing) {
            PairingOverlay(
                pairButtonBounds = pairButtonBounds,
                onClose = { showPairing = false },
                onPairingCandidate = { openPairingCandidate(it) },
            )
        }

        pairingReview?.let { review ->
            PairingApprovalSheet(
                candidate = review.candidate,
                existingTrustedDevice = review.existingTrustedDevice,
                approvingOwnDevice = pairingApprovalOwnDevice,
                error = pairingApprovalError,
                onTrustOwn = { approvePairing(review, ownDevice = true) },
                onTrustOther = { approvePairing(review, ownDevice = false) },
                onDismiss = {
                    if (review.source == PairingReviewSource.HCE) {
                        onPendingHcePairingPayloadConsumed(review.candidate.payload)
                    }
                    pairingApprovalError = null
                    pairingReview = null
                },
            )
        }
    }
}

@Composable
private fun NonBouncyModalNavigationDrawer(
    drawerState: DrawerState,
    gesturesEnabled: Boolean,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val appMotionScheme = MaterialTheme.motionScheme
    val drawerMotionScheme = remember(appMotionScheme) {
        NonBouncyDrawerMotionScheme(appMotionScheme)
    }
    MaterialTheme(motionScheme = drawerMotionScheme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            drawerContent = drawerContent,
        ) {
            MaterialTheme(motionScheme = appMotionScheme, content = content)
        }
    }
}

/** Drawer motion should be quick and settle once; expressive overshoot is distracting in navigation. */
private class NonBouncyDrawerMotionScheme(base: MotionScheme) : MotionScheme by base {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )
}

internal fun pairingOverlayAfterRunOpenRequest(currentlyVisible: Boolean, openRun: RunKey?): Boolean =
    currentlyVisible && openRun == null

@Composable
private fun TopLevelNavIcon(dest: AppDestination) {
    val glyphSize =
        if (dest == TopLevelDestination.IOS) TopLevelNavIosIconSize else TopLevelNavIconSize
    Box(Modifier.size(TopLevelNavIconSize), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = dest.icon,
            contentDescription = stringResource(dest.label),
            modifier = Modifier.size(glyphSize),
        )
    }
}

@Composable
private fun TopLevelNavLabel(dest: AppDestination) {
    Text(stringResource(dest.label), maxLines = 1)
}

/**
 * Switch tabs the Now-in-Android way: save the outgoing tab's nested state, restore the incoming
 * tab's, and keep a single copy on the back stack so System Back from any tab returns to the start
 * destination (and from the start destination, exits).
 */
private fun NavController.navigateToTopLevel(dest: AppDestination) {
    navigate(dest.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavDestination?.isOn(dest: AppDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(dest.route::class) } == true

/** Hosts the permission/launcher plumbing the Devices screen needs, scoped to that destination. */
// pairButtonModifier is threaded to the pair button specifically, not applied as the composable's root modifier.
@Suppress("ModifierParameter")
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
        onStartScreenMirror = { sourceId ->
            context.startActivity(AndroidScreenMirrorActivity.intent(context, sourceId))
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
