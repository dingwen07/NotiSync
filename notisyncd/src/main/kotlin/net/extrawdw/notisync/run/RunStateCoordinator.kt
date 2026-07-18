package net.extrawdw.notisync.run

import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.extrawdw.notisync.localapi.LocalRunBlockedReason
import net.extrawdw.notisync.localapi.LocalRunLlmSummary
import net.extrawdw.notisync.localapi.LocalRunPhase
import net.extrawdw.notisync.localapi.LocalRunProgress
import net.extrawdw.notisync.localapi.LocalRunPromptKind
import net.extrawdw.notisync.localapi.LocalRunTerminalSnapshot
import net.extrawdw.notisync.localapi.LocalRunUpdateReason
import net.extrawdw.notisync.localapi.RunStateRequest
import net.extrawdw.notisync.run.llm.ContentGenerator
import net.extrawdw.notisync.run.llm.GeneratedContent
import net.extrawdw.notisync.run.llm.GenerationContext
import net.extrawdw.notisync.run.output.OutputSnapshot
import net.extrawdw.notisync.run.output.PromptKind
import net.extrawdw.notisync.run.process.BlockedReason

/** Builds monotonic, complete Run snapshots for the daemon's DATA_SYNC transport. */
class RunStateCoordinator(
    private val sessionId: String,
    private val runId: String,
    private val argv: List<String>,
    private val pwd: Path,
    private val usesPty: Boolean,
    private val publish: (RunStateRequest) -> Boolean,
    private val generator: ContentGenerator? = null,
    private val tree: String = "",
    private val clock: Clock = Clock.systemUTC(),
    private val startedAt: Long = clock.millis(),
    private val llmShutdownTimeoutSeconds: Long = 9,
    private val llmExecutor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "nsrun-llm").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true },
) : AutoCloseable {
    private var lastUpdatedAt = startedAt
    private var revision = 0L
    private var interactionGeneration = 0L
    private var inputPrompt: LocalRunPromptKind? = null
    private var phase: LocalRunPhase? = null
    private var blockedReason: LocalRunBlockedReason? = null
    private var snapshot = OutputSnapshot("", null, null)
    private var exitCode: Int? = null
    private var failureMessage: String? = null
    private var endedAt: Long? = null
    private var llmSummary: LocalRunLlmSummary? = null
    private var lastPeriodicFingerprint: String? = null
    private var llmFuture: Future<*>? = null

    @Synchronized
    fun currentInteractionGeneration(): Long = interactionGeneration

    @Synchronized
    fun currentState(): RunStateRequest? = phase?.let {
        buildState(LocalRunUpdateReason.REFRESH, responseToRequestId = null, nextRevision = revision)
    }

    fun initial(snapshot: OutputSnapshot) {
        transition(LocalRunPhase.RUNNING, LocalRunUpdateReason.INITIAL, snapshot)
    }

    @Synchronized
    fun periodic(snapshot: OutputSnapshot) {
        val fingerprint = "${snapshot.progress}:${snapshot.prompt}:${snapshot.tail}:${snapshot.truncated}"
        if (fingerprint == lastPeriodicFingerprint) return
        lastPeriodicFingerprint = fingerprint
        transitionLocked(LocalRunPhase.RUNNING, LocalRunUpdateReason.PERIODIC, snapshot)
    }

    fun blocked(snapshot: OutputSnapshot, reason: BlockedReason) {
        synchronized(this) {
            blockedReason = reason.toLocal()
            transitionLocked(LocalRunPhase.BLOCKED, LocalRunUpdateReason.BLOCKED, snapshot)
        }
    }

    fun resumed(snapshot: OutputSnapshot) {
        synchronized(this) {
            blockedReason = null
            transitionLocked(LocalRunPhase.RUNNING, LocalRunUpdateReason.RESUMED, snapshot)
        }
    }

    fun completed(exitCode: Int, snapshot: OutputSnapshot, completedAt: Long = clock.millis()) {
        synchronized(this) {
            this.exitCode = exitCode
            endedAt = completedAt.coerceAtLeast(startedAt)
            blockedReason = null
            transitionLocked(LocalRunPhase.COMPLETED, LocalRunUpdateReason.COMPLETED, snapshot)
        }
    }

    fun spawnFailed(message: String, failedAt: Long = clock.millis()) {
        synchronized(this) {
            failureMessage = message.takeUtf8Bytes(MAX_FAILURE_BYTES).ifBlank { "Unknown start failure" }
            endedAt = failedAt.coerceAtLeast(startedAt)
            transitionLocked(
                LocalRunPhase.FAILED_TO_START,
                LocalRunUpdateReason.FAILED,
                OutputSnapshot("", null, null),
            )
        }
    }

    /** Publish the latest complete state immediately, correlated to the requesting control. */
    @Synchronized
    fun refresh(requestId: String): Boolean {
        require(requestId.isNotBlank()) { "refresh request id is blank" }
        val currentPhase = phase ?: return false
        return publish(buildState(LocalRunUpdateReason.REFRESH, requestId, ++revision, currentPhase))
    }

    private fun transition(newPhase: LocalRunPhase, reason: LocalRunUpdateReason, snapshot: OutputSnapshot) {
        synchronized(this) { transitionLocked(newPhase, reason, snapshot) }
    }

    private fun transitionLocked(
        newPhase: LocalRunPhase,
        reason: LocalRunUpdateReason,
        snapshot: OutputSnapshot,
    ) {
        val nextPrompt = snapshot.prompt?.toLocal()?.takeIf {
            newPhase == LocalRunPhase.BLOCKED && blockedReason == LocalRunBlockedReason.TERMINAL_INPUT
        }
        if (nextPrompt != inputPrompt) {
            interactionGeneration++
            inputPrompt = nextPrompt
        }
        this.phase = newPhase
        this.snapshot = snapshot
        llmSummary = null
        val postedRevision = ++revision
        publish(buildState(reason, null, postedRevision, newPhase))
        requestSummary(newPhase, snapshot, postedRevision)
    }

    private fun requestSummary(newPhase: LocalRunPhase, snapshot: OutputSnapshot, postedRevision: Long) {
        val activeGenerator = generator ?: return
        val context = GenerationContext(newPhase.name, argv, pwd, tree, snapshot.tail, exitCode)
        llmFuture?.cancel(true)
        llmFuture = llmExecutor.submit {
            val generated = runCatching { activeGenerator.generate(context) }.getOrNull() ?: return@submit
            if (generated.title.isBlank() || generated.text.isBlank()) return@submit
            synchronized(this) {
                if (revision != postedRevision || phase != newPhase) return@synchronized
                llmSummary = generated.toLocalSummary()
                publish(buildState(LocalRunUpdateReason.LLM_SUMMARY, null, ++revision, newPhase))
            }
        }
    }

    private fun buildState(
        reason: LocalRunUpdateReason,
        responseToRequestId: String?,
        nextRevision: Long,
        currentPhase: LocalRunPhase = requireNotNull(phase),
    ): RunStateRequest {
        val updatedAt = maxOf(clock.millis(), startedAt, endedAt ?: startedAt, lastUpdatedAt)
        lastUpdatedAt = updatedAt
        return RunStateRequest(
        sessionId = sessionId,
        runId = runId,
        revision = nextRevision,
        phase = currentPhase,
        updateReason = reason,
        startedAt = startedAt,
        updatedAt = updatedAt,
        endedAt = endedAt,
        argv = argv,
        cwd = pwd.toString(),
        usesPty = usesPty,
        blockedReason = blockedReason,
        prompt = inputPrompt.takeIf { currentPhase == LocalRunPhase.BLOCKED },
        progress = snapshot.progress?.takeUnless {
            currentPhase == LocalRunPhase.COMPLETED || currentPhase == LocalRunPhase.FAILED_TO_START
        }?.let { LocalRunProgress(it.current.coerceIn(0, it.total), it.total) },
        exitCode = exitCode,
        failureMessage = failureMessage,
        interactionGeneration = interactionGeneration,
        terminal = LocalRunTerminalSnapshot(snapshot.tail, snapshot.truncated, snapshot.rawBytesSeen),
        llmSummary = llmSummary,
        responseToRequestId = responseToRequestId,
    )
    }

    override fun close() {
        llmExecutor.shutdown()
        runCatching { llmExecutor.awaitTermination(llmShutdownTimeoutSeconds, TimeUnit.SECONDS) }
        llmExecutor.shutdownNow()
    }

    private fun BlockedReason.toLocal() = when (this) {
        BlockedReason.TERMINAL_INPUT -> LocalRunBlockedReason.TERMINAL_INPUT
        BlockedReason.OUTPUT_AND_CPU_IDLE -> LocalRunBlockedReason.OUTPUT_AND_CPU_IDLE
    }

    private fun PromptKind.toLocal() = when (this) {
        PromptKind.YES_NO -> LocalRunPromptKind.YES_NO
        PromptKind.TEXT -> LocalRunPromptKind.TEXT
    }

    private fun GeneratedContent.toLocalSummary() = LocalRunLlmSummary(
        title = title.takeUtf8Bytes(MAX_TITLE_BYTES),
        text = text.takeUtf8Bytes(MAX_TEXT_BYTES),
        expandedText = expandedText?.takeUtf8Bytes(MAX_EXPANDED_BYTES),
    )

    private fun String.takeUtf8Bytes(limit: Int): String {
        if (encodeToByteArray().size <= limit) return this
        var bytes = 0
        var index = 0
        while (index < length) {
            val next = offsetByCodePoints(index, 1)
            val count = substring(index, next).encodeToByteArray().size
            if (bytes + count > limit) break
            bytes += count
            index = next
        }
        return substring(0, index)
    }

    private companion object {
        const val MAX_TITLE_BYTES = 160
        const val MAX_TEXT_BYTES = 512
        const val MAX_EXPANDED_BYTES = 2_048
        const val MAX_FAILURE_BYTES = 2_048
    }
}
