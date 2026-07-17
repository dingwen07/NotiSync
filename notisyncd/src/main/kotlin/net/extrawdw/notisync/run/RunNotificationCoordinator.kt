package net.extrawdw.notisync.run

import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import net.extrawdw.notisync.localapi.LocalNotificationAction
import net.extrawdw.notisync.localapi.LocalProgress
import net.extrawdw.notisync.localapi.NotificationActionKind
import net.extrawdw.notisync.localapi.NotificationActionLifetime
import net.extrawdw.notisync.localapi.NotificationPhase
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.run.llm.ContentGenerator
import net.extrawdw.notisync.run.llm.GeneratedContent
import net.extrawdw.notisync.run.llm.GenerationContext
import net.extrawdw.notisync.run.output.OutputSnapshot
import net.extrawdw.notisync.run.output.PromptKind
import net.extrawdw.notisync.run.process.BlockedReason

class RunNotificationCoordinator(
    private val sessionId: String,
    private val argv: List<String>,
    private val pwd: Path,
    private val publish: (NotificationRequest) -> Unit,
    private val generator: ContentGenerator? = null,
    private val tree: String = "",
    private val llmShutdownTimeoutSeconds: Long = 9,
    private val llmExecutor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "nsrun-llm").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true },
) : AutoCloseable {
    private var generation = 0L
    private var currentPhase: NotificationPhase? = null
    private var lastPeriodicFingerprint: String? = null
    private var llmFuture: Future<*>? = null

    @Synchronized
    fun currentGeneration(): Long = generation

    fun initial(snapshot: OutputSnapshot) {
        val command = commandLabel()
        post(
            phase = NotificationPhase.INITIAL,
            content = GeneratedContent(command, "Running in ${pwd.fileName ?: pwd}", snapshot.tail.ifBlank { null }),
            snapshot = snapshot,
        )
    }

    @Synchronized
    fun periodic(snapshot: OutputSnapshot) {
        val fingerprint = "${snapshot.progress}:${snapshot.tail}"
        if (fingerprint == lastPeriodicFingerprint) return
        lastPeriodicFingerprint = fingerprint
        post(
            phase = NotificationPhase.PERIODIC,
            content = GeneratedContent(
                commandLabel(),
                snapshot.progress?.let { "${it.percent}% complete" } ?: "Still running",
                snapshot.tail.ifBlank { null },
            ),
            snapshot = snapshot,
        )
    }

    fun blocked(snapshot: OutputSnapshot, reason: BlockedReason) {
        val text = when (reason) {
            BlockedReason.TERMINAL_INPUT -> "Waiting for input"
            BlockedReason.OUTPUT_AND_CPU_IDLE -> "May need your attention"
        }
        post(NotificationPhase.BLOCKED, GeneratedContent(commandLabel(), text, snapshot.tail.ifBlank { null }), snapshot)
    }

    fun resumed(snapshot: OutputSnapshot) {
        post(
            NotificationPhase.RESUMED,
            GeneratedContent(commandLabel(), "Running again", snapshot.tail.ifBlank { null }),
            snapshot,
        )
    }

    fun completed(exitCode: Int, snapshot: OutputSnapshot) {
        val text = if (exitCode == 0) "Completed successfully" else "Exited with code $exitCode"
        post(
            NotificationPhase.COMPLETED,
            GeneratedContent(commandLabel(), text, snapshot.tail.ifBlank { null }),
            snapshot,
            exitCode,
        )
    }

    fun spawnFailed(message: String) {
        post(
            NotificationPhase.FAILED,
            GeneratedContent(commandLabel(), "Could not start", message.take(2048)),
            OutputSnapshot("", null, null),
            exitCode = 127,
        )
    }

    private fun post(
        phase: NotificationPhase,
        content: GeneratedContent,
        snapshot: OutputSnapshot,
        exitCode: Int? = null,
    ) {
        val postedGeneration = postNow(phase, content, snapshot, forceSilent = false)
        val activeGenerator = generator ?: return
        val context = GenerationContext(phase.name, argv, pwd, tree, snapshot.tail, exitCode)
        synchronized(this) {
            // There is never more than one queued/running model request. A state transition or newer
            // periodic snapshot supersedes older context; HttpClient.send observes interruption.
            llmFuture?.cancel(true)
            llmFuture = llmExecutor.submit {
            val generated = runCatching { activeGenerator.generate(context) }.getOrNull() ?: return@submit
            synchronized(this) {
                if (generation != postedGeneration || currentPhase != phase) return@synchronized
                postNow(phase, generated, snapshot, forceSilent = true)
            }
        }
        }
    }

    @Synchronized
    private fun postNow(
        phase: NotificationPhase,
        content: GeneratedContent,
        snapshot: OutputSnapshot,
        forceSilent: Boolean,
    ): Long {
        val nextGeneration = ++generation
        currentPhase = phase
        val terminal = phase == NotificationPhase.COMPLETED || phase == NotificationPhase.FAILED
        val silent = forceSilent || phase in setOf(
            NotificationPhase.INITIAL,
            NotificationPhase.PERIODIC,
            NotificationPhase.RESUMED,
        )
        // A terminal notification must render its final output, not remain eligible for the
        // promoted ongoing ProgressStyle used while the command is running.
        val progress = if (terminal) null else snapshot.progress?.let { LocalProgress(it.current, it.total) }
        publish(
            NotificationRequest(
                sessionId = sessionId,
                generation = nextGeneration,
                phase = phase,
                title = content.title.take(160),
                text = content.text.take(512),
                expandedText = content.expandedText?.take(2048),
                shortCriticalText = when {
                    phase == NotificationPhase.BLOCKED -> "Input"
                    terminal -> if (phase == NotificationPhase.COMPLETED) "Done" else "Failed"
                    progress != null -> "${snapshot.progress?.percent ?: 0}%"
                    else -> "Run"
                },
                progress = progress,
                silent = silent,
                ongoing = !terminal,
                clearable = terminal,
                requestPromotedOngoing = !terminal,
                actions = actions(phase, snapshot.prompt, nextGeneration),
                metadata = mapOf("runner" to "nsrun"),
            ),
        )
        return nextGeneration
    }

    private fun actions(
        phase: NotificationPhase,
        prompt: PromptKind?,
        generation: Long,
    ): List<LocalNotificationAction> {
        if (phase == NotificationPhase.COMPLETED || phase == NotificationPhase.FAILED) return emptyList()
        val interrupt = LocalNotificationAction(
            "signal-int", "Interrupt", NotificationActionKind.SIGNAL, generation, signal = "SIGINT",
            lifetime = NotificationActionLifetime.SESSION,
        )
        val terminate = LocalNotificationAction(
            "signal-term", "Terminate", NotificationActionKind.SIGNAL, generation, signal = "SIGTERM",
            lifetime = NotificationActionLifetime.SESSION,
        )
        if (phase != NotificationPhase.BLOCKED) return listOf(interrupt, terminate)
        return when (prompt) {
            PromptKind.YES_NO -> listOf(
                LocalNotificationAction("yes", "Yes", NotificationActionKind.WRITE_INPUT, generation, inputText = "y\n"),
                LocalNotificationAction("no", "No", NotificationActionKind.WRITE_INPUT, generation, inputText = "n\n"),
                interrupt,
            )
            else -> listOf(
                LocalNotificationAction(
                    "input", "Input", NotificationActionKind.REMOTE_INPUT, generation,
                    remoteInputLabel = "Send to command",
                ),
                interrupt,
                terminate,
            )
        }
    }

    private fun commandLabel(): String = argv.firstOrNull()?.let { Path.of(it).fileName.toString() } ?: "Command"

    override fun close() {
        llmExecutor.shutdown()
        runCatching { llmExecutor.awaitTermination(llmShutdownTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS) }
        llmExecutor.shutdownNow()
    }
}
