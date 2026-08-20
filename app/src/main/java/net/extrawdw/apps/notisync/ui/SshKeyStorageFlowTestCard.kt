package net.extrawdw.apps.notisync.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.sshagent.SshKeyStorageFlowTest
import net.extrawdw.apps.notisync.ui.theme.SecurityGreenDark
import net.extrawdw.apps.notisync.ui.theme.SecurityGreenLight
import net.extrawdw.apps.notisync.ui.theme.SecurityRedDark
import net.extrawdw.apps.notisync.ui.theme.SecurityRedLight

/**
 * Settings → Advanced card for the SSH Agent key-storage flow test.
 *
 * Self-contained on purpose: it resolves the graph, the activity, and its own state, and the runner
 * ([SshKeyStorageFlowTest]) only uses the public [net.extrawdw.apps.notisync.sshagent.SshKeyProviderStore]
 * API. Removing this feature means deleting this file, `SshKeyStorageFlowTest.kt`, and the single
 * `SshKeyStorageFlowTestCard()` list item in `SettingsScreen.kt` — nothing else.
 */
@Composable
fun SshKeyStorageFlowTestCard() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SshKeyStorageFlowTestState>(SshKeyStorageFlowTestState.Idle) }

    fun runTest() {
        if (state is SshKeyStorageFlowTestState.Running) return
        val activity = context as? Activity
        if (activity == null) {
            state = SshKeyStorageFlowTestState.Failed(
                "The flow test needs the settings activity to show system authentication prompts.",
            )
            return
        }
        state = SshKeyStorageFlowTestState.Running(emptyList())
        scope.launch {
            val results = mutableListOf<SshKeyStorageFlowTest.CaseOutcome>()
            val runner = SshKeyStorageFlowTest(activity, graph.sshKeyProviderStore, graph.identity.clientId)
            val failure = try {
                runner.run { outcome ->
                    results += outcome
                    state = SshKeyStorageFlowTestState.Running(results.toList())
                }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure.message ?: failure.javaClass.simpleName
            }
            state = if (failure == null) {
                SshKeyStorageFlowTestState.Done(results.toList())
            } else {
                SshKeyStorageFlowTestState.Failed(failure)
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SSH Agent key storage flow test", style = MaterialTheme.typography.titleSmall)
            Text(
                "Generates one Ed25519 key for every combination of the key-storage options (export copy, " +
                    "TEE-only export backend, per-use biometric verification), signs test data through the real " +
                    "SSH Agent signing path, and verifies the signature. The system shows several authentication " +
                    "prompts for the protected options. Each test key is deleted afterwards; one \"flowtest\" " +
                    "record remains in the SSH Agent history per verified key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = ::runTest,
                enabled = state !is SshKeyStorageFlowTestState.Running,
            ) {
                Text(
                    if (state is SshKeyStorageFlowTestState.Running) {
                        "Running SSH key-storage flow test…"
                    } else {
                        "Run SSH key-storage flow test"
                    },
                )
            }
            when (val current = state) {
                is SshKeyStorageFlowTestState.Failed -> Text(
                    "Flow test failed: ${current.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                is SshKeyStorageFlowTestState.Running,
                is SshKeyStorageFlowTestState.Done,
                -> {
                    val results = when (current) {
                        is SshKeyStorageFlowTestState.Running -> current.results
                        is SshKeyStorageFlowTestState.Done -> current.results
                        else -> emptyList()
                    }
                    if (current is SshKeyStorageFlowTestState.Running && results.isEmpty()) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    results.forEach { outcome ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                outcome.case.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            when (outcome) {
                                is SshKeyStorageFlowTest.CaseOutcome.Passed -> Text(
                                    "OK — ${outcome.detail}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = flowTestTone(ok = true),
                                )

                                is SshKeyStorageFlowTest.CaseOutcome.Failed -> Text(
                                    "FAILED — ${outcome.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = flowTestTone(ok = false),
                                )
                            }
                        }
                    }
                    if (current is SshKeyStorageFlowTestState.Done) {
                        val passed = results.count { it is SshKeyStorageFlowTest.CaseOutcome.Passed }
                        Text(
                            "Done: $passed of ${results.size} combinations passed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SshKeyStorageFlowTestState.Idle -> Unit
            }
        }
    }
}

private sealed interface SshKeyStorageFlowTestState {
    data object Idle : SshKeyStorageFlowTestState
    data class Running(val results: List<SshKeyStorageFlowTest.CaseOutcome>) : SshKeyStorageFlowTestState
    data class Done(val results: List<SshKeyStorageFlowTest.CaseOutcome>) : SshKeyStorageFlowTestState
    data class Failed(val message: String) : SshKeyStorageFlowTestState
}

@Composable
private fun flowTestTone(ok: Boolean): Color {
    val dark = isSystemInDarkTheme()
    return if (ok) {
        if (dark) SecurityGreenDark else SecurityGreenLight
    } else {
        if (dark) SecurityRedDark else SecurityRedLight
    }
}
