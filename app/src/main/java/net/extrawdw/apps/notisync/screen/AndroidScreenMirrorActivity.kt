package net.extrawdw.apps.notisync.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.ui.theme.NotiSyncTheme
import net.extrawdw.notisync.protocol.ClientId

private const val LOG_TAG = "AndroidScreenViewer"

/** Foreground-only Android viewer. Leaving the Activity ends the authenticated LAN session. */
class AndroidScreenMirrorActivity : ComponentActivity() {
    private data class ActiveAttempt(
        val ownerToken: String,
        var decoder: AndroidScreenVideoDecoder? = null,
    )

    private val attemptLock = Any()
    private var activeAttempt: ActiveAttempt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This Activity is a separate Recents task; never retain a thumbnail of the remote screen.
        setRecentsScreenshotEnabled(false)
        val rawSourceId = intent.getStringExtra(EXTRA_SOURCE_ID)?.takeIf(String::isNotBlank)
        if (rawSourceId == null) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        hideNavigationChrome()
        setContent {
            NotiSyncTheme {
                AndroidScreenMirrorViewer(
                    activity = this,
                    sourceId = ClientId(rawSourceId),
                    onImeDismissed = ::hideNavigationChrome,
                    onClose = {
                        closeActiveSession()
                        finish()
                    },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Transient system bars can reappear after dialogs or the software keyboard. Restore the
        // viewer's immersive navigation state without hiding the status bar.
        if (hasFocus) hideNavigationChrome()
    }

    private fun hideNavigationChrome() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            show(WindowInsetsCompat.Type.statusBars())
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onStop() {
        // There is deliberately no requester FGS. A viewer that is no longer visible relinquishes
        // its sockets and tells the source to stop capture instead of continuing in the background.
        if (!isChangingConfigurations) closeActiveSession()
        super.onStop()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) closeActiveSession()
        super.onDestroy()
    }

    internal fun closeActiveSession() {
        val attempt = synchronized(attemptLock) {
            activeAttempt.also { activeAttempt = null }
        } ?: return
        runCatching { attempt.decoder?.close() }
        requester()?.closeOwner(attempt.ownerToken)
    }

    internal fun beginAttempt(ownerToken: String) {
        val previous = synchronized(attemptLock) {
            activeAttempt.also { activeAttempt = ActiveAttempt(ownerToken) }
        }
        if (previous != null) {
            runCatching { previous.decoder?.close() }
            requester()?.closeOwner(previous.ownerToken, "Android viewer attempt replaced")
        }
    }

    internal fun attachDecoder(ownerToken: String, decoder: AndroidScreenVideoDecoder): Boolean {
        val previous = synchronized(attemptLock) {
            val attempt = activeAttempt?.takeIf { it.ownerToken == ownerToken }
                ?: return@synchronized null
            attempt.decoder.also { attempt.decoder = decoder }
        }
        val attached = synchronized(attemptLock) {
            activeAttempt?.let { it.ownerToken == ownerToken && it.decoder === decoder } == true
        }
        if (attached) {
            if (previous !== decoder) runCatching { previous?.close() }
        } else {
            runCatching { decoder.close() }
        }
        return attached
    }

    internal fun detachDecoder(ownerToken: String, decoder: AndroidScreenVideoDecoder?) {
        if (decoder == null) return
        synchronized(attemptLock) {
            activeAttempt?.takeIf { it.ownerToken == ownerToken && it.decoder === decoder }
                ?.decoder = null
        }
    }

    internal fun isCurrentAttempt(ownerToken: String): Boolean = synchronized(attemptLock) {
        activeAttempt?.ownerToken == ownerToken
    }

    internal fun finishAttempt(ownerToken: String) {
        val attempt = synchronized(attemptLock) {
            activeAttempt?.takeIf { it.ownerToken == ownerToken }?.also { activeAttempt = null }
        }
        runCatching { attempt?.decoder?.close() }
    }

    internal fun closeAttempt(ownerToken: String, detail: String) {
        val attempt = synchronized(attemptLock) {
            activeAttempt?.takeIf { it.ownerToken == ownerToken }?.also { activeAttempt = null }
        } ?: return
        runCatching { attempt.decoder?.close() }
        requester()?.closeOwner(ownerToken, detail)
    }

    private fun requester(): AndroidScreenMirrorRequester? =
        (applicationContext as NotiSyncApp).graphIfReady?.screenMirrorRequester

    companion object {
        private const val EXTRA_SOURCE_ID = "net.extrawdw.apps.notisync.screen.SOURCE_ID"

        fun intent(context: Context, sourceId: ClientId): Intent =
            Intent(context, AndroidScreenMirrorActivity::class.java)
                .putExtra(EXTRA_SOURCE_ID, sourceId.value)
                // The viewer is the root of its own task. CLEAR_TOP replaces an older viewer
                // instance instead of stacking two Activities which would contend for one session.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

private enum class AndroidViewerUiPhase { PREPARING, CONNECTING, CONNECTED, ENDED, ERROR }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AndroidScreenMirrorViewer(
    activity: AndroidScreenMirrorActivity,
    sourceId: ClientId,
    onImeDismissed: () -> Unit,
    onClose: () -> Unit,
) {
    val app = activity.applicationContext as NotiSyncApp
    var graph by remember { mutableStateOf(app.graphIfReady) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var dimensions by remember { mutableStateOf<ScrcpySessionDimensions?>(null) }
    var control by remember { mutableStateOf<AndroidScreenControlDispatcher?>(null) }
    var sourceName by remember { mutableStateOf(sourceId.shortForm()) }
    var codecName by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(AndroidViewerUiPhase.PREPARING) }
    var detail by remember { mutableStateOf<String?>(null) }
    var retryGeneration by remember { mutableIntStateOf(0) }
    var imeView by remember { mutableStateOf<AndroidScreenImeView?>(null) }
    var toolbarPreferences by remember {
        mutableStateOf(
            graph?.screenViewerToolbarPreferences?.preferences?.value
                ?: ScreenViewerToolbarPreferences(),
        )
    }
    val uiHandler = remember { Handler(Looper.getMainLooper()) }
    val toolbarPreferenceStore = graph?.screenViewerToolbarPreferences

    val localPermissionRequired = Build.VERSION.SDK_INT >= 37
    val wifiAwareSupported = remember {
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    }
    var localNetworkGranted by remember {
        mutableStateOf(
            !localPermissionRequired || ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var nearbyWifiGranted by remember {
        mutableStateOf(
            wifiAwareSupported && ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionResolved by remember { mutableStateOf(false) }
    // On API 37 every direct socket, including an Aware NDP's IPv6 link-local socket, is gated by
    // ACCESS_LOCAL_NETWORK. Before API 37 this state is initialized to true.
    val canAttemptConnection = localNetworkGranted

    fun missingConnectionPermissions(): Array<String> = buildList {
        if (!localNetworkGranted && localPermissionRequired) {
            add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (wifiAwareSupported && !nearbyWifiGranted) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        localNetworkGranted = !localPermissionRequired ||
            grants[Manifest.permission.ACCESS_LOCAL_NETWORK] == true ||
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED
        nearbyWifiGranted = wifiAwareSupported &&
            (grants[Manifest.permission.NEARBY_WIFI_DEVICES] == true ||
                ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                ) == PackageManager.PERMISSION_GRANTED)
        permissionResolved = true
        if (!localNetworkGranted) {
            phase = AndroidViewerUiPhase.ERROR
            detail = activity.getString(R.string.screen_viewer_local_network_denied)
        }
    }

    LaunchedEffect(Unit) {
        if (graph == null) graph = app.awaitGraphReady()
        val missing = missingConnectionPermissions()
        if (missing.isEmpty()) {
            permissionResolved = true
        } else {
            permissionLauncher.launch(missing)
        }
    }

    LaunchedEffect(graph, sourceId) {
        graph?.trust?.displayName(sourceId)?.let { sourceName = it }
    }

    LaunchedEffect(toolbarPreferenceStore) {
        toolbarPreferenceStore?.preferences?.collect { toolbarPreferences = it }
    }

    LaunchedEffect(
        graph,
        permissionResolved,
        localNetworkGranted,
        nearbyWifiGranted,
        surface,
        retryGeneration,
    ) {
        val readyGraph = graph ?: return@LaunchedEffect
        val requester = readyGraph.screenMirrorRequester ?: return@LaunchedEffect
        val outputSurface = surface?.takeIf(Surface::isValid) ?: return@LaunchedEffect
        if (!permissionResolved || !canAttemptConnection) return@LaunchedEffect

        val ownerToken = UUID.randomUUID().toString()
        var session: AndroidViewerSession? = null
        var dispatcher: AndroidScreenControlDispatcher? = null
        var decoder: AndroidScreenVideoDecoder? = null
        val controlFailure = AtomicReference<Throwable?>()
        activity.beginAttempt(ownerToken)
        phase = AndroidViewerUiPhase.CONNECTING
        detail = null
        dimensions = null
        codecName = null
        try {
            session = requester.open(sourceId, ownerToken)
            sourceName = session.sourceName
            codecName = session.codec.name.lowercase()
            val writer = AndroidScreenControlWriter(session.controlOutput)
            val controlDispatcher = AndroidScreenControlDispatcher(writer) { error ->
                controlFailure.compareAndSet(null, error)
                Log.e(LOG_TAG, "Screen control channel failed", error)
                uiHandler.post {
                    if (activity.isCurrentAttempt(ownerToken)) {
                        phase = AndroidViewerUiPhase.ERROR
                        detail = error.message
                            ?: activity.getString(R.string.screen_viewer_control_failed)
                        activity.closeAttempt(ownerToken, "screen control channel failed")
                    }
                }
            }
            dispatcher = controlDispatcher
            control = controlDispatcher
            decoder = AndroidScreenVideoDecoder(
                input = session.videoInput,
                expectedCodec = session.codec,
                surface = outputSurface,
                hardwareDecoderName = readyGraph.screenMirrorDecoderSupport
                    .hardwareDecoderName(session.codec),
                onDimensionsChanged = { next ->
                    uiHandler.post { dimensions = next }
                },
            )
            check(activity.attachDecoder(ownerToken, decoder)) { "screen viewer attempt was replaced" }
            phase = AndroidViewerUiPhase.CONNECTED
            withContext(Dispatchers.IO) { decoder.decode() }
            if (activity.isCurrentAttempt(ownerToken)) {
                val controlError = controlFailure.get()
                if (controlError == null) {
                    phase = AndroidViewerUiPhase.ENDED
                    detail = requester.state.value.detail
                        ?: activity.getString(R.string.screen_viewer_ended)
                } else {
                    phase = AndroidViewerUiPhase.ERROR
                    detail = controlError.message
                        ?: activity.getString(R.string.screen_viewer_control_failed)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (activity.isCurrentAttempt(ownerToken)) {
                val controlError = controlFailure.get()
                phase = AndroidViewerUiPhase.ERROR
                detail = requester.state.value.detail
                    ?: controlError?.message?.takeIf(String::isNotBlank)
                    ?: error.message?.takeIf(String::isNotBlank)
                    ?: activity.getString(R.string.screen_viewer_failed)
            }
        } finally {
            activity.detachDecoder(ownerToken, decoder)
            runCatching { decoder?.close() }
            runCatching { dispatcher?.close() }
            runCatching { session?.close() }
            if (control === dispatcher) control = null
            activity.finishAttempt(ownerToken)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity.closeActiveSession()
        }
    }

    BackHandler(onBack = onClose)

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val toolbarInsets = WindowInsets.safeDrawing
            .only(
                WindowInsetsSides.Top +
                    WindowInsetsSides.Bottom +
                    WindowInsetsSides.Horizontal,
            )
            .union(
                WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom),
            )
        val density = LocalDensity.current
        val toolbarOuterHeightPx = toolbarInsets.getTop(density) +
            toolbarInsets.getBottom(density) +
            with(density) {
                (TOOLBAR_HEIGHT_DP + TOOLBAR_VERTICAL_MARGIN_DP * 2).dp.roundToPx()
            }
        val toolbarAnchors = remember(constraints.maxHeight, toolbarOuterHeightPx) {
            DraggableAnchors {
                ScreenViewerToolbarEdge.TOP at 0f
                ScreenViewerToolbarEdge.BOTTOM at screenViewerToolbarTravelDistance(
                    viewportHeightPx = constraints.maxHeight,
                    toolbarHeightPx = toolbarOuterHeightPx,
                )
            }
        }
        val persistedToolbarEdge = toolbarPreferenceStore?.preferences?.value?.edge
            ?: toolbarPreferences.edge
        val toolbarDragState = remember(toolbarPreferenceStore) {
            AnchoredDraggableState(
                initialValue = persistedToolbarEdge,
                anchors = toolbarAnchors,
            )
        }

        SideEffect {
            toolbarDragState.updateAnchors(
                toolbarAnchors,
                // Preserve the intended edge if the viewport or its system insets change.
                newTarget = toolbarDragState.targetValue,
            )
        }

        LaunchedEffect(toolbarDragState, toolbarPreferenceStore) {
            val store = toolbarPreferenceStore ?: return@LaunchedEffect
            snapshotFlow { toolbarDragState.settledValue }
                .distinctUntilChanged()
                .collect { settledEdge ->
                    if (store.preferences.value.edge != settledEdge) {
                        // Use the application scope so an immediate Activity close cannot cancel
                        // the preference write made when the finger is released.
                        graph?.scope?.launch { store.setEdge(settledEdge) }
                    }
                }
        }

        LaunchedEffect(persistedToolbarEdge, toolbarDragState, toolbarAnchors) {
            // A late DataStore emission (for example, from the prior viewer Activity) remains
            // authoritative, but this effect does not restart merely because a drag settles.
            if (toolbarDragState.settledValue != persistedToolbarEdge) {
                toolbarDragState.snapTo(persistedToolbarEdge)
            }
        }

        // The video owns the complete edge-to-edge window; lightweight chrome floats over it.
        AndroidScreenSurface(
            dimensions = dimensions,
            control = control,
            onSurface = { next ->
                if (next == null && surface != null) {
                    activity.closeActiveSession()
                }
                surface = next
            },
        )

        // Keep the requested status bar visible and its white icons readable over arbitrary
        // mirrored content without reserving any additional vertical space.
        Spacer(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Color.Black.copy(alpha = 0.42f)),
        )

        when (phase) {
            AndroidViewerUiPhase.PREPARING, AndroidViewerUiPhase.CONNECTING -> ViewerProgress(
                if (phase == AndroidViewerUiPhase.PREPARING) {
                    stringResource(R.string.screen_viewer_preparing)
                } else {
                    stringResource(R.string.screen_viewer_connecting, sourceName)
                },
            )

            AndroidViewerUiPhase.ERROR, AndroidViewerUiPhase.ENDED -> ViewerMessage(
                message = detail ?: stringResource(R.string.screen_viewer_failed),
                primaryLabel = if (!canAttemptConnection) {
                    stringResource(R.string.screen_viewer_grant_local_network)
                } else {
                    stringResource(R.string.screen_viewer_retry)
                },
                onPrimary = {
                    if (!canAttemptConnection) {
                        permissionResolved = false
                        permissionLauncher.launch(missingConnectionPermissions())
                    } else {
                        retryGeneration++
                    }
                },
                onClose = onClose,
            )

            AndroidViewerUiPhase.CONNECTED -> Unit
        }

        AndroidScreenViewerToolbar(
            sourceName = sourceName,
            codecName = codecName,
            dragState = toolbarDragState,
            selectedControls = toolbarPreferences.pinnedControls,
            enabled = control != null && phase == AndroidViewerUiPhase.CONNECTED,
            control = control,
            onShowKeyboard = { imeView?.showKeyboardWhenWindowFocused() },
            onClose = onClose,
            onControlVisibilityChanged = { viewerControl, visible ->
                if (
                    (viewerControl in toolbarPreferences.pinnedControls) != visible &&
                    toolbarPreferenceStore != null
                ) {
                    graph?.scope?.launch {
                        toolbarPreferenceStore.setControlPinned(viewerControl, visible)
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset {
                    val offset = toolbarDragState.offset
                    IntOffset(
                        x = 0,
                        y = if (offset.isNaN()) 0 else offset.roundToInt(),
                    )
                }
                .windowInsetsPadding(toolbarInsets)
                .padding(
                    horizontal = 8.dp,
                    vertical = TOOLBAR_VERTICAL_MARGIN_DP.dp,
                ),
        )

        // A real, attached text-editor View gives every IME a standard InputConnection without
        // retaining typed text in Compose state or rendering it into the viewer hierarchy.
        AndroidView(
            factory = { context ->
                AndroidScreenImeView(context, onImeDismissed).also { imeView = it }
            },
            update = { view -> view.control = control },
            modifier = Modifier.align(Alignment.Center).size(1.dp),
        )
    }
}

@Composable
private fun AndroidScreenViewerToolbar(
    sourceName: String,
    codecName: String?,
    dragState: AnchoredDraggableState<ScreenViewerToolbarEdge>,
    selectedControls: Set<ScreenViewerControl>,
    enabled: Boolean,
    control: AndroidScreenControlDispatcher?,
    onShowKeyboard: () -> Unit,
    onClose: () -> Unit,
    onControlVisibilityChanged: (ScreenViewerControl, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var customizeControls by remember { mutableStateOf(false) }
    val toolbarScope = rememberCoroutineScope()
    val toolbarFlingBehavior = AnchoredDraggableDefaults.flingBehavior(state = dragState)
    val edge = dragState.settledValue

    BoxWithConstraints(modifier = modifier.widthIn(max = 720.dp).fillMaxWidth()) {
        val directControlSlots = screenViewerDirectControlSlots(maxWidth.value)
        val controlLayout = screenViewerControlLayout(selectedControls, directControlSlots)
        val directControls = controlLayout.direct
        val overflowControls = controlLayout.overflow

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .anchoredDraggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    flingBehavior = toolbarFlingBehavior,
                ),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TOOLBAR_HEIGHT_DP.dp)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.screen_viewer_close),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        sourceName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    codecName?.let {
                        Text(
                            stringResource(R.string.screen_viewer_connected_codec, it),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                directControls.forEach { viewerControl ->
                    ScreenFunctionButton(
                        enabled = enabled,
                        icon = viewerControl.icon(),
                        label = stringResource(viewerControl.labelResource()),
                    ) {
                        performScreenViewerControl(viewerControl, control, onShowKeyboard)
                    }
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.screen_viewer_more_options),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        overflowControls.forEach { viewerControl ->
                            DropdownMenuItem(
                                text = { Text(stringResource(viewerControl.labelResource())) },
                                leadingIcon = {
                                    Icon(viewerControl.icon(), contentDescription = null)
                                },
                                enabled = enabled,
                                onClick = {
                                    menuExpanded = false
                                    performScreenViewerControl(
                                        viewerControl,
                                        control,
                                        onShowKeyboard,
                                    )
                                },
                            )
                        }
                        if (overflowControls.isNotEmpty()) HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screen_viewer_customize_controls)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Settings, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                customizeControls = true
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (edge == ScreenViewerToolbarEdge.TOP) {
                                            R.string.screen_viewer_move_toolbar_bottom
                                        } else {
                                            R.string.screen_viewer_move_toolbar_top
                                        },
                                    ),
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (edge == ScreenViewerToolbarEdge.TOP) {
                                        Icons.Outlined.VerticalAlignBottom
                                    } else {
                                        Icons.Outlined.VerticalAlignTop
                                    },
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                val nextEdge =
                                    if (edge == ScreenViewerToolbarEdge.TOP) {
                                        ScreenViewerToolbarEdge.BOTTOM
                                    } else {
                                        ScreenViewerToolbarEdge.TOP
                                    }
                                toolbarScope.launch { dragState.animateTo(nextEdge) }
                            },
                        )
                    }
                }
            }
        }

        if (customizeControls) {
            ScreenViewerControlDialog(
                selectedControls = selectedControls,
                maximumControls = directControlSlots,
                onControlVisibilityChanged = onControlVisibilityChanged,
                onDismissRequest = { customizeControls = false },
            )
        }
    }
}

@Composable
private fun ScreenViewerControlDialog(
    selectedControls: Set<ScreenViewerControl>,
    maximumControls: Int,
    onControlVisibilityChanged: (ScreenViewerControl, Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.screen_viewer_toolbar_controls)) },
        text = {
            Column {
                Text(
                    pluralStringResource(
                        R.plurals.screen_viewer_toolbar_control_limit,
                        maximumControls,
                        maximumControls,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ScreenViewerControl.entries.forEach { viewerControl ->
                    val checked = viewerControl in selectedControls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { selected ->
                                    onControlVisibilityChanged(viewerControl, selected)
                                },
                            )
                            .padding(horizontal = 4.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(viewerControl.icon(), contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(viewerControl.labelResource()),
                            modifier = Modifier.weight(1f),
                        )
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.screen_viewer_done))
            }
        },
    )
}

