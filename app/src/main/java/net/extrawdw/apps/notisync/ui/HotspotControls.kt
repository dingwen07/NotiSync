package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.hotspot.controller.RemoteHotspotState
import net.extrawdw.apps.notisync.hotspot.controller.SavedHotspot
import net.extrawdw.apps.notisync.ui.icons.material.outlined.refresh as RefreshIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.visibility as VisibilityIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.visibility_off as VisibilityOffIcon
import net.extrawdw.apps.notisync.ui.icons.material.outlined.wifi_tethering as HotspotIcon
import net.extrawdw.notisync.protocol.HotspotResult
import net.extrawdw.notisync.protocol.HotspotState
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HotspotControls(
    state: RemoteHotspotState?,
    saved: SavedHotspot?,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = state?.status
    val snapshot = status?.snapshot
    val pending = state?.pendingRequest != null
    val liveDetails = snapshot?.let { SavedHotspot.from(it, status.issuedAt) }
    val details = liveDetails ?: saved
    val usingSavedDetails = liveDetails == null && saved != null
    val hotspotOn = snapshot?.state == HotspotState.ENABLED
    val settled = hotspotOn || snapshot?.state == HotspotState.DISABLED
    val failure = state?.failure ?: status?.result?.takeUnless { it == HotspotResult.OK }
    val statusAvailable = settled && failure == null
    val canToggle = enabled && state?.canToggle == true
    val busy = pending || snapshot?.state in listOf(HotspotState.ENABLING, HotspotState.DISABLING)
    val statusText = when {
        pending -> R.string.hotspot_waiting
        failure != null -> R.string.hotspot_status_unavailable
        status == null && saved != null -> R.string.hotspot_saved_network
        else -> when (snapshot?.state) {
            HotspotState.ENABLED -> R.string.hotspot_on
            HotspotState.DISABLED -> R.string.hotspot_off
            HotspotState.ENABLING -> R.string.hotspot_starting
            HotspotState.DISABLING -> R.string.hotspot_stopping
            HotspotState.FAILED -> R.string.hotspot_status_unavailable
            else -> R.string.hotspot_unknown
        }
    }
    val statusColor = when {
        failure != null || snapshot?.state == HotspotState.FAILED -> MaterialTheme.colorScheme.error
        hotspotOn || busy -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(HotspotIcon, contentDescription = null, modifier = Modifier.padding(12.dp).size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.hotspot_title), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (busy) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    else Box(Modifier.size(6.dp).background(statusColor, CircleShape))
                    Text(stringResource(statusText), style = MaterialTheme.typography.labelMedium, color = statusColor)
                }
            }
            IconButton(onClick = onRefresh, enabled = enabled && !pending) {
                Icon(RefreshIcon, contentDescription = stringResource(R.string.hotspot_refresh_action))
            }
            val label = stringResource(R.string.hotspot_title)
            Switch(checked = statusAvailable && hotspotOn, onCheckedChange = onSetEnabled, enabled = canToggle,
                modifier = Modifier.semantics { contentDescription = label })
        }

        val message = when {
            pending -> null
            failure != null -> hotspotResultText(failure)
            snapshot?.state == HotspotState.FAILED -> R.string.hotspot_failed
            status == null && saved == null -> R.string.hotspot_refresh_hint
            else -> null
        }
        message?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Cached credentials keep their own timestamp even when a fresh request fails.
        val updatedAt = if (usingSavedDetails) saved.updatedAt else status?.issuedAt
        (details?.ssid ?: snapshot?.ssid)?.let { ssid ->
            HotspotNetworkDetails(ssid, details?.psk, usingSavedDetails, updatedAt)
        }
        details?.let { HotspotSavedNetworkActions(it) }
        if (!statusAvailable || details?.usable != true) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = { onSetEnabled(true) }, enabled = enabled && !pending,
                    modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.hotspot_start))
                }
                OutlinedButton(onClick = { onSetEnabled(false) }, enabled = enabled && !pending,
                    modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.hotspot_stop))
                }
            }
        }
    }
}

@Composable
private fun HotspotNetworkDetails(ssid: String, password: String?, cached: Boolean, updatedAt: Long?) {
    var showPassword by remember(ssid, password) { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (cached) R.string.hotspot_saved_network else R.string.hotspot_network_name),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).alignByBaseline())
                    updatedAt?.let {
                        Text(
                            text = stringResource(R.string.hotspot_updated,
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f).alignByBaseline(),
                        )
                    }
                }
                SelectionContainer { Text(ssid, style = MaterialTheme.typography.titleLarge) }
            }
            if (password != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.hotspot_password_label), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (showPassword) SelectionContainer {
                            Text(password, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                        } else Text("••••••••", style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                    }
                    IconToggleButton(checked = showPassword, onCheckedChange = { showPassword = it }) {
                        Icon(if (showPassword) VisibilityOffIcon else VisibilityIcon,
                            contentDescription = stringResource(if (showPassword) R.string.hotspot_hide_password else R.string.hotspot_show_password))
                    }
                }
            }
        }
    }
}

private fun hotspotResultText(result: HotspotResult?): Int = when (result) {
    HotspotResult.UNAUTHORIZED -> R.string.hotspot_unauthorized
    HotspotResult.UNSUPPORTED -> R.string.hotspot_unsupported
    HotspotResult.BUSY -> R.string.hotspot_busy
    HotspotResult.TIMEOUT, HotspotResult.EXPIRED -> R.string.hotspot_timeout
    HotspotResult.FAILED -> R.string.hotspot_failed
    else -> R.string.hotspot_unavailable
}
