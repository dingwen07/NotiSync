package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

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
