package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.NotificationFilterRule
import net.extrawdw.notisync.protocol.OriginPlatform
import net.extrawdw.notisync.protocol.TrustStatus

// pairButtonModifier targets the pair button specifically, not the composable root (which takes its modifier
// from NotiScaffold), so the "first Modifier param must be named modifier" convention doesn't apply here.
@Suppress("ModifierParameter")
@Composable
fun DevicesScreen(
    permissions: PermissionState,
    onPair: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    onStartScreenMirror: (ClientId) -> Unit = {},
    pairButtonModifier: Modifier = Modifier,
) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val roster by graph.trust.roster.collectAsStateWithLifecycle()
    val quarantined by graph.trust.quarantined.collectAsStateWithLifecycle()
    val deviceName by graph.settings.deviceName.collectAsStateWithLifecycle()
    val screenMirroringEnabled by graph.settings.screenMirroringEnabled.collectAsStateWithLifecycle()
    val screenAuthorizedPeers by graph.screenMirrorAuthorizations.authorizedPeerIds.collectAsStateWithLifecycle()
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
    // The own device whose received notification-filters sheet is open (null = closed).
    var filterSheetFor by remember { mutableStateOf<RosterDevice?>(null) }
    // Device details are keyed by id so a live profile/key-epoch update refreshes the open sheet.
    var detailsSheetFor by remember { mutableStateOf<ClientId?>(null) }

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
                        onApprove = {
                            graph.launchDurableTrustAction(context) {
                                graph.trust.approveQuarantine()
                                graph.broadcastTrust()
                            }
                        },
                        onClear = {
                            graph.launchDurableTrustAction(context) {
                                graph.trust.clearQuarantine()
                            }
                        },
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
                Button(
                    onClick = onPair,
                    enabled = !quarantined,
                    modifier = Modifier.fillMaxWidth().then(pairButtonModifier)
                ) {
                    Icon(
                        Icons.Outlined.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
                    Spacer(Modifier.size(4.dp))
                    Icon(
                        Icons.Outlined.Contactless,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize)
                    )
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
                item {
                    DeviceListCard(
                        ownDevices, now, graph, enabled = !quarantined,
                        onShowFilters = {
                            detailsSheetFor = null
                            filterSheetFor = it
                        },
                        onShowDetails = {
                            filterSheetFor = null
                            detailsSheetFor = it.clientId
                        },
                        onStartScreenMirror = { onStartScreenMirror(it.clientId) },
                    )
                }
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
                item {
                    DeviceListCard(
                        otherDevices,
                        now,
                        graph,
                        enabled = !quarantined,
                        onShowDetails = {
                            filterSheetFor = null
                            detailsSheetFor = it.clientId
                        },
                    )
                }
            }
        }

        filterSheetFor?.let { device ->
            val filters by graph.notificationFilters.filters.collectAsStateWithLifecycle()
            NotificationFilterSheet(
                deviceName = device.displayName ?: stringResource(R.string.device_unknown),
                filter = filters[device.clientId.value],
                onClear = { graph.notificationFilters.remove(device.clientId) },
                onDismiss = { filterSheetFor = null },
            )
        }

        detailsSheetFor?.let { clientId ->
            roster.firstOrNull { it.clientId == clientId }?.let { device ->
                DeviceDetailsSheet(
                    device = device,
                    screenMirroringEnabled = screenMirroringEnabled,
                    screenControlAuthorized = device.clientId.value in screenAuthorizedPeers,
                    screenMirrorRequestEnabled = !quarantined,
                    onScreenControlAuthorizedChange = { authorized ->
                        graph.screenMirrorAuthorizations.setAuthorized(device.clientId, authorized)
                    },
                    onStartScreenMirror = {
                        detailsSheetFor = null
                        onStartScreenMirror(device.clientId)
                    },
                    onDismiss = { detailsSheetFor = null },
                )
            }
        }
    }
}

