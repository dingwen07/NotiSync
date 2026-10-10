package net.extrawdw.apps.notisync.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import net.extrawdw.apps.notisync.ui.icons.material.outlined.screen_share as ScreenShareIcon
import net.extrawdw.apps.notisync.ui.icons.material.filled.delete as FilledDeleteIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.restore as RestoreIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.smartphone as SmartphoneIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.wifi_notification as WifiNotificationIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.chevron_right as ChevronRightIcon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.hotspot.controller.RemoteHotspotState
import net.extrawdw.apps.notisync.hotspot.controller.SavedHotspot
import net.extrawdw.apps.notisync.data.RosterDevice
import net.extrawdw.apps.notisync.data.RosterKeyEpoch
import net.extrawdw.apps.notisync.data.TrustStore
import net.extrawdw.apps.notisync.screen.AndroidScreenDecoderSupport
import net.extrawdw.apps.notisync.screen.availableAndroidScreenCodecs
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.TrustStatus
import java.text.DateFormat
import java.util.Date

/**
 * Details for a paired device. Identity values come from the individually verified card and key-epoch held
 * by the trust store; forwarding and screen preferences are local to this Android device.
 */
@Suppress("DEPRECATION") // Stable M3 factory is deprecated only by the Expressive artifact in use.
@Composable
internal fun DeviceDetailsSheet(
    device: RosterDevice,
    nowMillis: Long,
    screenMirroringEnabled: Boolean,
    screenControlAuthorized: Boolean,
    virtualDisplayAuthorized: Boolean = false,
    virtualDisplayAvailable: Boolean = false,
    onVirtualDisplayAuthorizedChange: (Boolean) -> Unit = {},
    screenMirrorRequestEnabled: Boolean = true,
    trustActionsEnabled: Boolean = true,
    forwardLocalNotifications: Boolean,
    forwardIphoneNotifications: Boolean,
    onForwardLocalNotificationsChange: (Boolean) -> Unit,
    onForwardIphoneNotificationsChange: (Boolean) -> Unit,
    screenMirrorCodecOverride: ScreenMirrorCodec?,
    screenMirrorDecoderSupport: AndroidScreenDecoderSupport,
    onScreenControlAuthorizedChange: (Boolean) -> Unit,
    onScreenMirrorCodecOverrideChange: (ScreenMirrorCodec?) -> Unit,
    onStartScreenMirror: (ClientId) -> Unit = {},
    hotspotState: RemoteHotspotState? = null,
    savedHotspot: SavedHotspot? = null,
    onRefreshHotspot: () -> Unit = {},
    onSetHotspotEnabled: (Boolean) -> Unit = {},
    hasNotificationFilters: Boolean = false,
    onShowNotificationFilters: () -> Unit = {},
    onRemove: () -> Unit = {},
    onRestore: () -> Unit = {},
    onPurge: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val name = device.displayName ?: stringResource(R.string.device_unknown)
    var keyEpochExpanded by remember(device.clientId) { mutableStateOf(false) }
    var capabilitiesExpanded by remember(device.clientId) { mutableStateOf(false) }

    AdaptiveDetailSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.statusBarsPadding(),
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top) },
    ) {
        DisableModalBottomSheetNavigationBarContrast()
        val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            // Keep the viewport behind system navigation; inset only the scrollable content.
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 4.dp,
                end = 20.dp,
                bottom = 40.dp + bottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                DeviceDetailsHeader(
                    name = name,
                    platform = platformLabel(device.platform),
                    verified = device.verified,
                )
            }
            item {
                DeviceDetailsField(
                    label = stringResource(R.string.pair_field_verification_number),
                    value = device.clientId.value,
                    monospace = true,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DeviceDetailsField(
                        label = stringResource(R.string.pair_field_identity_key),
                        value = device.identityKeyFingerprint ?: EM_DASH,
                        monospace = true,
                    )
                    CollapsibleDeviceSection(
                        title = stringResource(R.string.device_details_key_epoch),
                        summary = device.keyEpoch?.let { stringResource(R.string.pair_operational_chip_title, it.epoch) }
                            ?: stringResource(R.string.device_details_operational_unavailable),
                        expanded = keyEpochExpanded,
                        onToggle = { keyEpochExpanded = !keyEpochExpanded },
                    ) {
                        OperationalEpochDetails(device.keyEpoch)
                    }
                    CollapsibleDeviceSection(
                        title = stringResource(R.string.device_details_capabilities),
                        summary = pluralStringResource(R.plurals.device_details_capability_count,
                            device.capabilities.size, device.capabilities.size),
                        expanded = capabilitiesExpanded,
                        onToggle = { capabilitiesExpanded = !capabilitiesExpanded },
                    ) {
                        DeviceCapabilities(device.capabilities)
                    }
                }
            }
            if (device.ownDevice && device.status == TrustStatus.TRUSTED && device.verified) {
                if (Capability.HOTSPOT_PROVIDER_V1 in device.capabilities || savedHotspot != null) item {
                    HotspotControls(
                        hotspotState, savedHotspot,
                        trustActionsEnabled && Capability.HOTSPOT_PROVIDER_V1 in device.capabilities,
                        onRefreshHotspot, onSetHotspotEnabled,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                }
            }
            if (device.ownDevice && device.status == TrustStatus.TRUSTED && device.verified) {
                val supportsScreenMirror = device.supportsScreenMirrorRequest()
                val availableCodecs = availableAndroidScreenCodecs(
                    sourceCapabilities = device.capabilities.toSet(),
                    decoderSupport = screenMirrorDecoderSupport,
                )
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScreenSharingHeader(
                            device = device,
                            showConnect = supportsScreenMirror,
                            connectEnabled = screenMirrorRequestEnabled && availableCodecs.isNotEmpty(),
                            onConnectMirror = { onStartScreenMirror(device.clientId) },
                            onDismiss = onDismiss,
                        )
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (supportsScreenMirror) {
                                    ScreenMirrorCodecSelector(
                                        // Preserve an unavailable override; sessions temporarily fall back to Auto.
                                        selectedCodec = screenMirrorCodecOverride,
                                        availableCodecs = availableCodecs,
                                        enabled = screenMirrorRequestEnabled,
                                        onSelected = onScreenMirrorCodecOverrideChange,
                                    )
                                    HorizontalDivider(Modifier.padding(top = 8.dp))
                                }
                                ScreenControlAuthorization(
                                    masterEnabled = screenMirroringEnabled,
                                    enabled = screenMirroringEnabled && trustActionsEnabled,
                                    authorized = screenControlAuthorized,
                                    onAuthorizedChange = onScreenControlAuthorizedChange,
                                    additionalBody = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
                                        R.string.screen_mirror_device_hotspot_body else null,
                                )
                                HorizontalDivider(Modifier.padding(top = 8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ScreenControlAuthorization(
                                        masterEnabled = screenMirroringEnabled,
                                        enabled = trustActionsEnabled && (virtualDisplayAuthorized ||
                                            (screenMirroringEnabled && screenControlAuthorized && virtualDisplayAvailable)),
                                        authorized = virtualDisplayAuthorized,
                                        onAuthorizedChange = onVirtualDisplayAuthorizedChange,
                                        title = R.string.screen_virtual_allow_title,
                                        body = R.string.screen_virtual_allow_body,
                                    )
                                    if (!virtualDisplayAvailable) Text(
                                        stringResource(R.string.screen_virtual_unavailable),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                                Icon(WifiNotificationIcon, contentDescription = null,
                                    modifier = Modifier.padding(12.dp).size(24.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.device_forward_notifications_title),
                                    style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.device_forward_notifications_body),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Surface(shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                NotificationForwardingSwitch(
                                    label = stringResource(R.string.device_forward_notifications_local),
                                    checked = forwardLocalNotifications,
                                    enabled = trustActionsEnabled,
                                    onCheckedChange = onForwardLocalNotificationsChange,
                                )
                                HorizontalDivider()
                                NotificationForwardingSwitch(
                                    label = stringResource(R.string.device_forward_notifications_iphone),
                                    checked = forwardIphoneNotifications,
                                    enabled = trustActionsEnabled,
                                    onCheckedChange = onForwardIphoneNotificationsChange,
                                )
                                HorizontalDivider()
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(stringResource(R.string.device_filters_label), Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge)
                                    OutlinedButton(onClick = onShowNotificationFilters,
                                        enabled = trustActionsEnabled && hasNotificationFilters) {
                                        Text(stringResource(R.string.device_filters_manage))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (device.status == TrustStatus.TRUSTED) {
                item {
                    Button(
                        onClick = onRemove,
                        enabled = trustActionsEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            FilledDeleteIcon,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        androidx.compose.foundation.layout.Spacer(
                            Modifier.size(ButtonDefaults.IconSpacing)
                        )
                        Text(stringResource(R.string.device_remove_desc, name))
                    }
                }
            }
            if (device.status == TrustStatus.REVOKED) {
                item {
                    RevokedDeviceActions(
                        name = name,
                        revokedAt = device.revokedAt,
                        nowMillis = nowMillis,
                        actionsEnabled = trustActionsEnabled,
                        onRestore = onRestore,
                        onPurge = onPurge,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenSharingHeader(
    device: RosterDevice,
    showConnect: Boolean,
    connectEnabled: Boolean,
    onConnectMirror: () -> Unit,
    onDismiss: () -> Unit,
) {
    val virtualDisplay = Capability.SCREEN_VIRTUAL_DISPLAY_V1 in device.capabilities
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Icon(ScreenShareIcon, contentDescription = null, modifier = Modifier.padding(12.dp).size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_section_screen_sharing), style = MaterialTheme.typography.titleMedium)
            if (showConnect) Text(
                stringResource(if (virtualDisplay) R.string.screen_virtual_title else R.string.screen_mirror_device_start),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showConnect) {
            if (virtualDisplay) {
                ScreenVirtualDisplayButton(sourceId = device.clientId, enabled = connectEnabled, onLaunch = onDismiss)
            } else {
                FilledTonalIconButton(onClick = onConnectMirror, enabled = connectEnabled) {
                    Icon(ScreenShareIcon, contentDescription = stringResource(R.string.screen_mirror_device_start_desc,
                        device.displayName ?: stringResource(R.string.device_unknown)))
                }
            }
        }
    }
}

@Composable
private fun NotificationForwardingSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f).padding(end = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun RevokedDeviceActions(
    name: String,
    revokedAt: Long?,
    nowMillis: Long,
    actionsEnabled: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val remainingMillis = revokedAt?.let {
        (it + TrustStore.REVOKE_PURGE_DELAY_MS - nowMillis).coerceAtLeast(0L)
    }
    val purgeEnabled = actionsEnabled && remainingMillis == 0L

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.device_restore_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onRestore,
            enabled = actionsEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                RestoreIcon,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.device_restore_desc, name))
        }

        Text(
            stringResource(R.string.device_permanently_delete_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onPurge,
            enabled = purgeEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                FilledDeleteIcon,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.device_delete_desc, name))
        }
        Text(
            when {
                !actionsEnabled -> stringResource(R.string.device_actions_quarantined)
                remainingMillis == null -> stringResource(R.string.device_purge_timestamp_missing)
                remainingMillis > 0L -> stringResource(
                    R.string.device_purge_waiting,
                    formatDuration(remainingMillis.roundUpToSecond()),
                )
                else -> stringResource(R.string.device_purge_ready)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Long.roundUpToSecond(): Long = ((this + 999L) / 1_000L) * 1_000L

@Composable
private fun ScreenMirrorCodecSelector(
    selectedCodec: ScreenMirrorCodec?,
    availableCodecs: Set<ScreenMirrorCodec>,
    enabled: Boolean,
    onSelected: (ScreenMirrorCodec?) -> Unit,
) {
    val choices = listOf(
        null to stringResource(R.string.screen_mirror_codec_auto),
        ScreenMirrorCodec.AV1 to stringResource(R.string.screen_mirror_codec_av1),
        ScreenMirrorCodec.H265 to stringResource(R.string.screen_mirror_codec_h265),
        ScreenMirrorCodec.H264 to stringResource(R.string.screen_mirror_codec_h264),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.screen_mirror_codec_title),
            style = MaterialTheme.typography.labelLarge,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, (codec, label) ->
                SegmentedButton(
                    selected = codec == selectedCodec,
                    onClick = { onSelected(codec) },
                    enabled = enabled && (codec == null || codec in availableCodecs),
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                    label = {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
        Text(
            stringResource(R.string.screen_mirror_codec_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScreenControlAuthorization(
    masterEnabled: Boolean,
    enabled: Boolean,
    authorized: Boolean,
    onAuthorizedChange: (Boolean) -> Unit,
    title: Int = R.string.screen_mirror_device_title,
    body: Int = R.string.screen_mirror_device_body,
    additionalBody: Int? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = authorized,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onAuthorizedChange,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(title),
                modifier = Modifier.weight(1f).padding(end = 16.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            Switch(
                checked = authorized,
                onCheckedChange = onAuthorizedChange,
                enabled = enabled,
            )
        }
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        additionalBody?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!masterEnabled) {
            Text(
                stringResource(R.string.screen_mirror_device_master_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceDetailsHeader(name: String, platform: String, verified: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                SmartphoneIcon,
                contentDescription = null,
                modifier = Modifier.padding(12.dp).size(32.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (!LocalIsDetailPane.current) Text(
                name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                platform,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VerificationBadge(verified)
        }
    }
}

@Composable
private fun VerificationBadge(verified: Boolean) {
    val container: Color
    val content: Color
    val label: String
    if (verified) {
        container = MaterialTheme.colorScheme.primaryContainer
        content = MaterialTheme.colorScheme.onPrimaryContainer
        label = stringResource(R.string.device_details_verified)
    } else {
        container = MaterialTheme.colorScheme.errorContainer
        content = MaterialTheme.colorScheme.onErrorContainer
        label = stringResource(R.string.device_details_not_verified)
    }
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun CollapsibleDeviceSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val expansionState = stringResource(
        if (expanded) R.string.device_details_expanded else R.string.device_details_collapsed,
    )
    val chevronRotation by animateFloatAsState(if (expanded) 270f else 90f, label = "sectionChevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = expansionState }
                .heightIn(min = 48.dp)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(summary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            Icon(ChevronRightIcon, contentDescription = null,
                modifier = Modifier.size(20.dp).rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider()
                content()
            }
        }
    }
}

@Composable
private fun OperationalEpochDetails(epoch: RosterKeyEpoch?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EpochField(
            stringResource(R.string.pair_field_signing_key),
            epoch?.signingKeyFingerprint ?: EM_DASH,
            monospace = true,
        )
        EpochField(
            stringResource(R.string.pair_field_encryption_key),
            epoch?.encryptionKeyFingerprint ?: EM_DASH,
            monospace = true,
        )
        if (epoch != null) {
            EpochField(
                stringResource(R.string.device_details_not_before),
                epochTimeLabel(epoch.notBefore),
            )
            EpochField(
                stringResource(R.string.device_details_not_after),
                epochTimeLabel(epoch.notAfter),
            )
            EpochField(
                stringResource(R.string.device_details_minimum_epoch),
                epoch.minEpoch.toString(),
            )
        }
    }
}

@Composable
private fun EpochField(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
            )
        }
    }
}

@Composable
private fun DeviceCapabilities(capabilities: List<Capability>) {
    if (capabilities.isEmpty()) {
        Text(EM_DASH, style = MaterialTheme.typography.bodyMedium)
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            capabilities.forEach { capability ->
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        capabilityLabel(capability),
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceDetailsField(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (monospace) FontFamily.Monospace else null,
            )
        }
    }
}

@Composable
internal fun platformLabel(platform: String?): String = when (platform?.trim()?.lowercase()) {
    "android" -> stringResource(R.string.device_details_platform_android)
    "ios" -> stringResource(R.string.device_details_platform_ios)
    "web" -> stringResource(R.string.device_details_platform_web)
    "desktop" -> stringResource(R.string.device_details_platform_desktop)
    null, "" -> stringResource(R.string.device_details_platform_unknown)
    else -> platform.orEmpty()
}

@Composable
private fun capabilityLabel(capability: Capability): String = stringResource(
    when (capability) {
        Capability.CAPTURE -> R.string.device_capability_capture
        Capability.DISPLAY -> R.string.device_capability_display
        Capability.DISMISS_SYNC -> R.string.device_capability_dismiss_sync
        Capability.PROVIDE_ASSETS -> R.string.device_capability_provide_assets
        Capability.BACKGROUND_WAKE -> R.string.device_capability_background_wake
        Capability.FOREGROUND_CONNECTION -> R.string.device_capability_foreground_connection
        Capability.CAPABILITY_ROUTING_V1 -> R.string.device_capability_routing
        Capability.PUSH_FILTERING -> R.string.device_capability_push_filtering
        Capability.DISPLAY_NOTIFICATION_UPDATES -> R.string.device_capability_notification_updates
        Capability.DISPLAY_ANDROID_GROUP_SUMMARIES -> R.string.device_capability_android_group_summaries
        Capability.PUBLISH_RUNS -> R.string.device_capability_publish_runs
        Capability.RECEIVE_RUNS -> R.string.device_capability_receive_runs
        Capability.SCREEN_MIRROR_SOURCE_V1 -> R.string.device_capability_screen_source
        Capability.SCREEN_MIRROR_CONTROL_V1 -> R.string.device_capability_screen_control
        Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1 -> R.string.device_capability_screen_clipboard
        Capability.SCREEN_MIRROR_ENCODER_H264_HW -> R.string.device_capability_screen_h264
        Capability.SCREEN_MIRROR_ENCODER_H265_HW -> R.string.device_capability_screen_h265
        Capability.SCREEN_MIRROR_ENCODER_AV1_HW -> R.string.device_capability_screen_av1
        Capability.SCREEN_MIRROR_VIDEO_VISIBILITY_V1 -> R.string.device_capability_screen_visibility
        Capability.SCREEN_MIRROR_BROKER_RELAY_V1 -> R.string.device_capability_screen_broker_relay
        Capability.OPENPGP_SIGN_V1 -> R.string.device_capability_openpgp_sign
        Capability.OPENPGP_SIGN_GIT_TAG_V1 -> R.string.device_capability_openpgp_tag_sign
        Capability.SCREEN_VIRTUAL_DISPLAY_V1 -> R.string.screen_virtual_title
        Capability.SSH_KEY_PROVIDER_V1 -> R.string.device_capability_ssh_key_provider
        Capability.SSH_AGENT_V1 -> R.string.device_capability_ssh_agent
        Capability.HOTSPOT_PROVIDER_V1 -> R.string.hotspot_title
        Capability.HOTSPOT_CONTROL_V1 -> R.string.hotspot_control_capability
    },
)

@Composable
private fun epochTimeLabel(value: Long): String {
    if (value == 0L) return stringResource(R.string.device_details_immediate)
    if (value == Long.MAX_VALUE) return stringResource(R.string.device_details_no_expiry)
    val locale = LocalConfiguration.current.locales[0]
    return remember(value, locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(value))
    }
}

private const val EM_DASH = "—"
