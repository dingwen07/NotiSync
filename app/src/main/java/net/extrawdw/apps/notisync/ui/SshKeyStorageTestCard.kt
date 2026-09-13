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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.sshkeyprovider.SshKeyStorageTest
import net.extrawdw.apps.notisync.ui.theme.SecurityGreenDark
import net.extrawdw.apps.notisync.ui.theme.SecurityGreenLight
import net.extrawdw.apps.notisync.ui.theme.SecurityRedDark
import net.extrawdw.apps.notisync.ui.theme.SecurityRedLight

/**
 * Settings → Advanced card for the SSH key storage test.
 *
 * Self-contained on purpose: it resolves the graph, the activity, and its own state, and the runner
 * ([SshKeyStorageTest]) only uses the public [net.extrawdw.apps.notisync.sshkeyprovider.SshKeyProviderStore]
 * API. Removing this feature means deleting this file, `SshKeyStorageTest.kt`, and the single
 * `SshKeyStorageTestCard()` list item in `SettingsScreen.kt`, and the `diagnostics_ssh_key_storage_test*` resources.
 */
@Composable
fun SshKeyStorageTestCard() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SshKeyStorageTestState>(SshKeyStorageTestState.Idle) }

    fun runTest() {
        if (state is SshKeyStorageTestState.Running) return
        val activity = context as? Activity
        if (activity == null) {
            state = SshKeyStorageTestState.Failed(
                context.getString(R.string.ssh_key_provider_storage_auth_unavailable),
            )
            return
        }
        state = SshKeyStorageTestState.Running(emptyList())
        scope.launch {
            val results = mutableListOf<SshKeyStorageTest.CaseOutcome>()
            val runner = SshKeyStorageTest(activity, graph.sshKeyProviderStore, graph.identity.clientId)
            val failure = try {
                runner.run { outcome ->
                    results += outcome
                    state = SshKeyStorageTestState.Running(results.toList())
                }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure.message ?: failure.javaClass.simpleName
            }
            state = if (failure == null) {
                SshKeyStorageTestState.Done(results.toList())
            } else {
                SshKeyStorageTestState.Failed(failure)
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diagnostics_ssh_key_storage_test), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.diagnostics_ssh_key_storage_test_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = ::runTest,
                enabled = state !is SshKeyStorageTestState.Running,
            ) {
                Text(
                    stringResource(
                        if (state is SshKeyStorageTestState.Running) {
                            R.string.diagnostics_ssh_key_storage_test_running
                        } else {
                            R.string.diagnostics_ssh_key_storage_test_run
                        },
                    ),
                )
            }
            when (val current = state) {
                is SshKeyStorageTestState.Failed -> Text(
                    stringResource(R.string.diagnostics_ssh_key_storage_test_failed, current.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                is SshKeyStorageTestState.Running,
                is SshKeyStorageTestState.Done,
                -> {
                    val results = when (current) {
                        is SshKeyStorageTestState.Running -> current.results
                        is SshKeyStorageTestState.Done -> current.results
                    }
                    if (current is SshKeyStorageTestState.Running && results.isEmpty()) {
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
                                is SshKeyStorageTest.CaseOutcome.Passed -> Text(
                                    stringResource(R.string.diagnostics_ssh_key_storage_test_case_passed, outcome.detail),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = keyStorageTestTone(ok = true),
                                )

                                is SshKeyStorageTest.CaseOutcome.Failed -> Text(
                                    stringResource(R.string.diagnostics_ssh_key_storage_test_case_failed, outcome.message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = keyStorageTestTone(ok = false),
                                )
                            }
                        }
                    }
                    if (current is SshKeyStorageTestState.Done) {
                        val passed = results.count { it is SshKeyStorageTest.CaseOutcome.Passed }
                        Text(
                            stringResource(R.string.diagnostics_ssh_key_storage_test_done, passed, results.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SshKeyStorageTestState.Idle -> Unit
            }
        }
    }
}

private sealed interface SshKeyStorageTestState {
    data object Idle : SshKeyStorageTestState
    data class Running(val results: List<SshKeyStorageTest.CaseOutcome>) : SshKeyStorageTestState
    data class Done(val results: List<SshKeyStorageTest.CaseOutcome>) : SshKeyStorageTestState
    data class Failed(val message: String) : SshKeyStorageTestState
}

@Composable
private fun keyStorageTestTone(ok: Boolean): Color {
    val dark = isSystemInDarkTheme()
    return if (ok) {
        if (dark) SecurityGreenDark else SecurityGreenLight
    } else {
        if (dark) SecurityRedDark else SecurityRedLight
    }
}
