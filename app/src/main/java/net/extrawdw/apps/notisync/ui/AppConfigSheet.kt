package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.data.AppConfigRepository
import net.extrawdw.apps.notisync.data.PerAppConfig
import net.extrawdw.apps.notisync.data.SeenChannel
import kotlin.math.roundToInt

/**
 * The update-frequency slider's discrete stops, mapped to [PerAppConfig.updateIntervalSec]:
 *  - 0  = mirror only the initial ongoing post (no updates),
 *  - >0 = throttle updates to a minimum gap of this many seconds,
 *  - -1 = mirror every update (no throttle).
 * Order is the slider's left-to-right order, so its index is the slider position.
 */
private val UPDATE_STOPS = listOf(0, 60, 30, 10, 5, -1)

/**
 * Per-app mirroring configuration, opened by tapping an enabled app in the Apps list. Lets the user opt into
 * mirroring the app's ongoing (media/transport/foreground-service) notifications, choose how often their
 * updates may be re-sent (delivered quietly, never as alerts), decide whether ongoing/media rows may reach iOS
 * peers, and disable specific notification channels or whole channel groups. All backed by [AppConfigRepository];
 * channels are the ones observed from this app's captures so far — "Remove" only forgets one from this list, and
 * it reappears on the next capture in it.
 */
@Suppress("DEPRECATION") // see NotificationFilterSheet: the stable M3 factory is deprecated by the Expressive artifact.
@Composable
internal fun AppConfigSheet(
    app: InstalledApp,
    appConfig: AppConfigRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val configs by appConfig.configs.collectAsStateWithLifecycle()
    val seen by appConfig.seenChannels.collectAsStateWithLifecycle()
    val cfg = configs[app.packageName] ?: PerAppConfig()
    val channels = seen[app.packageName].orEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)

            // --- Incoming: how a call RECEIVED from this app is alerted on THIS device. Kept at the top and
            // separated from the capture (outgoing) settings below, so its opposite direction isn't confused. ---
            SettingSwitchRow(
                title = stringResource(R.string.app_config_ring_calls_title),
                subtitle = stringResource(R.string.app_config_ring_calls_desc),
                checked = cfg.ringForCalls,
                onCheckedChange = { appConfig.setRingForCalls(app.packageName, it) },
            )

            HorizontalDivider()

            // --- Outgoing capture: ongoing notifications this device mirrors OUT to your other devices ---
            SettingSwitchRow(
                title = stringResource(R.string.app_config_ongoing_title),
                subtitle = stringResource(R.string.app_config_ongoing_desc),
                checked = cfg.mirrorOngoing,
                onCheckedChange = { appConfig.setMirrorOngoing(app.packageName, it) },
            )
            if (cfg.mirrorOngoing) {
                UpdateFrequencySlider(
                    current = cfg.updateIntervalSec,
                    onChange = { appConfig.setUpdateIntervalSec(app.packageName, it) },
                )
                SettingSwitchRow(
                    title = stringResource(R.string.app_config_ongoing_ios_title),
                    subtitle = stringResource(R.string.app_config_ongoing_ios_desc),
                    checked = cfg.mirrorOngoingToIos,
                    onCheckedChange = { appConfig.setMirrorOngoingToIos(app.packageName, it) },
                )
            }
            SettingSwitchRow(
                title = stringResource(R.string.app_config_media_ios_title),
                subtitle = stringResource(R.string.app_config_media_ios_desc),
                checked = cfg.mirrorMediaPlaybackToIos,
                onCheckedChange = { appConfig.setMirrorMediaPlaybackToIos(app.packageName, it) },
            )

            HorizontalDivider()

            // --- Notification channels ---
            Text(stringResource(R.string.app_config_channels_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.app_config_channels_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (channels.isEmpty()) {
                Text(
                    stringResource(R.string.app_config_channels_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Group by channel group; null-group channels fall into a trailing "Other" bucket that has no
                // group-level toggle (there is no group id to disable). Groups with a real id get a group switch.
                val byGroup = channels.groupBy { it.groupId }
                byGroup.forEach { (groupId, groupChannels) ->
                    ChannelGroup(app.packageName, groupId, groupChannels, cfg, appConfig)
                }
            }
        }
    }
}

@Composable
private fun UpdateFrequencySlider(current: Int, onChange: (Int) -> Unit) {
    val index = UPDATE_STOPS.indexOf(current).let { if (it < 0) 0 else it }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.app_config_update_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                updateStopLabel(current),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            stringResource(R.string.app_config_update_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = index.toFloat(),
            onValueChange = {
                onChange(UPDATE_STOPS[it.roundToInt().coerceIn(0, UPDATE_STOPS.lastIndex)])
            },
            valueRange = 0f..UPDATE_STOPS.lastIndex.toFloat(),
            steps = UPDATE_STOPS.size - 2,
        )
    }
}

@Composable
private fun updateStopLabel(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.app_config_update_initial_only)
    seconds < 0 -> stringResource(R.string.app_config_update_every)
    else -> stringResource(R.string.app_config_update_seconds, seconds)
}

@Composable
private fun ChannelGroup(
    packageName: String,
    groupId: String?,
    channels: List<SeenChannel>,
    cfg: PerAppConfig,
    appConfig: AppConfigRepository,
) {
    val groupDisabled = groupId != null && groupId in cfg.disabledGroupIds
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val groupLabel = channels.firstNotNullOfOrNull { it.groupName?.takeIf(String::isNotBlank) }
            ?: groupId
            ?: stringResource(R.string.app_config_group_other)
        if (groupId != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    groupLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = !groupDisabled,
                    onCheckedChange = { appConfig.setGroupEnabled(packageName, groupId, it) },
                )
            }
        } else {
            Text(
                groupLabel,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        channels.forEach { ch ->
            ChannelRow(
                packageName = packageName,
                channel = ch,
                on = !groupDisabled && ch.channelId !in cfg.disabledChannelIds,
                enabled = !groupDisabled,
                appConfig = appConfig,
            )
        }
    }
}

@Composable
private fun ChannelRow(
    packageName: String,
    channel: SeenChannel,
    on: Boolean,
    enabled: Boolean,
    appConfig: AppConfigRepository,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                channel.channelName?.takeIf(String::isNotBlank) ?: channel.channelId,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Channel ID, always shown (a channel's user-visible name may be redacted for a plain listener).
            Text(
                channel.channelId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Forget this channel from the list only — it reappears on the next capture in it (not a block).
        IconButton(onClick = { appConfig.removeSeenChannel(packageName, channel.channelId) }) {
            Icon(
                painterResource(R.drawable.ic_delete_history),
                contentDescription = stringResource(R.string.app_config_remove_channel),
            )
        }
        Switch(
            checked = on,
            enabled = enabled,
            onCheckedChange = { appConfig.setChannelEnabled(packageName, channel.channelId, it) },
        )
    }
}

/** A title + subtitle row with a trailing [Switch] — the sheet's ongoing-notifications toggle. */
@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
