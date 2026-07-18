package net.extrawdw.notisync.daemon

import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.localapi.AcceptedResponse
import net.extrawdw.notisync.localapi.EventCompletionRequest
import net.extrawdw.notisync.localapi.LocalNotificationAction
import net.extrawdw.notisync.localapi.LocalRunPhase
import net.extrawdw.notisync.localapi.LocalRunPromptKind
import net.extrawdw.notisync.localapi.LocalRunUpdateReason
import net.extrawdw.notisync.localapi.NotificationActionKind
import net.extrawdw.notisync.localapi.NotificationActionLifetime
import net.extrawdw.notisync.localapi.NotificationPhase
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.localapi.RunStateRequest
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunBlockedReason
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlResult
import net.extrawdw.notisync.protocol.RunControlResultStatus
import net.extrawdw.notisync.protocol.RunLlmSummary
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunProgress
import net.extrawdw.notisync.protocol.RunPromptKind
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunSync
import net.extrawdw.notisync.protocol.RunSyncKind
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SecureChannel

@Serializable
data class PendingRunState(
    val id: String,
    val sourceKey: String,
    val state: RunStateRequest,
    val acceptedAt: Long,
)

@Serializable
data class PendingRunControlResult(
    val id: String,
    val recipient: ClientId,
    val result: RunControlResult,
    val acceptedAt: Long,
)

interface RunOutbox {
    fun enqueue(item: PendingRunState)
    fun peekRun(): PendingRunState?
    fun removeRun(id: String)
    fun retryRunLater(id: String)
}

interface RunResultOutbox {
    fun enqueueResult(item: PendingRunControlResult)
    fun peekResult(): PendingRunControlResult?
    fun removeResult(id: String)
    fun retryResultLater(id: String)
}

interface RunIosNotificationOutbox {
    fun enqueueIos(item: PendingNotification)
    fun peekIos(): PendingNotification?
    fun removeIos(id: String)
    fun retryIosLater(id: String)
}

class InMemoryRunOutbox : RunOutbox, RunResultOutbox {
    private val lock = Any()
    private val pending = linkedMapOf<String, PendingRunState>()
    private val results = linkedMapOf<String, PendingRunControlResult>()

    override fun enqueue(item: PendingRunState) = synchronized(lock) {
        coalesceRuns(pending, item)
    }

    override fun peekRun(): PendingRunState? = synchronized(lock) { pending.values.firstOrNull() }
    override fun removeRun(id: String) = synchronized(lock) { pending.remove(id); Unit }
    override fun retryRunLater(id: String) = synchronized(lock) {
        val item = pending.remove(id) ?: return@synchronized
        pending[id] = item
    }

    override fun enqueueResult(item: PendingRunControlResult) = synchronized(lock) {
        results.putIfAbsent(item.id, item)
        Unit
    }
    override fun peekResult(): PendingRunControlResult? = synchronized(lock) { results.values.firstOrNull() }
    override fun removeResult(id: String) = synchronized(lock) { results.remove(id); Unit }
    override fun retryResultLater(id: String) = synchronized(lock) {
        val item = results.remove(id) ?: return@synchronized
        results[id] = item
    }
}

class InMemoryRunIosNotificationOutbox : RunIosNotificationOutbox {
    private val lock = Any()
    private val pending = linkedMapOf<String, PendingNotification>()

    override fun enqueueIos(item: PendingNotification) = synchronized(lock) {
        require(item.audience == NotificationAudience.RUN_IOS_COMPAT)
        coalesceRunIosNotifications(pending, item)
    }
    override fun peekIos(): PendingNotification? = synchronized(lock) { pending.values.firstOrNull() }
    override fun removeIos(id: String) = synchronized(lock) { pending.remove(id); Unit }
    override fun retryIosLater(id: String) = synchronized(lock) {
        val item = pending.remove(id) ?: return@synchronized
        pending[id] = item
    }
}

interface RunMeshSender {
    val clientId: ClientId
    suspend fun sendState(item: PendingRunState)
    suspend fun sendControlResult(recipient: ClientId, result: RunControlResult): Int
}

class SecureChannelRunSender(private val channel: SecureChannel) : RunMeshSender {
    override val clientId: ClientId get() = channel.clientId

