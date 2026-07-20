package net.extrawdw.notisync.run

import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunBlockedReason
import net.extrawdw.notisync.protocol.RunLlmSummary
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunProgress
import net.extrawdw.notisync.protocol.RunPromptKind
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import net.extrawdw.notisync.run.llm.ContentGenerator
import net.extrawdw.notisync.run.llm.GenerationContext
import net.extrawdw.notisync.run.llm.TitleGenerationMode
import net.extrawdw.notisync.run.output.OutputSnapshot
import net.extrawdw.notisync.run.output.PromptKind
import net.extrawdw.notisync.run.process.BlockedReason

/** Builds monotonic, complete Run snapshots for the daemon's DATA_SYNC transport. */
class RunStateCoordinator(
    private val hostClientId: ClientId,
    private val runId: String,
    private val argv: List<String>,
    private val pwd: Path,
    private val usesPty: Boolean,
    private val publish: (RunState) -> Boolean,
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
    private var inputPrompt: RunPromptKind? = null
    private var phase: RunPhase? = null
    private var blockedReason: RunBlockedReason? = null
    private var snapshot = OutputSnapshot("", null, null)
    private var exitCode: Int? = null
    private var failureMessage: String? = null
    private var endedAt: Long? = null
    private var durationMs: Long? = null
    private var llmSummary: RunLlmSummary? = null
    private var lastPeriodicFingerprint: String? = null
    private var llmFuture: Future<*>? = null
    /** Changes for every model request so superseded content can never publish out of order. */
    private var llmRequestGeneration = 0L
    /** Remains pending across superseding ordinary updates until a replacement title succeeds. */
    private var pendingTitleMode: TitleGenerationMode? = null

    @Synchronized
    fun currentInteractionGeneration(): Long = interactionGeneration

    @Synchronized
    fun canAcceptInput(generation: Long?): Boolean =
        phase == RunPhase.BLOCKED && inputPrompt != null && generation == interactionGeneration

    /** Serializes an external atomic completion with every state mutation/publication. */
    @Synchronized
    fun <T> withPublicationLock(operation: () -> T): T = operation()

    @Synchronized
    fun currentState(): RunState? = phase?.let {
        buildState(RunUpdateReason.REFRESH, responseToRequestId = null, nextRevision = revision)
    }

    fun initial(snapshot: OutputSnapshot) {
        transition(
            RunPhase.RUNNING,
            RunUpdateReason.INITIAL,
            snapshot,
            replacementTitleMode = TitleGenerationMode.TASK_IDENTITY,
        )
    }

    @Synchronized
    fun periodic(snapshot: OutputSnapshot) {
        val fingerprint = "${snapshot.progress}:${snapshot.prompt}:${snapshot.tail}:${snapshot.truncated}"
        if (fingerprint == lastPeriodicFingerprint) return
        lastPeriodicFingerprint = fingerprint
        transitionLocked(
            RunPhase.RUNNING,
            RunUpdateReason.PERIODIC,
            snapshot,
            replacementTitleMode = null,
        )
    }

    /**
     * Publish a low-frequency liveness snapshot without changing lifecycle/prompt state or asking the model to
     * summarize identical content again. RunState is deliberately a full authoritative snapshot, so callers keep
     * this cadence much lower than ordinary change detection and rely on the transport's revision coalescing.
     */
    @Synchronized
    fun heartbeat(): Boolean {
        val currentPhase = phase?.takeIf { it == RunPhase.RUNNING || it == RunPhase.BLOCKED } ?: return false
        return publish(buildState(RunUpdateReason.PERIODIC, null, ++revision, currentPhase))
    }

    fun blocked(snapshot: OutputSnapshot, reason: BlockedReason) {
        synchronized(this) {
            blockedReason = reason.toLocal()
            transitionLocked(
                RunPhase.BLOCKED,
                RunUpdateReason.BLOCKED,
                snapshot,
                replacementTitleMode = if (reason == BlockedReason.OUTPUT_AND_CPU_IDLE) {
                    TitleGenerationMode.HANG
                } else null,
            )
        }
    }

    fun resumed(snapshot: OutputSnapshot) {
        synchronized(this) {
            val resumedFromHang = blockedReason == RunBlockedReason.OUTPUT_AND_CPU_IDLE
            blockedReason = null
            transitionLocked(
                RunPhase.RUNNING,
                RunUpdateReason.RESUMED,
                snapshot,
                replacementTitleMode = if (resumedFromHang) TitleGenerationMode.RECOVERY else null,
            )
        }
    }

    fun completed(
        exitCode: Int,
        snapshot: OutputSnapshot,
        completedAt: Long = clock.millis(),
        durationMs: Long? = null,
    ) {
        synchronized(this) {
            require(durationMs == null || durationMs >= 0) { "durationMs must be non-negative" }
            this.exitCode = exitCode
            endedAt = completedAt.coerceAtLeast(startedAt)
            this.durationMs = durationMs
            blockedReason = null
            transitionLocked(
                RunPhase.COMPLETED,
                RunUpdateReason.COMPLETED,
                snapshot,
                replacementTitleMode = TitleGenerationMode.OUTCOME,
            )
        }
    }

    fun spawnFailed(message: String, failedAt: Long = clock.millis()) {
        synchronized(this) {
            failureMessage = message.takeUtf8Bytes(MAX_FAILURE_BYTES).ifBlank { "Unknown start failure" }
            endedAt = failedAt.coerceAtLeast(startedAt)
            transitionLocked(
                RunPhase.FAILED_TO_START,
                RunUpdateReason.FAILED,
                OutputSnapshot("", null, null),
                replacementTitleMode = TitleGenerationMode.OUTCOME,
            )
        }
    }

    /** Publish the latest complete state immediately, correlated to the requesting control. */
    @Synchronized
    fun refresh(requestId: String): Boolean {
        require(requestId.isNotBlank()) { "refresh request id is blank" }
        return publish(prepareRefresh(requestId) ?: return false)
    }

    /** Builds, but does not publish, a refresh so it can be atomically completed with its result. */
    @Synchronized
    fun prepareRefresh(requestId: String): RunState? {
        require(requestId.isNotBlank()) { "refresh request id is blank" }
        val currentPhase = phase ?: return null
        return buildState(RunUpdateReason.REFRESH, requestId, ++revision, currentPhase)
    }

    private fun transition(
        newPhase: RunPhase,
        reason: RunUpdateReason,
        snapshot: OutputSnapshot,
        replacementTitleMode: TitleGenerationMode?,
    ) {
        synchronized(this) { transitionLocked(newPhase, reason, snapshot, replacementTitleMode) }
    }

    private fun transitionLocked(
        newPhase: RunPhase,
        reason: RunUpdateReason,
        snapshot: OutputSnapshot,
        replacementTitleMode: TitleGenerationMode?,
    ) {
        val nextPrompt = snapshot.prompt?.toLocal()?.takeIf {
            newPhase == RunPhase.BLOCKED && blockedReason == RunBlockedReason.TERMINAL_INPUT
        }
        if (nextPrompt != inputPrompt) {
            interactionGeneration++
            inputPrompt = nextPrompt
        }
        this.phase = newPhase
        this.snapshot = snapshot
        if (replacementTitleMode != null) pendingTitleMode = replacementTitleMode
        publish(buildState(reason, null, ++revision, newPhase))
        requestSummary(reason, newPhase, snapshot)
    }

    private fun requestSummary(
        reason: RunUpdateReason,
        newPhase: RunPhase,
        snapshot: OutputSnapshot,
    ) {
        val activeGenerator = generator ?: return
        val requestedTitleMode = pendingTitleMode ?: TitleGenerationMode.KEEP
        val context = GenerationContext(
            phase = newPhase.name,
            argv = argv,
            pwd = pwd,
            tree = tree,
            output = snapshot.tail,
            exitCode = exitCode,
            event = reason.name,
            titleMode = requestedTitleMode,
            currentTitle = llmSummary?.title.takeIf { requestedTitleMode == TitleGenerationMode.KEEP },
            failureMessage = failureMessage,
        )
        val requestGeneration = ++llmRequestGeneration
        llmFuture?.cancel(true)
        llmFuture = llmExecutor.submit {
            val generated = runCatching { activeGenerator.generate(context) }.getOrNull() ?: return@submit
            if (generated.text.isBlank()) return@submit
            synchronized(this) {
                if (llmRequestGeneration != requestGeneration || phase == null) return@synchronized
                val normalizedText = generated.text.normalizeModelText(MAX_TEXT_BYTES, multiline = true)
                    .takeIf { it.isNotBlank() } ?: return@synchronized
                val replacementTitle = generated.title
                    ?.normalizeModelText(MAX_TITLE_BYTES, multiline = false)
                    ?.takeIf { it.isNotBlank() }
                val title = when (requestedTitleMode) {
                    TitleGenerationMode.KEEP -> llmSummary?.title ?: return@synchronized
                    else -> replacementTitle ?: llmSummary?.title ?: return@synchronized
                }
                if (requestedTitleMode != TitleGenerationMode.KEEP && replacementTitle != null) {
                    pendingTitleMode = null
                }
                llmSummary = RunLlmSummary(
                    title = title.normalizeModelText(MAX_TITLE_BYTES, multiline = false),
                    text = normalizedText,
                    expandedText = generated.expandedText
                        ?.normalizeModelText(MAX_EXPANDED_BYTES, multiline = true)
                        ?.takeIf { it.isNotBlank() },
                )
                publish(buildState(RunUpdateReason.LLM_SUMMARY, null, ++revision))
            }
        }
    }

    private fun buildState(
        reason: RunUpdateReason,
        responseToRequestId: String?,
        nextRevision: Long,
        currentPhase: RunPhase = requireNotNull(phase),
    ): RunState {
        // Strictly monotonic timestamps give the iOS notification projection a deterministic
        // last-writer-wins clock without making a retry of the same revision change its body.
        val nextMonotonic = if (lastUpdatedAt == Long.MAX_VALUE) Long.MAX_VALUE else lastUpdatedAt + 1
        val updatedAt = maxOf(clock.millis(), startedAt, endedAt ?: startedAt, nextMonotonic)
        lastUpdatedAt = updatedAt
        return RunState(
            hostClientId = hostClientId,
            runId = runId,
            revision = nextRevision,
            phase = currentPhase,
            updateReason = reason,
            startedAt = startedAt,
            updatedAt = updatedAt,
            endedAt = endedAt,
            durationMs = durationMs,
            argv = argv,
            cwd = pwd.toString(),
            usesPty = usesPty,
            blockedReason = blockedReason,
            prompt = inputPrompt.takeIf { currentPhase == RunPhase.BLOCKED },
            progress = snapshot.progress?.takeUnless {
                currentPhase == RunPhase.COMPLETED || currentPhase == RunPhase.FAILED_TO_START
            }?.let { RunProgress(it.current.coerceIn(0, it.total), it.total) },
            exitCode = exitCode,
            failureMessage = failureMessage,
            interactionGeneration = interactionGeneration,
            terminal = RunTerminalSnapshot(snapshot.tail, snapshot.truncated, snapshot.rawBytesSeen),
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
        BlockedReason.TERMINAL_INPUT -> RunBlockedReason.TERMINAL_INPUT
        BlockedReason.OUTPUT_AND_CPU_IDLE -> RunBlockedReason.OUTPUT_AND_CPU_IDLE
    }

    private fun PromptKind.toLocal() = when (this) {
        PromptKind.YES_NO -> RunPromptKind.YES_NO
        PromptKind.TEXT -> RunPromptKind.TEXT
    }

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

    /** Normalize untrusted model copy before it reaches local DTOs, wire models, or platform notifications. */
    private fun String.normalizeModelText(limit: Int, multiline: Boolean): String {
        val normalized = StringBuilder(length.coerceAtMost(limit))
        var index = 0
        var previousWasCarriageReturn = false

        fun appendSpace() {
            if (normalized.isNotEmpty() && normalized.last() != ' ' && normalized.last() != '\n') {
                normalized.append(' ')
            }
        }

        fun appendLineBreak() {
            while (normalized.isNotEmpty() && normalized.last() == ' ') normalized.setLength(normalized.length - 1)
            if (normalized.isNotEmpty() && normalized.last() != '\n') normalized.append('\n')
        }

        while (index < length) {
            val codePoint = codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint == '\n'.code && previousWasCarriageReturn) {
                previousWasCarriageReturn = false
                continue
            }
            previousWasCarriageReturn = codePoint == '\r'.code
            when {
                codePoint == '\r'.code || codePoint == '\n'.code || codePoint == 0x2028 || codePoint == 0x2029 ->
                    if (multiline) appendLineBreak() else appendSpace()
                codePoint == '\t'.code -> appendSpace()
                !codePoint.isSafeModelCodePoint() -> Unit
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> appendSpace()
                else -> normalized.appendCodePoint(codePoint)
            }
        }
        return normalized.toString().trim().takeUtf8Bytes(limit).trimEnd()
    }

    private fun Int.isSafeModelCodePoint(): Boolean =
        this >= 0x20 && this != 0x7f && this !in 0x80..0x9f && this !in 0xd800..0xdfff &&
            Character.getType(this) != Character.FORMAT.toInt() &&
            this != 0x061c && this !in 0x200e..0x200f && this !in 0x202a..0x202e &&
            this !in 0x2066..0x2069 && this !in 0x206a..0x206f &&
            this !in 0xfdd0..0xfdef && (this and 0xffff) !in 0xfffe..0xffff

    private companion object {
        const val MAX_TITLE_BYTES = 160
        const val MAX_TEXT_BYTES = 512
        const val MAX_EXPANDED_BYTES = 2_048
        const val MAX_FAILURE_BYTES = 2_048
    }
}
