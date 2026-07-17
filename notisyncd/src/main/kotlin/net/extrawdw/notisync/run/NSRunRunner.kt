package net.extrawdw.notisync.run

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.api.DaemonAutostarter
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.EventStream
import net.extrawdw.notisync.localapi.CreateSessionRequest
import net.extrawdw.notisync.localapi.LocalEventType
import net.extrawdw.notisync.localapi.SessionResponse
import net.extrawdw.notisync.run.llm.CappedTree
import net.extrawdw.notisync.run.llm.ContentGenerator
import net.extrawdw.notisync.run.llm.OpenAiCompatibleContentGenerator
import net.extrawdw.notisync.run.logging.RunLog
import net.extrawdw.notisync.run.output.OutputAnalyzer
import net.extrawdw.notisync.run.output.DetectedProgress
import net.extrawdw.notisync.run.output.RemoteInputRedactor
import net.extrawdw.notisync.run.process.BlockedReason
import net.extrawdw.notisync.run.process.ChildLauncher
import net.extrawdw.notisync.run.process.ChildProcessLauncher
import net.extrawdw.notisync.run.process.ChildSignal
import net.extrawdw.notisync.run.process.ManagedChild
import net.extrawdw.notisync.run.process.ProcessInspector
import net.extrawdw.notisync.run.process.ProcessInspectors
import net.extrawdw.notisync.run.process.ProcessMonitor
import net.extrawdw.notisync.run.process.SttyTerminalController
import net.extrawdw.notisync.run.process.TerminalController
import net.extrawdw.notisync.run.process.TerminalFileDescriptors
import net.extrawdw.notisync.run.process.UnixSignalBridge

