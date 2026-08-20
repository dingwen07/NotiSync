package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.data.activity.ActivityAction
import net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode
import net.extrawdw.apps.notisync.data.activity.ActivityEvent
import net.extrawdw.apps.notisync.data.activity.ActivityFeature
import net.extrawdw.apps.notisync.data.activity.ActivityOutcome
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgs
import net.extrawdw.apps.notisync.data.activity.ActivityRepository

@Composable
internal fun ActivityScreen(repository: ActivityRepository) {
    val model: ActivityTimelineViewModel = viewModel(
        factory = ActivityTimelineViewModel.factory(repository),
    )
    val state by model.state.collectAsStateWithLifecycle()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    NotiScaffold(stringResource(R.string.tab_activity)) { modifier ->
        when {
            state.isLoading -> CenteredActivityState(modifier) {
                CircularProgressIndicator()
            }
            state.error != null -> CenteredActivityState(modifier) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.activity_load_failed),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            state.events.isEmpty() -> CenteredActivityState(modifier) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.activity_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.activity_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(modifier.fillMaxSize()) {
                items(state.events, key = ActivityEvent::eventId) { event ->
                    ListItem(
                        overlineContent = {
                            Text(
                                stringResource(
                                    R.string.activity_event_overline,
                                    activityFeatureLabel(event.feature),
                                    timeFormat.format(Date(event.occurredAt)),
                                ),
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(
                                    R.string.activity_event_headline,
                                    activityActionLabel(event.semanticAction),
                                    activityOutcomeLabel(event.outcome),
                                ),
                            )
                        },
                        supportingContent = { Text(activityDetail(event)) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CenteredActivityState(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun activityDetail(event: ActivityEvent): String {
    val details = buildList {
        event.peerClientId?.let { peerId ->
            add(stringResource(R.string.activity_peer, peerId.take(MAX_DISPLAY_PEER_CHARS)))
        }
        when (val args = event.renderArgs) {
            is ActivityRenderArgs.V1 -> {
                args.count?.let { add(stringResource(R.string.activity_arg_count, it)) }
                args.revision?.let { add(stringResource(R.string.activity_arg_revision, it)) }
                args.durationMillis?.let { add(stringResource(R.string.activity_arg_duration_ms, it)) }
            }
            is ActivityRenderArgs.Unsupported,
            is ActivityRenderArgs.Corrupt,
            -> add(stringResource(R.string.activity_details_unavailable))
        }
        if (event.coalescedCount > 1) {
            add(stringResource(R.string.activity_coalesced_count, event.coalescedCount))
        }
        event.deliveryMode?.let { add(activityDeliveryModeLabel(it)) }
    }
    return details.joinToString(separator = stringResource(R.string.activity_detail_separator))
        .ifEmpty { stringResource(R.string.activity_no_additional_details) }
}

@Composable
private fun activityFeatureLabel(feature: ActivityFeature): String = stringResource(
    when (feature) {
        ActivityFeature.NOTIFICATION -> R.string.activity_feature_notification
        ActivityFeature.RUN -> R.string.activity_feature_run
        ActivityFeature.SCREEN_MIRRORING -> R.string.activity_feature_screen
        ActivityFeature.SEAL -> R.string.activity_feature_seal
        ActivityFeature.SSH_AGENT -> R.string.activity_feature_ssh
        ActivityFeature.PROFILE -> R.string.activity_feature_profile
        ActivityFeature.TRUST -> R.string.activity_feature_trust
        ActivityFeature.PAIRING -> R.string.activity_feature_pairing
        ActivityFeature.ROUTE -> R.string.activity_feature_route
        ActivityFeature.SECURITY -> R.string.activity_feature_security
    },
)

@Composable
private fun activityActionLabel(action: ActivityAction): String = stringResource(
    when (action) {
        ActivityAction.CAPTURED -> R.string.activity_kind_captured
        ActivityAction.RECEIVED -> R.string.activity_kind_received
        ActivityAction.APPLIED -> R.string.activity_action_applied
        ActivityAction.QUEUED -> R.string.activity_action_queued
        ActivityAction.SENT -> R.string.activity_kind_sent
        ActivityAction.DISMISSED -> R.string.activity_kind_dismissed
        ActivityAction.CONTROLLED -> R.string.activity_action_controlled
        ActivityAction.REQUESTED -> R.string.activity_action_requested
        ActivityAction.ACCEPTED -> R.string.activity_action_accepted
        ActivityAction.REJECTED -> R.string.activity_action_rejected
        ActivityAction.CONNECTED -> R.string.activity_action_connected
        ActivityAction.ENDED -> R.string.activity_action_ended
        ActivityAction.CANCELLED -> R.string.activity_action_cancelled
        ActivityAction.EXPIRED -> R.string.activity_action_expired
        ActivityAction.FAILED -> R.string.activity_action_failed
        ActivityAction.PAIRED -> R.string.activity_kind_paired
        ActivityAction.REPAIRED -> R.string.activity_kind_route_repair
        ActivityAction.CONFLICT -> R.string.activity_action_conflict
    },
)

@Composable
private fun activityOutcomeLabel(outcome: ActivityOutcome): String = stringResource(
    when (outcome) {
        ActivityOutcome.SUCCESS -> R.string.activity_outcome_success
        ActivityOutcome.NO_OP -> R.string.activity_outcome_no_op
        ActivityOutcome.DUPLICATE -> R.string.activity_outcome_duplicate
        ActivityOutcome.SUPERSEDED -> R.string.activity_outcome_superseded
        ActivityOutcome.REJECTED -> R.string.activity_outcome_rejected
        ActivityOutcome.CANCELLED -> R.string.activity_outcome_cancelled
        ActivityOutcome.EXPIRED -> R.string.activity_outcome_expired
        ActivityOutcome.FAILED -> R.string.activity_outcome_failed
        ActivityOutcome.SECURITY_BLOCKED -> R.string.activity_outcome_security_blocked
    },
)

@Composable
private fun activityDeliveryModeLabel(mode: ActivityDeliveryMode): String = stringResource(
    when (mode) {
        ActivityDeliveryMode.UNKNOWN -> R.string.activity_delivery_unknown
        ActivityDeliveryMode.WEBSOCKET -> R.string.activity_delivery_websocket
        ActivityDeliveryMode.FCM_INLINE -> R.string.activity_delivery_fcm_inline
        ActivityDeliveryMode.FCM_RELAY_FETCH -> R.string.activity_delivery_fcm_relay_fetch
        ActivityDeliveryMode.RELAY_DRAIN -> R.string.activity_delivery_relay_drain
    },
)

private const val MAX_DISPLAY_PEER_CHARS = 12
