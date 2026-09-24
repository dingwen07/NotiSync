package net.extrawdw.apps.notisync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import net.extrawdw.apps.notisync.ui.icons.material.outlined.info as InfoIcon
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerDefaults
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
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.window.core.layout.WindowSizeClass
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.extrawdw.apps.notisync.pairing.PairingDeepLinks
import net.extrawdw.notisync.peer.pairing.BrokerPairingLink
import net.extrawdw.apps.notisync.ui.BrokerPairingSheet
import net.extrawdw.apps.notisync.pairing.PairingCandidate
import net.extrawdw.apps.notisync.pairing.PairingCardStore
import net.extrawdw.apps.notisync.pairing.PairingManager
import net.extrawdw.apps.notisync.pairing.PairingNfcController
import net.extrawdw.apps.notisync.pairing.PairingNfcInbox
import net.extrawdw.apps.notisync.run.RunKey
import net.extrawdw.apps.notisync.screen.AndroidScreenMirrorActivity
import net.extrawdw.apps.notisync.ui.AboutScreen
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
import net.extrawdw.apps.notisync.ui.MenuManagementScreen
import net.extrawdw.apps.notisync.navigation.AppMenuShortcuts
import net.extrawdw.apps.notisync.navigation.MenuDestination
import net.extrawdw.apps.notisync.navigation.icon
import net.extrawdw.apps.notisync.navigation.label
import net.extrawdw.apps.notisync.ui.icons.material.outlined.menu as MenuIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.edit as EditIcon
import net.extrawdw.apps.notisync.ui.RunScreen
import net.extrawdw.apps.notisync.ui.SealScreen
import net.extrawdw.apps.notisync.ui.SshKeyProviderScreen
import net.extrawdw.apps.notisync.ui.rememberGraph
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import net.extrawdw.notisync.peer.trust.RosterDevice
import net.extrawdw.notisync.protocol.TrustStatus

class MainActivity : ComponentActivity() {
    private val pendingPairingPayload = MutableStateFlow<String?>(null)
    private val pendingMenuDestination = MutableStateFlow<MenuDestination?>(null)
    private val pendingOpenDevices = MutableStateFlow(false)
    private val pendingDeviceDetails = MutableStateFlow<String?>(null)
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
        consumeMenuDestination(intent)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val startupState by app.startupState.collectAsStateWithLifecycle()
            val pairingPayload by pendingPairingPayload.collectAsStateWithLifecycle()
            val hcePairingPayload by PairingNfcInbox.pendingPayload.collectAsStateWithLifecycle()
            val menuDestination by pendingMenuDestination.collectAsStateWithLifecycle()
            val openDevices by pendingOpenDevices.collectAsStateWithLifecycle()
            val deviceDetails by pendingDeviceDetails.collectAsStateWithLifecycle()
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
                                openMenuDestination = menuDestination,
                                onOpenMenuDestinationConsumed = { pendingMenuDestination.value = null },
                                openDevices = openDevices,
                                onOpenDevicesConsumed = { pendingOpenDevices.value = false },
                                openDeviceDetails = deviceDetails,
                                onOpenDeviceDetailsConsumed = { pendingDeviceDetails.value = null },
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
        consumeMenuDestination(intent)
    }

    private fun consumeMenuDestination(intent: Intent?) {
        AppMenuShortcuts.consumeDestination(intent)?.let { pendingMenuDestination.value = it }
    }

    /** A trust notification asked us to open the Devices tab. */
    private fun consumeOpenDevices(intent: Intent?) {
        intent?.getStringExtra(EXTRA_DEVICE_DETAILS_CLIENT_ID)?.takeIf(String::isNotBlank)?.let {
            pendingDeviceDetails.value = it
            intent.removeExtra(EXTRA_DEVICE_DETAILS_CLIENT_ID)
        }
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
        const val ACTION_OPEN_DEVICE_DETAILS = "net.extrawdw.apps.notisync.OPEN_DEVICE_DETAILS"
        const val EXTRA_DEVICE_DETAILS_CLIENT_ID = "net.extrawdw.apps.notisync.DEVICE_DETAILS_CLIENT_ID"
        const val ACTION_OPEN_RUN = "net.extrawdw.apps.notisync.OPEN_RUN"
        const val EXTRA_RUN_HOST_CLIENT_ID = "net.extrawdw.apps.notisync.RUN_HOST_CLIENT_ID"
        const val EXTRA_RUN_ID = "net.extrawdw.apps.notisync.RUN_ID"
        const val ACTION_OPEN_SSH_HISTORY = "net.extrawdw.apps.notisync.OPEN_SSH_HISTORY"
        const val EXTRA_SSH_REQUEST_ID = "net.extrawdw.apps.notisync.SSH_REQUEST_ID"
    }

    private fun updatePendingPairingPayload(intent: Intent?) {
        intent ?: return
        if (intent.action == ACTION_OPEN_DEVICE_DETAILS) return
        // Reopening the app from the Recents list re-delivers the task's base intent — for a
        // QR-launched task that's the original pairing deep link. Ignore it, otherwise every
        // return-from-Recents would surface the trust dialog again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val link = intent.dataString ?: return
        val payload = BrokerPairingLink.parse(link)?.encode() ?: PairingDeepLinks.payloadFrom(link)
        // Consume the link so the same intent can't re-trigger pairing on a later recreation
        // (including malformed links). Never let external input throw during activity startup.
        intent.data = null
        if (payload == null) {
            Toast.makeText(this, R.string.pair_invalid_link, Toast.LENGTH_LONG).show()
            return
        }
        pendingPairingPayload.value = payload
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

    @Serializable
    data object About : Route

    @Serializable
    data object Menu : Route
}

