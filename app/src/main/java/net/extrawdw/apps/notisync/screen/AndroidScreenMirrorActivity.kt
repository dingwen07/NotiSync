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
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        setContent {
            NotiSyncTheme {
                AndroidScreenMirrorViewer(
                    activity = this,
                    sourceId = ClientId(rawSourceId),
                    onClose = {
                        closeActiveSession()
                        finish()
                    },
                )
            }
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

@Composable
private fun AndroidScreenMirrorViewer(
    activity: AndroidScreenMirrorActivity,
    sourceId: ClientId,
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
    val uiHandler = remember { Handler(Looper.getMainLooper()) }

    val permissionRequired = Build.VERSION.SDK_INT >= 37
    var localNetworkGranted by remember {
        mutableStateOf(
            !permissionRequired || ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionResolved by remember { mutableStateOf(localNetworkGranted) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        localNetworkGranted = granted
        permissionResolved = true
        if (!granted) {
            phase = AndroidViewerUiPhase.ERROR
            detail = activity.getString(R.string.screen_viewer_local_network_denied)
        }
    }

    LaunchedEffect(Unit) {
        if (graph == null) graph = app.awaitGraphReady()
        if (permissionRequired && !localNetworkGranted && !permissionResolved) {
            permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    LaunchedEffect(graph, sourceId) {
        graph?.trust?.displayName(sourceId)?.let { sourceName = it }
    }

    LaunchedEffect(graph, localNetworkGranted, surface, retryGeneration) {
        val requester = graph?.screenMirrorRequester ?: return@LaunchedEffect
        val outputSurface = surface?.takeIf(Surface::isValid) ?: return@LaunchedEffect
        if (!localNetworkGranted) return@LaunchedEffect

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(sourceName, maxLines = 1)
                        codecName?.let {
                            Text(
                                stringResource(R.string.screen_viewer_connected_codec, it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.screen_viewer_close),
                        )
                    }
                },
            )
        },
        bottomBar = {
            AndroidScreenFunctionBar(
                enabled = control != null && phase == AndroidViewerUiPhase.CONNECTED,
                control = control,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
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
                    primaryLabel = if (!localNetworkGranted) {
                        stringResource(R.string.screen_viewer_grant_local_network)
                    } else {
                        stringResource(R.string.screen_viewer_retry)
                    },
                    onPrimary = {
                        if (!localNetworkGranted) {
                            permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                        } else {
                            retryGeneration++
                        }
                    },
                    onClose = onClose,
                )

                AndroidViewerUiPhase.CONNECTED -> Unit
            }
        }
    }
}

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

@Composable
private fun AndroidScreenFunctionBar(
    enabled: Boolean,
    control: AndroidScreenControlDispatcher?,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenFunctionButton(
                enabled,
                Icons.AutoMirrored.Outlined.ArrowBack,
                stringResource(R.string.screen_viewer_back),
            ) { control?.sendKeyPress(KeyEvent.KEYCODE_BACK) }
            ScreenFunctionButton(
                enabled,
                Icons.Outlined.Home,
                stringResource(R.string.screen_viewer_home),
            ) { control?.sendKeyPress(KeyEvent.KEYCODE_HOME) }
            ScreenFunctionButton(
                enabled,
                Icons.Outlined.Apps,
                stringResource(R.string.screen_viewer_recents),
            ) { control?.sendKeyPress(KeyEvent.KEYCODE_APP_SWITCH) }
            ScreenFunctionButton(
                enabled,
                Icons.Outlined.PowerSettingsNew,
                stringResource(R.string.screen_viewer_power),
            ) { control?.togglePower() }
        }
    }
}

@Composable
private fun ScreenFunctionButton(
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ViewerProgress(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
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
