package net.extrawdw.apps.notisync.run

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.data.run.RunApplyResult
import net.extrawdw.apps.notisync.data.run.RunKey
import net.extrawdw.apps.notisync.data.run.RunRepository
import net.extrawdw.apps.notisync.data.run.StoredRun
import net.extrawdw.notisync.peer.channel.InboundMessage
import net.extrawdw.notisync.peer.channel.RetryableDeliveryException
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SecureChannel
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import net.extrawdw.notisync.protocol.RunControlResult
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
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** Production convenience constructor: Room owns Run state; SecureChannel remains the existing wire path. */
    constructor(
        channel: SecureChannel,
        repository: RunRepository,
        presenter: RunStatePresenter,
        scope: CoroutineScope,
        now: () -> Long = { System.currentTimeMillis() },
    ) : this(
        repository = repository,
        presenter = presenter,
        scope = scope,
        sendControl = { control -> sendOverChannel(channel, control) },
        now = now,
    )

    /** Room-backed snapshots are the sole presentation source; the engine does not own another cache. */
    val runs: StateFlow<List<StoredRun>> = repository.observeAll().stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    private val refreshByKey = ConcurrentHashMap<RunKey, String>()
    private val refreshTimeouts = ConcurrentHashMap<String, Job>()
    private val _pendingRefreshes = MutableStateFlow<Set<RunKey>>(emptySet())
    val pendingRefreshes: StateFlow<Set<RunKey>> = _pendingRefreshes.asStateFlow()
    private val presentationLock = Mutex()
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
        runBlocking(Dispatchers.IO) {
            when (run.kind) {
                RunSyncKind.STATE -> receiveState(message, run.state ?: return@runBlocking)
                RunSyncKind.CONTROL_RESULT -> {
                    val result = run.controlResult ?: return@runBlocking
                    receiveControlResult(message, result)
                }
                // Android is a Run display/controller. It never hosts controls or executes command requests.
                // These sub-kinds are not handled on this display/controller surface.
                RunSyncKind.CONTROL,
                RunSyncKind.COMMAND_REQUEST,
                -> Unit
            }
        }
    }

    private suspend fun receiveState(message: InboundMessage, state: RunState) {
        if (state.hostClientId != message.senderId || !state.validForDisplay()) return
        presentationLock.withLock {
            val key = RunKey(message.senderId, state.runId)
            val activeBefore = readSnapshotForDelivery()
                .filter(StoredRun::active)
                .mapTo(mutableSetOf(), StoredRun::key)
            val result = try {
                repository.apply(state)
            } catch (error: Exception) {
                throw RetryableDeliveryException("could not persist Run state", error)
            }
            dismissRunsNoLongerActive(activeBefore)
            when (result) {
                RunApplyResult.OLDER -> return
                RunApplyResult.CONFLICT ->
                    throw RetryableDeliveryException("conflicting Run revision cannot be acknowledged")
                RunApplyResult.CAPACITY_EXCEEDED ->
                    throw RetryableDeliveryException("Run storage capacity is exhausted")
                else -> Unit
            }
            // Equal is intentionally re-presented: it is the delivery retry produced by a crash or renderer
            // failure after Room committed. An equal row whose checkpoint is already current is instead a
            // transport replay with a new envelope; rendering it again could duplicate sound/vibration.
            val durable = runCatching { repository.find(key) }
                .getOrElse { throw RetryableDeliveryException("persisted Run state cannot be read", it) }
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
        runBlocking(Dispatchers.IO) {
            presentationLock.withLock {
                // Room retention can age an active-phase snapshot into History during cold start, before this presenter exists.
                // Dismiss any stable ongoing notification left behind by a previous process in that case.
                val storedRuns = snapshot()
                storedRuns
                    .filter { !it.active && it.state.remotePhaseIsActive() }
                    .forEach { stored -> runCatching { presenter.dismiss(stored.key) } }
                storedRuns.filter { it.presentationPending }.forEach { stored ->
                    runCatching {
                        val posted = presenter.render(stored.state)
                        if (posted || !stored.active) {
                            repository.markPresented(stored.key, stored.state.revision)
                        }
                    }
                }
            }
        }
    }

    /** Testable one-shot used by the long-lived maintenance loop. */
    internal fun runMaintenanceNow() {
        runBlocking(Dispatchers.IO) {
            presentationLock.withLock {
                val activeBefore = snapshot().filter(StoredRun::active).mapTo(mutableSetOf(), StoredRun::key)
                if (runCatching { repository.prune(now()) }.isSuccess) {
                    dismissRunsNoLongerActive(activeBefore)
                }
            }
        }
    }

    /** A future higher-revision snapshot can reactivate this Run through [RunRepository.apply]. */
    fun markInactive(key: RunKey): Boolean = runBlocking(Dispatchers.IO) {
        presentationLock.withLock {
            val changed = runCatching { repository.markInactive(key) }.getOrDefault(false)
            if (changed) {
                refreshByKey[key]?.let { requestId -> completeRefresh(requestId, key) }
                runCatching { presenter.dismiss(key) }
            }
            changed
        }
    }

    fun clearHistory(): Boolean = runBlocking(Dispatchers.IO) {
        presentationLock.withLock {
            val historicalKeys = snapshot().filterNot { it.active }.map { it.key }
            if (runCatching { repository.clearHistory() }.isFailure) return@withLock false
            historicalKeys.forEach { key -> runCatching { presenter.dismiss(key) } }
            true
        }
    }

    private fun receiveControlResult(
        message: InboundMessage,
        result: net.extrawdw.notisync.protocol.RunControlResult,
    ) {
        val key = refreshByKey.entries.firstOrNull { it.value == result.requestId }?.key ?: return
        if (key.hostClientId != message.senderId || key.runId != result.runId) return
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
                    hostClientId = key.hostClientId,
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
                    hostClientId = key.hostClientId,
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
                    hostClientId = key.hostClientId,
                    runId = key.runId,
                    kind = RunControlKind.SIGNAL,
                    requestedAt = now(),
                    signal = value,
                )
            )
        }.getOrDefault(false)
    }

    /** Notification actions send the caller-minted request once; process death may lose the control. */
    internal suspend fun sendBestEffortControl(control: RunControl): Boolean =
        runCatching { send(control) }.getOrDefault(false)

    private suspend fun send(control: RunControl): Boolean {
        return sendControl(control)
    }

    private fun completeRefresh(requestId: String, key: RunKey) {
        if (!refreshByKey.remove(key, requestId)) return
        refreshTimeouts.remove(requestId)?.cancel()
        publishPendingRefreshes()
    }

    private fun publishPendingRefreshes() {
        _pendingRefreshes.value = refreshByKey.keys.toSet()
    }

    private suspend fun snapshot(): List<StoredRun> = repository.observeAll().first()

    private suspend fun readSnapshotForDelivery(): List<StoredRun> =
        runCatching { snapshot() }
            .getOrElse { throw RetryableDeliveryException("persisted Run history cannot be read", it) }

    private suspend fun dismissRunsNoLongerActive(activeBefore: Set<RunKey>) {
        val activeAfter = snapshot().filter(StoredRun::active).mapTo(mutableSetOf(), StoredRun::key)
        (activeBefore - activeAfter).forEach { key -> runCatching { presenter.dismiss(key) } }
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
                        DataSyncKind.RUN,
                        run = RunSync(kind = RunSyncKind.CONTROL, control = control),
                    ),
                ),
                Recipients.Only(control.hostClientId),
                Urgency.NORMAL,
            ) > 0
    }
}
