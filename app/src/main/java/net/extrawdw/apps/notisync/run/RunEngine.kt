package net.extrawdw.apps.notisync.run

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.ActivityText
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.RetryableDeliveryException
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.peer.transport.ifKnown
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import net.extrawdw.notisync.protocol.RunControlResult
import net.extrawdw.notisync.protocol.RunCommandRequest
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunSync
import net.extrawdw.notisync.protocol.RunSyncKind
import net.extrawdw.notisync.protocol.Urgency

fun interface RunStatePresenter {
    /** True only when posted; false means notification permission is currently unavailable. */
    fun render(state: RunState): Boolean

    /** Remove the stable notification when a Run leaves the local Active section. */
    fun dismiss(key: RunKey) = Unit
}

/** Text-prompt affordances submit one terminal line; the lower-level control API remains byte-exact. */
internal fun String.asRunTerminalLine(): String = trimEnd('\r', '\n') + "\n"

/** Android owner of DATA_SYNC/RUN receive state and client-originated controls. */
class RunEngine internal constructor(
    private val repository: RunRepository,
    private val presenter: RunStatePresenter,
    private val scope: CoroutineScope,
    private val sendControl: suspend (RunControl) -> Boolean,
    private val activityLog: ActivityLog? = null,
    private val activityText: ActivityText? = null,
    private val deviceNameOf: (ClientId) -> String? = { null },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        channel: SecureChannel,
        store: RunStore,
        presenter: RunStatePresenter,
        scope: CoroutineScope,
        activityLog: ActivityLog,
        activityText: ActivityText,
        deviceNameOf: (ClientId) -> String?,
        now: () -> Long = { System.currentTimeMillis() },
    ) : this(
        repository = store,
        presenter = presenter,
        scope = scope,
        sendControl = { control -> sendOverChannel(channel, control) },
        activityLog = activityLog,
        activityText = activityText,
        deviceNameOf = deviceNameOf,
        now = now,
    )

    val runs: StateFlow<List<StoredRun>> = repository.runs

    private val refreshByKey = ConcurrentHashMap<RunKey, String>()
    private val refreshTimeouts = ConcurrentHashMap<String, Job>()
    private val _pendingRefreshes = MutableStateFlow<Set<RunKey>>(emptySet())
    val pendingRefreshes: StateFlow<Set<RunKey>> = _pendingRefreshes.asStateFlow()
    private val presentationLock = Any()
    private val maintenanceJob = scope.launch {
        while (isActive) {
            delay(RUN_MAINTENANCE_INTERVAL_MS)
            runMaintenanceNow()
        }
    }

    /** Called inline by FoundationEngine so durable persistence completes before SecureChannel acknowledges. */
    fun onRunSync(message: InboundMessage, sync: DataSync) {
        if (!message.senderOwnDevice || sync.kind != DataSyncKind.RUN) return
        val run = sync.run ?: return
        when (run.kind) {
            RunSyncKind.STATE -> receiveState(message, run.state ?: return)
            RunSyncKind.CONTROL_RESULT -> {
                val result = run.controlResult ?: return
                logReceived(message, result.activitySummary())
                receiveControlResult(message, result)
            }
            // Android is a Run display/controller. It never hosts controls or executes command requests.
            // Still record safely summarized traffic: Activity is also the protocol diagnostics feed.
            RunSyncKind.CONTROL -> logReceived(message, (run.control ?: return).activitySummary())
            RunSyncKind.COMMAND_REQUEST ->
                logReceived(message, (run.commandRequest ?: return).activitySummary())
        }
    }

    private fun receiveState(message: InboundMessage, state: RunState) {
        if (state.hostClientId != message.senderId || !state.validForDisplay()) return
        synchronized(presentationLock) {
            val key = RunKey(message.senderId.value, state.runId)
            val activeBefore = activeKeys()
            val result = try {
                repository.apply(state)
            } catch (error: Exception) {
                throw RetryableDeliveryException("could not persist Run state", error)
            }
            dismissRunsNoLongerActive(activeBefore)
            if (result == RunApplyResult.OLDER) return
            // Match the other application handlers: log a valid, newly-applied inbound update, but not a stale
            // revision or an equal-revision transport replay. Unlike notification presentation, every semantic
            // Run update (including PERIODIC, LLM_SUMMARY, and REFRESH) belongs in the diagnostics feed.
            if (result == RunApplyResult.INSERTED || result == RunApplyResult.UPDATED) {
                logReceived(message, state.activitySummary())
            }

            // Equal is intentionally re-presented: it is the delivery retry produced by a crash or renderer
            // failure after SQLite committed. An equal row whose checkpoint is already current is instead a
            // durable-outbox resend with a new envelope; rendering it again could duplicate sound/vibration.
            val durable = repository.find(key)
                ?: throw RetryableDeliveryException("persisted Run state is unavailable")
            if (result == RunApplyResult.EQUAL && !durable.presentationPending) return
            // Always render the durable current row, never alternate data carrying the same revision number.
            try {
                val posted = presenter.render(durable.state)
                if (posted || !durable.active) {
                    // Do not accumulate terminal Runs while notifications are disabled: granting permission later
                    // must restore active work, not audibly replay an arbitrary historical backlog. A crash before
                    // this presentation attempt still leaves terminal state pending and recoverable on startup.
                    repository.markPresented(key, durable.state.revision)
                }
            } catch (error: Exception) {
                throw RetryableDeliveryException("could not render Run notification", error)
            }

            // A correlated refresh completes only after persistence and the presentation attempt. An unavailable
            // notification permission leaves an active presentation pending but the in-app durable state is usable.
            durable.state.responseToRequestId?.let { completeRefresh(it, key) }
        }
    }

    /** Re-post only snapshots that committed without a successful presentation checkpoint. */
    fun reconcilePendingPresentations() {
        synchronized(presentationLock) {
            // A store can age an active-phase snapshot into History during cold start, before this presenter exists.
            // Dismiss any stable ongoing notification left behind by a previous process in that case.
            repository.runs.value
                .filter { !it.active && it.state.remotePhaseIsActive() }
                .forEach { stored -> runCatching { presenter.dismiss(stored.key) } }
            repository.runs.value.filter { it.presentationPending }.forEach { stored ->
                runCatching {
                    val posted = presenter.render(stored.state)
                    if (posted || !stored.active) {
                        repository.markPresented(stored.key, stored.state.revision)
                    }
                }
            }
        }
    }

    /** Testable one-shot used by the long-lived maintenance loop. */
    internal fun runMaintenanceNow() {
        synchronized(presentationLock) {
            val activeBefore = activeKeys()
            if (runCatching { repository.prune() }.isSuccess) {
                dismissRunsNoLongerActive(activeBefore)
            }
        }
    }

    /** A future higher-revision snapshot can reactivate this Run through [RunRepository.apply]. */
    fun markInactive(key: RunKey): Boolean = synchronized(presentationLock) {
        val changed = runCatching { repository.markInactive(key) }.getOrDefault(false)
        if (changed) {
            refreshByKey[key]?.let { requestId -> completeRefresh(requestId, key) }
            runCatching { presenter.dismiss(key) }
        }
        changed
    }

    fun clearHistory(): Boolean = synchronized(presentationLock) {
        val historicalKeys = repository.runs.value.filterNot { it.active }.map { it.key }
        if (runCatching { repository.clearHistory() }.isFailure) return@synchronized false
        historicalKeys.forEach { key -> runCatching { presenter.dismiss(key) } }
        true
    }

    private fun receiveControlResult(
        message: InboundMessage,
        result: net.extrawdw.notisync.protocol.RunControlResult,
    ) {
        val key = refreshByKey.entries.firstOrNull { it.value == result.requestId }?.key ?: return
        if (key.hostClientId != message.senderId.value || key.runId != result.runId) return
        completeRefresh(result.requestId, key)
    }

    suspend fun refresh(key: RunKey): Boolean {
        val stored = repository.find(key) ?: return false
        // Retention can move a quiet RUNNING/BLOCKED snapshot to local History even though the host is still
        // executing it. Current hosts emit a low-frequency liveness snapshot, but REFRESH also lets the user
        // elicit the higher authenticated revision immediately (and recovers Runs produced by older hosts).
        // Keep terminal snapshots ineligible and one request in flight for both active and locally-stale Runs.
        if (!stored.state.remotePhaseIsActive() || refreshByKey.containsKey(key)) return false
        val requestId = UUID.randomUUID().toString()
        refreshByKey[key] = requestId
        publishPendingRefreshes()
        val sent = runCatching {
            send(
                RunControl(
                    requestId = requestId,
                    hostClientId = ClientId(key.hostClientId),
                    runId = key.runId,
                    kind = RunControlKind.REFRESH,
                    requestedAt = now(),
                )
            )
        }.getOrElse {
            completeRefresh(requestId, key)
            return false
        }
        if (!sent) {
            completeRefresh(requestId, key)
            return false
        }
        refreshTimeouts[requestId] = scope.launch {
            delay(REFRESH_TIMEOUT_MS)
            completeRefresh(requestId, key)
        }
        return true
    }

    suspend fun writeInput(key: RunKey, input: String, interactionGeneration: Long): Boolean {
        if (interactionGeneration < 0 || input.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) return false
        return runCatching {
            send(
                RunControl(
                    requestId = UUID.randomUUID().toString(),
                    hostClientId = ClientId(key.hostClientId),
                    runId = key.runId,
                    kind = RunControlKind.WRITE_INPUT,
                    requestedAt = now(),
                    interactionGeneration = interactionGeneration,
                    inputText = input,
                )
            )
        }.getOrDefault(false)
    }

    suspend fun signal(key: RunKey, signal: String): Boolean {
        val value = signal.trim()
        if (value.isEmpty() || value.length > MAX_SIGNAL_LENGTH) return false
        return runCatching {
            send(
                RunControl(
                    requestId = UUID.randomUUID().toString(),
                    hostClientId = ClientId(key.hostClientId),
                    runId = key.runId,
                    kind = RunControlKind.SIGNAL,
                    requestedAt = now(),
                    signal = value,
                )
            )
        }.getOrDefault(false)
    }

    /** WorkManager/outbox path: preserve the caller-minted request id across transport retries. */
    internal suspend fun sendPersistedControl(control: RunControl): Boolean =
        runCatching { send(control) }.getOrDefault(false)

    private suspend fun send(control: RunControl): Boolean {
        val sent = sendControl(control)
        // Like notification/action sends, only record an outbound row after the channel accepted a recipient.
        if (sent) logSent(control)
        return sent
    }

    private fun logReceived(message: InboundMessage, summary: String) {
        val log = activityLog ?: return
        val text = activityText ?: return
        log.add(
            ActivityEvent.Kind.RECEIVED,
            text.runTitle(),
            text.runReceived(summary, deviceName(message.senderId)),
            now(),
            deliveryMode = message.deliveryMode.ifKnown(),
        )
    }

    private fun logSent(control: RunControl) {
        val log = activityLog ?: return
        val text = activityText ?: return
        log.add(
            ActivityEvent.Kind.SENT,
            text.runTitle(),
            text.runSent(control.activitySummary(), deviceName(control.hostClientId)),
            now(),
        )
    }

    private fun deviceName(clientId: ClientId): String =
        deviceNameOf(clientId) ?: clientId.shortForm()

    private fun completeRefresh(requestId: String, key: RunKey) {
        if (!refreshByKey.remove(key, requestId)) return
        refreshTimeouts.remove(requestId)?.cancel()
        publishPendingRefreshes()
    }

    private fun publishPendingRefreshes() {
        _pendingRefreshes.value = refreshByKey.keys.toSet()
    }

    private fun activeKeys(): Set<RunKey> =
        repository.runs.value.filter { it.active }.map { it.key }.toSet()

    private fun dismissRunsNoLongerActive(activeBefore: Set<RunKey>) {
        (activeBefore - activeKeys()).forEach { key -> runCatching { presenter.dismiss(key) } }
    }

    private fun RunState.validForDisplay(): Boolean =
        runId.isNotBlank() &&
            revision >= 0 &&
            argv.isNotEmpty() &&
            terminal.text.toByteArray(Charsets.UTF_8).size <= MAX_TERMINAL_BYTES

    private fun RunState.remotePhaseIsActive(): Boolean =
        phase == RunPhase.RUNNING || phase == RunPhase.BLOCKED

    companion object {
        private const val MAX_TERMINAL_BYTES = 64 * 1024
        private const val MAX_INPUT_BYTES = 64 * 1024
        private const val MAX_SIGNAL_LENGTH = 64
        private const val REFRESH_TIMEOUT_MS = 15_000L
        private const val RUN_MAINTENANCE_INTERVAL_MS = 15L * 60 * 1000

        private suspend fun sendOverChannel(channel: SecureChannel, control: RunControl): Boolean =
            channel.send(
                MessageType.DATA_SYNC,
                ProtocolCodec.encodeToCbor(
                    DataSync(
                        kind = DataSyncKind.RUN,
                        run = RunSync(kind = RunSyncKind.CONTROL, control = control),
                    )
                ),
                Recipients.Only(control.hostClientId),
                Urgency.NORMAL,
            ) > 0
    }
}

