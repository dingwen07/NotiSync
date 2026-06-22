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
import androidx.compose.material.icons.outlined.Contactless
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.crypto.KeyBacking
import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.apps.notisync.data.TrustStore
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.TrustStatus

@Composable
fun DevicesScreen(
    permissions: PermissionState,
    onPair: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    pairButtonModifier: Modifier = Modifier,
) {
    val graph = rememberGraph()
    val roster by graph.trust.roster.collectAsStateWithLifecycle()
    val quarantined by graph.trust.quarantined.collectAsStateWithLifecycle()
    val deviceName by graph.settings.deviceName.collectAsStateWithLifecycle()
    // Tick while any revoked tombstone is on screen, so its permanent-delete button enables once the
    // purge delay elapses without needing to leave and reopen the page.
    val hasRevoked = roster.any { it.status == TrustStatus.REVOKED }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hasRevoked) {
        while (hasRevoked) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val ownDevices = roster.filter { it.ownDevice }
    val otherDevices = roster.filterNot { it.ownDevice }

    NotiScaffold(stringResource(R.string.tab_devices)) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            // Bottom inset clears the app's bottom navigation bar (this inner scaffold has no bottom bar).
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (quarantined) {
                item {
                    QuarantineCard(
                        // Approve re-signs the current roster as-is; broadcast it so peers re-converge once
                        // we're active again. Clear wipes it (nothing left to announce — they'll re-pair).
                        onApprove = { graph.trust.approveQuarantine(); graph.broadcastTrust() },
                        onClear = { graph.trust.clearQuarantine() },
                    )
                }
            }
            if (!permissions.listenerEnabled) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.devices_enable_access_title),
                        body = stringResource(R.string.devices_enable_access_body),
                        action = stringResource(R.string.devices_open_settings),
                        onClick = onOpenListenerSettings,
                    )
                }
            }
            if (!permissions.postNotificationsGranted) {
                item {
                    PermissionCard(
                        title = stringResource(R.string.devices_allow_posting_title),
                        body = stringResource(R.string.devices_allow_posting_body),
                        action = stringResource(R.string.devices_grant),
                        onClick = onRequestPostNotifications,
                    )
                }
            }
            item {
                ThisDeviceCard(
                    name = deviceName,
                    safetyNumber = graph.identity.clientId.value,
                    backing = graph.identity.backing,
                )
            }
            item {
                Button(onClick = onPair, enabled = !quarantined, modifier = Modifier.fillMaxWidth().then(pairButtonModifier)) {
                    Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(4.dp))
                    Icon(Icons.Outlined.Contactless, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.pair_a_device))
                }
            }
            item {
                Text(
                    stringResource(R.string.devices_my_devices, ownDevices.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (ownDevices.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.devices_my_devices_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item { DeviceListCard(ownDevices, now, graph, enabled = !quarantined) }
            }
            if (otherDevices.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.devices_other_devices, otherDevices.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item {
                    Text(
                        stringResource(R.string.devices_other_devices_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { DeviceListCard(otherDevices, now, graph, enabled = !quarantined) }
            }
        }
    }
}

@Composable
private fun DeviceListCard(devices: List<RosterDevice>, now: Long, graph: net.extrawdw.apps.notisync.AppGraph, enabled: Boolean = true) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            devices.forEachIndexed { index, device ->
                DeviceRow(
                    device = device,
                    now = now,
                    enabled = enabled,
                    // Overturns (deny / keep) and removals propagate now; agreements ride anti-entropy.
                    onApprove = { if (graph.trust.approveTrust(it, System.currentTimeMillis())) graph.broadcastTrust() },
                    onDeny = { if (graph.trust.rejectTrust(it, System.currentTimeMillis())) graph.broadcastTrust() },
                    onRemoveConfirm = { if (graph.trust.confirmRevoke(it, System.currentTimeMillis())) graph.broadcastTrust() },
                    onKeep = { if (graph.trust.keepTrusted(it, System.currentTimeMillis())) graph.broadcastTrust() },
                    onRemove = { if (graph.trust.revokeLocal(it, System.currentTimeMillis())) graph.broadcastTrust() },
                    onPurge = { graph.trust.purgeRevoked(it) },
                )
                if (index < devices.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp))
            }
        }
    }
}