class NSRunRunner(
    private val paths: DesktopPaths = DesktopPaths.default(),
    private val daemonConnector: () -> DaemonLocalApi = { DaemonAutostarter(paths).connect() },
    private val processInspector: ProcessInspector = ProcessInspectors.system(),
    private val childLauncher: ChildProcessLauncher = ChildLauncher(inspector = processInspector),
    private val terminalFactory: () -> TerminalController = ::SttyTerminalController,
    private val terminalOutputAttached: () -> Boolean = { TerminalFileDescriptors.isTerminal(1) },
    private val stdinTerminalAttached: () -> Boolean = { TerminalFileDescriptors.isTerminal(0) },
    private val stdin: InputStream = System.`in`,
    private val stdout: OutputStream = System.out,
    private val stderr: Appendable = System.err,
    private val reportingSetupTimeout: Duration = Duration.ofSeconds(2),
) {
    fun run(options: RunOptions): Int {
        val pwd = Path.of("").toAbsolutePath().normalize()
        val logFailureWarned = AtomicBoolean()
        val log = runCatching { RunLog.create(options.config, paths) }
            .onFailure { warn("Run logging unavailable: ${it.message}") }
            .getOrNull()
        fun record(action: (RunLog) -> Unit) {
            val current = log ?: return
            runCatching { action(current) }.onFailure { error ->
                if (logFailureWarned.compareAndSet(false, true)) warn("Run logging failed: ${error.message}")
            }
        }
        record { it.header(pwd, options.command) }
        val runId = log?.runId ?: "${ProcessHandle.current().pid()}-${System.currentTimeMillis()}"

        val analyzer = OutputAnalyzer()
        val generator = if (options.llmEnabled) {
            options.config.llm?.let(::OpenAiCompatibleContentGenerator)
        } else null

        val child = try {
            childLauncher.launch(options.command, pwd, System.getenv(), options.ptyMode)
        } catch (error: Exception) {
            val connection = connectForReporting(runId, pwd)
            val reporter = Reporter(connection.api, connection.session, ::warn)
            val notifications = notificationCoordinator(options, pwd, reporter, connection.session, generator, tree = "")
            record { it.state("spawn-failed") }
            notifications.spawnFailed(error.message ?: error.javaClass.simpleName)
            record { it.completed(127) }
            notifications.close()
            reporter.close()
            runCatching { log?.close() }
            warn("Unable to start ${options.command.first()}: ${error.message}")
            return 127
        }

        val childInput = ChildInput(child.input)
        val remoteInputRedactor = RemoteInputRedactor()
        val outputActivity = AtomicLong(System.nanoTime())
        val loggedProgress = AtomicReference<DetectedProgress?>()
        val blocked = AtomicBoolean(false)
        val trackTerminalPresentation = child.usesPty && terminalOutputAttached()
        val terminal = runCatching { terminalFactory() }
            .onFailure { warn("Terminal controller unavailable: ${it.message}") }
            .getOrElse { NoTerminalController }
        val signalBridge = UnixSignalBridge.install(child)
        val eventStream = AtomicReference<EventStream?>()
        val outputLock = ReentrantLock()
        val outputOpen = AtomicBoolean(true)
        val inputOpen = AtomicBoolean(true)
        val interactiveIoFinished = AtomicBoolean()
        var outputThread: Thread? = null
        var inputThread: Thread? = null
        var scheduler: ScheduledExecutorService? = null
        var reporter: Reporter? = null
        var notifications: RunNotificationCoordinator? = null
        val terminalShutdownHook = if (child.usesPty) Thread({
            outputOpen.set(false)
            if (child.isAlive()) runCatching { child.signal(ChildSignal.TERMINATE) }
            runCatching { terminal.close() }
        }, "nsrun-terminal-recovery") else null
        val shutdownHookRegistered = AtomicBoolean(
            terminalShutdownHook?.let { hook ->
                runCatching { Runtime.getRuntime().addShutdownHook(hook) }.isSuccess
            } == true,
        )

        fun finishInteractiveIo() {
            if (!interactiveIoFinished.compareAndSet(false, true)) return
            runCatching { scheduler?.shutdownNow() }
            eventStream.getAndSet(null)?.let { runCatching { it.close() } }
            if (child.isAlive()) runCatching { child.signal(ChildSignal.TERMINATE) }
            inputOpen.set(false)
            inputThread?.let { thread ->
                thread.interrupt()
                joinPreservingInterrupt(thread, INPUT_CLOSE_GRACE_MILLIS)
            }
            if (!child.usesPty) childInput.close()

            // Let normal program teardown (vim's alternate-screen exit, for example) drain first.
            // If a descendant inherited the PTY and keeps it open, close our read side rather than
            // allowing it to write terminal-control bytes after the terminal has been restored.
            outputThread?.let { thread ->
                joinPreservingInterrupt(thread, OUTPUT_DRAIN_GRACE_MILLIS)
                if (thread.isAlive) {
                    runCatching { child.mergedOutput.close() }
                    joinPreservingInterrupt(thread, OUTPUT_CLOSE_GRACE_MILLIS)
                }
            }
            // PTY4J's input and output streams share the same master descriptor. Closing child
            // stdin before this point would also close the read side and discard vim/top teardown.
            if (child.usesPty) childInput.close()
            outputOpen.set(false)
            val outputGateAcquired = try {
                outputLock.tryLock(OUTPUT_GATE_GRACE_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            try {
                runCatching { terminal.close() }
                    .onFailure { warn("Unable to restore terminal: ${it.message}") }
            } finally {
                if (outputGateAcquired) outputLock.unlock()
            }
            terminalShutdownHook?.takeIf { shutdownHookRegistered.compareAndSet(true, false) }?.let { hook ->
                runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
            // Keep INT/TERM intercepted until after terminal recovery; otherwise another signal in
            // the cleanup window could terminate nsrun while its controlling TTY is still raw.
            runCatching { signalBridge.close() }
        }

        try {
            record { it.state("running") }
            if (child.usesPty) {
                runCatching { terminal.enterRawMode() }
                    .onFailure { warn("Unable to enter terminal raw mode: ${it.message}") }
                terminal.size()?.let { child.resize(it.columns, it.rows) }
            }

            val activeOutputThread = daemonThread("nsrun-output") {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = try {
                        child.mergedOutput.read(buffer)
                    } catch (error: Exception) {
                        if (!interactiveIoFinished.get()) throw error
                        break
                    }
                    if (count < 0) break
                    if (count == 0) continue
                    if (outputOpen.get()) {
                        outputLock.lock()
                        var recoverAfterLateWrite = false
                        try {
                            if (!outputOpen.get()) continue
                            stdout.write(buffer, 0, count)
                            stdout.flush()
                            if (trackTerminalPresentation) terminal.observeOutput(buffer, 0, count)
                            val captured = remoteInputRedactor.accept(buffer, count)
                            if (captured.isNotEmpty()) {
                                capture(captured, analyzer, loggedProgress, outputActivity, ::record)
                            }
                            recoverAfterLateWrite = !outputOpen.get()
                        } finally {
                            outputLock.unlock()
                        }
                        if (recoverAfterLateWrite) runCatching { terminal.close() }
                    }
                }
                if (outputOpen.get()) {
                    outputLock.lock()
                    try {
                        if (!outputOpen.get()) return@daemonThread
                        val trailing = remoteInputRedactor.finish()
                        if (trailing.isNotEmpty()) {
                            capture(trailing, analyzer, loggedProgress, outputActivity, ::record)
                        }
                    } finally {
                        outputLock.unlock()
                    }
                }
            }
            outputThread = activeOutputThread
            val pollStdin = stdinTerminalAttached()
            val activeInputThread = daemonThread("nsrun-input") {
                try {
                    val buffer = ByteArray(4096)
                    while (inputOpen.get() && child.isAlive()) {
                        if (pollStdin && stdin.available() == 0) {
                            Thread.sleep(INPUT_POLL_MILLIS)
                            continue
                        }
                        if (!inputOpen.get() || !child.isAlive()) break
                        val count = stdin.read(buffer)
                        if (count < 0) {
                            // A PTY master has no independent stdin half-close in PTY4J. An EOT
                            // provides terminal EOF without discarding the child's remaining output.
                            if (child.usesPty) childInput.write(byteArrayOf(END_OF_TRANSMISSION.toByte()), 1)
                            else childInput.close()
                            break
                        }
                        if (count > 0) childInput.write(buffer, count)
                    }
                } catch (error: Exception) {
                    if (!interactiveIoFinished.get()) throw error
                }
            }
            inputThread = activeInputThread

            // Drain output and proxy local input before touching the daemon. Even a same-UID daemon
            // that accepts a UDS connection and never responds cannot delay process launch or fill the
            // child's pipe while reporting setup is bounded on a daemon background thread.
            activeOutputThread.start()
            activeInputThread.start()

            val connection = connectForReporting(runId, pwd)
            val activeReporter = Reporter(connection.api, connection.session, ::warn)
            reporter = activeReporter
            val tree = if (generator != null) {
                runCatching { CappedTree.collect(pwd, requireNotNull(options.config.llm)) }.getOrDefault("")
            } else ""
            val activeNotifications = notificationCoordinator(
                options, pwd, activeReporter, connection.session, generator, tree,
            )
            notifications = activeNotifications
            activeNotifications.initial(analyzer.snapshot())
            // An ACK can fail after the child action succeeds. Remember handled durable IDs so
            // reconnecting retries the ACK without writing input or delivering a signal twice.
            val handledEventIds = linkedSetOf<String>()
            val eventThread = if (connection.session != null) daemonThread("nsrun-events") {
                var reconnectDelayMillis = 250L
                val streamFailureWarned = AtomicBoolean()
                while (child.isAlive()) {
                    var current: EventStream? = null
                    try {
                        current = activeReporter.openEvents() ?: break
                        eventStream.set(current)
                        while (child.isAlive()) {
                            val event = current.next() ?: break
                            reconnectDelayMillis = 250L
                            streamFailureWarned.set(false)
                            if (event.type != LocalEventType.HEARTBEAT) {
                                if (event.id !in handledEventIds) {
                                    if (event.type == LocalEventType.ACTION) {
                                        val currentGeneration =
                                            event.generation == activeNotifications.currentGeneration()
                                        when (event.actionId) {
                                            // Process-control actions are valid for the live session,
                                            // even when an older notification instance produced them.
                                            "signal-int" -> child.signal(ChildSignal.INTERRUPT)
                                            "signal-term" -> child.signal(ChildSignal.TERMINATE)
                                            "yes" -> if (currentGeneration) childInput.write("y\n")
                                            "no" -> if (currentGeneration) childInput.write("n\n")
                                            "input" -> if (currentGeneration) event.inputText?.let { remoteInput ->
                                                remoteInputRedactor.register(remoteInput)
                                                childInput.write(remoteInput.trimEnd('\r', '\n') + "\n")
                                            }
                                        }
                                    }
                                    handledEventIds += event.id
                                }
                                activeReporter.ack(event.id)
                            }
                        }
                    } catch (error: Exception) {
                        if (child.isAlive() && streamFailureWarned.compareAndSet(false, true)) {
                            warn("NotiSync action stream closed: ${error.message}")
                        }
                    } finally {
                        eventStream.compareAndSet(current, null)
                        runCatching { current?.close() }
                    }
                    if (!child.isAlive()) break
                    try {
                        Thread.sleep(reconnectDelayMillis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    reconnectDelayMillis = (reconnectDelayMillis * 2).coerceAtMost(5_000L)
                }
            } else null

            eventThread?.start()

            val monitor = ProcessMonitor(processInspector, child.pid, options.stuckAfter, outputActivity::get)
            val activeScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "nsrun-monitor").apply { isDaemon = true }
            }
            scheduler = activeScheduler
            val lastPeriodic = AtomicLong(System.nanoTime())
            val lastSize = AtomicReference<net.extrawdw.notisync.run.process.TerminalSize?>()
            activeScheduler.scheduleAtFixedRate({
                if (!child.isAlive()) return@scheduleAtFixedRate
                val snapshot = analyzer.snapshot()
                monitor.sample(prompt = snapshot.prompt)?.let { transition ->
                    blocked.set(transition.blocked)
                    if (transition.blocked) {
                        record { it.state("blocked:${transition.reason}") }
                        activeNotifications.blocked(snapshot, transition.reason ?: BlockedReason.OUTPUT_AND_CPU_IDLE)
                    } else {
                        record { it.state("resumed") }
                        activeNotifications.resumed(snapshot)
                        outputActivity.set(System.nanoTime())
                    }
                }
                if (!blocked.get() && System.nanoTime() - lastPeriodic.get() >= options.updateInterval.toNanos()) {
                    activeNotifications.periodic(snapshot)
                    lastPeriodic.set(System.nanoTime())
                }
                if (child.usesPty) terminal.size()?.let { size ->
                    if (lastSize.getAndSet(size) != size) child.resize(size.columns, size.rows)
                }
            }, 2, 2, TimeUnit.SECONDS)

            val exitCode = child.waitFor()
            finishInteractiveIo()
            record { it.completed(exitCode) }
            activeNotifications.completed(exitCode, analyzer.snapshot())
            return exitCode
        } finally {
            finishInteractiveIo()
            runCatching { notifications?.close() }
            runCatching { reporter?.close() }
            runCatching { child.close() }
            runCatching { log?.close() }.onFailure {
                if (logFailureWarned.compareAndSet(false, true)) warn("Run log close failed")
            }
        }
    }

    private fun capture(
        bytes: ByteArray,
        analyzer: OutputAnalyzer,
        loggedProgress: AtomicReference<DetectedProgress?>,
        outputActivity: AtomicLong,
        record: ((RunLog) -> Unit) -> Unit,
    ) {
        record { it.output(bytes) }
        analyzer.accept(bytes)
        analyzer.snapshot().progress?.let { progress ->
            if (loggedProgress.getAndSet(progress) != progress) {
                record { it.progress(progress.current, progress.total) }
            }
        }
        outputActivity.set(System.nanoTime())
    }

    private fun connectForReporting(runId: String, pwd: Path): ReportingConnection {
        val future = CompletableFuture<ReportingConnection>()
        Thread({
            try {
                val api = daemonConnector()
                val session = api.createSession(
                    CreateSessionRequest(
                        clientName = "nsrun",
                        requestedSourceName = runId,
                        metadata = mapOf("pwd" to pwd.toString()),
                    ),
                )
                val connection = ReportingConnection(api, session)
                if (!future.complete(connection)) {
                    // A timed-out setup may eventually return. Do not leave an unreachable durable
                    // local session behind when that happens.
                    runCatching { api.closeSession(session) }
                }
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }, "nsrun-reporting-setup").apply { isDaemon = true }.start()

        return try {
            future.get(reportingSetupTimeout.toMillis().coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            if (!future.complete(ReportingConnection.OFFLINE)) {
                return runCatching { future.getNow(ReportingConnection.OFFLINE) }
                    .getOrDefault(ReportingConnection.OFFLINE)
            }
            warn("NotiSync reporting setup timed out after ${reportingSetupTimeout.toMillis()} ms; command continues offline")
            ReportingConnection.OFFLINE
        } catch (error: ExecutionException) {
            warn("NotiSync reporting unavailable: ${error.cause?.message ?: error.message}")
            ReportingConnection.OFFLINE
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            warn("NotiSync reporting setup was interrupted; command continues offline")
            ReportingConnection.OFFLINE
        }
    }

    private fun notificationCoordinator(
        options: RunOptions,
        pwd: Path,
        reporter: Reporter,
        session: SessionResponse?,
        generator: ContentGenerator?,
        tree: String,
    ) = RunNotificationCoordinator(
        sessionId = session?.sessionId ?: "offline",
        argv = options.command,
        pwd = pwd,
        publish = reporter::post,
        generator = generator,
        tree = tree,
        llmShutdownTimeoutSeconds = (options.config.llm?.timeoutSeconds?.toLong() ?: 8L) + 1,
    )

    private fun warn(message: String) {
        synchronized(stderr) { stderr.appendLine("nsrun: $message") }
    }

    private fun daemonThread(name: String, body: () -> Unit): Thread =
        Thread({ runCatching(body).onFailure { warn("$name: ${it.message}") } }, name).apply { isDaemon = true }

    private fun joinPreservingInterrupt(thread: Thread, timeoutMillis: Long) {
        try {
            thread.join(timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private data class ReportingConnection(
        val api: DaemonLocalApi?,
        val session: SessionResponse?,
    ) {
        companion object {
            val OFFLINE = ReportingConnection(null, null)
        }
    }

    private class ChildInput(private val output: OutputStream) : AutoCloseable {
        private var closed = false

        @Synchronized
        fun write(bytes: ByteArray, length: Int) {
            if (closed) return
            output.write(bytes, 0, length)
            output.flush()
        }

        fun write(text: String) = write(text.encodeToByteArray(), text.encodeToByteArray().size)

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            runCatching { output.close() }
        }
    }

    private object NoTerminalController : TerminalController {
        override fun enterRawMode() = Unit
        override fun size() = null
        override fun close() = Unit
    }

    private class Reporter(
        private val api: DaemonLocalApi?,
        private val session: SessionResponse?,
        private val warning: (String) -> Unit,
    ) {
        private val warned = AtomicBoolean()

        fun post(request: net.extrawdw.notisync.localapi.NotificationRequest) {
            val currentApi = api ?: return
            val currentSession = session ?: return
            runCatching { currentApi.postNotification(currentSession, request) }.onFailure(::warnOnce)
        }

        fun openEvents(): EventStream? {
            val currentApi = api ?: return null
            val currentSession = session ?: return null
            return currentApi.openEvents(currentSession)
        }

        fun ack(id: String) {
            val currentApi = api ?: return
            val currentSession = session ?: return
            runCatching { currentApi.acknowledgeEvent(currentSession, id) }.onFailure(::warnOnce)
        }

        fun close() {
            val currentApi = api ?: return
            val currentSession = session ?: return
            runCatching { currentApi.closeSession(currentSession) }.onFailure(::warnOnce)
        }

        private fun warnOnce(error: Throwable) {
            if (warned.compareAndSet(false, true)) warning("NotiSync reporting failed: ${error.message}")
        }
    }

    private companion object {
        const val INPUT_POLL_MILLIS = 20L
        const val END_OF_TRANSMISSION = 0x04
        const val INPUT_CLOSE_GRACE_MILLIS = 250L
        const val OUTPUT_DRAIN_GRACE_MILLIS = 750L
        const val OUTPUT_CLOSE_GRACE_MILLIS = 250L
        const val OUTPUT_GATE_GRACE_MILLIS = 250L
    }
}
