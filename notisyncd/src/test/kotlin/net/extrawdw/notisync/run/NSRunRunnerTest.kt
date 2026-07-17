package net.extrawdw.notisync.run

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.EventStream
import net.extrawdw.notisync.desktop.config.NSRunConfig
import net.extrawdw.notisync.desktop.config.PtyMode
import net.extrawdw.notisync.localapi.AcceptedResponse
import net.extrawdw.notisync.localapi.CreateSessionRequest
import net.extrawdw.notisync.localapi.DaemonConnectionState
import net.extrawdw.notisync.localapi.DaemonStatus
import net.extrawdw.notisync.localapi.LocalEvent
import net.extrawdw.notisync.localapi.LocalEventType
import net.extrawdw.notisync.localapi.LocalApiJson
import net.extrawdw.notisync.localapi.NotificationPhase
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.localapi.SessionResponse
import net.extrawdw.notisync.run.process.ChildProcessLauncher
import net.extrawdw.notisync.run.process.ChildSignal
import net.extrawdw.notisync.run.process.ManagedChild
import net.extrawdw.notisync.run.process.ProcessHandleInspector
import net.extrawdw.notisync.run.process.TerminalController
import net.extrawdw.notisync.run.process.TerminalSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.serialization.decodeFromString
import net.extrawdw.notisync.run.logging.RunLogRecord

class NSRunRunnerTest {
    @Test
    fun `external child group termination restores PTY terminal before runner returns`() {
        val root = Files.createTempDirectory("nsrun-external-signal").toRealPath()
        val child = ExternallyCompletingPtyChild()
        val terminal = RecordingTerminal()
        val stdin = DelayedInput()
        val result = AtomicInteger(Int.MIN_VALUE)
        val runner = NSRunRunner(
            paths = DesktopPaths(root),
            daemonConnector = { error("offline") },
            childLauncher = ChildProcessLauncher { _, _, _, _ -> child },
            terminalFactory = { terminal },
            stdinTerminalAttached = { true },
            stdin = stdin,
            stderr = StringBuilder(),
            reportingSetupTimeout = Duration.ofMillis(75),
        )
        val runnerThread = Thread({ result.set(runner.run(defaultOptions(PtyMode.ALWAYS))) }, "runner-test")

        runnerThread.start()
        assertTrue(terminal.entered.await(2, TimeUnit.SECONDS))
        assertTrue(stdin.polled.await(2, TimeUnit.SECONDS))
        child.terminateExternally()
        runnerThread.join(4_000)
        stdin.makeReadable()
        Thread.sleep(50)

        assertFalse(runnerThread.isAlive)
        assertEquals(143, result.get())
        assertEquals(1, terminal.closeCount.get())
        assertTrue(terminal.closed.get())
        assertEquals(0, stdin.readCount.get())
    }

    @Test
    fun `PTY output is quiesced before terminal restoration`() {
        val root = Files.createTempDirectory("nsrun-output-recovery-order").toRealPath()
        val events = mutableListOf<String>()
        val child = BlockingOutputPtyChild(events)
        val terminal = RecordingTerminal(onClose = { synchronized(events) { events += "terminal-restored" } })
        val runner = NSRunRunner(
            paths = DesktopPaths(root),
            daemonConnector = { error("offline") },
            childLauncher = ChildProcessLauncher { _, _, _, _ -> child },
            terminalFactory = { terminal },
            stderr = StringBuilder(),
            reportingSetupTimeout = Duration.ofMillis(75),
        )

        assertEquals(143, runner.run(defaultOptions(PtyMode.ALWAYS)))

        val snapshot = synchronized(events) { events.toList() }
        assertTrue(snapshot.indexOf("output-closed") >= 0)
        assertTrue(snapshot.indexOf("output-closed") < snapshot.indexOf("terminal-restored"))
    }