/**
 * Red banner shown while the trust roster fails its identity signature. The device lists below stay
 * visible but inert (see [DeviceListCard]'s `enabled`) so the user can eyeball them before choosing to
 * Approve (re-sign the current roster) or Clear (wipe and re-pair).
 */
@Composable
private fun QuarantineCard(onApprove: () -> Unit, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.devices_tamper_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.devices_tamper_body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) { Text(stringResource(R.string.devices_tamper_approve)) }
                OutlinedButton(onClick = onClear) { Text(stringResource(R.string.devices_tamper_clear)) }
            }
        }
    }
}

@Composable
private fun ThisDeviceCard(name: String, safetyNumber: String, backing: KeyBacking) {
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
                Text(stringResource(R.string.devices_this_device), style = MaterialTheme.typography.labelMedium)
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.devices_verification_number, safetyNumber),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
                Text(
                    stringResource(R.string.settings_key_backing, keyBackingLabel(backing)),
                    style = MaterialTheme.typography.bodySmall,
                    color = keyBackingColor(backing),
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
    now: Long,
    enabled: Boolean = true,
    onApprove: (ClientId) -> Unit,
    onDeny: (ClientId) -> Unit,
    onRemoveConfirm: (ClientId) -> Unit,
    onKeep: (ClientId) -> Unit,
    onRemove: (ClientId) -> Unit,
    onPurge: (ClientId) -> Unit,
) {
    val name = device.displayName ?: stringResource(R.string.device_unknown)
    val isRevoked = device.status == TrustStatus.REVOKED
    val statusLabel = when (device.status) {
        TrustStatus.TRUSTED -> stringResource(R.string.device_status_trusted)
        TrustStatus.PENDING_TRUST -> stringResource(R.string.device_status_pending_trust)
        TrustStatus.PENDING_REVOKE -> stringResource(R.string.device_status_pending_revoke)
        TrustStatus.REVOKED -> stringResource(R.string.device_status_removed)
    }
    val unavailableLabel = stringResource(R.string.device_status_unavailable)
    val statusLine = buildList {
        add(statusLabel)
        if (!device.keyAvailable) add(unavailableLabel) // we hold no card for it yet
        add(device.clientId.shortForm())
    }.joinToString(" · ")
    val by = device.introducedByName ?: stringResource(R.string.device_introducer_unknown)

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
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    // Only a fully-trusted device gets an upright name; everything else is italic.
                    fontStyle = if (device.status == TrustStatus.TRUSTED) FontStyle.Normal else FontStyle.Italic,
                    color = if (isRevoked) MaterialTheme.colorScheme.error else Color.Unspecified,
                    textDecoration = if (isRevoked) TextDecoration.LineThrough else null,
                )
                Text(
                    statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (device.status) {
                // Own and other devices alike: delete revokes (tombstone) and announces a new trust table.
                TrustStatus.TRUSTED -> IconButton(onClick = { onRemove(device.clientId) }, enabled = enabled) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.device_remove_desc, name))
                }
                TrustStatus.REVOKED -> {
                    // Permanently forgettable only after the tombstone has outlived the stale-trust window.
                    val canPurge = device.revokedAt != null &&
                        now - device.revokedAt >= TrustStore.REVOKE_PURGE_DELAY_MS
                    IconButton(onClick = { onPurge(device.clientId) }, enabled = canPurge && enabled) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.device_permanently_delete_desc, name))
                    }
                }
                else -> Unit
            }
        }
        when (device.status) {
            TrustStatus.PENDING_TRUST -> {
                Text(stringResource(R.string.device_added_by, by), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.devices_verification_number, device.clientId.value), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.device_approve_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onApprove(device.clientId) }, enabled = enabled) { Text(stringResource(R.string.action_approve)) }
                    OutlinedButton(onClick = { onDeny(device.clientId) }, enabled = enabled) { Text(stringResource(R.string.action_deny)) }
                }
            }
            TrustStatus.PENDING_REVOKE -> {
                Text(stringResource(R.string.device_removed_by, by), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRemoveConfirm(device.clientId) }, enabled = enabled) { Text(stringResource(R.string.action_remove)) }
                    OutlinedButton(onClick = { onKeep(device.clientId) }, enabled = enabled) { Text(stringResource(R.string.action_keep)) }
                }
            }
            else -> Unit
        }
    }
}