private fun ScreenViewerControl.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    ScreenViewerControl.BACK -> Icons.AutoMirrored.Outlined.ArrowBack
    ScreenViewerControl.HOME -> Icons.Outlined.Home
    ScreenViewerControl.RECENTS -> Icons.Outlined.Apps
    ScreenViewerControl.KEYBOARD -> Icons.Outlined.Keyboard
    ScreenViewerControl.POWER -> Icons.Outlined.PowerSettingsNew
}

private fun ScreenViewerControl.labelResource(): Int = when (this) {
    ScreenViewerControl.BACK -> R.string.screen_viewer_back
    ScreenViewerControl.HOME -> R.string.screen_viewer_home
    ScreenViewerControl.RECENTS -> R.string.screen_viewer_recents
    ScreenViewerControl.KEYBOARD -> R.string.screen_viewer_keyboard
    ScreenViewerControl.POWER -> R.string.screen_viewer_power
}

private fun performScreenViewerControl(
    viewerControl: ScreenViewerControl,
    control: AndroidScreenControlDispatcher?,
    onShowKeyboard: () -> Unit,
) {
    when (viewerControl) {
        ScreenViewerControl.BACK -> control?.sendKeyPress(KeyEvent.KEYCODE_BACK)
        ScreenViewerControl.HOME -> control?.sendKeyPress(KeyEvent.KEYCODE_HOME)
        ScreenViewerControl.RECENTS -> control?.sendKeyPress(KeyEvent.KEYCODE_APP_SWITCH)
        ScreenViewerControl.KEYBOARD -> if (control != null) onShowKeyboard()
        ScreenViewerControl.POWER -> control?.togglePower()
    }
}

