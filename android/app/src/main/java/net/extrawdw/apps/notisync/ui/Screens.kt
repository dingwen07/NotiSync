package net.extrawdw.apps.notisync.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.NotiSyncApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ICON_PX = 128

data class PermissionState(
    val listenerEnabled: Boolean = false,
    val postNotificationsGranted: Boolean = false,
)

@Composable
fun rememberGraph(): AppGraph {
    val context = LocalContext.current
    return remember { (context.applicationContext as NotiSyncApp).graph }
}

/** Shared scaffold with a standard Material 3 top app bar (pinned, does not collapse on scroll). */
@Composable
private fun NotiScaffold(title: String, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(title) }) },
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

@Composable
fun DevicesScreen(
    permissions: PermissionState,
    onPair: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onOpenListenerSettings: () -> Unit,
) {
    val graph = rememberGraph()
    val peers by graph.peers.peers.collectAsStateWithLifecycle()
    val deviceName by graph.settings.deviceName.collectAsStateWithLifecycle()

    NotiScaffold("Devices") { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!permissions.listenerEnabled) {
                item {
                    PermissionCard(
                        title = "Enable notification access",
                        body = "NotiSync needs notification listener access to mirror this device's notifications.",
                        action = "Open settings",
                        onClick = onOpenListenerSettings,
                    )
                }
            }
            if (!permissions.postNotificationsGranted) {
                item {
                    PermissionCard(
                        title = "Allow showing notifications",
                        body = "Grant notification posting so mirrored notifications can appear on this device.",
                        action = "Grant",
                        onClick = onRequestPostNotifications,
                    )
                }
            }
            item {
                ThisDeviceCard(
                    name = deviceName,
                    safetyNumber = graph.identity.clientId.value,
                    keyBacking = graph.identity.backing.toString(),
                )
            }
            item {
                Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Pair a device")
                }
            }
            item {
                Text(
                    "Trusted devices (${peers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (peers.isEmpty()) {
                item {
                    Text(
                        "No paired devices yet. Pair another device to start mirroring notifications between them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column {
                            peers.forEachIndexed { index, peer ->
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    leadingContent = {
                                        Icon(Icons.Outlined.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    },
                                    headlineContent = { Text(peer.displayName) },
                                    supportingContent = { Text(peer.clientId.shortForm(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingContent = {
                                        IconButton(onClick = { graph.peers.remove(peer.clientId) }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Remove ${peer.displayName}")
                                        }
                                    },
                                )
                                if (index < peers.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThisDeviceCard(name: String, safetyNumber: String, keyBacking: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Smartphone, contentDescription = null, modifier = Modifier.size(40.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("THIS DEVICE", style = MaterialTheme.typography.labelMedium)
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Safety number  $safetyNumber",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
                Text(
                    "Key backing  $keyBacking",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(title: String, body: String, action: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}

private data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap?)

@Composable
fun AppsScreen() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val selection = graph.appSelection!!
    val enabled by selection.enabled.collectAsStateWithLifecycle()
    val lastSeen by selection.lastSeen.collectAsStateWithLifecycle()
    val apps = remember { mutableStateListOf<InstalledApp>() }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val loaded = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null } // user-facing apps
                .map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        icon = runCatching { pm.getApplicationIcon(info).toImageBitmap(ICON_PX) }.getOrNull(),
                    )
                }
                .toList()
        }
        apps.clear(); apps.addAll(loaded)
        loading = false
    }

    fun norm(s: String) = s.lowercase(Locale.getDefault())
    val q = norm(query.trim())
    fun matches(a: InstalledApp) = q.isEmpty() || norm(a.label).contains(q) || norm(a.packageName).contains(q)

    val enabledApps = apps.filter { it.packageName in enabled && matches(it) }.sortedBy { norm(it.label) }
    val otherApps = apps.filter { it.packageName !in enabled && matches(it) }
        .sortedWith(compareByDescending<InstalledApp> { lastSeen[it.packageName] ?: 0L }.thenBy { norm(it.label) })
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    NotiScaffold("Apps") { modifier ->
        Column(modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                enabledApps.isEmpty() && otherApps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (q.isEmpty()) "No apps found" else "No apps match \"$query\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (enabledApps.isNotEmpty()) {
                        item { SectionHeader("Mirroring", enabledApps.size) }
                        items(enabledApps, key = { "on:${it.packageName}" }) { AppRow(it, true, lastSeen[it.packageName], fmt, selection) }
                    }
                    item { SectionHeader(if (q.isEmpty()) "All apps" else "Other results", otherApps.size) }
                    items(otherApps, key = { "off:${it.packageName}" }) { AppRow(it, false, lastSeen[it.packageName], fmt, selection) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        "$title ($count)",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AppRow(app: InstalledApp, isOn: Boolean, lastSeen: Long?, fmt: SimpleDateFormat, selection: net.extrawdw.apps.notisync.data.AppSelectionRepository) {
    ListItem(
        modifier = Modifier.clickable { selection.setEnabled(app.packageName, !isOn) },
        leadingContent = { AppIcon(app.icon) },
        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                if (lastSeen != null) "Last notification ${fmt.format(Date(lastSeen))}" else app.packageName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Switch(checked = isOn, onCheckedChange = { on -> selection.setEnabled(app.packageName, on) })
        },
    )
}

@Composable
private fun AppIcon(icon: ImageBitmap?) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
    } else {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
    (this as? BitmapDrawable)?.bitmap?.let { return Bitmap.createScaledBitmap(it, sizePx, sizePx, true).asImageBitmap() }
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, sizePx, sizePx)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

@Composable
fun ActivityScreen() {
    val graph = rememberGraph()
    val events by graph.activityLog.events.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    NotiScaffold("Activity") { modifier ->
        if (events.isEmpty()) {
            Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                Text("No activity yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pair a device, then notifications you receive will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier.fillMaxSize()) {
                items(events) { e ->
                    ListItem(
                        overlineContent = { Text("${e.kind} · ${fmt.format(Date(e.timestamp))}") },
                        headlineContent = { Text(e.title) },
                        supportingContent = { Text(e.detail) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val brokerUrl by graph.settings.brokerUrl.collectAsStateWithLifecycle()
    val deviceName by graph.settings.deviceName.collectAsStateWithLifecycle()
    val batchLow by graph.settings.batchLowPriority.collectAsStateWithLifecycle()
    val advanced by graph.settings.advancedDiagnostics.collectAsStateWithLifecycle()

    NotiScaffold("Settings") { modifier ->
        LazyColumn(
            modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { scope.launch { graph.settings.setDeviceName(it) } },
                    label = { Text("Device name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = brokerUrl,
                    onValueChange = { scope.launch { graph.settings.setBrokerUrl(it) } },
                    label = { Text("Broker URL (ws://…)") },
                    supportingText = { Text("Use ws://10.0.2.2:8080 for a local server from the emulator.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                ToggleRow("Batch low-priority notifications", batchLow) { scope.launch { graph.settings.setBatchLowPriority(it) } }
            }
            item {
                ToggleRow("Advanced diagnostics", advanced) { scope.launch { graph.settings.setAdvancedDiagnostics(it) } }
            }
            if (advanced) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                            Text("Client ID: ${graph.identity.clientId.value}", style = MaterialTheme.typography.bodySmall)
                            Text("Key backing: ${graph.identity.backing}", style = MaterialTheme.typography.bodySmall)
                            Text("Transport: ${graph.transport.type}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
