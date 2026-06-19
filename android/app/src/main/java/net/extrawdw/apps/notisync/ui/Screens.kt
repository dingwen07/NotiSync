package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.NotiSyncApp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PermissionState(
    val listenerEnabled: Boolean = false,
    val postNotificationsGranted: Boolean = false,
)

@Composable
fun rememberGraph(): AppGraph {
    val context = LocalContext.current
    return remember { (context.applicationContext as NotiSyncApp).graph }
}

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
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("This device", style = MaterialTheme.typography.titleMedium)
                        Text(graph.settings.deviceName.collectAsStateWithLifecycle().value, style = MaterialTheme.typography.bodyLarge)
                        Text("Safety number: ${graph.identity.clientId.value}", style = MaterialTheme.typography.bodySmall)
                        Text("Key: ${graph.identity.backing}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.QrCode2, contentDescription = null)
                    Text("  Pair a device")
                }
            }
            item { Text("Trusted devices (${peers.size})", style = MaterialTheme.typography.titleMedium) }
            items(peers, key = { it.clientId.value }) { peer ->
                Card(Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(peer.displayName) },
                        supportingContent = { Text(peer.clientId.shortForm(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            IconButton(onClick = { graph.peers.remove(peer.clientId) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove")
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(title: String, body: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}

private data class InstalledApp(val packageName: String, val label: String)

@Composable
fun AppsScreen() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val selection = graph.appSelection!!
    val enabled by selection.enabled.collectAsStateWithLifecycle()
    val lastSeen by selection.lastSeen.collectAsStateWithLifecycle()
    val apps = remember { mutableStateListOf<InstalledApp>() }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val loaded = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null } // user-facing apps
                .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
                .toList()
        }
        apps.clear(); apps.addAll(loaded)
    }

    fun norm(s: String) = s.lowercase(Locale.getDefault())
    val q = norm(query.trim())
    val visible = apps
        .filter { q.isEmpty() || norm(it.label).contains(q) || norm(it.packageName).contains(q) }
        .sortedWith(
            compareByDescending<InstalledApp> { lastSeen[it.packageName] ?: 0L }
                .thenBy { norm(it.label) }
        )
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    NotiScaffold("Apps") { modifier ->
        Column(modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps or packages") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Choose which apps to mirror — nothing is mirrored until you turn it on. Apps that recently sent notifications appear first.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.packageName }) { app ->
                    val seen = lastSeen[app.packageName]
                    ListItem(
                        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (seen != null) "Last notification ${fmt.format(Date(seen))}" else app.packageName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = app.packageName in enabled,
                                onCheckedChange = { on -> selection.setEnabled(app.packageName, on) },
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun ActivityScreen() {
    val graph = rememberGraph()
    val events by graph.activityLog.events.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    NotiScaffold("Activity") { modifier ->
        if (events.isEmpty()) {
            Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No activity yet", style = MaterialTheme.typography.titleMedium)
                Text("Pair a device, then notifications you receive will appear here.", style = MaterialTheme.typography.bodyMedium)
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
