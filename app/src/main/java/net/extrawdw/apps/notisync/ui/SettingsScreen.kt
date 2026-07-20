package net.extrawdw.apps.notisync.ui

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.extrawdw.apps.notisync.R
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val brokerUrl by graph.settings.brokerUrl.collectAsStateWithLifecycle()
    val deviceName by graph.settings.deviceName.collectAsStateWithLifecycle()
    val batchLow by graph.settings.batchLowPriority.collectAsStateWithLifecycle()
    val advanced by graph.settings.advancedDiagnostics.collectAsStateWithLifecycle()
    val analytics by graph.settings.analyticsEnabled.collectAsStateWithLifecycle()
    val callRinger by graph.settings.callRingerEnabled.collectAsStateWithLifecycle()
    val lockScreenPublicIdentity by graph.settings.lockScreenPublicIdentity.collectAsStateWithLifecycle()

    // The full-screen-intent special access gates the mirrored incoming-call screen; the user (or Play) can
    // revoke it silently. Re-read on every resume so returning from the system settings screen refreshes it.
    val context = LocalContext.current
    var fullScreenAllowed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        fullScreenAllowed = runCatching {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        }.getOrDefault(true)
        onPauseOrDispose { }
    }

    // Diagnostics probe, hoisted to screen scope so it loads once when the Settings tab is opened (and
    // on manual refresh) — not on every list-scroll recomposition of the card item, and never on a timer.
    var probe by remember { mutableStateOf<ServerProbe>(ServerProbe.Idle) }
    var probeKey by remember { mutableIntStateOf(0) }
    var benchmark by remember { mutableStateOf<BenchmarkState>(BenchmarkState.Idle) }
    var oversizedTest by remember { mutableStateOf<OversizedTestState>(OversizedTestState.Idle) }
    var rotateNow by remember { mutableStateOf<RotateNowState>(RotateNowState.Idle) }
    LaunchedEffect(advanced, probeKey) {
        if (!advanced) {
            probe = ServerProbe.Idle
            return@LaunchedEffect
        }
        probe = ServerProbe.Loading
        probe = probeServer(graph)
    }

    NotiScaffold(stringResource(R.string.tab_settings)) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsTextField(
                    value = deviceName,
                    onCommit = { graph.scope.launch { graph.settings.setDeviceName(it) } },
                    label = { Text(stringResource(R.string.settings_device_name)) },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SettingsTextField(
                    value = brokerUrl,
                    onCommit = { graph.scope.launch { graph.settings.setBrokerUrl(it) } },
                    label = { Text(stringResource(R.string.settings_broker_url)) },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                ToggleRow(
                    stringResource(R.string.settings_call_ringer),
                    callRinger
                ) { scope.launch { graph.settings.setCallRingerEnabled(it) } }
            }
            if (callRinger && !fullScreenAllowed) {
                item { FullScreenIntentBlockedRow() }
            }
            item {
                ToggleRow(
                    stringResource(R.string.settings_lock_screen_public_identity),
                    lockScreenPublicIdentity
                ) { scope.launch { graph.settings.setLockScreenPublicIdentity(it) } }
            }
            item {
                ToggleRow(
                    stringResource(R.string.settings_batch_low_priority),
                    batchLow
                ) { scope.launch { graph.settings.setBatchLowPriority(it) } }
            }
            item {
                ToggleRow(
                    stringResource(R.string.settings_analytics),
                    analytics
                ) { scope.launch { graph.settings.setAnalyticsEnabled(it) } }
            }
            item {
                ToggleRow(
                    stringResource(R.string.settings_advanced_diagnostics),
                    advanced
                ) { scope.launch { graph.settings.setAdvancedDiagnostics(it) } }
            }
            if (advanced) {
                item {
                    DiagnosticsCard(
                        graph = graph,
                        probe = probe,
                        benchmark = benchmark,
                        oversizedTest = oversizedTest,
                        rotateNow = rotateNow,
                        onRefresh = {
                            graph.transport.resetVerificationBackoff()
                            probeKey++
                        },
                        onClearToken = {
                            graph.transport.clearCachedAuth()
                            probeKey++
                        },
                        onBenchmark = {
                            if (benchmark !is BenchmarkState.Running) {
                                scope.launch { runPowBenchmark { benchmark = it } }
                            }
                        },
                        onSendOversizedTest = {
                            if (oversizedTest !is OversizedTestState.Sending) {
                                oversizedTest = OversizedTestState.Sending
                                scope.launch {
                                    oversizedTest =
                                        runCatching { OversizedTestState.Sent(graph.sendOversizedDiagnostic()) }
                                            .getOrElse { OversizedTestState.Failed }
                                }
                            }
                        },
                        onRotateNow = {
                            if (rotateNow !is RotateNowState.Running) {
                                rotateNow = RotateNowState.Running
                                scope.launch {
                                    rotateNow = runCatching {
                                        graph.rotateNowDiagnostic()?.let { RotateNowState.Done(it) }
                                            ?: RotateNowState.AlreadyPending
                                    }.getOrElse { RotateNowState.Failed }
                                }
                            }
                        },
                        onResetChannels = { graph.resetNotificationChannels() },
                        onTamperSignature = {
                            graph.launchDurableTrustAction(context) {
                                graph.trust.simulateSignatureTamper()
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * A settings text field that commits its value only when editing is *done* — when the field loses
 * focus, or the user presses the IME action (Done commits + dismisses; Next advances focus, which
 * also triggers the focus-loss commit). Keystrokes only update local state, so a single edit never
 * fans out a string of partial values (important for [setDeviceName], which propagates to peers).
 */
@Composable
private fun SettingsTextField(
    value: String,
    onCommit: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val focusManager = LocalFocusManager.current
    var textValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    var isFocused by remember { mutableStateOf(false) }
    // The value we just committed, awaiting its round-trip through the StateFlow, so the sync below
    // doesn't briefly clobber the field back to the pre-commit value (and so we never double-commit).
    var pendingSavedText by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(value, isFocused) {
        if (value == pendingSavedText) {
            pendingSavedText = null
        }
        if (!isFocused && pendingSavedText == null && value != textValue.text) {
            textValue = TextFieldValue(text = value, selection = TextRange(value.length))
        }
    }

    fun commit() {
        val edited = textValue.text.trim()
        // Ignore no-ops and blank input (the sync above reverts a blank field to the stored value).
        if (edited.isNotEmpty() && edited != value && edited != pendingSavedText) {
            pendingSavedText = edited
            onCommit(edited)
        }
    }

    val commitOnDispose by rememberUpdatedState(::commit)
    DisposableEffect(Unit) {
        onDispose { commitOnDispose() }
    }

    OutlinedTextField(
        value = textValue,
        onValueChange = {
            textValue = it
        }, // local edit only — commit happens on focus loss / IME action
        label = label,
        supportingText = supportingText,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
        modifier = modifier.onFocusChanged { state ->
            if (isFocused && !state.isFocused) commit() // committing on blur covers tap-away and Next-advance
            isFocused = state.isFocused
        },
    )
}

/** Shown under the call-ringer switch while USE_FULL_SCREEN_INTENT is revoked: without it the platform strips
 *  the incoming-call screen at post time. Tapping opens the system's per-app full-screen-notification page. */
@Composable
private fun FullScreenIntentBlockedRow() {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            "package:${context.packageName}".toUri(),
                        )
                    )
                }
            },
    ) {
        Text(
            stringResource(R.string.settings_call_ringer_fsi_blocked),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(R.string.settings_call_ringer_fsi_blocked_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