internal fun screenViewerToolbarTravelDistance(
    viewportHeightPx: Int,
    toolbarHeightPx: Int,
): Float = (viewportHeightPx - toolbarHeightPx).coerceAtLeast(1).toFloat()

internal fun screenViewerDirectControlSlots(widthDp: Float): Int =
    ((widthDp - TOOLBAR_FIXED_WIDTH_DP) / TOOLBAR_CONTROL_WIDTH_DP)
        .toInt()
        .coerceIn(1, ScreenViewerControl.entries.size)

internal data class ScreenViewerControlLayout(
    val direct: List<ScreenViewerControl>,
    val overflow: List<ScreenViewerControl>,
)

internal fun screenViewerControlLayout(
    selectedControls: Set<ScreenViewerControl>,
    directControlSlots: Int,
): ScreenViewerControlLayout {
    val direct = ScreenViewerControl.entries
        .filter(selectedControls::contains)
        .take(directControlSlots.coerceAtLeast(0))
    return ScreenViewerControlLayout(
        direct = direct,
        overflow = ScreenViewerControl.entries.filterNot(direct::contains),
    )
}

private const val TOOLBAR_FIXED_WIDTH_DP = 196f
private const val TOOLBAR_CONTROL_WIDTH_DP = 48f
private const val TOOLBAR_HEIGHT_DP = 56f
private const val TOOLBAR_VERTICAL_MARGIN_DP = 6f