@Composable
private fun DeviceListCard(
    devices: List<RosterDevice>,
    now: Long,
    graph: net.extrawdw.apps.notisync.AppGraph,
    enabled: Boolean = true,
    onShowFilters: (RosterDevice) -> Unit = {},
    onShowDetails: (RosterDevice) -> Unit = {},
    onStartScreenMirror: (RosterDevice) -> Unit = {},
) {
    val context = LocalContext.current
    val filters by graph.notificationFilters.filters.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column {
            devices.forEachIndexed { index, device ->
                DeviceRow(
                    device = device,
                    now = now,
                    enabled = enabled,
                    onShowFilters = { onShowFilters(device) },
                    onShowDetails = { onShowDetails(device) },
                    onStartScreenMirror = { onStartScreenMirror(device) },
                    hasFilters = filters[device.clientId.value]?.rules?.isNotEmpty() == true,
                    // Overturns (deny / keep) and removals propagate now; agreements ride anti-entropy.
                    onApprove = {
                        graph.launchDurableTrustAction(context) {
                            if (graph.trust.approveTrust(
                                    it,
                                    System.currentTimeMillis()
                                )
                            ) graph.broadcastTrust()
                        }
                    },
                    onDeny = {
                        graph.launchDurableTrustAction(context) {
                            if (graph.trust.rejectTrust(
                                    it,
                                    System.currentTimeMillis()
                                )
                            ) graph.broadcastTrust()
                        }
                    },
                    onRemoveConfirm = {
                        graph.launchDurableTrustAction(context) {
                            if (graph.trust.confirmRevoke(
                                    it,
                                    System.currentTimeMillis()
                                )
                            ) graph.broadcastTrust()
                        }
                    },
                    onKeep = {
                        graph.launchDurableTrustAction(context) {
                            if (graph.trust.keepTrusted(
                                    it,
                                    System.currentTimeMillis()
                                )
                            ) graph.broadcastTrust()
                        }
                    },
                    onRemove = {
                        graph.launchDurableTrustAction(context) {
                            if (graph.trust.revokeLocal(
                                    it,
                                    System.currentTimeMillis()
                                )
                            ) graph.broadcastTrust()
                        }
                    },
                    onPurge = {
                        graph.launchDurableTrustAction(context) {
                            graph.trust.purgeRevoked(it)
                            // Forget this peer's filter only after its trust-store removal was durable.
                            graph.notificationFilters.remove(it)
                        }
                    },
                    onRestore = {
                        graph.launchDurableTrustAction(context) {
                            if (graph.trust.restoreTrust(it, System.currentTimeMillis())) {
                                graph.broadcastTrust()
                            }
                        }
                    },
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
            Text(
                stringResource(R.string.devices_tamper_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.devices_tamper_body),
                style = MaterialTheme.typography.bodyMedium
            )
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
            Icon(
                Icons.Outlined.Smartphone,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.devices_this_device),
                    style = MaterialTheme.typography.labelMedium
                )
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
    onShowFilters: () -> Unit = {},
    onShowDetails: () -> Unit = {},
    onStartScreenMirror: () -> Unit = {},
    hasFilters: Boolean = false,
    onApprove: (ClientId) -> Unit,
    onDeny: (ClientId) -> Unit,
    onRemoveConfirm: (ClientId) -> Unit,
    onKeep: (ClientId) -> Unit,
    onRemove: (ClientId) -> Unit,
    onPurge: (ClientId) -> Unit,
    onRestore: (ClientId) -> Unit = {},
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
    val epochLabel = stringResource(R.string.device_epoch, device.currentEpoch)
    val statusLine = buildList {
        add(statusLabel)
        // Reachability in NS2 is the key-epoch, not the card: show the epoch when we hold one, else
        // "unavailable" (trusted but not yet converged — its key-epoch is still being pulled).
        if (device.currentEpoch > 0) add(epochLabel) else add(unavailableLabel)
        add(device.clientId.shortForm())
    }.joinToString(" · ")
    val by = device.introducedByName ?: stringResource(R.string.device_introducer_unknown)

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onShowDetails)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Smartphone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
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
                // Own devices also expose the notification-filters this device received from them (DATA_SYNC
                // FILTER) — what this device won't forward to that peer.
                TrustStatus.TRUSTED -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (device.supportsScreenMirrorRequest()) {
                        IconButton(onClick = onStartScreenMirror, enabled = enabled) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ScreenShare,
                                contentDescription = stringResource(
                                    R.string.screen_mirror_device_start_desc,
                                    name,
                                ),
                            )
                        }
                    }
                    // Only own devices send filters; disabled when this device is hiding nothing from them.
                    if (device.ownDevice) {
                        IconButton(onClick = onShowFilters, enabled = enabled && hasFilters) {
                            Icon(
                                Icons.Outlined.NotificationsOff,
                                contentDescription = stringResource(R.string.device_filters_button_desc, name)
                            )
                        }
                    }
                    IconButton(
                        onClick = { onRemove(device.clientId) },
                        enabled = enabled
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.device_remove_desc, name)
                        )
                    }
                }

                TrustStatus.REVOKED -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Revert an accidental delete: re-trust the device (peers re-confirm via RE_TRUST).
                    IconButton(onClick = { onRestore(device.clientId) }, enabled = enabled) {
                        Icon(
                            Icons.Outlined.Restore,
                            contentDescription = stringResource(R.string.device_restore_desc, name)
                        )
                    }
                    // Permanently forgettable only after the tombstone has outlived the stale-trust window.
                    val revokedAt = device.revokedAt
                    val canPurge = revokedAt != null &&
                            now - revokedAt >= TrustStore.REVOKE_PURGE_DELAY_MS
                    IconButton(
                        onClick = { onPurge(device.clientId) },
                        enabled = canPurge && enabled
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(
                                R.string.device_permanently_delete_desc,
                                name
                            )
                        )
                    }
                }

                else -> Unit
            }
        }
        when (device.status) {
            TrustStatus.PENDING_TRUST -> {
                Text(
                    stringResource(R.string.device_added_by, by),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.devices_verification_number, device.clientId.value),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.device_approve_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onApprove(device.clientId) }, enabled = enabled) {
                        Text(
                            stringResource(R.string.action_approve)
                        )
                    }
                    OutlinedButton(onClick = { onDeny(device.clientId) }, enabled = enabled) {
                        Text(
                            stringResource(R.string.action_deny)
                        )
                    }
                }
            }

            TrustStatus.PENDING_REVOKE -> {
                Text(
                    stringResource(R.string.device_removed_by, by),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onRemoveConfirm(device.clientId) },
                        enabled = enabled
                    ) { Text(stringResource(R.string.action_remove)) }
                    OutlinedButton(onClick = { onKeep(device.clientId) }, enabled = enabled) {
                        Text(
                            stringResource(R.string.action_keep)
                        )
                    }
                }
            }

            else -> Unit
        }
    }
}