    override suspend fun sendState(item: PendingRunState) {
        val state = item.state.toWire(clientId)
        val urgency = runUrgency(state.updateReason)
        val recipients = channel.send(
            typ = MessageType.DATA_SYNC,
            body = ProtocolCodec.encodeToCbor(
                DataSync(DataSyncKind.RUN, run = RunSync(RunSyncKind.STATE, state = state)),
            ),
            scope = Recipients.OwnMeshFiltered(
                requiredCapabilities = ANDROID_RUN_CAPABILITIES,
                requireCapabilityRoutingV1 = true,
            ),
            urgency = urgency,
        )
        check(recipients > 0) { "no eligible, sealable Run recipient is currently available" }
    }

    override suspend fun sendControlResult(recipient: ClientId, result: RunControlResult): Int = channel.send(
        typ = MessageType.DATA_SYNC,
        body = ProtocolCodec.encodeToCbor(
            DataSync(DataSyncKind.RUN, run = RunSync(RunSyncKind.CONTROL_RESULT, controlResult = result)),
        ),
        scope = Recipients.Only(recipient),
        urgency = Urgency.NORMAL,
    )
}

internal val ANDROID_RUN_CAPABILITIES = setOf(
    Capability.DISPLAY,
    Capability.BACKGROUND_WAKE,
    Capability.PUSH_FILTERING,
)

internal fun runUrgency(reason: RunUpdateReason): Urgency = when (reason) {
    RunUpdateReason.INITIAL,
    RunUpdateReason.BLOCKED,
    RunUpdateReason.RESUMED,
    RunUpdateReason.COMPLETED,
    RunUpdateReason.FAILED,
    -> Urgency.HIGH
    RunUpdateReason.PERIODIC,
    RunUpdateReason.LLM_SUMMARY,
    RunUpdateReason.REFRESH,
    -> Urgency.NORMAL
}

