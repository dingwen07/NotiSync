package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.R
import net.extrawdw.notisync.protocol.HealthResponse
import net.extrawdw.notisync.protocol.VerificationStatusResponse
import net.extrawdw.notisync.protocol.crypto.HttpRequestSigning
import net.extrawdw.notisync.protocol.crypto.ProofOfWork
import net.extrawdw.apps.notisync.ui.theme.SecurityAmberDark
import net.extrawdw.apps.notisync.ui.theme.SecurityAmberLight
import net.extrawdw.apps.notisync.ui.theme.SecurityGreenDark
import net.extrawdw.apps.notisync.ui.theme.SecurityGreenLight
import net.extrawdw.apps.notisync.ui.theme.SecurityRedDark
import net.extrawdw.apps.notisync.ui.theme.SecurityRedLight

/** Result of a one-shot broker probe (health + attestation status). */
sealed interface ServerProbe {
    data object Idle : ServerProbe
    data object Loading : ServerProbe
    data class Result(val health: HealthResponse?, val status: VerificationStatusResponse?) : ServerProbe

    fun version(): String = (this as? Result)?.let { it.health?.version ?: it.status?.version } ?: "?"
}

/** One difficulty's benchmark sample: hashes tried, wall-clock time, and the derived hashrate. */
data class PowBench(val difficulty: Int, val hashes: Long, val ms: Double)

/** Progressive proof-of-work benchmark, difficulty 1..5, reporting hashes + time per difficulty. */
sealed interface BenchmarkState {
    data object Idle : BenchmarkState
    data class Running(val done: List<PowBench>) : BenchmarkState
    data class Done(val results: List<PowBench>) : BenchmarkState
}

/** State of the "send oversized test notification" action (exercises the wake → relay-fetch path). */
sealed interface OversizedTestState {
    data object Idle : OversizedTestState
    data object Sending : OversizedTestState
    data class Sent(val deviceCount: Int) : OversizedTestState
    data object Failed : OversizedTestState
}

/** One round-trip to the broker, off the main thread. Returns a Result even when calls fail (nulls). */
suspend fun probeServer(graph: AppGraph): ServerProbe = withContext(Dispatchers.IO) {
    val health = runCatching { graph.transport.fetchHealth() }.getOrNull()
    val status = runCatching { graph.transport.fetchVerificationStatus() }.getOrNull()
    ServerProbe.Result(health, status)
}

/** Grind the PoW at difficulties 1..5 on a background thread, emitting each result as it completes. */
suspend fun runPowBenchmark(onProgress: (BenchmarkState) -> Unit) {
    val acc = ArrayList<PowBench>()
    onProgress(BenchmarkState.Running(emptyList()))
    for (difficulty in 1..5) {
        val sample = withContext(Dispatchers.Default) {
            val signature = HttpRequestSigning.newNonce(32) // a stand-in signature to grind against
            val timestamp = System.currentTimeMillis()
            val start = System.nanoTime()
            val solution = ProofOfWork.solveCounted(signature, timestamp, difficulty)
            PowBench(difficulty, solution.hashes, (System.nanoTime() - start) / 1_000_000.0)
        }
        acc.add(sample)
        onProgress(BenchmarkState.Running(acc.toList()))
    }
    onProgress(BenchmarkState.Done(acc.toList()))
}