@Composable
private fun AndroidScreenSurface(
    dimensions: ScrcpySessionDimensions?,
    control: AndroidScreenControlDispatcher?,
    onSurface: (Surface?) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ratio = dimensions?.let { it.width.toFloat() / it.height } ?: (9f / 16f)
        val availableRatio = if (maxHeight.value == 0f) ratio else maxWidth.value / maxHeight.value
        val viewWidth = if (ratio >= availableRatio) maxWidth else maxHeight * ratio
        val viewHeight = if (ratio >= availableRatio) maxWidth / ratio else maxHeight

        AndroidView(
            factory = { context ->
                AndroidScreenSurfaceView(context).apply {
                    holder.setFormat(PixelFormat.OPAQUE)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            onSurface(holder.surface)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
                    })
                }
            },
            update = { view ->
                view.control = control
                view.sourceDimensions = dimensions
            },
            modifier = Modifier.width(viewWidth).height(viewHeight),
        )
    }
}

private class AndroidScreenSurfaceView(context: Context) : SurfaceView(context) {
    var control: AndroidScreenControlDispatcher? = null
    var sourceDimensions: ScrcpySessionDimensions? = null

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val dispatcher = control ?: return true
        val source = sourceDimensions ?: return true
        if (width <= 0 || height <= 0) return true