    @Test
    fun `PTY final teardown drains before closing its shared master through stdin`() {
        val root = Files.createTempDirectory("nsrun-shared-pty-master").toRealPath()
        val events = mutableListOf<String>()
        val child = SharedMasterPtyChild(events)
        val stdout = ByteArrayOutputStream()
        val runner = NSRunRunner(
            paths = DesktopPaths(root),
            daemonConnector = { error("offline") },
            childLauncher = ChildProcessLauncher { _, _, _, _ -> child },
            terminalFactory = { RecordingTerminal() },
            stdin = ByteArrayInputStream(ByteArray(0)),
            stdout = stdout,
            stderr = StringBuilder(),
            reportingSetupTimeout = Duration.ofMillis(75),
        )

        assertEquals(143, runner.run(defaultOptions(PtyMode.ALWAYS)))

        assertEquals("\u001B[?1049hfull screen\u001B[?1049l", stdout.toString(Charsets.UTF_8))
        val snapshot = synchronized(events) { events.toList() }
        assertTrue(snapshot.indexOf("teardown-output") < snapshot.indexOf("master-closed"))
    }

    @Test
    fun `blocked stdout cannot prevent terminal restoration`() {
        val root = Files.createTempDirectory("nsrun-blocked-stdout").toRealPath()
        val stdout = BlockingOutput()
        val child = StdoutBlockingPtyChild(stdout.started)
        val terminal = RecordingTerminal()
        val result = AtomicInteger(Int.MIN_VALUE)
        val runner = NSRunRunner(
            paths = DesktopPaths(root),
            daemonConnector = { error("offline") },
            childLauncher = ChildProcessLauncher { _, _, _, _ -> child },
            terminalFactory = { terminal },
            stdin = ByteArrayInputStream(ByteArray(0)),
            stdout = stdout,
            stderr = StringBuilder(),
            reportingSetupTimeout = Duration.ofMillis(75),
        )
        val runnerThread = Thread({ result.set(runner.run(defaultOptions(PtyMode.ALWAYS))) }, "blocked-output-runner")

        try {
            runnerThread.start()
            assertTrue(stdout.started.await(2, TimeUnit.SECONDS))
            runnerThread.join(4_000)
            assertFalse(runnerThread.isAlive)
            assertEquals(143, result.get())
            assertTrue(terminal.closed.get())
        } finally {
            stdout.release.countDown()
            runnerThread.join(1_000)
        }
    }

    @Test
    fun `failure after raw mode still restores terminal and terminates child`() {
        val root = Files.createTempDirectory("nsrun-setup-failure-recovery").toRealPath()
        val child = SignalCompletingChild(usesPty = true)
        val terminal = RecordingTerminal(failSize = true)
        val runner = NSRunRunner(
            paths = DesktopPaths(root),
            daemonConnector = { error("must not connect") },
            childLauncher = ChildProcessLauncher { _, _, _, _ -> child },
            terminalFactory = { terminal },
            stderr = StringBuilder(),
        )

        val failure = runCatching { runner.run(defaultOptions(PtyMode.ALWAYS)) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(ChildSignal.TERMINATE), child.signals)
        assertEquals(1, terminal.closeCount.get())
    }

    @Test
    fun `piped stdin EOF is delivered to a non-PTY child`() {
        val root = Files.createTempDirectory("nsrun-piped-stdin").toRealPath()
        val child = EofWaitingChild()
        val runner = NSRunRunner(
            paths = DesktopPaths(root),
            daemonConnector = { error("offline") },
            childLauncher = ChildProcessLauncher { _, _, _, _ -> child },
            terminalFactory = { NoTerminal },
            stdinTerminalAttached = { false },
            stdin = ByteArrayInputStream("piped input".encodeToByteArray()),
            stdout = ByteArrayOutputStream(),
            stderr = StringBuilder(),
            reportingSetupTimeout = Duration.ofMillis(75),
        )

        assertEquals(0, runner.run(defaultOptions(PtyMode.NEVER)))
        assertEquals("piped input", child.received.toString(Charsets.UTF_8))
    }

