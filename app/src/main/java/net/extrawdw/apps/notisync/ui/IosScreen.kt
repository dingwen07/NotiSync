package net.extrawdw.apps.notisync.ui

import android.Manifest
import android.app.Application
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import net.extrawdw.apps.notisync.ancs.AncsBridgeService
import net.extrawdw.apps.notisync.ancs.AncsCompanion
import net.extrawdw.apps.notisync.ancs.AncsStatus
import net.extrawdw.apps.notisync.ancs.IosApp
import net.extrawdw.apps.notisync.ancs.IosBundleIdExclusions
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

private const val ICON_PX = 128

/** The runtime Bluetooth permissions needed to advertise + connect for ANCS (all runtime on minSdk 34). */
private val BT_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_SCAN,
)

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
    val bridgeEnabled by settings.ancsBridgeEnabled.collectAsStateWithLifecycle()
    val localDisplay by settings.ancsLocalDisplay.collectAsStateWithLifecycle()
    val meshMirror by settings.ancsMeshMirror.collectAsStateWithLifecycle()
    val enabled by registry.enabled.collectAsStateWithLifecycle()
    val discovered by registry.discovered.collectAsStateWithLifecycle()
    val icons by viewModel<IosAppsViewModel>().icons.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) graph.setAncsBridgeEnabled(true)
        }

    // If the bridge was left enabled but isn't running — after a process death (status OFF) or a transient
    // failure such as Bluetooth having been off (status ERROR) — resume it now; starting the connectedDevice
    // foreground service is allowed because this tab is foregrounded. UNSUPPORTED is hardware-permanent, so
    // we don't retry it. (Re-entry is safe: the manager's own `running` guard no-ops a redundant start.)
    LaunchedEffect(bridgeEnabled, status) {
        if (bridgeEnabled && (status == AncsStatus.OFF || status == AncsStatus.ERROR) &&
            BT_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(
                    context,
                    it
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } &&
            // Re-confirm the PERSISTED intent: right after the user toggles off, `bridgeEnabled` (DataStore-backed)
            // can still read true here while `status` has already flipped to OFF — without this the effect would
            // restart the bridge it was just asked to stop. (setAncsBridgeEnabled persists before stopping.)
            settings.ancsBridgeEnabledNow()
        ) {
            AncsBridgeService.start(context)
        }
    }

    fun enableBridge() {
        if (BT_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(
                    context,
                    it
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }) {
            graph.setAncsBridgeEnabled(true)
        } else {
            permissionLauncher.launch(BT_PERMISSIONS)
        }
    }

    // --- iPhone pairing via CompanionDeviceManager (one-time association + bond + presence) ---
    val cdm = remember { context.getSystemService(CompanionDeviceManager::class.java) }
    var cdmAssociated by remember { mutableStateOf(AncsCompanion.isAssociated(context)) }
    var cdmDeviceName by remember { mutableStateOf(AncsCompanion.associatedDeviceName(context)) }
    val intentSenderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            // Bond with the picked device here (the reliable path) — association alone doesn't pair the iPhone.
            val picked = AncsCompanion.deviceFromPickerResult(result.data)
            AncsCompanion.bondDevice(picked)
            cdmAssociated = AncsCompanion.isAssociated(context)
            cdmDeviceName = AncsCompanion.associatedDeviceName(context)
            if (cdmAssociated) {
                AncsCompanion.observePresence(context)
                graph.setAncsBridgeEnabled(true) // run the bridge so it connects once the iPhone bonds
            }
            // createBond() raises a pairing request on BOTH ends — remind the user to accept each.
            if (picked != null) {
                Toast.makeText(
                    context,
                    resources.getString(R.string.ios_pairing_accept_prompt),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    fun startPairing() {
        val mgr = cdm
        if (mgr == null) {
            Log.w("IosScreen", "CompanionDeviceManager unavailable")
            Toast.makeText(
                context,
                resources.getString(R.string.ios_pairing_unavailable),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Toast.makeText(
            context,
            resources.getString(R.string.ios_pairing_open_bt),
            Toast.LENGTH_LONG
        ).show()
        fun launchPicker(intentSender: IntentSender) {
            runCatching {
                intentSenderLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
                .onFailure { Log.w("IosScreen", "launching CDM picker failed", it) }
        }
        runCatching {
            mgr.associate(
                AncsCompanion.request(),
                context.mainExecutor,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) {
                        launchPicker(intentSender)
                    }

                    @Suppress("OVERRIDE_DEPRECATION") // pre-33 entry point; delegate to the same picker launch
                    override fun onDeviceFound(intentSender: IntentSender) {
                        launchPicker(intentSender)
                    }

                    override fun onAssociationCreated(associationInfo: AssociationInfo) {
                        Log.i("IosScreen", "CDM association created: ${associationInfo.id}")
                        cdmAssociated = true
                        cdmDeviceName = AncsCompanion.associatedDeviceName(context)
                        AncsCompanion.observePresence(context)
                        // Also bond here in case the result Intent didn't carry the device (belt-and-suspenders).
                        AncsCompanion.bondDevice(runCatching { associationInfo.associatedDevice?.bluetoothDevice }.getOrNull())
                        graph.setAncsBridgeEnabled(true) // run the bridge so it connects once bonded
                    }

                    override fun onFailure(error: CharSequence?) {
                        Log.w("IosScreen", "CDM association failed: $error")
                    }
                })
        }.onFailure {
            Log.w("IosScreen", "associate() threw", it)
            Toast.makeText(
                context,
                resources.getString(R.string.ios_pairing_failed, it.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun norm(s: String) = s.lowercase(Locale.getDefault())
    val q = norm(query.trim())
    fun matches(app: IosApp) =
        q.isEmpty() || norm(app.displayName).contains(q) || norm(app.bundleId).contains(q)

    val effectiveEnabled = IosBundleIdExclusions.filterEnabled(enabled)
    val enabledApps = discovered.values
        .filter { it.bundleId in effectiveEnabled && matches(it) }
        .sortedBy { norm(it.displayName) }
    val otherApps = discovered.values
        .filter { it.bundleId !in effectiveEnabled && matches(it) }
        .sortedWith(compareByDescending<IosApp> { it.lastSeen }.thenBy { norm(it.displayName) })
    NotiScaffold(stringResource(R.string.tab_ios)) { modifier ->
        Column(modifier.fillMaxSize()) {
            IosAppsSearchField(
                query = query,
                onQueryChange = { query = it },
            )
            LazyColumn(Modifier.weight(1f)) {
                item("connection") {
                    ConnectionCard(
                        status = status,
                        deviceName = deviceName,
                        bridgeEnabled = bridgeEnabled,
                        onToggle = { on ->
                            if (on) enableBridge() else graph.setAncsBridgeEnabled(
                                false
                            )
                        },
                    )
                }
                item("pairing") {
                    PairingCard(
                        associated = cdmAssociated,
                        pairedName = cdmDeviceName,
                        onPair = ::startPairing,
                        onForget = {
                            AncsCompanion.disassociateAll(context)
                            cdmAssociated = false
                            cdmDeviceName = null
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
                        onLocal = { on -> scope.launch { settings.setAncsLocalDisplay(on) } },
                        onMesh = { on -> scope.launch { settings.setAncsMeshMirror(on) } },
                    )
                }
                if (enabledApps.isEmpty() && otherApps.isEmpty()) {
                    stickyHeader(key = "header:iphone") {
                        SectionHeader(stringResource(R.string.ios_apps_section), 0)
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
                    if (enabledApps.isNotEmpty()) {
                        stickyHeader(key = "header:mirroring") {
                            SectionHeader(
                                stringResource(R.string.ios_apps_section_mirroring),
                                enabledApps.size
                            )
                        }
                        items(enabledApps, key = { "on:${it.bundleId}" }) { app ->
                            IosAppRow(
                                app = app,
                                isOn = true,
                                canToggle = true,
                                icon = icons[app.bundleId],
                                onToggle = { on -> registry.setEnabled(app.bundleId, on) },
                            )
                        }
                    }
                    stickyHeader(key = "header:iphone") {
                        SectionHeader(stringResource(R.string.ios_apps_section), otherApps.size)
                    }
                    items(otherApps, key = { "off:${it.bundleId}" }) { app ->
                        IosAppRow(
                            app = app,
                            isOn = false,
                            canToggle = !IosBundleIdExclusions.isExcluded(app.bundleId),
                            icon = icons[app.bundleId],
                            onToggle = { on -> registry.setEnabled(app.bundleId, on) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IosAppsSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.ios_apps_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.apps_clear_search)
                    )
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        stringResource(R.string.section_header, title, count),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun ConnectionCard(
    status: AncsStatus,
    deviceName: String?,
    bridgeEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(!bridgeEnabled) }
                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
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
                    statusText(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (bridgeEnabled && deviceName != null && status == AncsStatus.SHARING) {
                    ConnectedDeviceText(deviceName)
                }
            }
            Switch(checked = bridgeEnabled, onCheckedChange = onToggle)
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
    associated: Boolean,
    pairedName: String?,
    onPair: () -> Unit,
    onForget: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.ios_pairing_title)) },
            supportingContent = {
                Text(
                    when {
                        !associated -> stringResource(R.string.ios_pairing_off)
                        pairedName != null -> stringResource(R.string.ios_pairing_on, pairedName)
                        else -> stringResource(R.string.ios_pairing_on_unknown)
                    }
                )
            },
            trailingContent = {
                if (associated) TextButton(onClick = onForget) { Text(stringResource(R.string.ios_pairing_forget)) }
                else Button(onClick = onPair) { Text(stringResource(R.string.ios_pairing_setup)) }
            },
        )
    }
}

@Composable
private fun ForwardingCard(
    localDisplay: Boolean,
    meshMirror: Boolean,
    onLocal: (Boolean) -> Unit,
    onMesh: (Boolean) -> Unit
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
    }
}

@Composable
private fun CompactSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 4.dp),
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
    onToggle: (Boolean) -> Unit
) {
    ListItem(
        modifier = if (canToggle) Modifier.clickable { onToggle(!isOn) } else Modifier,
        leadingContent = { AppIconSquare(icon) },
        headlineContent = { Text(app.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                app.bundleId,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Switch(
                checked = isOn,
                onCheckedChange = onToggle,
                enabled = canToggle
            )
        },
    )
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
private fun statusText(status: AncsStatus): String = stringResource(
    when (status) {
        AncsStatus.OFF -> R.string.ios_status_off
        AncsStatus.UNSUPPORTED -> R.string.ios_status_unsupported
        AncsStatus.ADVERTISING -> R.string.ios_status_advertising
        AncsStatus.CONNECTING -> R.string.ios_status_connecting
        AncsStatus.NEEDS_PAIRING -> R.string.ios_status_needs_pairing
        AncsStatus.SHARING -> R.string.ios_status_sharing
        AncsStatus.NO_ANCS -> R.string.ios_status_no_ancs
        AncsStatus.ERROR -> R.string.ios_status_error
    },
)