private enum class AppDestination(val menu: MenuDestination, val route: Route) {
    DEVICES(MenuDestination.DEVICES, Route.Devices),
    APPS(MenuDestination.APPS, Route.Apps),
    IOS(MenuDestination.IPHONE, Route.Ios),
    ACTIVITY(MenuDestination.ACTIVITY, Route.Activity),
    SETTINGS(MenuDestination.SETTINGS, Route.Settings),
    RUN(MenuDestination.RUN, Route.Run),
    SEAL(MenuDestination.SEAL, Route.Seal),
    SSH_AGENT(MenuDestination.SSH_KEYS, Route.SshAgent);

    @get:StringRes val label: Int get() = menu.label
    val icon: ImageVector get() = menu.icon

    companion object {
        fun fromMenu(menu: MenuDestination): AppDestination = entries.first { it.menu == menu }
    }
}

private enum class PairingReviewSource {
    INTERACTIVE,
    DEEP_LINK,
    HCE,
    BROKER,
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
    val orderedDestinations = menuConfiguration.destinations().map(AppDestination::fromMenu)
    val pairing = remember { PairingManager(graph) }
    val pairingScope = rememberCoroutineScope()
    val navController = rememberNavController()
    val openMenu = { navController.navigate(Route.Menu) { launchSingleTop = true } }
    val openAbout = {
        navController.navigate(Route.About) { launchSingleTop = true }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // NavHost remembers the graph it builds. Keep the mutable notification handoff behind stable State
    // objects so the remembered Run destination observes the latest request instead of the value captured
    // when the graph was first created (normally null).
    val latestOpenRun = rememberUpdatedState(openRun)
    val latestOnOpenRunConsumed = rememberUpdatedState(onOpenRunConsumed)
    val latestOpenSshHistoryRequestId = rememberUpdatedState(openSshHistoryRequestId)
    val latestOnOpenSshHistoryConsumed = rememberUpdatedState(onOpenSshHistoryConsumed)
    val latestOpenDeviceDetails = rememberUpdatedState(openDeviceDetails)
    val latestOnOpenDeviceDetailsConsumed = rememberUpdatedState(onOpenDeviceDetailsConsumed)

    // Pairing is frozen during a trust-tamper quarantine — the stripe is disabled in DevicesScreen, and
    // this also blocks the deep-link path so a pairing link can't bypass the freeze.
    val quarantined by graph.trust.quarantined.collectAsStateWithLifecycle()

    // Pairing is a state-driven overlay rather than a nav destination, so it can expand out of — and
    // collapse back into — the "Device Pairing" stripe with a predictive-back-driven container transform
    // (see PairingOverlay). The stripe reports its live position here; the overlay renders above the
    // whole navigation suite so the Devices tab (and bar) stay visible as the page folds away.
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var pairButtonBounds by remember { mutableStateOf<Rect?>(null) }
    var pairingReview by remember { mutableStateOf<PairingReview?>(null) }
    var brokerPairingLink by remember { mutableStateOf<BrokerPairingLink?>(null) }
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
    LifecycleResumeEffect(showPairing, pairingReview != null || brokerPairingLink != null, foregroundPairingUrl) {
        when {
            showPairing -> Unit
            pairingReview != null || brokerPairingLink != null -> PairingNfcController.enableForegroundCustomAidOnly(context)
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
        navController.navigateToTopLevel(AppDestination.DEVICES)
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

    LaunchedEffect(pendingPairingPayload, pendingHcePairingPayload, quarantined, pairingReview != null, brokerPairingLink) {
        if (quarantined) {
            showPairing = false
            brokerPairingLink = null
            return@LaunchedEffect
        }
        // Queue another scan while a secure exchange or CARD review is active, including a failed approval
        // that the user may retry. Never replace the visible identity during a trust decision.
        if (pairingReview != null || brokerPairingLink != null) return@LaunchedEffect
        val fromDeepLink = pendingPairingPayload != null
        val payload = pendingPairingPayload ?: pendingHcePairingPayload ?: return@LaunchedEffect
        navController.navigateToTopLevel(AppDestination.DEVICES)
        BrokerPairingLink.parse(payload)?.let { link ->
            showPairing = false
            pairingReview = null
            brokerPairingLink = link
            if (fromDeepLink) onPendingPairingPayloadConsumed() else onPendingHcePairingPayloadConsumed(payload)
            return@LaunchedEffect
        }
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
            navController.navigateToTopLevel(AppDestination.DEVICES)
            onOpenDevicesConsumed()
        }
    }

    LaunchedEffect(openDeviceDetails) {
        if (openDeviceDetails != null) {
            showPairing = false
            navController.navigateToTopLevel(AppDestination.DEVICES)
        }
    }

    LaunchedEffect(openRun) {
        if (openRun != null) {
            // Pairing is not a navigation destination, so changing tabs alone leaves it drawn above Run.
            // A notification open is explicit navigation: dismiss the overlay before selecting the Run tab.
            showPairing = pairingOverlayAfterRunOpenRequest(showPairing, openRun)
            navController.navigateToTopLevel(AppDestination.RUN)
        }
    }

    LaunchedEffect(openSshHistoryRequestId) {
        if (openSshHistoryRequestId != null) {
            showPairing = false
            navController.navigateToTopLevel(AppDestination.SSH_AGENT)
        }
    }

    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val layoutType = NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)
    val suiteIsDrawer = layoutType == NavigationSuiteType.NavigationDrawer
    val suiteIsRail = layoutType == NavigationSuiteType.WideNavigationRailCollapsed ||
        layoutType == NavigationSuiteType.WideNavigationRailExpanded
    val railItemSpacing = if (adaptiveInfo.windowSizeClass.isHeightAtLeastBreakpoint(
            WindowSizeClass.HEIGHT_DP_EXPANDED_LOWER_BOUND,
        )) 12.dp else 8.dp
    val navigationLimit = if (suiteIsDrawer) 8 else if (suiteIsRail) 7 else 5
    val visibleDestinations = if (suiteIsDrawer) orderedDestinations else
        menuConfiguration.navigationDestinations(navigationLimit).map(AppDestination::fromMenu)
    val overflowDestinations = menuConfiguration.overflowDestinations(navigationLimit).map(AppDestination::fromMenu)
    val featureDrawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var pendingFeatureDestination by remember { mutableStateOf<AppDestination?>(null) }
    val configuration = LocalConfiguration.current

    // Refresh labels after locale changes and retry any background rate-limited update on foregrounding.
    LaunchedEffect(configuration.locales.toLanguageTags(), foregroundResumeGeneration) {
        withContext(Dispatchers.IO) {
            runCatching { AppMenuShortcuts.update(context, graph.settings.menuConfiguration.value) }
                .onFailure { android.util.Log.w("MainActivity", "Failed to refresh launcher shortcuts", it) }
        }
    }
    LaunchedEffect(openMenuDestination) {
        openMenuDestination?.let {
            showPairing = false
            featureDrawerState.close()
            navController.navigateToTopLevel(AppDestination.fromMenu(it))
            onOpenMenuDestinationConsumed()
        }
    }

    val navigationItems: @Composable () -> Unit = {
        visibleDestinations.forEach { dest ->
            NavigationSuiteItem(
                navigationSuiteType = layoutType,
                selected = currentDestination.isOn(dest),
                onClick = { navController.navigateToTopLevel(dest) },
                icon = { TopLevelNavIcon(dest) },
                label = { TopLevelNavLabel(dest) },
            )
        }
        if (suiteIsDrawer) {
            HorizontalDivider()
            NavigationSuiteItem(
                navigationSuiteType = layoutType,
                selected = currentDestination?.hasRoute<Route.Menu>() == true,
                onClick = openMenu,
                icon = { Icon(EditIcon, null) },
                label = { Text(stringResource(R.string.menu_manage)) },
            )
            NavigationSuiteItem(
                navigationSuiteType = layoutType,
                selected = currentDestination?.hasRoute<Route.About>() == true,
                onClick = openAbout,
                icon = { Icon(InfoIcon, null) },
                label = { Text(stringResource(R.string.about_title)) },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NonBouncyModalNavigationDrawer(
            drawerState = featureDrawerState,
            gesturesEnabled = !suiteIsDrawer,
            drawerContent = { sheetModifier ->
                ModalDrawerSheet(modifier = sheetModifier.width(296.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.menu_title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                drawerScope.launch { featureDrawerState.close(); openMenu() }
                            }) {
                                Icon(EditIcon, stringResource(R.string.menu_manage))
                            }
                        }
                        HorizontalDivider()
                        // Hide only pages actually visible in navigation. Selections beyond its adaptive
                        // limit remain reachable here when the window becomes smaller.
                        overflowDestinations.forEach { dest ->
                            NavigationDrawerItem(
                                selected = pendingFeatureDestination?.let { it == dest }
                                    ?: currentDestination.isOn(dest),
                                onClick = {
                                    if (pendingFeatureDestination == null) {
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
                    HorizontalDivider()
                    NavigationDrawerItem(
                        selected = currentDestination?.hasRoute<Route.About>() == true,
                        onClick = { drawerScope.launch { featureDrawerState.close(); openAbout() } },
                        icon = { Icon(InfoIcon, null) },
                        label = { Text(stringResource(R.string.about_title)) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            },
        ) {
        CompositionLocalProvider(
            // On tablets the hamburger belongs to the rail header, not the page's top app bar.
            LocalFeatureDrawerOpener provides if (suiteIsDrawer || suiteIsRail) null else ({
                drawerScope.launch { featureDrawerState.open() }
            })
        ) {
        NavigationSuiteScaffold(
            navigationSuiteType = layoutType,
            primaryActionContent = {
                if (suiteIsRail) {
                    IconButton(onClick = { drawerScope.launch { featureDrawerState.open() } }) {
                        Icon(MenuIcon, stringResource(R.string.open_features))
                    }
                }
            },
            navigationItems = {
                if (suiteIsRail) {
                    // The scroll wrapper owns item spacing; WideNavigationRail only sees one child.
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(railItemSpacing),
                    ) { navigationItems() }
                } else if (suiteIsDrawer) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { navigationItems() }
                } else navigationItems()
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
                        openDeviceDetails = latestOpenDeviceDetails.value,
                        onOpenDeviceDetailsConsumed = latestOnOpenDeviceDetailsConsumed.value,
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
                composable<Route.Settings> { SettingsScreen(onOpenAbout = openAbout, onOpenMenu = openMenu) }
                composable<Route.Menu> {
                    MenuManagementScreen(
                        navigationLimit = navigationLimit,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<Route.About> {
                    AboutScreen(onBack = { navController.popBackStack() })
                }
            }
        }
        }
        }

        if (showPairing && !quarantined) {
            PairingOverlay(
                pairButtonBounds = pairButtonBounds,
                onClose = { showPairing = false },
                onPairingCandidate = { openPairingCandidate(it) },
                onBrokerPairingCandidate = { openPairingCandidate(it, PairingReviewSource.BROKER) },
                onBrokerPairing = {
                    showPairing = false
                    brokerPairingLink = it
                },
            )
        }

        if (!quarantined) brokerPairingLink?.let { link ->
            BrokerPairingSheet(
                link = link,
                pairing = pairing,
                onCandidate = {
                    brokerPairingLink = null
                    openPairingCandidate(it, PairingReviewSource.BROKER)
                },
                onDismiss = { brokerPairingLink = null },
            )
        }

        pairingReview?.let { review ->
            PairingApprovalSheet(
                candidate = review.candidate,
                brokerAuthenticated = review.source == PairingReviewSource.BROKER,
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
    drawerContent: @Composable (Modifier) -> Unit,
    content: @Composable () -> Unit,
) {
    val appMotionScheme = MaterialTheme.motionScheme
    val drawerMotionScheme = remember(appMotionScheme) {
        NonBouncyDrawerMotionScheme(appMotionScheme)
    }
    val backProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val closingDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed) backProgress.snapTo(0f)
    }
    MaterialTheme(motionScheme = drawerMotionScheme) {
        val scrimColor = DrawerDefaults.scrimColor
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            scrimColor = scrimColor.copy(alpha = scrimColor.alpha * (1f - backProgress.value)),
            drawerContent = {
                drawerContent(
                    Modifier.graphicsLayer {
                        val offset = drawerState.currentOffset.takeIf { it.isFinite() } ?: 0f
                        // Keep the preview displacement while close() animates its own offset.
                        // Reducing it with the remaining width avoids snapping or sliding twice.
                        val remainingWidth = (size.width + offset).coerceIn(0f, size.width)
                        translationX = closingDirection * remainingWidth * backProgress.value
                    },
                )
            },
        ) {
            MaterialTheme(motionScheme = appMotionScheme, content = content)
        }
        // Register after the destination content so Back closes the drawer before navigating.
        // Use the stateless sheet above to avoid Material's predictive scaling/stretching.
        PredictiveBackHandler(enabled = gesturesEnabled && drawerState.isOpen) { events ->
            try {
                backProgress.stop()
                events.collect { event -> backProgress.snapTo(event.progress.coerceIn(0f, 1f)) }
                drawerState.close()
                backProgress.snapTo(0f)
            } catch (cancelled: CancellationException) {
                scope.launch {
                    backProgress.animateTo(0f, drawerMotionScheme.defaultSpatialSpec())
                }
                throw cancelled
            }
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
        if (dest == AppDestination.IOS) TopLevelNavIosIconSize else TopLevelNavIconSize
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
private fun DevicesDestination(
    onPair: () -> Unit,
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
        openDeviceDetails = openDeviceDetails,
        onOpenDeviceDetailsConsumed = onOpenDeviceDetailsConsumed,
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