private fun RunState.activitySummary(): String =
    "STATE/${updateReason.name} · ${phase.name} · r$revision · run ${runId.diagnosticValue()}"

private fun RunControl.activitySummary(): String =
    "CONTROL/${kind.name} · req ${requestId.diagnosticRequestId()} · run ${runId.diagnosticValue()}"

private fun RunControlResult.activitySummary(): String =
    "CONTROL_RESULT/${status.name} · req ${requestId.diagnosticRequestId()} · run ${runId.diagnosticValue()}"

private fun RunCommandRequest.activitySummary(): String =
    "COMMAND_REQUEST · req ${requestId.diagnosticRequestId()}"

private fun String.diagnosticRequestId(): String = take(8)

/** Bound and neutralize protocol identifiers so a diagnostic row cannot inject controls or become very tall. */
private fun String.diagnosticValue(): String {
    val bounded = take(DIAGNOSTIC_VALUE_MAX_CHARS)
    val safe = buildString(bounded.length) {
        bounded.forEach { char ->
            append(
                if (
                    char.isISOControl() || char.isSurrogate() ||
                    Character.getType(char) == Character.FORMAT.toInt()
                ) '�' else char
            )
        }
    }
    return if (length <= DIAGNOSTIC_VALUE_MAX_CHARS) safe else "$safe…"
}

private const val DIAGNOSTIC_VALUE_MAX_CHARS = 64