    @Test
    fun `runner preserves exit code output and terminal notification`() {
        val root = Files.createTempDirectory("nsrun-runner").toRealPath()
        val daemon = RecordingDaemon()
        val child = FakeChild("half 50%\ndone\n", 7)
        val output = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(output, true, Charsets.UTF_8))
        try {
            val runner = NSRunRunner(
                paths = DesktopPaths(root),
                daemonConnector = { daemon },
                processInspector = ProcessHandleInspector(),
                childLauncher = ChildProcessLauncher { _, _, _, _ -> child },
                terminalFactory = { NoTerminal },
                stderr = StringBuilder(),
            )
            val code = runner.run(
                RunOptions(
                    listOf("fake"), false, Duration.ofSeconds(30), Duration.ofMinutes(5), PtyMode.NEVER,
                    NSRunConfig(),
                ),
            )
            assertEquals(7, code)
            assertEquals("half 50%\ndone\n", output.toString(Charsets.UTF_8))
            assertEquals(NotificationPhase.INITIAL, daemon.notifications.first().phase)
            assertEquals(NotificationPhase.COMPLETED, daemon.notifications.last().phase)
            assertTrue(Files.walk(root.resolve("runs")).use { files ->
                files.anyMatch { it.fileName.toString() == "run.ndjson" }
            })
        } finally {
            System.setOut(original)
        }
    }

    @Test
    fun `child launches and drains before wedged daemon reporting times out`() {
        val root = Files.createTempDirectory("nsrun-wedged-daemon").toRealPath()
        val launched = CountDownLatch(1)
        val never = CountDownLatch(1)
        val child = FakeChild("started\n", 0)
        val runner = NSRunRunner(
            paths = DesktopPaths(root),
            daemonConnector = {
                assertEquals(0L, launched.count)
                never.await()
                error("unreachable")
            },
            childLauncher = ChildProcessLauncher { _, _, _, _ ->
                launched.countDown()
                child
            },
            terminalFactory = { NoTerminal },
            stderr = StringBuilder(),
            reportingSetupTimeout = Duration.ofMillis(75),
        )

        val started = System.nanoTime()
        assertEquals(0, runner.run(defaultOptions()))
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(1))
    }

    @Test
    fun `event stream reconnects after EOF while child remains alive`() {
        val root = Files.createTempDirectory("nsrun-event-reconnect").toRealPath()
        val child = SignalCompletingChild()
        val opens = AtomicInteger()
        val daemon = RecordingDaemon(eventFactory = {
            val attempt = opens.incrementAndGet()
            object : EventStream {
                private var delivered = false
                override fun next(): LocalEvent? {
                    if (attempt == 1 || delivered) return null
                    delivered = true
                    return LocalEvent(
                        "event", LocalEventType.ACTION, "session", System.currentTimeMillis(),
                        generation = 1, actionId = "signal-term",
                    )
                }
                override fun close() = Unit
            }
        })
        val runner = NSRunRunner(
            paths = DesktopPaths(root),
            daemonConnector = { daemon },
            childLauncher = ChildProcessLauncher { _, _, _, _ -> child },
            terminalFactory = { NoTerminal },
            stderr = StringBuilder(),
        )

        assertEquals(0, runner.run(defaultOptions()))
        assertTrue(opens.get() >= 2)
        assertEquals(listOf(ChildSignal.TERMINATE), child.signals)
    }

    @Test
    fun `old process signal remains valid while old contextual input is ignored`() {
        val root = Files.createTempDirectory("nsrun-session-signal").toRealPath()
        val child = SignalCompletingChild()
        val events = listOf(
            LocalEvent(
                "old-input", LocalEventType.ACTION, "session", System.currentTimeMillis(),
                generation = 0, actionId = "input", inputText = "stale input",
            ),
            LocalEvent(
                "old-signal", LocalEventType.ACTION, "session", System.currentTimeMillis(),
                generation = 0, actionId = "signal-term",
            ),
        ).iterator()
        val daemon = RecordingDaemon(eventFactory = {
            object : EventStream {
                override fun next(): LocalEvent? = if (events.hasNext()) events.next() else null
                override fun close() = Unit
            }
        })
        val runner = NSRunRunner(
            paths = DesktopPaths(root),
            daemonConnector = { daemon },
            childLauncher = ChildProcessLauncher { _, _, _, _ -> child },
            terminalFactory = { NoTerminal },
            stdinTerminalAttached = { true },
            stdin = DelayedInput(),
            stdout = ByteArrayOutputStream(),
            stderr = StringBuilder(),
        )

        assertEquals(0, runner.run(defaultOptions()))
        assertEquals(listOf(ChildSignal.TERMINATE), child.signals)
        assertEquals("", child.input.toString(Charsets.UTF_8))
    }

    @Test
    fun `remote input echo is visible locally but excluded from logs and notification content`() {
        val root = Files.createTempDirectory("nsrun-remote-input-redaction").toRealPath()
        val secret = "correct horse battery staple"
        val child = EchoCompletingChild(secret)
        val daemon = RecordingDaemon(eventFactory = {
            object : EventStream {
                private var delivered = false
                override fun next(): LocalEvent? {
                    if (delivered) return null
                    delivered = true
                    return LocalEvent(
                        "event", LocalEventType.ACTION, "session", System.currentTimeMillis(),
                        generation = 1, actionId = "input", inputText = secret,
                    )
                }
                override fun close() = Unit
            }
        })
        val output = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(output, true, Charsets.UTF_8))
        try {
            val runner = NSRunRunner(
                paths = DesktopPaths(root),
                daemonConnector = { daemon },
                childLauncher = ChildProcessLauncher { _, _, _, _ -> child },
                terminalFactory = { NoTerminal },
                stdinTerminalAttached = { true },
                stdin = DelayedInput(),
                stderr = StringBuilder(),
            )
            assertEquals(0, runner.run(defaultOptions()))
        } finally {
            System.setOut(originalOut)
        }

        assertTrue(output.toString(Charsets.UTF_8).contains(secret))
        val logPath = Files.walk(root.resolve("runs")).use { files ->
            files.filter { it.fileName.toString() == "run.ndjson" }.findFirst().orElseThrow()
        }
        val captured = Files.readAllLines(logPath)
            .map { LocalApiJson.decodeFromString<RunLogRecord>(it) }
            .mapNotNull(RunLogRecord::outputBase64)
            .flatMap { Base64.getDecoder().decode(it).asIterable() }
            .toByteArray()
            .toString(Charsets.UTF_8)
        assertFalse(captured.contains(secret))
        assertTrue(captured.contains("[remote input redacted]"))
        assertTrue(daemon.notifications.none { request ->
            request.text.contains(secret) || request.expandedText.orEmpty().contains(secret)
        })
    }

    @Test
    fun `failed event ack is retried without performing the child action twice`() {
        val root = Files.createTempDirectory("nsrun-action-ack-retry").toRealPath()
        val child = AckCompletingChild()
        val ackAttempts = AtomicInteger()
        val event = LocalEvent(
            "durable-action", LocalEventType.ACTION, "session", System.currentTimeMillis(),
            generation = 1, actionId = "yes",
        )
        val daemon = RecordingDaemon(
            eventFactory = {
                object : EventStream {
                    private var delivered = false
                    override fun next(): LocalEvent? = if (delivered) null else event.also { delivered = true }
                    override fun close() = Unit
                }
            },
            acknowledge = {
                if (ackAttempts.incrementAndGet() == 1) error("simulated ACK loss")
                child.complete()
            },
        )
        val runner = NSRunRunner(
            paths = DesktopPaths(root),
            daemonConnector = { daemon },
            childLauncher = ChildProcessLauncher { _, _, _, _ -> child },
            terminalFactory = { NoTerminal },
            stdinTerminalAttached = { true },
            stdin = DelayedInput(),
            stderr = StringBuilder(),
        )

        assertEquals(0, runner.run(defaultOptions()))
        assertTrue(ackAttempts.get() >= 2)
        assertEquals("y\n", child.inputBytes.toString(Charsets.UTF_8))
    }

    private fun defaultOptions(ptyMode: PtyMode = PtyMode.NEVER) = RunOptions(
        listOf("fake"), false, Duration.ofSeconds(30), Duration.ofMinutes(5), ptyMode, NSRunConfig(),
    )

    private class RecordingDaemon(
        private val eventFactory: () -> EventStream = {
            object : EventStream {
                override fun next(): LocalEvent? = null
                override fun close() = Unit
            }
        },
        private val acknowledge: (String) -> Unit = {},
    ) : DaemonLocalApi {
        val notifications = mutableListOf<NotificationRequest>()
        private val session = SessionResponse("session", "source", "bearer", true)
        override fun status() = DaemonStatus("1", connectionState = DaemonConnectionState.CONNECTED)
        override fun createSession(request: CreateSessionRequest) = session
        override fun closeSession(session: SessionResponse) = Unit
        override fun postNotification(session: SessionResponse, request: NotificationRequest): AcceptedResponse {
            notifications += request
            return AcceptedResponse(request.generation.toString(), System.currentTimeMillis())
        }
        override fun openEvents(session: SessionResponse): EventStream = eventFactory()
        override fun acknowledgeEvent(session: SessionResponse, eventId: String) = acknowledge(eventId)
    }

    private class FakeChild(output: String, private val code: Int) : ManagedChild {
        private val alive = AtomicBoolean(true)
        override val pid = ProcessHandle.current().pid()
        override val input = ByteArrayOutputStream()
        override val mergedOutput = ByteArrayInputStream(output.encodeToByteArray())
        override val usesPty = false
        override fun waitFor(): Int = code.also { alive.set(false) }
        override fun isAlive(): Boolean = alive.get()
        override fun resize(columns: Int, rows: Int) = Unit
        override fun signal(signal: ChildSignal) = Unit
        override fun close() = Unit
    }

    private class SignalCompletingChild(override val usesPty: Boolean = false) : ManagedChild {
        private val done = CountDownLatch(1)
        private val alive = AtomicBoolean(true)
        val signals = mutableListOf<ChildSignal>()
        override val pid = ProcessHandle.current().pid()
        override val input = ByteArrayOutputStream()
        override val mergedOutput = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            check(done.await(3, TimeUnit.SECONDS)) { "remote signal was not delivered" }
            return 0
        }
        override fun isAlive() = alive.get()
        override fun resize(columns: Int, rows: Int) = Unit
        override fun signal(signal: ChildSignal) {
            signals += signal
            alive.set(false)
            done.countDown()
        }
        override fun close() = Unit
    }

    private class ExternallyCompletingPtyChild : ManagedChild {
        private val done = CountDownLatch(1)
        private val alive = AtomicBoolean(true)
        private val code = AtomicInteger()
        override val pid = ProcessHandle.current().pid()
        override val input = ByteArrayOutputStream()
        override val mergedOutput = ByteArrayInputStream("\u001B[?1049hfull screen".encodeToByteArray())
        override val usesPty = true
        override fun waitFor(): Int {
            check(done.await(3, TimeUnit.SECONDS)) { "external signal was not observed" }
            return code.get()
        }
        override fun isAlive() = alive.get()
        override fun resize(columns: Int, rows: Int) = Unit
        override fun signal(signal: ChildSignal) = Unit
        override fun close() = Unit
        fun terminateExternally() {
            code.set(143)
            alive.set(false)
            done.countDown()
        }
    }

    private class EofWaitingChild : ManagedChild {
        private val eof = CountDownLatch(1)
        private val alive = AtomicBoolean(true)
        val received = ByteArrayOutputStream()
        override val pid = ProcessHandle.current().pid()
        override val input: OutputStream = object : OutputStream() {
            private val closed = AtomicBoolean()
            override fun write(value: Int) {
                received.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                received.write(bytes, offset, length)
            }
            override fun close() {
                if (closed.compareAndSet(false, true)) eof.countDown()
            }
        }
        override val mergedOutput = ByteArrayInputStream(ByteArray(0))
        override val usesPty = false
        override fun waitFor(): Int {
            check(eof.await(2, TimeUnit.SECONDS)) { "piped EOF was not delivered" }
            alive.set(false)
            return 0
        }
        override fun isAlive() = alive.get()
        override fun resize(columns: Int, rows: Int) = Unit
        override fun signal(signal: ChildSignal) = Unit
        override fun close() = input.close()
    }

    private class BlockingOutputPtyChild(private val events: MutableList<String>) : ManagedChild {
        private val alive = AtomicBoolean(true)
        private val emitted = CountDownLatch(1)
        private val output = object : java.io.InputStream() {
            private val closed = AtomicBoolean()
            private var first = true
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (first) {
                    first = false
                    bytes[offset] = 'x'.code.toByte()
                    synchronized(events) { events += "output" }
                    emitted.countDown()
                    return 1
                }
                while (!closed.get()) Thread.sleep(10)
                return -1
            }
            override fun close() {
                closed.set(true)
                synchronized(events) { events += "output-closed" }
            }
        }
        override val pid = ProcessHandle.current().pid()
        override val input = ByteArrayOutputStream()
        override val mergedOutput = output
        override val usesPty = true
        override fun waitFor(): Int {
            check(emitted.await(2, TimeUnit.SECONDS)) { "PTY output was not proxied" }
            alive.set(false)
            return 143
        }
        override fun isAlive() = alive.get()
        override fun resize(columns: Int, rows: Int) = Unit
        override fun signal(signal: ChildSignal) = Unit
        override fun close() = output.close()
    }

    private class StdoutBlockingPtyChild(private val stdoutStarted: CountDownLatch) : ManagedChild {
        private val alive = AtomicBoolean(true)
        override val pid = ProcessHandle.current().pid()
        override val input = ByteArrayOutputStream()
        override val mergedOutput = ByteArrayInputStream("blocked output".encodeToByteArray())
        override val usesPty = true
        override fun waitFor(): Int {
            check(stdoutStarted.await(2, TimeUnit.SECONDS)) { "stdout write did not start" }
            alive.set(false)
            return 143
        }
        override fun isAlive() = alive.get()
        override fun resize(columns: Int, rows: Int) = Unit
        override fun signal(signal: ChildSignal) = Unit
        override fun close() = Unit
    }

    private class SharedMasterPtyChild(private val events: MutableList<String>) : ManagedChild {
        private val alive = AtomicBoolean(true)
        private val initialOutputRead = CountDownLatch(1)
        private val childExited = CountDownLatch(1)
        private val masterClosed = AtomicBoolean()
        private val inputStream = object : java.io.InputStream() {
            private var part = 0
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = when (part++) {
                0 -> copy("\u001B[?1049hfull screen", bytes, offset).also { initialOutputRead.countDown() }
                1 -> {
                    check(childExited.await(2, TimeUnit.SECONDS)) { "child did not exit" }
                    if (masterClosed.get()) -1 else copy("\u001B[?1049l", bytes, offset).also {
                        synchronized(events) { events += "teardown-output" }
                    }
                }
                else -> -1
            }
            private fun copy(text: String, target: ByteArray, offset: Int): Int {
                val encoded = text.encodeToByteArray()
                encoded.copyInto(target, offset)
                return encoded.size
            }
        }
        override val pid = ProcessHandle.current().pid()
        override val input: OutputStream = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun close() {
                if (masterClosed.compareAndSet(false, true)) {
                    synchronized(events) { events += "master-closed" }
                }
            }
        }
        override val mergedOutput = inputStream
        override val usesPty = true
        override fun waitFor(): Int {
            check(initialOutputRead.await(2, TimeUnit.SECONDS)) { "initial PTY output was not read" }
            alive.set(false)
            childExited.countDown()
            return 143
        }
        override fun isAlive() = alive.get()
        override fun resize(columns: Int, rows: Int) = Unit
        override fun signal(signal: ChildSignal) = Unit
        override fun close() = input.close()
    }

    private class BlockingOutput : OutputStream() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun write(value: Int) = block()
        override fun write(bytes: ByteArray, offset: Int, length: Int) = block()
        private fun block() {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "blocked stdout was not released" }
        }
    }

    private class DelayedInput : java.io.InputStream() {
        val polled = CountDownLatch(1)
        val readCount = AtomicInteger()
        private val readable = AtomicBoolean()
        override fun available(): Int {
            polled.countDown()
            return if (readable.get()) 1 else 0
        }
        override fun read(): Int {
            readCount.incrementAndGet()
            return 'x'.code
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            readCount.incrementAndGet()
            bytes[offset] = 'x'.code.toByte()
            return 1
        }
        fun makeReadable() {
            readable.set(true)
        }
    }

    private class RecordingTerminal(
        private val failSize: Boolean = false,
        private val onClose: () -> Unit = {},
    ) : TerminalController {
        val entered = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val closed = AtomicBoolean()
        override fun enterRawMode() {
            entered.countDown()
        }
        override fun size(): TerminalSize? {
            if (failSize) error("simulated terminal-size failure")
            return TerminalSize(80, 24)
        }
        override fun close() {
            closeCount.incrementAndGet()
            closed.set(true)
            onClose()
        }
    }

    private class EchoCompletingChild(private val secret: String) : ManagedChild {
        private val done = CountDownLatch(1)
        private val alive = AtomicBoolean(true)
        private val outputWriter = PipedOutputStream()
        override val pid = ProcessHandle.current().pid()
        override val mergedOutput = PipedInputStream(outputWriter)
        override val usesPty = false
        override val input: OutputStream = object : OutputStream() {
            private val received = ByteArrayOutputStream()
            override fun write(value: Int) {
                received.write(value)
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                received.write(bytes, offset, length)
            }
            override fun flush() {
                val bytes = received.toByteArray()
                if (!bytes.toString(Charsets.UTF_8).contains(secret)) return
                val split = secret.encodeToByteArray().size / 2
                val echoed = ("echo: " + secret + "\r\n").encodeToByteArray()
                val boundary = "echo: ".encodeToByteArray().size + split
                outputWriter.write(echoed, 0, boundary)
                outputWriter.flush()
                outputWriter.write(echoed, boundary, echoed.size - boundary)
                outputWriter.close()
                alive.set(false)
                done.countDown()
            }
        }
        override fun waitFor(): Int {
            check(done.await(3, TimeUnit.SECONDS)) { "remote input was not delivered" }
            return 0
        }
        override fun isAlive() = alive.get()
        override fun resize(columns: Int, rows: Int) = Unit
        override fun signal(signal: ChildSignal) = Unit
        override fun close() = Unit
    }

    private class AckCompletingChild : ManagedChild {
        private val done = CountDownLatch(1)
        private val alive = AtomicBoolean(true)
        val inputBytes = ByteArrayOutputStream()
        override val pid = ProcessHandle.current().pid()
        override val input: OutputStream = inputBytes
        override val mergedOutput = ByteArrayInputStream(ByteArray(0))
        override val usesPty = false
        override fun waitFor(): Int {
            check(done.await(3, TimeUnit.SECONDS)) { "event ACK was not retried" }
            alive.set(false)
            return 0
        }
        override fun isAlive() = alive.get()
        override fun resize(columns: Int, rows: Int) = Unit
        override fun signal(signal: ChildSignal) = Unit
        override fun close() = Unit
        fun complete() = done.countDown()
    }

    private object NoTerminal : TerminalController {
        override fun enterRawMode() = Unit
        override fun size() = null
        override fun close() = Unit
    }
}