class RunDispatcher(
    private val sessions: LocalSessionRegistry,
    private val runOutbox: RunOutbox,
    private val resultOutbox: RunResultOutbox,
    private val iosOutbox: RunIosNotificationOutbox,
    private val iosSender: NotificationMeshSender,
    private val sender: RunMeshSender,
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
) : AutoCloseable {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val resultWake = Channel<Unit>(Channel.CONFLATED)
    private val iosWake = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)
    private var worker: Job? = null
    private var resultWorker: Job? = null
    private var iosWorker: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        worker = scope.launch {
            wake.trySend(Unit)
            var consecutiveFailures = 0
            while (isActive) {
                val item = runOutbox.peekRun()
                if (item == null) {
                    wake.receive()
                    continue
                }
                try {
                    sender.sendState(item)
                    runOutbox.removeRun(item.id)
                    consecutiveFailures = 0
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    runOutbox.retryRunLater(item.id)
                    consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(5)
                    delay((1_000L shl (consecutiveFailures - 1)).coerceAtMost(30_000L))
                }
            }
        }
        resultWorker = scope.launch {
            resultWake.trySend(Unit)
            var consecutiveFailures = 0
            while (isActive) {
                val item = resultOutbox.peekResult()
                if (item == null) {
                    // Results can also be queued by the inbound runtime for immediately rejected controls.
                    // A bounded poll keeps that producer decoupled from this dispatcher's lifecycle/wake channel.
                    withTimeoutOrNull(EXTERNAL_RESULT_POLL_MILLIS) { resultWake.receive() }
                    continue
                }
                try {
                    check(sender.sendControlResult(item.recipient, item.result) > 0) {
                        "Run control result recipient is not currently sealable"
                    }
                    resultOutbox.removeResult(item.id)
                    consecutiveFailures = 0
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    resultOutbox.retryResultLater(item.id)
                    consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(5)
                    delay((1_000L shl (consecutiveFailures - 1)).coerceAtMost(30_000L))
                }
            }
        }
        iosWorker = scope.launch {
            iosWake.trySend(Unit)
            var consecutiveFailures = 0
            while (isActive) {
                val item = iosOutbox.peekIos()
                if (item == null) {
                    iosWake.receive()
                    continue
                }
                try {
                    iosSender.send(item)
                    iosOutbox.removeIos(item.id)
                    consecutiveFailures = 0
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    iosOutbox.retryIosLater(item.id)
                    consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(5)
                    delay((1_000L shl (consecutiveFailures - 1)).coerceAtMost(30_000L))
                }
            }
        }
    }

    fun accept(request: RunStateRequest, bearer: String?, peer: LocalPeer): AcceptedResponse {
        validateRunState(request)
        request.toWire(sender.clientId)
        val acceptedAt = clock.millis()
        val projection = request.toIosNotification()
        val accepted = sessions.acceptRunState(
            request = request,
            iosProjection = projection,
            bearer = bearer,
            peer = peer,
            acceptedAt = acceptedAt,
            runItemId = UUID.randomUUID().toString(),
            iosItemId = projection?.let { UUID.randomUUID().toString() },
            runOutbox = runOutbox,
            iosOutbox = iosOutbox,
        )
        if (accepted.iosItem != null) {
            iosWake.trySend(Unit)
        }
        wake.trySend(Unit)
        return AcceptedResponse(accepted.stateItem.id, acceptedAt)
    }

    suspend fun complete(
        eventId: String,
        request: EventCompletionRequest,
        bearer: String?,
        peer: LocalPeer,
    ) {
        val event = sessions.runControlEvent(request.sessionId, eventId, bearer, peer)
        val requestId = requireNotNull(event.requestId)
        val runId = requireNotNull(event.runId)
        val senderId = ClientId(requireNotNull(event.senderClientId))
        val now = clock.millis()
        val item = PendingRunControlResult(
            id = UUID.randomUUID().toString(),
            recipient = senderId,
            result = RunControlResult(
                requestId = requestId,
                runId = runId,
                status = RunControlResultStatus.valueOf(request.status.name),
                respondedAt = now,
                message = request.message?.take(MAX_RESULT_MESSAGE_CHARS),
            ),
            acceptedAt = now,
        )
        sessions.completeRunControl(
            sessionId = request.sessionId,
            eventId = eventId,
            bearer = bearer,
            peer = peer,
            result = item,
            resultOutbox = resultOutbox,
        )
        resultWake.trySend(Unit)
    }

    override fun close() {
        worker?.cancel()
        resultWorker?.cancel()
        iosWorker?.cancel()
        wake.close()
        resultWake.close()
        iosWake.close()
    }

    private fun validateRunState(request: RunStateRequest) {
        require(request.runId.isNotBlank() && request.runId.length <= 256) { "invalid Run id" }
        require(request.revision > 0) { "Run revision must be positive" }
        require(request.interactionGeneration >= 0) { "invalid interaction generation" }
        require(request.argv.isNotEmpty() && request.argv.size <= 1_024) { "Run argv must not be empty" }
        require(request.argv.all { it.length <= 64 * 1024 }) { "Run argv entry is too long" }
        require(Path.of(request.cwd).isAbsolute) { "Run cwd must be absolute" }
        require(request.terminal.text.encodeToByteArray().size <= 64 * 1024) { "Run terminal snapshot is too large" }
        require(request.terminal.rawBytesSeen >= 0) { "invalid Run raw byte count" }
        require((request.failureMessage?.length ?: 0) <= 2_048) { "Run failure message is too long" }
        require(request.updatedAt >= request.startedAt) { "Run update predates its start" }
        require(request.endedAt?.let { it >= request.startedAt } != false) { "Run end predates its start" }
    }

    private companion object {
        const val MAX_RESULT_MESSAGE_CHARS = 512
        const val EXTERNAL_RESULT_POLL_MILLIS = 500L
    }
}

internal fun RunStateRequest.toWire(hostClientId: ClientId) = RunState(
    hostClientId = hostClientId,
    runId = runId,
    revision = revision,
    phase = RunPhase.valueOf(phase.name),
    updateReason = RunUpdateReason.valueOf(updateReason.name),
    startedAt = startedAt,
    updatedAt = updatedAt,
    endedAt = endedAt,
    argv = argv,
    cwd = cwd,
    usesPty = usesPty,
    blockedReason = blockedReason?.let { RunBlockedReason.valueOf(it.name) },
    prompt = prompt?.let { RunPromptKind.valueOf(it.name) },
    progress = progress?.let { RunProgress(it.current, it.total, it.indeterminate) },
    exitCode = exitCode,
    failureMessage = failureMessage,
    interactionGeneration = interactionGeneration,
    terminal = RunTerminalSnapshot(terminal.text, terminal.truncated, terminal.rawBytesSeen),
    llmSummary = llmSummary?.let { RunLlmSummary(it.title, it.text, it.expandedText) },
    responseToRequestId = responseToRequestId,
)