        val action = event.actionMasked
        val indexes: IntRange = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.actionIndex..event.actionIndex
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL -> 0 until event.pointerCount
            else -> return true
        }
        val remoteAction = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> MotionEvent.ACTION_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> MotionEvent.ACTION_UP
            MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE
            else -> MotionEvent.ACTION_CANCEL
        }
        val touches = indexes.map { index ->
            AndroidScreenTouch(
                action = remoteAction,
                pointerId = event.getPointerId(index).toLong(),
                x = mapAndroidScreenTouchCoordinate(event.getX(index), width, source.width),
                y = mapAndroidScreenTouchCoordinate(event.getY(index), height, source.height),
                sourceWidth = source.width,
                sourceHeight = source.height,
                pressure = event.getPressure(index),
            )
        }
        if (touches.isNotEmpty()) dispatcher.sendTouches(touches)
        return true
    }
}

/**
 * Transparent editor used only to obtain the viewer device's IME. It has no editable buffer:
 * committed text is immediately divided into bounded scrcpy frames, while composition updates are
 * deliberately ignored so predictive IMEs cannot duplicate partially composed text remotely.
 */
private class AndroidScreenImeView(
    context: Context,
    onImeDismissed: () -> Unit,
) : View(context) {
    var control: AndroidScreenControlDispatcher? = null
        set(value) {
            field = value
            if (value == null && hasFocus()) {
                inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
                clearFocus()
            }
        }

    private val inputMethodManager: InputMethodManager =
        context.getSystemService(InputMethodManager::class.java)
    private var imeWasVisible = false

    init {
        id = generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = false
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeWasVisible && !imeVisible) post(onImeDismissed)
            imeWasVisible = imeVisible
            insets
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                text == null || forwardCommittedText(text)

            // Only final commits are forwarded. Password input normally commits each key. The
            // source still uses Android KeyCharacterMap, so characters absent from that map cannot
            // be reproduced even when a complex IME commits them successfully on the viewer.
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true

            override fun setComposingRegion(start: Int, end: Int): Boolean = true

            override fun finishComposingText(): Boolean = true

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                control?.let {
                    forwardScreenImeDeletion(it, beforeLength, afterLength)
                } ?: false

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean = control?.let {
                forwardScreenImeDeletion(it, beforeLength, afterLength)
            } ?: false

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val remoteKey = when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> KeyEvent.KEYCODE_DEL
                    KeyEvent.KEYCODE_FORWARD_DEL -> KeyEvent.KEYCODE_FORWARD_DEL
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyEvent.KEYCODE_ENTER
                    else -> return false
                }
                // The dispatcher writes its own adjacent down/up pair; consume the IME's matching UP.
                return event.action != KeyEvent.ACTION_DOWN ||
                    control?.sendKeyPress(remoteKey) == true
            }

            override fun performEditorAction(actionCode: Int): Boolean =
                control?.sendKeyPress(KeyEvent.KEYCODE_ENTER) == true
        }
    }

    fun showKeyboard(): Boolean {
        if (control == null || !isAttachedToWindow) return false
        if (!requestFocus()) return false
        post {
            if (control != null && hasFocus()) {
                inputMethodManager.restartInput(this)
                inputMethodManager.showSoftInput(this, 0)
            }
        }
        return true
    }

    /** Waits for a focusable Compose popup to release the Activity window before opening the IME. */
    fun showKeyboardWhenWindowFocused() {
        val deadline = SystemClock.uptimeMillis() + IME_WINDOW_FOCUS_TIMEOUT_MS
        lateinit var attempt: Runnable
        attempt = Runnable {
            if (control == null || !isAttachedToWindow) return@Runnable
            if (hasWindowFocus()) {
                showKeyboard()
            } else if (SystemClock.uptimeMillis() < deadline) {
                postOnAnimation(attempt)
            }
        }
        postOnAnimation(attempt)
    }

    private fun forwardCommittedText(text: CharSequence): Boolean {
        val dispatcher = control ?: return false
        // Snapshot the IME-owned CharSequence for this call only. It is never saved or logged.
        val committed = text.toString()
        if (!isBoundedImeCommit(committed)) return false

        var chunkStart = 0
        var chunkBytes = 0
        var index = 0
        while (index < committed.length) {
            val current = committed[index]
            if (current == '\r' || current == '\n') {
                if (!forwardTextRange(dispatcher, committed, chunkStart, index)) return false
                if (!dispatcher.sendKeyPress(KeyEvent.KEYCODE_ENTER)) return false
                index += if (current == '\r' && committed.getOrNull(index + 1) == '\n') 2 else 1
                chunkStart = index
                chunkBytes = 0
                continue
            }

            val codeUnitCount = if (
                Character.isHighSurrogate(current) &&
                committed.getOrNull(index + 1)?.let(Character::isLowSurrogate) == true
            ) 2 else 1
            val nextBytes = utf8BytesForCodePointAt(committed, index)
            if (chunkBytes + nextBytes > SCRCPY_INJECT_TEXT_MAX_BYTES) {
                if (!forwardTextRange(dispatcher, committed, chunkStart, index)) return false
                chunkStart = index
                chunkBytes = 0
            }
            chunkBytes += nextBytes
            index += codeUnitCount
        }
        return forwardTextRange(dispatcher, committed, chunkStart, committed.length)
    }

    private fun forwardTextRange(
        dispatcher: AndroidScreenControlDispatcher,
        text: String,
        start: Int,
        end: Int,
    ): Boolean = start == end || dispatcher.sendText(text.substring(start, end))

    companion object {
        // Bound one callback below the dispatcher's queue capacity, including newline key events.
        const val MAX_IME_COMMANDS = 8
        const val MAX_IME_COMMIT_BYTES = SCRCPY_INJECT_TEXT_MAX_BYTES * 8
        private const val IME_WINDOW_FOCUS_TIMEOUT_MS = 1_000L
    }
}

