package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.notisync.R
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
 * by the trust store; the only mutable field is the local screen-control grant.
 */
@Suppress("DEPRECATION") // Stable M3 factory is deprecated only by the Expressive artifact in use.
@Composable
internal fun DeviceDetailsSheet(
    device: RosterDevice,
    nowMillis: Long,
    screenMirroringEnabled: Boolean,
    screenControlAuthorized: Boolean,
    screenMirrorRequestEnabled: Boolean = true,
    trustActionsEnabled: Boolean = true,
    screenMirrorCodecOverride: ScreenMirrorCodec?,
    screenMirrorDecoderSupport: AndroidScreenDecoderSupport,
    onScreenControlAuthorizedChange: (Boolean) -> Unit,
    onScreenMirrorCodecOverrideChange: (ScreenMirrorCodec?) -> Unit,
    onStartScreenMirror: (ClientId) -> Unit = {},
    onRemove: () -> Unit = {},
    onRestore: () -> Unit = {},
    onPurge: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val name = device.displayName ?: stringResource(R.string.device_unknown)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 4.dp,
                end = 20.dp,
                bottom = 40.dp,
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
                DeviceDetailsField(
                    label = stringResource(R.string.pair_field_identity_key),
                    value = device.identityKeyFingerprint ?: EM_DASH,
                    monospace = true,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.device_details_key_epoch),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OperationalEpochCard(device.keyEpoch)
                }
            }
            item {
                DeviceCapabilities(device.capabilities)
            }
            if (device.supportsScreenMirrorRequest()) {
                val availableCodecs = availableAndroidScreenCodecs(
                    sourceCapabilities = device.capabilities.toSet(),
                    decoderSupport = screenMirrorDecoderSupport,
                )
                item {
                    ScreenMirrorCodecSelector(
                        // Preserve and display an unavailable durable override as selected+disabled;
                        // the requester temporarily falls back to Auto until that codec returns.
                        selectedCodec = screenMirrorCodecOverride,
                        availableCodecs = availableCodecs,
                        enabled = screenMirrorRequestEnabled,
                        onSelected = onScreenMirrorCodecOverrideChange,
                    )
                }
                item {
                    Button(
                        onClick = { onStartScreenMirror(device.clientId) },
                        enabled = screenMirrorRequestEnabled && availableCodecs.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ScreenShare,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        androidx.compose.foundation.layout.Spacer(
                            Modifier.size(ButtonDefaults.IconSpacing)
                        )
                        Text(stringResource(R.string.screen_mirror_device_start))
                    }
                }
            }
            if (device.ownDevice && device.status == TrustStatus.TRUSTED && device.verified) {
                item {
                    ScreenControlAuthorization(
                        masterEnabled = screenMirroringEnabled,
                        authorized = screenControlAuthorized,
                        onAuthorizedChange = onScreenControlAuthorizedChange,
                    )
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
                            Icons.Filled.Delete,
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
                Icons.Outlined.Restore,
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
                Icons.Filled.Delete,
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
    authorized: Boolean,
    onAuthorizedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.screen_mirror_device_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Switch(
                checked = authorized,
                onCheckedChange = onAuthorizedChange,
                enabled = masterEnabled,
            )
        }
        Text(
            stringResource(R.string.screen_mirror_device_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                Icons.Outlined.Smartphone,
                contentDescription = null,
                modifier = Modifier.padding(12.dp).size(32.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
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
private fun OperationalEpochCard(epoch: RosterKeyEpoch?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (epoch == null) {
                    stringResource(R.string.device_details_operational_unavailable)
                } else {
                    stringResource(R.string.pair_operational_chip_title, epoch.epoch)
                },
                style = MaterialTheme.typography.titleSmall,
            )
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
}

@Composable
private fun EpochField(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.device_details_capabilities),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun platformLabel(platform: String?): String = when (platform?.trim()?.lowercase()) {
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
