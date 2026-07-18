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
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.RetryableDeliveryException
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunSync
import net.extrawdw.notisync.protocol.RunSyncKind
import net.extrawdw.notisync.protocol.Urgency

fun interface RunStatePresenter {
    /** True only when posted; false means notification permission is currently unavailable. */
    fun render(state: RunState): Boolean
}

/** Text-prompt affordances submit one terminal line; the lower-level control API remains byte-exact. */
internal fun String.asRunTerminalLine(): String = trimEnd('\r', '\n') + "\n"

/** Android owner of DATA_SYNC/RUN receive state and client-originated controls. */
class RunEngine internal constructor(
    private val repository: RunRepository,
    private val presenter: RunStatePresenter,
    private val scope: CoroutineScope,
    private val sendControl: suspend (RunControl) -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        channel: SecureChannel,
        store: RunStore,
        presenter: RunStatePresenter,
        scope: CoroutineScope,
        now: () -> Long = { System.currentTimeMillis() },
    ) : this(
        repository = store,
        presenter = presenter,
        scope = scope,
        sendControl = { control -> sendOverChannel(channel, control) },
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
            RunSyncKind.CONTROL_RESULT -> receiveControlResult(message, run.controlResult ?: return)
            // Android is a Run display/controller. It never hosts controls or executes command requests.
            RunSyncKind.CONTROL, RunSyncKind.COMMAND_REQUEST -> Unit
        }
    }

    private fun receiveState(message: InboundMessage, state: RunState) {
        if (state.hostClientId != message.senderId || !state.validForDisplay()) return
        synchronized(presentationLock) {
            val key = RunKey(message.senderId.value, state.runId)
            val result = try {
                repository.apply(state)
            } catch (error: Exception) {
                throw RetryableDeliveryException("could not persist Run state", error)
            }
            if (result == RunApplyResult.OLDER) return

            // Equal is intentionally re-presented: it is the delivery retry produced by a crash or renderer
            // failure after SQLite committed. Always render the durable current row, never alternate data carrying
            // the same revision number.
            val durable = repository.find(key)
                ?: throw RetryableDeliveryException("persisted Run state is unavailable")
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
            runCatching { repository.prune() }
        }
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
        if (repository.find(key)?.active != true || refreshByKey.containsKey(key)) return false
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

    private suspend fun send(control: RunControl): Boolean = sendControl(control)

    private fun completeRefresh(requestId: String, key: RunKey) {
        if (!refreshByKey.remove(key, requestId)) return
        refreshTimeouts.remove(requestId)?.cancel()
        publishPendingRefreshes()
    }

    private fun publishPendingRefreshes() {
        _pendingRefreshes.value = refreshByKey.keys.toSet()
    }

    private fun RunState.validForDisplay(): Boolean =
        runId.isNotBlank() &&
            revision >= 0 &&
            argv.isNotEmpty() &&
            terminal.text.toByteArray(Charsets.UTF_8).size <= MAX_TERMINAL_BYTES

    companion object {
        private const val MAX_TERMINAL_BYTES = 64 * 1024
        private const val MAX_INPUT_BYTES = 64 * 1024
        private const val MAX_SIGNAL_LENGTH = 64
        private const val REFRESH_TIMEOUT_MS = 15_000L
        private const val RUN_MAINTENANCE_INTERVAL_MS = 6L * 60 * 60 * 1000

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
