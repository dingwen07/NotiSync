package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.transport.DeliveryMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityScreen() {
    val graph = rememberGraph()
    val events by graph.activityLog.events.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    NotiScaffold(stringResource(R.string.tab_activity)) { modifier ->
        if (events.isEmpty()) {
            Column(
                modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.activity_empty_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.activity_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier.fillMaxSize()) {
                items(events) { e ->
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(
                                    R.string.activity_event_overline,
                                    activityKindLabel(e.kind),
                                    fmt.format(Date(e.timestamp))
                                )
                            )
                        },
                        headlineContent = { Text(e.title) },
                        supportingContent = { Text(activityDetail(e)) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun activityDetail(event: ActivityEvent): String {
    val mode = event.deliveryMode ?: return event.detail
    return stringResource(
        R.string.activity_detail_with_delivery,
        event.detail,
        deliveryModeLabel(mode)
    )
}

@Composable
private fun deliveryModeLabel(mode: DeliveryMode): String = when (mode) {
    DeliveryMode.UNKNOWN -> stringResource(R.string.activity_delivery_unknown)
    DeliveryMode.WEBSOCKET -> stringResource(R.string.activity_delivery_websocket)
    DeliveryMode.FCM_INLINE -> stringResource(R.string.activity_delivery_fcm_inline)
    // Both relay-fetch modes share one user-facing label; the wake-vs-drain split is a perf-trace concern.
    DeliveryMode.FCM_RELAY_FETCH, DeliveryMode.RELAY_DRAIN ->
        stringResource(R.string.activity_delivery_fcm_relay_fetch)
}

@Composable
private fun activityKindLabel(kind: ActivityEvent.Kind): String = when (kind) {
    ActivityEvent.Kind.CAPTURED -> stringResource(R.string.activity_kind_captured)
    ActivityEvent.Kind.SENT -> stringResource(R.string.activity_kind_sent)
    ActivityEvent.Kind.RECEIVED -> stringResource(R.string.activity_kind_received)
    ActivityEvent.Kind.DISMISSED -> stringResource(R.string.activity_kind_dismissed)
    ActivityEvent.Kind.PAIRED -> stringResource(R.string.activity_kind_paired)
    ActivityEvent.Kind.ROUTE_REPAIR -> stringResource(R.string.activity_kind_route_repair)
    ActivityEvent.Kind.ERROR -> stringResource(R.string.activity_kind_error)
}