internal fun RunStateRequest.toIosNotification(): NotificationRequest? {
    if (updateReason == LocalRunUpdateReason.PERIODIC || updateReason == LocalRunUpdateReason.REFRESH) return null
    val terminalPhase = phase == LocalRunPhase.COMPLETED || phase == LocalRunPhase.FAILED_TO_START
    val summary = llmSummary
    // Keep the model title across transitions, but never alert with body copy from an older state. Model text and
    // expansion become current only on their correlated LLM_SUMMARY snapshot.
    val freshSummary = summary.takeIf { updateReason == LocalRunUpdateReason.LLM_SUMMARY }
    val currentProgress = progress
    val deterministicText = when (phase) {
        LocalRunPhase.RUNNING -> if (updateReason == LocalRunUpdateReason.RESUMED) "Running again" else {
            progress?.let { progress ->
                if (progress.indeterminate) "Still running"
                else "${((progress.current ?: 0).coerceIn(0, progress.total ?: 1) * 100) / (progress.total ?: 1)}% complete"
            } ?: "Still running"
        }
        LocalRunPhase.BLOCKED -> when (blockedReason) {
            net.extrawdw.notisync.localapi.LocalRunBlockedReason.TERMINAL_INPUT -> "Waiting for input"
            else -> "May need your attention"
        }
        LocalRunPhase.COMPLETED -> if (exitCode == 0) "Completed successfully" else "Exited with code $exitCode"
        LocalRunPhase.FAILED_TO_START -> "Could not start"
    }
    val command = argv.firstOrNull()?.let { runCatching { Path.of(it).fileName.toString() }.getOrNull() }
        ?.takeIf(String::isNotBlank) ?: "Command"
    val generation = interactionGeneration
    return NotificationRequest(
        sessionId = sessionId,
        generation = generation,
        phase = when (updateReason) {
            LocalRunUpdateReason.INITIAL -> NotificationPhase.INITIAL
            LocalRunUpdateReason.BLOCKED -> NotificationPhase.BLOCKED
            LocalRunUpdateReason.RESUMED -> NotificationPhase.RESUMED
            LocalRunUpdateReason.COMPLETED -> NotificationPhase.COMPLETED
            LocalRunUpdateReason.FAILED -> NotificationPhase.FAILED
            LocalRunUpdateReason.LLM_SUMMARY -> when (phase) {
                LocalRunPhase.RUNNING -> NotificationPhase.INITIAL
                LocalRunPhase.BLOCKED -> NotificationPhase.BLOCKED
                LocalRunPhase.COMPLETED -> NotificationPhase.COMPLETED
                LocalRunPhase.FAILED_TO_START -> NotificationPhase.FAILED
            }
            LocalRunUpdateReason.PERIODIC -> NotificationPhase.PERIODIC
            LocalRunUpdateReason.REFRESH -> NotificationPhase.PERIODIC
        },
        title = (summary?.title ?: command).take(160),
        text = (freshSummary?.text ?: deterministicText).take(512),
        expandedText = (freshSummary?.expandedText?.takeIf(String::isNotBlank) ?: failureMessage ?: terminal.text)
            .take(2_048).ifBlank { null },
        shortCriticalText = when {
            phase == LocalRunPhase.BLOCKED &&
                blockedReason == net.extrawdw.notisync.localapi.LocalRunBlockedReason.TERMINAL_INPUT -> "Input"
            phase == LocalRunPhase.BLOCKED -> "Check"
            phase == LocalRunPhase.COMPLETED -> "Done"
            phase == LocalRunPhase.FAILED_TO_START -> "Failed"
            currentProgress != null -> {
                val total = currentProgress.total ?: 1
                "${(((currentProgress.current ?: 0).coerceIn(0, total) * 100) / total).coerceIn(0, 100)}%"
            }
            else -> "Run"
        },
        progress = if (terminalPhase) null else currentProgress?.let {
            net.extrawdw.notisync.localapi.LocalProgress(it.current, it.total, it.indeterminate)
        },
        silent = updateReason in setOf(
            LocalRunUpdateReason.INITIAL,
            LocalRunUpdateReason.RESUMED,
            LocalRunUpdateReason.LLM_SUMMARY,
        ),
        ongoing = !terminalPhase,
        clearable = terminalPhase,
        requestPromotedOngoing = !terminalPhase,
        actions = iosActions(generation),
        metadata = mapOf(
            "runner" to "nsrun",
            "runId" to runId,
            "runUpdateReason" to updateReason.name,
        ),
    )
}