@Composable
fun DiagnosticsCard(
    graph: AppGraph,
    probe: ServerProbe,
    benchmark: BenchmarkState,
    oversizedTest: OversizedTestState,
    onRefresh: () -> Unit,
    onBenchmark: () -> Unit,
    onSendOversizedTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_diagnostics), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = probe !is ServerProbe.Loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.diag_refresh))
                }
            }

            val loading = probe is ServerProbe.Loading || probe is ServerProbe.Idle
            val result = probe as? ServerProbe.Result

            val (serverValue, serverTone) = when {
                loading -> stringResource(R.string.diag_checking) to Tone.NEUTRAL
                result?.health?.status == "ok" -> stringResource(R.string.diag_server_healthy, result.version()) to Tone.GOOD
                result?.status != null -> stringResource(R.string.diag_server_reachable, result.version()) to Tone.GOOD
                else -> stringResource(R.string.diag_server_unreachable) to Tone.BAD
            }
            StatusRow(stringResource(R.string.diag_server), serverValue, serverTone, loading)

            val status = result?.status
            val (attValue, attTone) = when {
                loading -> stringResource(R.string.diag_checking) to Tone.NEUTRAL
                status == null -> stringResource(R.string.diag_attestation_unknown) to Tone.NEUTRAL
                !status.playIntegrityRequired -> stringResource(R.string.diag_attestation_not_required) to Tone.NEUTRAL
                status.verified -> stringResource(R.string.diag_attestation_verified) to Tone.GOOD
                else -> stringResource(R.string.diag_attestation_unverified) to Tone.WARN
            }
            StatusRow(stringResource(R.string.diag_attestation), attValue, attTone, loading)
            status?.takeIf { it.verified }?.expiresAt?.let { expiresAt ->
                Text(
                    stringResource(R.string.diag_token_expires, tokenTimeRemaining(expiresAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.settings_client_id, graph.identity.clientId.value),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.settings_key_backing, keyBackingLabel(graph.identity.backing)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.settings_transport, graph.transport.type.toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()

            Text(stringResource(R.string.diag_pow), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onBenchmark, enabled = benchmark !is BenchmarkState.Running) {
                Text(
                    if (benchmark is BenchmarkState.Running) stringResource(R.string.diag_pow_running)
                    else stringResource(R.string.diag_pow_benchmark),
                )
            }
            val rows = when (benchmark) {
                is BenchmarkState.Running -> benchmark.done
                is BenchmarkState.Done -> benchmark.results
                BenchmarkState.Idle -> emptyList()
            }
            rows.forEach { b ->
                Text(
                    stringResource(
                        R.string.diag_pow_result,
                        b.difficulty,
                        "%,d".format(b.hashes),
                        "%.1f ms".format(b.ms),
                        formatHashrate(b.hashes, b.ms),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Text(stringResource(R.string.diag_delivery_test), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.diag_oversized_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onSendOversizedTest,
                enabled = oversizedTest !is OversizedTestState.Sending,
            ) {
                Text(
                    if (oversizedTest is OversizedTestState.Sending) stringResource(R.string.diag_oversized_sending)
                    else stringResource(R.string.diag_oversized_send),
                )
            }
            when (oversizedTest) {
                is OversizedTestState.Sent -> Text(
                    if (oversizedTest.deviceCount > 0) {
                        stringResource(R.string.diag_oversized_sent, oversizedTest.deviceCount)
                    } else {
                        stringResource(R.string.diag_oversized_no_peers)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OversizedTestState.Failed -> Text(
                    stringResource(R.string.diag_oversized_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
    }
}

private enum class Tone { GOOD, WARN, BAD, NEUTRAL }

@Composable
private fun Tone.color(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        Tone.GOOD -> if (dark) SecurityGreenDark else SecurityGreenLight
        Tone.WARN -> if (dark) SecurityAmberDark else SecurityAmberLight
        Tone.BAD -> if (dark) SecurityRedDark else SecurityRedLight
        Tone.NEUTRAL -> MaterialTheme.colorScheme.outline
    }
}

@Composable
private fun StatusRow(label: String, value: String, tone: Tone, loading: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        } else {
            Box(Modifier.size(10.dp).clip(CircleShape).background(tone.color()))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun tokenTimeRemaining(epochMillis: Long): String {
    val remainingMillis = epochMillis - System.currentTimeMillis()
    if (remainingMillis <= 0) return stringResource(R.string.diag_token_expired)

    val totalMinutes = remainingMillis / MILLIS_PER_MINUTE
    if (totalMinutes == 0L) return stringResource(R.string.diag_token_less_than_minute)

    val days = totalMinutes / MINUTES_PER_DAY
    val hours = (totalMinutes % MINUTES_PER_DAY) / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    val parts = mutableListOf<String>()
    if (days > 0) parts += pluralStringResource(R.plurals.diag_duration_days, days.toInt(), days)
    if (hours > 0) parts += pluralStringResource(R.plurals.diag_duration_hours, hours.toInt(), hours)
    if (minutes > 0) parts += pluralStringResource(R.plurals.diag_duration_minutes, minutes.toInt(), minutes)
    return parts.joinToString(" ")
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24L * MINUTES_PER_HOUR

private fun formatHashrate(hashes: Long, ms: Double): String {
    if (ms <= 0.0) return "—"
    val hps = hashes / (ms / 1000.0)
    return when {
        hps >= 1_000_000 -> "%.2f Mh/s".format(hps / 1_000_000)
        hps >= 1_000 -> "%.1f kh/s".format(hps / 1_000)
        else -> "%.0f h/s".format(hps)
    }
}
