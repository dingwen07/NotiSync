package net.extrawdw.apps.notisync.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ios.IphoneSetupPreferences
import net.extrawdw.apps.notisync.ios.IosBridgeService
import net.extrawdw.apps.notisync.ios.IosBluetoothPermissions
import net.extrawdw.apps.notisync.ios.IosCompanion
import net.extrawdw.apps.notisync.ios.IosBridgeStatus
import net.extrawdw.apps.notisync.ios.IosApp
import net.extrawdw.apps.notisync.ios.IosBundleIdExclusions
import net.extrawdw.apps.notisync.ui.icons.material.outlined.phone_iphone as PhoneIphoneIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.refresh as RefreshIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.settings as SettingsIcon
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

private const val ICON_PX = 128

/**
 * Resolves an icon per discovered iOS app off the main thread (PackageManager lookups + App Store fetches),
 * keyed by bundle id. Scoped to the iOS nav entry so the resolution is cached across returns to the tab.
 *
 * Each app is resolved in its own bounded-concurrency coroutine that posts its icon to [icons] the instant it
 * lands, so the list fills in incrementally rather than refreshing all-at-once after the whole batch — a
 * difference that matters now that a miss may cost one or two App Store round-trips.
 */
internal class IosAppsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as NotiSyncApp).graph
    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val icons: StateFlow<Map<String, ImageBitmap>> = _icons

    // Bundle ids currently being resolved, so rapid re-emissions of `discovered` don't double-fetch.
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    // Bound concurrent App Store fetches: surface icons fast without hammering the iTunes API.
    private val fetchGate = Semaphore(MAX_CONCURRENT_FETCHES)

    init {
        val registry = graph.iosAppRegistry
        val resolver = graph.iconResolver
        if (registry != null && resolver != null) {
            viewModelScope.launch {
                registry.discovered.collect { discovered ->
                    for (iosApp in discovered.values) {
                        if (iosApp.bundleId in _icons.value || !inFlight.add(iosApp.bundleId)) continue
                        launch(Dispatchers.IO) {
                            try {
                                val pkg = resolver.androidPackageForIos(
                                    iosApp.bundleId,
                                    iosApp.displayName
                                )
                                // Fast pass — shipped pack + persisted disk cache + installed icon, NO network — so
                                // already-cached icons render immediately on restart instead of queuing behind other
                                // apps' App Store fetches. Only a genuine local miss takes the bounded network path.
                                var bmp = resolver.colorIcon(
                                    pkg,
                                    iosApp.bundleId,
                                    includeIosGenericFallback = false
                                )
                                if (bmp == null) bmp = fetchGate.withPermit {
                                    resolver.colorIconEnsuringRemote(
                                        pkg,
                                        iosApp.bundleId
                                    )
                                }
                                bmp?.let { img ->
                                    _icons.update { it + (iosApp.bundleId to IosIconMask.prepare(img)) } // atomic: many coroutines update concurrently
                                }
                            } finally {
                                inFlight.remove(iosApp.bundleId) // failed lookups become retryable on the next discovery
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_CONCURRENT_FETCHES = 4
    }
}

private object IosIconMask {
    private const val SEGMENTS = 128
    private val unitPoints: List<Pair<Float, Float>> = List(SEGMENTS) { i ->
        val t = 2.0 * PI * i / SEGMENTS
        0.5f + 0.5f * signedSuperellipse(cos(t)) to 0.5f + 0.5f * signedSuperellipse(sin(t))
    }
    private val masks = ConcurrentHashMap<Int, Bitmap>()

    fun prepare(icon: Bitmap): ImageBitmap = icon.scale(ICON_PX, ICON_PX).masked().asImageBitmap()

    fun composePath(width: Float, height: Float): ComposePath = ComposePath().apply {
        unitPoints.forEachIndexed { index, (x, y) ->
            if (index == 0) moveTo(x * width, y * height) else lineTo(x * width, y * height)
        }
        close()
    }

    private fun Bitmap.masked(): Bitmap {
        val out = createBitmap(width, height)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(this, 0f, 0f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(mask(width), 0f, 0f, paint)
        paint.xfermode = null
        return out
    }

    private fun mask(size: Int): Bitmap = masks.getOrPut(size) {
        createBitmap(size, size).also { bitmap ->
            Canvas(bitmap).drawPath(
                androidPath(size.toFloat(), size.toFloat()),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                })
        }
    }

    private fun androidPath(width: Float, height: Float): AndroidPath = AndroidPath().apply {
        unitPoints.forEachIndexed { index, (x, y) ->
            if (index == 0) moveTo(x * width, y * height) else lineTo(x * width, y * height)
        }
        close()
    }

    private fun signedSuperellipse(value: Double): Float =
        (sign(value) * abs(value).pow(2.0 / 5.0)).toFloat()
}

private object IosIconShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline =
        Outline.Generic(IosIconMask.composePath(size.width, size.height))
}

@Composable
fun IosScreen() {
    val graph = rememberGraph()
    val context = LocalContext.current
    // Resolve strings via LocalResources so they re-read on configuration changes (locale, etc.);
    // context is retained for non-resource use (CompanionDeviceManager, Toast, mainExecutor).
    val resources = LocalResources.current
    val registry = graph.iosAppRegistry ?: return
    val settings = graph.settings
    val scope = rememberCoroutineScope()

    val status by graph.iosDeviceRepo.status.collectAsStateWithLifecycle()
    val deviceName by graph.iosDeviceRepo.deviceName.collectAsStateWithLifecycle()
    val bridgeEnabled by settings.iosBridgeEnabled.collectAsStateWithLifecycle()
    val localDisplay by settings.iosLocalDisplay.collectAsStateWithLifecycle()
    val meshMirror by settings.iosMeshMirror.collectAsStateWithLifecycle()
    val mediaMirror by settings.iosMediaMirror.collectAsStateWithLifecycle()
    val enabled by registry.enabled.collectAsStateWithLifecycle()
    val discovered by registry.discovered.collectAsStateWithLifecycle()
    val icons by viewModel<IosAppsViewModel>().icons.collectAsStateWithLifecycle()
    val searchState = rememberTextFieldState()
    val query = searchState.text.toString()
    var mode by rememberSaveable { mutableStateOf(AppListMode.MIRRORING) }

    // A runtime permission can be revoked in system settings while the persisted bridge preference remains on.
    // Reconcile that stale opt-in whenever this screen returns to the foreground so the switch reflects reality;
    // the next user attempt to enable it goes through the permission launcher below.
    LifecycleResumeEffect(bridgeEnabled) {
        if (bridgeEnabled && !IosBluetoothPermissions.hasRequired(context)) {
            graph.setIosBridgeEnabled(false)
        }
        onPauseOrDispose { }
    }

    val bridgePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (IosBluetoothPermissions.hasRequired(context)) {
                graph.setIosBridgeEnabled(true)
            } else {
                graph.setIosBridgeEnabled(false)
                Toast.makeText(
                    context,
                    resources.getString(R.string.ios_bluetooth_permission_required),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    // If the bridge was left enabled but isn't running — after a process death (status OFF) or a transient
    // failure such as Bluetooth having been off (status ERROR) — resume it now; starting the connectedDevice
    // foreground service is allowed because this tab is foregrounded. UNSUPPORTED is hardware-permanent, so
    // we don't retry it. (Re-entry is safe: the manager's own `running` guard no-ops a redundant start.)
    LaunchedEffect(bridgeEnabled, status) {
        if (bridgeEnabled && (status == IosBridgeStatus.OFF || status == IosBridgeStatus.ERROR) &&
            IosBluetoothPermissions.hasRequired(context) &&
            // Re-confirm the PERSISTED intent: right after the user toggles off, `bridgeEnabled` (DataStore-backed)
            // can still read true here while `status` has already flipped to OFF — without this the effect would
            // restart the bridge it was just asked to stop. (setIosBridgeEnabled persists before stopping.)
            settings.iosBridgeEnabledNow()
        ) {
            IosBridgeService.start(context)
        }
    }

    fun enableBridge() {
        if (IosBluetoothPermissions.hasRequired(context)) {
            graph.setIosBridgeEnabled(true)
        } else {
            bridgePermissionLauncher.launch(IosBluetoothPermissions.requestPermissions())
        }
    }

    val bluetoothAdapter = remember(context) {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    }
    var bluetoothOn by remember { mutableStateOf(bluetoothAdapter?.isEnabled == true) }
    DisposableEffect(context, bluetoothAdapter) {
        if (bluetoothAdapter == null) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> bluetoothOn = true
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> bluetoothOn = false
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    var selectedPeer by remember { mutableStateOf(IosCompanion.selectedPeer(context)) }
    val setupPeer by graph.iosDeviceRepo.setupPeer.collectAsStateWithLifecycle()
    val setupPreferences = remember(context) { IosCompanion.setupPreferences(context) }
    val appSetup by setupPreferences.appModeChanges.collectAsStateWithLifecycle(setupPreferences.usesApp)
    val pairedName = remember(selectedPeer, deviceName) {
        IosCompanion.selectedDeviceName(context, selectedPeer) ?: deviceName ?: selectedPeer?.address
    }
    fun refreshPeer() {
        selectedPeer = IosCompanion.selectedPeer(context)
        graph.iosBridgeManager?.refreshSelection()
    }
    val needsEnrollment = remember(selectedPeer, status, bluetoothOn) {
        IosCompanion.needsLeEnrollment(context, selectedPeer)
    }
    IphoneCompanionAccess(
        enabled = bridgeEnabled && (appSetup || needsEnrollment && status == IosBridgeStatus.ADVERTISING),
        appSetup = appSetup, selected = selectedPeer,
        onSelected = ::refreshPeer,
    )
    LaunchedEffect(status, deviceName, setupPeer) {
        selectedPeer = IosCompanion.selectedPeer(context)
        bluetoothOn = bluetoothAdapter?.isEnabled == true
    }

    LifecycleResumeEffect(Unit) {
        bluetoothOn = bluetoothAdapter?.isEnabled == true
        refreshPeer()
        onPauseOrDispose { }
    }

    val uiState = remember(
        bridgeEnabled,
        bluetoothAdapter,
        bluetoothOn,
        status,
        selectedPeer,
        needsEnrollment,
        appSetup,
        setupPeer,
    ) {
        resolveIosBridgeUiState(
            bridgeEnabled = bridgeEnabled,
            bluetoothAvailable = bluetoothAdapter != null,
            bluetoothOn = bluetoothOn,
            status = status,
            associated = selectedPeer != null,
            needsEnrollment = needsEnrollment,
            appSetup = appSetup,
            hasSetupPeer = setupPeer != null,
        )
    }

    fun norm(s: String) = s.lowercase(Locale.getDefault())
    val q = norm(query.trim())
    fun matches(app: IosApp) =
        q.isEmpty() || norm(app.displayName).contains(q) || norm(app.bundleId).contains(q)

    val effectiveEnabled = IosBundleIdExclusions.filterEnabled(enabled)
    val matching = discovered.values.filter { matches(it) }
    val byRecency = compareByDescending<IosApp> { it.lastSeen }.thenBy { norm(it.displayName) }
    val mirroringTitle = stringResource(R.string.ios_apps_section_mirroring)
    val recentTitle = stringResource(R.string.apps_section_recent)
    val iphoneTitle = stringResource(R.string.ios_apps_section)
    val sections: List<AppSection<IosApp>> = when (mode) {
        AppListMode.MIRRORING -> buildList {
            val on = matching.filter { it.bundleId in effectiveEnabled }
                .sortedBy { norm(it.displayName) }
            if (on.isNotEmpty()) add(AppSection("on", mirroringTitle, on))
            add(AppSection("iphone", iphoneTitle, matching.sortedWith(byRecency)))
        }
        AppListMode.NAME ->
            listOf(AppSection("iphone", iphoneTitle, matching.sortedBy { norm(it.displayName) }))
        AppListMode.RECENT -> buildList {
            val recent =
                matching.filter { it.lastSeen > 0L }.sortedWith(byRecency).take(RECENT_APP_COUNT)
            val recentKeys = recent.mapTo(HashSet()) { it.bundleId }
            val rest = matching.filter { it.bundleId !in recentKeys }
                .sortedBy { norm(it.displayName) }
            if (recent.isNotEmpty()) add(AppSection("recent", recentTitle, recent))
            add(AppSection("iphone", iphoneTitle, rest))
        }
    }
    val toggleable = matching.filterNot { IosBundleIdExclusions.isExcluded(it.bundleId) }
    val allEnabled = toggleable.isNotEmpty() && toggleable.all { it.bundleId in effectiveEnabled }
    AppListScaffold(
        title = stringResource(R.string.tab_ios),
        searchBar = { modifier, compact ->
            AppListSearchBar(
                state = searchState,
                placeholder = stringResource(R.string.ios_apps_search_hint),
                mode = mode,
                onModeChange = { mode = it },
                allEnabled = allEnabled,
                canToggleAll = toggleable.isNotEmpty(),
                onToggleAll = { on -> registry.setEnabled(toggleable.map { it.bundleId }, on) },
                modifier = modifier,
                compact = compact,
            )
        },
    ) { padding, searchBar ->
        Column(Modifier.fillMaxSize().padding(padding.topAndSides()).consumeWindowInsets(padding)) {
            searchBar()
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
            ) {
                item("connection") {
                    ConnectionCard(
                        uiState = uiState,
                        status = status,
                        deviceName = deviceName,
                        bridgeEnabled = bridgeEnabled,
                        onRetrySharing = { graph.iosBridgeManager?.retrySharing() },
                        onOpenBluetoothSettings = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            }
                        },
                        onToggle = { on ->
                            if (on) enableBridge() else graph.setIosBridgeEnabled(false)
                        },
                    )
                }
                item("pairing") {
                    PairingCard(
                        uiState = uiState,
                        associated = selectedPeer != null,
                        needsEnrollment = needsEnrollment,
                        pairedName = pairedName,
                        appSetup = appSetup,
                        setupPeer = setupPeer,
                        onModeChanged = {
                            if (bridgeEnabled) {
                                graph.iosBridgeManager?.refreshSelection()
                            }
                        },
                        onConfirmSetup = { peer ->
                            if (graph.iosBridgeManager?.confirmSetupPeer(peer.address) == true) {
                                refreshPeer()
                            } else Toast.makeText(context, resources.getString(R.string.ios_pairing_bond_lost), Toast.LENGTH_LONG).show()
                        },
                        onEnableBridge = ::enableBridge,
                        onForget = {
                            IosCompanion.disassociateAll(context)
                            refreshPeer()
                            Toast.makeText(
                                context,
                                resources.getString(R.string.ios_pairing_forget_prompt),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
                item("forwarding") {
                    ForwardingCard(
                        localDisplay = localDisplay,
                        meshMirror = meshMirror,
                        mediaMirror = mediaMirror,
                        onLocal = { on -> scope.launch { settings.setIosLocalDisplay(on) } },
                        onMesh = { on -> scope.launch { settings.setIosMeshMirror(on) } },
                        onMedia = { on -> scope.launch { settings.setIosMediaMirror(on) } },
                    )
                }
                if (matching.isEmpty()) {
                    stickyHeader(key = "header:iphone") {
                        SectionHeader(iphoneTitle, 0)
                    }
                    item("apps-empty") {
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (q.isEmpty()) stringResource(R.string.ios_apps_empty) else stringResource(
                                    R.string.ios_apps_no_match,
                                    query
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    sections.forEach { section ->
                        stickyHeader(key = "header:${section.key}") {
                            SectionHeader(section.title, section.items.size)
                        }
                        items(section.items, key = { "${section.key}:${it.bundleId}" }) { app ->
                            IosAppRow(
                                app = app,
                                isOn = app.bundleId in effectiveEnabled,
                                canToggle = !IosBundleIdExclusions.isExcluded(app.bundleId),
                                icon = icons[app.bundleId],
                                onToggle = { on -> registry.setEnabled(app.bundleId, on) },
                                onForget = { registry.forgetSeen(app.bundleId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal enum class IosConnectionBannerAction { NONE, RETRY_SHARING, OPEN_BLUETOOTH_SETTINGS }

internal sealed interface IosPairingPresentation {
    /** Matches the existing "Sharing notifications" flat ListItem row (also used when paired & waiting/off/reconnecting). */
    data object SettledRow : IosPairingPresentation

    /** BLE peripheral advertising is unsupported by hardware. */
    data object UnsupportedRow : IosPairingPresentation

    /** Elevated Material 3 setup card for unpaired, unbonded, active bonding, or incoming app-setup confirmation. */
    data class SetupCard(
        val body: SetupCardBody,
        val showBusyIndicator: Boolean,
        val showModeSelector: Boolean,
    ) : IosPairingPresentation
}

internal enum class SetupCardBody {
    BRIDGE_OFF,
    WAITING_BLUETOOTH,
    BRIDGE_ERROR,
    PAIRING_IN_PROGRESS,
    CONNECTING,
    CONFIRM_APP_PEER,
    METHOD_STEPS,
}

internal data class IosBridgeUiState(
    val connectionBannerAction: IosConnectionBannerAction,
    val showBluetoothOffStatus: Boolean,
    val showConnectedDeviceName: Boolean,
    val pairing: IosPairingPresentation,
)

internal fun resolveIosBridgeUiState(
    bridgeEnabled: Boolean,
    bluetoothAvailable: Boolean,
    bluetoothOn: Boolean,
    status: IosBridgeStatus,
    associated: Boolean,
    needsEnrollment: Boolean,
    appSetup: Boolean,
    hasSetupPeer: Boolean,
): IosBridgeUiState {
    val bluetoothOff = bridgeEnabled && status == IosBridgeStatus.ERROR && bluetoothAvailable && !bluetoothOn
    val connectionBannerAction = when {
        bridgeEnabled && status == IosBridgeStatus.NO_ANCS -> IosConnectionBannerAction.RETRY_SHARING
        bluetoothOff -> IosConnectionBannerAction.OPEN_BLUETOOTH_SETTINGS
        else -> IosConnectionBannerAction.NONE
    }
    val showConnectedDeviceName = bridgeEnabled && (
        status == IosBridgeStatus.SHARING ||
            status == IosBridgeStatus.NO_ANCS ||
            status == IosBridgeStatus.CONNECTING
        )
    val confirmAppPeer = bridgeEnabled && appSetup && hasSetupPeer
    val settledPaired = associated && !needsEnrollment && !confirmAppPeer && status != IosBridgeStatus.NEEDS_PAIRING
    val pairing = when {
        status == IosBridgeStatus.UNSUPPORTED ->
            if (associated) IosPairingPresentation.SettledRow else IosPairingPresentation.UnsupportedRow
        settledPaired -> IosPairingPresentation.SettledRow
        !bridgeEnabled -> IosPairingPresentation.SetupCard(
            body = SetupCardBody.BRIDGE_OFF,
            showBusyIndicator = false,
            showModeSelector = true,
        )
        bluetoothOff -> IosPairingPresentation.SetupCard(
            body = SetupCardBody.WAITING_BLUETOOTH,
            showBusyIndicator = false,
            showModeSelector = true,
        )
        status == IosBridgeStatus.ERROR -> IosPairingPresentation.SetupCard(
            body = SetupCardBody.BRIDGE_ERROR,
            showBusyIndicator = false,
            showModeSelector = true,
        )
        status == IosBridgeStatus.NEEDS_PAIRING -> IosPairingPresentation.SetupCard(
            body = SetupCardBody.PAIRING_IN_PROGRESS,
            showBusyIndicator = true,
            showModeSelector = false,
        )
        confirmAppPeer -> IosPairingPresentation.SetupCard(
            body = SetupCardBody.CONFIRM_APP_PEER,
            showBusyIndicator = status == IosBridgeStatus.CONNECTING,
            showModeSelector = false,
        )
        status == IosBridgeStatus.CONNECTING -> IosPairingPresentation.SetupCard(
            body = SetupCardBody.CONNECTING,
            showBusyIndicator = true,
            showModeSelector = false,
        )
        else -> IosPairingPresentation.SetupCard(
            body = SetupCardBody.METHOD_STEPS,
            showBusyIndicator = false,
            showModeSelector = true,
        )
    }
    return IosBridgeUiState(
        connectionBannerAction = connectionBannerAction,
        showBluetoothOffStatus = bluetoothOff,
        showConnectedDeviceName = showConnectedDeviceName,
        pairing = pairing,
    )
}

@Composable
private fun ConnectionCard(
    uiState: IosBridgeUiState,
    status: IosBridgeStatus,
    deviceName: String?,
    bridgeEnabled: Boolean,
    onRetrySharing: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = bridgeEnabled, role = Role.Switch, onValueChange = onToggle)
                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp)
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    stringResource(R.string.ios_bridge_card_title),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    if (uiState.showBluetoothOffStatus) stringResource(R.string.ios_status_bluetooth_off)
                    else iosStatusText(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (uiState.showConnectedDeviceName && deviceName != null) {
                    ConnectedDeviceText(deviceName)
                }
            }
            Switch(checked = bridgeEnabled, onCheckedChange = onToggle)
        }
        AnimatedVisibility(visible = uiState.connectionBannerAction != IosConnectionBannerAction.NONE) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (uiState.connectionBannerAction) {
                        IosConnectionBannerAction.RETRY_SHARING -> {
                            Text(
                                stringResource(R.string.ios_no_ancs_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FilledTonalButton(onClick = onRetrySharing) {
                                Icon(
                                    RefreshIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.ios_pairing_retry_sharing))
                            }
                        }

                        IosConnectionBannerAction.OPEN_BLUETOOTH_SETTINGS -> {
                            Text(
                                stringResource(R.string.ios_bluetooth_off_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FilledTonalButton(onClick = onOpenBluetoothSettings) {
                                Icon(
                                    SettingsIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.ios_open_bluetooth_settings))
                            }
                        }

                        IosConnectionBannerAction.NONE -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedDeviceText(deviceName: String) {
    Text(
        stringResource(R.string.ios_connected_device, deviceName),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PairingCard(
    uiState: IosBridgeUiState,
    associated: Boolean,
    needsEnrollment: Boolean,
    pairedName: String?,
    appSetup: Boolean,
    setupPeer: IosCompanion.Peer?,
    onModeChanged: () -> Unit,
    onConfirmSetup: (IosCompanion.Peer) -> Unit,
    onEnableBridge: () -> Unit,
    onForget: () -> Unit,
) {
    when (val presentation = uiState.pairing) {
        IosPairingPresentation.SettledRow,
        IosPairingPresentation.UnsupportedRow -> {
            Card(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = ComposeColor.Transparent),
                    supportingContent = {
                        Text(
                            if (presentation == IosPairingPresentation.UnsupportedRow) {
                                stringResource(R.string.ios_status_unsupported)
                            } else {
                                pairedName ?: stringResource(R.string.ios_pairing_on_unknown)
                            }
                        )
                    },
                    trailingContent = {
                        if (associated) {
                            TextButton(onClick = onForget) { Text(stringResource(R.string.ios_pairing_forget)) }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (presentation == IosPairingPresentation.UnsupportedRow) R.string.ios_pairing_title
                            else R.string.ios_pairing_title_paired
                        )
                    )
                }
            }
        }

        is IosPairingPresentation.SetupCard -> {
            Card(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (presentation.showBusyIndicator) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.5.dp,
                                    )
                                } else {
                                    Icon(
                                        PhoneIphoneIcon,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                stringResource(R.string.ios_pairing_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                when {
                                    associated && needsEnrollment ->
                                        pairedName?.let { stringResource(R.string.ios_pairing_needs_enrollment, it) }
                                            ?: stringResource(R.string.ios_pairing_needs_enrollment_unknown)
                                    associated && pairedName != null -> stringResource(R.string.ios_pairing_on, pairedName)
                                    associated -> stringResource(R.string.ios_pairing_on_unknown)
                                    presentation.body == SetupCardBody.PAIRING_IN_PROGRESS ->
                                        stringResource(R.string.ios_status_needs_pairing)
                                    presentation.body == SetupCardBody.CONNECTING ->
                                        stringResource(R.string.ios_status_connecting)
                                    else -> stringResource(R.string.ios_pairing_off)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (associated) {
                            TextButton(onClick = onForget) {
                                Text(stringResource(R.string.ios_pairing_forget))
                            }
                        } else if (presentation.body == SetupCardBody.BRIDGE_OFF) {
                            FilledTonalButton(onClick = onEnableBridge) {
                                Text(stringResource(R.string.ios_pairing_start))
                            }
                        }
                    }

                    if (presentation.showModeSelector) {
                        IphoneSetupModeSelector(
                            appSetup = appSetup,
                            onModeChanged = onModeChanged,
                        )
                    }

                    when (presentation.body) {
                        SetupCardBody.BRIDGE_OFF -> if (associated) {
                            FilledTonalButton(
                                onClick = onEnableBridge,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.onboarding_iphone_enable))
                            }
                        }

                        SetupCardBody.WAITING_BLUETOOTH -> Text(
                            stringResource(R.string.ios_pairing_waiting_bluetooth),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        SetupCardBody.BRIDGE_ERROR -> Text(
                            stringResource(R.string.ios_pairing_error_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        SetupCardBody.PAIRING_IN_PROGRESS -> Text(
                            stringResource(R.string.ios_pairing_in_progress_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        SetupCardBody.CONNECTING -> Text(
                            stringResource(R.string.ios_pairing_connecting_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        SetupCardBody.CONFIRM_APP_PEER -> setupPeer?.let { peer ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        peer.name ?: peer.address,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        stringResource(R.string.ios_app_setup_ready),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Button(
                                        onClick = { onConfirmSetup(peer) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.ios_app_setup_confirm,
                                                peer.name ?: peer.address,
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        SetupCardBody.METHOD_STEPS -> IphoneSetupSteps(appSetup = appSetup)
                    }
                }
            }
        }
    }
}

@Composable
internal fun IphoneSetupModeSelector(
    appSetup: Boolean,
    onModeChanged: () -> Unit,
    modifier: Modifier = Modifier,
    preferences: IphoneSetupPreferences = IosCompanion.setupPreferences(LocalContext.current),
) {
    fun select(useApp: Boolean) {
        preferences.selectMode(useApp)
        onModeChanged()
    }
    var confirmNative by rememberSaveable { mutableStateOf(false) }
    if (confirmNative && appSetup) {
        AlertDialog(
            onDismissRequest = { confirmNative = false },
            title = { Text(stringResource(R.string.ios_native_warning_title)) },
            text = { Text(stringResource(R.string.ios_native_warning_message)) },
            confirmButton = {
                TextButton(onClick = { confirmNative = false; select(false) }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmNative = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    val choices = listOf(
        false to stringResource(R.string.ios_setup_mode_bluetooth),
        true to stringResource(R.string.ios_setup_mode_app),
    )
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        choices.forEachIndexed { index, (isApp, label) ->
            SegmentedButton(
                selected = isApp == appSetup,
                onClick = {
                    if (isApp != appSetup) {
                        if (!isApp && preferences.warnBeforeNative) confirmNative = true
                        else select(isApp)
                    }
                },
                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
internal fun IphoneSetupSteps(
    appSetup: Boolean,
    modifier: Modifier = Modifier,
) {
    val steps = if (appSetup) {
        listOf(
            stringResource(R.string.ios_setup_step_app_1),
            stringResource(R.string.ios_setup_step_app_2),
            stringResource(R.string.ios_setup_step_app_3),
        )
    } else {
        listOf(
            stringResource(R.string.ios_setup_step_bluetooth_1),
            stringResource(R.string.ios_setup_step_bluetooth_2),
            stringResource(R.string.ios_setup_step_bluetooth_3),
        )
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { index, text ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 1.dp).size(22.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun IncomingIphonePicker(peers: List<IosCompanion.Peer>, onDismiss: () -> Unit, onSelect: (IosCompanion.Peer) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(PhoneIphoneIcon, contentDescription = null) },
        title = { Text(stringResource(R.string.ios_pairing_choose_connected)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.ios_pairing_choose_connected_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(peers, key = { it.address }) { peer ->
                        Surface(
                            onClick = { onSelect(peer) },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = ComposeColor.Transparent),
                                leadingContent = {
                                    Icon(
                                        PhoneIphoneIcon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                supportingContent = peer.name?.let {
                                    {
                                        Text(
                                            peer.address,
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                            ) {
                                Text(
                                    peer.name ?: peer.address,
                                    fontFamily = if (peer.name == null) FontFamily.Monospace else null,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ForwardingCard(
    localDisplay: Boolean,
    meshMirror: Boolean,
    mediaMirror: Boolean,
    onLocal: (Boolean) -> Unit,
    onMesh: (Boolean) -> Unit,
    onMedia: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        CompactSwitchRow(
            label = stringResource(R.string.ios_show_here),
            checked = localDisplay,
            onCheckedChange = onLocal,
        )
        CompactSwitchRow(
            label = stringResource(R.string.ios_mirror_mesh),
            checked = meshMirror,
            onCheckedChange = onMesh,
        )
        CompactSwitchRow(
            label = stringResource(R.string.ios_mirror_media),
            checked = mediaMirror,
            onCheckedChange = onMedia,
        )
    }
}

@Composable
private fun CompactSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 4.dp)
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun IosAppRow(
    app: IosApp,
    isOn: Boolean,
    canToggle: Boolean,
    icon: ImageBitmap?,
    onToggle: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    ListItem(
        modifier = Modifier.toggleable(
            value = isOn,
            enabled = canToggle,
            role = Role.Switch,
            onValueChange = onToggle,
        ),
        leadingContent = { AppIconSquare(icon) },
        supportingContent = {
            Text(
                app.bundleId,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onForget) {
                    Icon(
                        painterResource(R.drawable.ic_delete_history),
                        contentDescription = stringResource(R.string.ios_apps_forget_app),
                    )
                }
                Switch(
                    checked = isOn,
                    onCheckedChange = onToggle,
                    enabled = canToggle
                )
            }
        },
    ) {
        Text(app.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AppIconSquare(icon: ImageBitmap?) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Box(
            Modifier.size(40.dp).clip(IosIconShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
internal fun iosStatusText(status: IosBridgeStatus): String = stringResource(
    when (status) {
        IosBridgeStatus.OFF -> R.string.ios_status_off
        IosBridgeStatus.UNSUPPORTED -> R.string.ios_status_unsupported
        IosBridgeStatus.ADVERTISING -> R.string.ios_status_advertising
        IosBridgeStatus.CONNECTING -> R.string.ios_status_connecting
        IosBridgeStatus.NEEDS_PAIRING -> R.string.ios_status_needs_pairing
        IosBridgeStatus.SHARING -> R.string.ios_status_sharing
        IosBridgeStatus.NO_ANCS -> R.string.ios_status_no_ancs
        IosBridgeStatus.ERROR -> R.string.ios_status_error
    },
)