private fun RunStateRequest.iosActions(generation: Long): List<LocalNotificationAction> {
    if (phase == LocalRunPhase.COMPLETED || phase == LocalRunPhase.FAILED_TO_START) return emptyList()
    val interrupt = LocalNotificationAction(
        "signal-int", "Interrupt", NotificationActionKind.SIGNAL, generation, signal = "INT",
        lifetime = NotificationActionLifetime.SESSION,
    )
    val terminate = LocalNotificationAction(
        "signal-term", "Terminate", NotificationActionKind.SIGNAL, generation, signal = "TERM",
        lifetime = NotificationActionLifetime.SESSION,
    )
    if (phase != LocalRunPhase.BLOCKED) return listOf(interrupt, terminate)
    return when (prompt) {
        LocalRunPromptKind.YES_NO -> listOf(
            LocalNotificationAction("yes", "Yes", NotificationActionKind.WRITE_INPUT, generation, inputText = "y\n"),
            LocalNotificationAction("no", "No", NotificationActionKind.WRITE_INPUT, generation, inputText = "n\n"),
            interrupt,
        )
        LocalRunPromptKind.TEXT -> listOf(
            LocalNotificationAction(
                "input", "Input", NotificationActionKind.REMOTE_INPUT, generation,
                remoteInputLabel = "Send to command",
            ),
            interrupt,
            terminate,
        )
        null -> listOf(interrupt, terminate)
    }
}

internal fun coalesceRuns(pending: MutableMap<String, PendingRunState>, item: PendingRunState) {
    val sameRun = pending.values.filter { it.state.runId == item.state.runId }
    if (sameRun.any { it.state.revision >= item.state.revision }) return
    if (item.state.phase == LocalRunPhase.COMPLETED || item.state.phase == LocalRunPhase.FAILED_TO_START) {
        pending.entries.removeIf {
            it.value.state.runId == item.state.runId && it.value.state.updateReason in REPLACEABLE_RUN_UPDATES
        }
    } else if (item.state.updateReason in REPLACEABLE_RUN_UPDATES) {
        pending.entries.removeIf {
            it.value.state.runId == item.state.runId && it.value.state.updateReason in REPLACEABLE_RUN_UPDATES
        }
    }
    pending[item.id] = item
}

/**
 * The iOS compatibility projection has its own durable lane. Only summary-only (and defensive
 * periodic) replacements are coalesced; blocked/resumed/completed/failed projections are
 * attention or lifecycle edges and must survive even when a later terminal projection arrives.
 */
internal fun coalesceRunIosNotifications(
    pending: MutableMap<String, PendingNotification>,
    item: PendingNotification,
) {
    require(item.audience == NotificationAudience.RUN_IOS_COMPAT)
    // Acceptance is monotonic by Run revision, so any newer projection supersedes older
    // summary-only material while attention/lifecycle records remain independently deliverable.
    pending.entries.removeIf {
        it.value.sourceKey == item.sourceKey && it.value.isReplaceableRunIosProjection()
    }
    pending[item.id] = item
}

private fun PendingNotification.isReplaceableRunIosProjection(): Boolean =
    request.phase == NotificationPhase.PERIODIC ||
        request.metadata["runUpdateReason"] == LocalRunUpdateReason.LLM_SUMMARY.name

private val REPLACEABLE_RUN_UPDATES = setOf(
    LocalRunUpdateReason.PERIODIC,
    LocalRunUpdateReason.LLM_SUMMARY,
    LocalRunUpdateReason.REFRESH,
)