/**
 * A bottom sheet listing the notification-suppression filter a peer device sent this device (DATA_SYNC
 * FILTER) — i.e. what this device will NOT forward to that peer. Deliberately plain: a text listing, one
 * line per rule, no per-app rendering (per the feature brief).
 */
// rememberModalBottomSheetState is the stable Material3 factory; the Expressive artifact on the classpath
// marks it deprecated in favor of an alpha replacement, which we deliberately don't adopt here.
@Suppress("DEPRECATION")
@Composable
private fun NotificationFilterSheet(
    deviceName: String,
    filter: FilterSync?,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.device_filters_title, deviceName),
                style = MaterialTheme.typography.titleMedium,
            )
            val rules = filter?.rules.orEmpty()
            if (rules.isEmpty()) {
                Text(
                    stringResource(R.string.device_filters_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.device_filters_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rules.forEach { rule ->
                    Text("•  ${filterRuleLabel(rule)}", style = MaterialTheme.typography.bodyMedium)
                }
                filter?.updatedAt?.takeIf { it > 0L }?.let { at ->
                    val stamp = java.text.DateFormat
                        .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                        .format(java.util.Date(at))
                    Text(
                        stringResource(R.string.device_filters_updated, stamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Clear this peer's filters locally (it resumes receiving). The peer still owns the list, so
                // it may re-announce on its next change/heartbeat — this is a local reset, not a remote one.
                OutlinedButton(onClick = onClear, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.device_filters_clear))
                }
            }
        }
    }
}

/** A single filter rule as one readable line: "<origin> · <scope>" (device / app / app+channel). */
@Composable
private fun filterRuleLabel(rule: NotificationFilterRule): String {
    val origin = when (rule.originPlatform) {
        OriginPlatform.ANDROID_LOCAL -> stringResource(R.string.device_filters_origin_android)
        OriginPlatform.IOS_ANCS -> stringResource(R.string.device_filters_origin_ios)
    }
    val appId = rule.appId
    val channelId = rule.channelId
    val scope = when {
        appId == null -> stringResource(R.string.device_filters_scope_all)
        channelId == null -> stringResource(R.string.device_filters_scope_app, appId)
        else -> stringResource(R.string.device_filters_scope_channel, appId, channelId)
    }
    return "$origin · $scope"
}
