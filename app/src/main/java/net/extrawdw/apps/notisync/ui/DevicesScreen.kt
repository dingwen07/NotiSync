package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.TrustStatus

@Composable
fun DevicesScreen(
    permissions: PermissionState,
    onPair: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onOpenListenerSettings: () -> Unit,
) {
    val graph = rememberGraph()
    val roster by graph.trust.roster.collectAsStateWithLifecycle()
    val deviceName by graph.settings.deviceName.collectAsStateWithLifecycle()

    NotiScaffold("Devices") { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            // Bottom inset clears the app's bottom navigation bar (this inner scaffold has no bottom bar).
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
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
                    "Devices (${roster.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (roster.isEmpty()) {
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
                            roster.forEachIndexed { index, device ->
                                DeviceRow(
                                    device = device,
                                    // Overturns (deny / keep) and removals propagate now; agreements ride anti-entropy.
                                    onApprove = { if (graph.trust.approveTrust(it, System.currentTimeMillis())) graph.broadcastTrust() },
                                    onDeny = { if (graph.trust.rejectTrust(it, System.currentTimeMillis())) graph.broadcastTrust() },
                                    onRemoveConfirm = { if (graph.trust.confirmRevoke(it, System.currentTimeMillis())) graph.broadcastTrust() },
                                    onKeep = { if (graph.trust.keepTrusted(it, System.currentTimeMillis())) graph.broadcastTrust() },
                                    onRemove = { if (graph.trust.revokeLocal(it, System.currentTimeMillis())) graph.broadcastTrust() },
                                )
                                if (index < roster.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp))
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

@Composable
private fun DeviceRow(
    device: RosterDevice,
    onApprove: (ClientId) -> Unit,
    onDeny: (ClientId) -> Unit,
    onRemoveConfirm: (ClientId) -> Unit,
    onKeep: (ClientId) -> Unit,
    onRemove: (ClientId) -> Unit,
) {
    val name = device.displayName ?: "Unknown device"
    val statusLabel = when (device.status) {
        TrustStatus.TRUSTED -> "Trusted"
        TrustStatus.PENDING_TRUST -> "Pending approval"
        TrustStatus.PENDING_REVOKE -> "Removal pending"
        TrustStatus.REVOKED -> "Removed"
    }
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "$statusLabel · ${device.clientId.shortForm()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (device.status == TrustStatus.TRUSTED) {
                IconButton(onClick = { onRemove(device.clientId) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove $name")
                }
            }
        }
        when (device.status) {
            TrustStatus.PENDING_TRUST -> {
                Text("Safety number  ${device.clientId.value}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Approve only if it matches the device's own safety number.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onApprove(device.clientId) }) { Text("Approve") }
                    OutlinedButton(onClick = { onDeny(device.clientId) }) { Text("Deny") }
                }
            }
            TrustStatus.PENDING_REVOKE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRemoveConfirm(device.clientId) }) { Text("Remove") }
                    OutlinedButton(onClick = { onKeep(device.clientId) }) { Text("Keep") }
                }
            }
            else -> Unit
        }
    }
}