/** Mirrors the exact bounded deletion count instead of acknowledging an IME request only once. */
internal fun forwardScreenImeDeletion(
    dispatcher: AndroidScreenControlDispatcher,
    beforeLength: Int,
    afterLength: Int,
): Boolean {
    if (beforeLength < 0 || afterLength < 0 ||
        beforeLength.toLong() + afterLength > MAX_IME_DELETE_KEYS
    ) return false
    repeat(beforeLength) {
        if (!dispatcher.sendKeyPress(KeyEvent.KEYCODE_DEL)) return false
    }
    repeat(afterLength) {
        if (!dispatcher.sendKeyPress(KeyEvent.KEYCODE_FORWARD_DEL)) return false
    }
    return true
}

private const val MAX_IME_DELETE_KEYS = 32

private fun isBoundedImeCommit(text: CharSequence): Boolean {
    var totalBytes = 0
    var frameBytes = 0
    var commandCount = 0
    var index = 0
    while (index < text.length) {
        val current = text[index]
        if (current == '\r' || current == '\n') {
            if (frameBytes > 0) commandCount++
            commandCount++
            frameBytes = 0
            index += if (current == '\r' && text.getOrNull(index + 1) == '\n') 2 else 1
        } else {
            val nextBytes = utf8BytesForCodePointAt(text, index)
            totalBytes += nextBytes
            if (totalBytes > AndroidScreenImeView.MAX_IME_COMMIT_BYTES) return false
            if (frameBytes + nextBytes > SCRCPY_INJECT_TEXT_MAX_BYTES) {
                commandCount++
                frameBytes = 0
            }
            frameBytes += nextBytes
            index += if (
                Character.isHighSurrogate(current) &&
                text.getOrNull(index + 1)?.let(Character::isLowSurrogate) == true
            ) 2 else 1
        }
        if (commandCount > AndroidScreenImeView.MAX_IME_COMMANDS) return false
    }
    if (frameBytes > 0) commandCount++
    return commandCount <= AndroidScreenImeView.MAX_IME_COMMANDS
}

private fun utf8BytesForCodePointAt(text: CharSequence, index: Int): Int {
    val current = text[index]
    return when {
        current.code <= 0x7f -> 1
        current.code <= 0x7ff -> 2
        Character.isHighSurrogate(current) &&
            text.getOrNull(index + 1)?.let(Character::isLowSurrogate) == true -> 4
        Character.isSurrogate(current) -> 1
        else -> 3
    }
}

@Composable
private fun ScreenFunctionButton(
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun ViewerProgress(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
            Text(label)
        }
    }
}

@Composable
private fun ViewerMessage(
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPrimary) { Text(primaryLabel) }
                Button(onClick = onClose) { Text(stringResource(R.string.screen_viewer_close)) }
            }
        }
    }
}
