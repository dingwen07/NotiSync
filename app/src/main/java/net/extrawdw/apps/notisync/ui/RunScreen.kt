package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.run.RunEngine
import net.extrawdw.apps.notisync.run.RunKey
import net.extrawdw.apps.notisync.run.StoredRun
import net.extrawdw.apps.notisync.run.asRunTerminalLine
import net.extrawdw.notisync.protocol.RunBlockedReason
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunPromptKind
import net.extrawdw.notisync.protocol.RunState

@Composable
fun RunScreen(
    initialSelection: RunKey? = null,
    onInitialSelectionConsumed: () -> Unit = {},
) {
    val graph = rememberGraph()
    val engine = graph.runEngine ?: return
    val runs by engine.runs.collectAsStateWithLifecycle()
    val pendingRefreshes by engine.pendingRefreshes.collectAsStateWithLifecycle()
    var selectedEncoded by rememberSaveable { mutableStateOf<String?>(null) }
    var showClearHistory by rememberSaveable { mutableStateOf(false) }
    val selectedKey = selectedEncoded?.let(RunKey::decode)
    val selected = selectedKey?.let { key -> runs.firstOrNull { it.key == key } }

    LaunchedEffect(initialSelection) {
        if (initialSelection != null) {
            selectedEncoded = initialSelection.encoded()
            onInitialSelectionConsumed()
        }
    }
    LaunchedEffect(selectedEncoded, selected) {
        if (selectedEncoded != null && selected == null) selectedEncoded = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.run_screen_title)) }) },
    ) { padding ->
        RunList(
            runs = runs,
            selectedKey = selectedKey,
            deviceNameOf = { id -> graph.trust.displayName(id) },
            onSelect = { run -> selectedEncoded = run.key.encoded() },
            onClearHistory = { showClearHistory = true },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }

    selected?.let { run ->
        RunDetailSheet(
            run = run,
            engine = engine,
            deviceName = graph.trust.displayName(run.state.hostClientId),
            refreshing = run.key in pendingRefreshes,
            onDismiss = { selectedEncoded = null },
        )
    }

    if (showClearHistory) {
        AlertDialog(
            onDismissRequest = { showClearHistory = false },
            title = { Text(stringResource(R.string.run_clear_history_title)) },
            text = { Text(stringResource(R.string.run_clear_history_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearHistory = false
                        engine.clearHistory()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.run_clear_history_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistory = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun RunList(
    runs: List<StoredRun>,
    selectedKey: RunKey?,
    deviceNameOf: (net.extrawdw.notisync.protocol.ClientId) -> String?,
    onSelect: (StoredRun) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.Terminal,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.run_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.run_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val active = runs.filter { it.active }
    val history = runs.filterNot { it.active }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        if (active.isNotEmpty()) {
            item { RunSectionHeader(stringResource(R.string.run_section_active)) }
            items(active, key = { it.key.encoded() }) { run ->
                RunListItem(run, selectedKey == run.key, deviceNameOf(run.state.hostClientId), onSelect)
            }
        }
        if (history.isNotEmpty()) {
            item {
                RunSectionHeader(
                    title = stringResource(R.string.run_section_history),
                    onClear = onClearHistory,
                )
            }
            items(history, key = { it.key.encoded() }) { run ->
                RunListItem(run, selectedKey == run.key, deviceNameOf(run.state.hostClientId), onSelect)
            }
        }
    }
}

@Composable
private fun RunSectionHeader(title: String, onClear: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (onClear != null) {
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Outlined.DeleteSweep,
                    contentDescription = stringResource(R.string.run_clear_history_action),
                )
            }
        }
    }
}

@Composable
private fun RunListItem(
    run: StoredRun,
    selected: Boolean,
    deviceName: String?,
    onSelect: (StoredRun) -> Unit,
) {
    val state = run.state
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Surface(color = background) {
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable { onSelect(run) },
            leadingContent = { RunPhaseIcon(state.phase) },
            headlineContent = {
                Text(commandLabel(state), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column {
                    Text(
                        runStatus(run),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            deviceName ?: state.hostClientId.shortForm(),
                            rememberTimeFormatter().format(Date(state.updatedAt)),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
    HorizontalDivider()
}

@Composable
private fun RunDetailSheet(
    run: StoredRun,
    engine: RunEngine,
    deviceName: String?,
    refreshing: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        RunDetail(
            run = run,
            engine = engine,
            deviceName = deviceName,
            refreshing = refreshing,
            onBack = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RunDetail(
    run: StoredRun,
    engine: RunEngine,
    deviceName: String?,
    refreshing: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = run.state
    val scope = rememberCoroutineScope()
    var input by remember(state.interactionGeneration) { mutableStateOf("") }
    var showSignal by remember { mutableStateOf(false) }
    var showKill by remember { mutableStateOf(false) }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.run_back))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        commandLabel(state),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        runStatus(run),
                        color = phaseColor(state.phase),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (run.active) {
                    IconButton(
                        onClick = { scope.launch { engine.refresh(run.key) } },
                        enabled = !refreshing,
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.run_action_refresh))
                        }
                    }
                }
            }
        }

        state.progress?.let { progress ->
            item {
                if (progress.indeterminate) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    val fraction = (progress.current!!.toDouble() / progress.total!!.toDouble()).toFloat()
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.run_progress_description, (fraction * 100).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        state.llmSummary?.let { summary ->
            item {
                RunCard(title = stringResource(R.string.run_summary)) {
                    Text(summary.title, style = MaterialTheme.typography.titleMedium)
                    Text(summary.text)
                    summary.expandedText?.takeIf { it.isNotBlank() }?.let { Text(it) }
                }
            }
        }

        state.failureMessage?.let { failure ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        failure,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (run.active) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.phase == RunPhase.BLOCKED && state.prompt == RunPromptKind.YES_NO) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(onClick = {
                                scope.launch { engine.writeInput(run.key, "y\n", state.interactionGeneration) }
                            }) { Text(stringResource(R.string.run_yes)) }
                            FilledTonalButton(onClick = {
                                scope.launch { engine.writeInput(run.key, "n\n", state.interactionGeneration) }
                            }) { Text(stringResource(R.string.run_no)) }
                        }
                    }
                    if (state.phase == RunPhase.BLOCKED && state.prompt == RunPromptKind.TEXT) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.run_input_hint)) },
                                maxLines = 4,
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val submitted = input
                                    input = ""
                                    scope.launch {
                                        engine.writeInput(
                                            run.key,
                                            submitted.asRunTerminalLine(),
                                            state.interactionGeneration,
                                        )
                                    }
                                },
                            ) { Text(stringResource(R.string.run_input_send)) }
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { scope.launch { engine.signal(run.key, "INT") } }) {
                            Text(stringResource(R.string.run_action_interrupt))
                        }
                        OutlinedButton(onClick = { scope.launch { engine.signal(run.key, "TERM") } }) {
                            Text(stringResource(R.string.run_action_terminate))
                        }
                        Button(
                            onClick = { showKill = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text(stringResource(R.string.run_action_kill)) }
                        TextButton(onClick = { showSignal = true }) {
                            Text(stringResource(R.string.run_action_signal))
                        }
                        TextButton(onClick = { engine.markInactive(run.key) }) {
                            Text(stringResource(R.string.run_action_mark_inactive))
                        }
                    }
                }
            }
        }

        item { RunTerminal(state) }

        item {
            RunCard(title = stringResource(R.string.run_details)) {
                DetailField(stringResource(R.string.run_detail_command), displayArgv(state.argv), monospace = true)
                DetailField(stringResource(R.string.run_detail_directory), state.cwd, monospace = true)
                DetailField(
                    stringResource(R.string.run_detail_host),
                    deviceName ?: state.hostClientId.value,
                )
                val formatter = rememberDateTimeFormatter()
                DetailField(stringResource(R.string.run_detail_started), formatter.format(Date(state.startedAt)))
                DetailField(stringResource(R.string.run_detail_updated), formatter.format(Date(state.updatedAt)))
                state.endedAt?.let {
                    DetailField(stringResource(R.string.run_detail_finished), formatter.format(Date(it)))
                }
                state.exitCode?.let {
                    DetailField(stringResource(R.string.run_detail_exit), it.toString())
                }
                DetailField(
                    stringResource(R.string.run_detail_pty),
                    stringResource(if (state.usesPty) R.string.run_pty_yes else R.string.run_pty_no),
                )
            }
        }
    }

    if (showSignal) {
        SignalDialog(
            onDismiss = { showSignal = false },
            onSend = { signal ->
                showSignal = false
                scope.launch { engine.signal(run.key, signal) }
            },
        )
    }
    if (showKill) {
        AlertDialog(
            onDismissRequest = { showKill = false },
            title = { Text(stringResource(R.string.run_kill_title)) },
            text = { Text(stringResource(R.string.run_kill_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showKill = false
                        scope.launch { engine.signal(run.key, "KILL") }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.run_kill_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showKill = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SignalDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var signal by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.run_signal_title)) },
        text = {
            OutlinedTextField(
                value = signal,
                onValueChange = { signal = it },
                label = { Text(stringResource(R.string.run_signal_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onSend(signal) }, enabled = signal.isNotBlank()) {
                Text(stringResource(R.string.run_signal_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RunTerminal(state: RunState) {
    val scroll = rememberScrollState()
    val followOutput = scroll.maxValue == 0 || scroll.value >= scroll.maxValue - 8
    LaunchedEffect(state.terminal.text) {
        if (followOutput) {
            withFrameNanos { }
            scroll.scrollTo(scroll.maxValue)
        }
    }
    RunCard(title = stringResource(R.string.run_terminal)) {
        if (state.terminal.truncated) {
            Text(
                stringResource(R.string.run_terminal_truncated),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 480.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            SelectionContainer {
                Text(
                    state.terminal.text.ifBlank { stringResource(R.string.run_terminal_empty) },
                    modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RunCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DetailField(label: String, value: String, monospace: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, fontFamily = if (monospace) FontFamily.Monospace else null)
        }
    }
}

@Composable
private fun RunPhaseIcon(phase: RunPhase) {
    val (icon, tint) = when (phase) {
        RunPhase.RUNNING -> Icons.Outlined.Terminal to MaterialTheme.colorScheme.primary
        RunPhase.BLOCKED -> Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.tertiary
        RunPhase.COMPLETED -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
        RunPhase.FAILED_TO_START -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(icon, contentDescription = null, tint = tint)
}

@Composable
private fun phaseColor(phase: RunPhase): Color = when (phase) {
    RunPhase.RUNNING, RunPhase.COMPLETED -> MaterialTheme.colorScheme.primary
    RunPhase.BLOCKED -> MaterialTheme.colorScheme.tertiary
    RunPhase.FAILED_TO_START -> MaterialTheme.colorScheme.error
}

@Composable
private fun runStatus(run: StoredRun): String {
    val state = run.state
    if (!run.active && (state.phase == RunPhase.RUNNING || state.phase == RunPhase.BLOCKED)) {
        return stringResource(R.string.run_status_inactive)
    }
    return when (state.phase) {
        RunPhase.RUNNING -> state.progress?.let { progress ->
            if (!progress.indeterminate) {
                val percent = (progress.current!!.toDouble() / progress.total!!.toDouble() * 100).toInt()
                stringResource(R.string.run_status_progress, percent)
            } else null
        } ?: stringResource(R.string.run_status_running)
        RunPhase.BLOCKED -> when (state.blockedReason) {
            RunBlockedReason.TERMINAL_INPUT -> stringResource(R.string.run_status_waiting_input)
            RunBlockedReason.OUTPUT_AND_CPU_IDLE -> stringResource(R.string.run_status_attention)
            null -> stringResource(R.string.run_status_blocked)
        }
        RunPhase.COMPLETED -> if (state.exitCode == 0) {
            stringResource(R.string.run_status_completed)
        } else stringResource(R.string.run_status_exit_code, state.exitCode ?: 0)
        RunPhase.FAILED_TO_START -> stringResource(R.string.run_status_failed_start)
    }
}

private fun commandLabel(state: RunState): String =
    state.argv.firstOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Command"

private fun displayArgv(argv: List<String>): String = argv.joinToString(" ") { arg ->
    if (arg.all { it.isLetterOrDigit() || it in "-._/:=@+,%" }) arg
    else "\"${arg.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

@Composable
private fun rememberTimeFormatter(): DateFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

@Composable
private fun rememberDateTimeFormatter(): DateFormat =
    remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
