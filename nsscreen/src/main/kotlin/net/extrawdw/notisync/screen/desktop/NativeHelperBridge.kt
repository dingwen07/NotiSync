package net.extrawdw.notisync.screen.desktop

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.screen.ScreenChannel
import net.extrawdw.notisync.screen.SecureChannelPair

fun interface NativeHelperLauncher {
    fun start(command: List<String>): Process
}

internal data class NativeBridgeStreams(
    val videoInput: InputStream,
    val controlInput: InputStream,
    val controlOutput: OutputStream,
    val controlEnabled: Boolean,
    val clipboardEnabled: Boolean,
    val closeSession: () -> Unit,
)

class NativeHelperBridge(
    private val executableResolver: () -> Path = ::resolveNativeHelper,
    private val launcher: NativeHelperLauncher = NativeHelperLauncher {
        ProcessBuilder(it)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    },
    private val connectTimeout: Duration = Duration.ofSeconds(10),
    private val gracefulExitTimeout: Duration = Duration.ofSeconds(1),
) {
    fun run(
        pair: SecureChannelPair,
        codec: ScreenMirrorCodec,
        title: String,
    ) {
        require(pair.video.channel == ScreenChannel.VIDEO) { "video channel is misbound" }
        require(pair.control.channel == ScreenChannel.CONTROL) { "control channel is misbound" }
        require(pair.video.descriptor == pair.control.descriptor) {
            "screen channels have different session descriptors"
        }
        val descriptor = pair.video.descriptor
        runStreams(
            NativeBridgeStreams(
                videoInput = pair.video.input,
                controlInput = pair.control.input,
                controlOutput = pair.control.output,
                controlEnabled = descriptor.controlEnabled,
                clipboardEnabled = descriptor.clipboardEnabled,
                closeSession = pair::close,
            ),
            codec,
            title,
        )
    }

    /** Visible to tests so EOF handling can be exercised without constructing a TLS peer. */
    internal fun runStreams(
        streams: NativeBridgeStreams,
        codec: ScreenMirrorCodec,
        title: String,
    ) {
        require(!connectTimeout.isNegative && !connectTimeout.isZero)
        require(!gracefulExitTimeout.isNegative)
        val directory = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "notisync-screen-")
        runCatching {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        }
        val videoPath = directory.resolve("video.sock")
        val controlPath = directory.resolve("control.sock")
        val videoServer = openUnixServer(videoPath)
        val controlServer = openUnixServer(controlPath)
        var process: Process? = null
        val video = AtomicReference<SocketChannel?>()
        val control = AtomicReference<SocketChannel?>()
        var shutdownHook: Thread? = null
        var hookInstalled = false
        val helperChallenge = ByteArray(HELPER_CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
        try {
            val command = listOf(
                executableResolver().toString(),
                "--video-socket", videoPath.toString(),
                "--control-socket", controlPath.toString(),
                "--codec", codec.name.lowercase(),
                "--control", streams.controlEnabled.toString(),
                "--clipboard", streams.clipboardEnabled.toString(),
                "--title", title.take(160),
                "--max-clipboard-bytes", MAX_CLIPBOARD_BYTES.toString(),
            )
            val startedProcess = launcher.start(command)
            process = startedProcess
            try {
                startedProcess.outputStream.use { challengePipe ->
                    challengePipe.write(helperChallenge)
                    challengePipe.flush()
                }
            } catch (error: IOException) {
                if (!startedProcess.isAlive) checkProcessExit(startedProcess.exitValue())
                throw IOException("could not authenticate the screen helper process", error)
            }
            shutdownHook = Thread(
                {
                    runCatching { streams.closeSession() }
                    runCatching { video.getAndSet(null)?.close() }
                    runCatching { control.getAndSet(null)?.close() }
                    runCatching { videoServer.close() }
                    runCatching { controlServer.close() }
                    stopProcess(startedProcess)
                },
                "nsscreen-helper-shutdown",
            )
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            hookInstalled = true
            val deadline = System.nanoTime() + connectTimeout.toNanos()
            video.set(
                acceptAuthenticatedConnection(
                    videoServer,
                    startedProcess,
                    HELPER_VIDEO_CHANNEL,
                    helperChallenge,
                    deadline,
                ),
            )
            control.set(
                acceptAuthenticatedConnection(
                    controlServer,
                    startedProcess,
                    HELPER_CONTROL_CHANNEL,
                    helperChallenge,
                    deadline,
                ),
            )
            relayUntilClosed(streams, requireNotNull(video.get()), requireNotNull(control.get()), startedProcess)
        } finally {
            helperChallenge.fill(0)
            if (hookInstalled) {
                runCatching { Runtime.getRuntime().removeShutdownHook(requireNotNull(shutdownHook)) }
            }
            // Close the network side first so blocked stream reads leave promptly, then stop the
            // helper and its private sockets. Every operation is deliberately idempotent.
            runCatching { streams.closeSession() }
            runCatching { video.getAndSet(null)?.close() }
            runCatching { control.getAndSet(null)?.close() }
            stopProcess(process)
            runCatching { videoServer.close() }
            runCatching { controlServer.close() }
            Files.deleteIfExists(videoPath)
            Files.deleteIfExists(controlPath)
            Files.deleteIfExists(directory)
        }
    }

    private fun relayUntilClosed(
        streams: NativeBridgeStreams,
        video: SocketChannel,
        control: SocketChannel,
        process: Process,
    ) {
        val executor = Executors.newFixedThreadPool(4)
        val completed = ExecutorCompletionService<RelayResult>(executor)
        try {
            completed.submit {
                copy(streams.videoInput, Channels.newOutputStream(video))
                // A half-close lets the helper drain and exit instead of leaving it blocked in read().
                video.shutdownOutput()
                RelayResult.StreamEnded("video input")
            }
            completed.submit {
                copy(streams.controlInput, Channels.newOutputStream(control))
                control.shutdownOutput()
                RelayResult.StreamEnded("control input")
            }
            completed.submit {
                copy(Channels.newInputStream(control), streams.controlOutput)
                streams.controlOutput.flush()
                streams.controlOutput.close()
                RelayResult.StreamEnded("helper control output")
            }
            completed.submit {
                RelayResult.ProcessExited(process.waitFor())
            }

            when (val first = completed.take().get()) {
                is RelayResult.ProcessExited -> checkProcessExit(first.status)
                is RelayResult.StreamEnded -> {
                    // Give a helper that observed the half-close a short window to unwind itself.
                    if (process.waitFor(gracefulExitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                        checkProcessExit(process.exitValue())
                    } else {
                        throw IOException("${first.name} closed while the screen helper was still running")
                    }
                }
            }
        } catch (error: ExecutionException) {
            throw unwrapExecutionException(error)
        } finally {
            runCatching { streams.closeSession() }
            runCatching { video.close() }
            runCatching { control.close() }
            stopProcess(process)
            executor.shutdownNow()
            runCatching { executor.awaitTermination(500, TimeUnit.MILLISECONDS) }
        }
    }

    private fun acceptAuthenticatedConnection(
        server: ServerSocketChannel,
        process: Process,
        channelId: Byte,
        challenge: ByteArray,
        deadlineNanos: Long,
    ): SocketChannel {
        server.configureBlocking(false)
        val expected = helperAuthenticationFrame(process.pid(), channelId, challenge)
        while (true) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) throw IOException("timed out waiting for screen helper IPC connection")
            if (!process.isAlive) checkProcessExit(process.exitValue())
            val candidate = server.accept()
            if (candidate == null) {
                Thread.sleep(CONNECTION_POLL.toMillis())
                continue
            }
            var authenticated = false
            try {
                candidate.configureBlocking(false)
                val received = ByteBuffer.allocate(expected.size)
                val candidateDeadline = minOf(
                    deadlineNanos,
                    System.nanoTime() + AUTHENTICATION_READ_TIMEOUT.toNanos(),
                )
                while (received.hasRemaining() && System.nanoTime() < candidateDeadline) {
                    val count = candidate.read(received)
                    if (count < 0) break
                    if (count == 0) Thread.sleep(CONNECTION_POLL.toMillis())
                }
                if (!received.hasRemaining() && MessageDigest.isEqual(expected, received.array())) {
                    candidate.configureBlocking(true)
                    authenticated = true
                    return candidate
                }
            } finally {
                if (!authenticated) runCatching { candidate.close() }
            }
        }
    }

    private fun helperAuthenticationFrame(pid: Long, channelId: Byte, challenge: ByteArray): ByteArray =
        ByteBuffer.allocate(HELPER_AUTH_FRAME_BYTES).apply {
            put(HELPER_AUTH_MAGIC)
            put(channelId)
            putLong(pid)
            put(challenge)
        }.array()

    private fun checkProcessExit(status: Int) {
        if (status != 0) throw IllegalStateException("screen helper exited with status $status")
    }

    private fun unwrapExecutionException(error: ExecutionException): Throwable {
        val cause = error.cause ?: error
        return when (cause) {
            is RuntimeException -> cause
            is IOException -> cause
            else -> IllegalStateException("screen helper relay failed", cause)
        }
    }

    private fun stopProcess(process: Process?) {
        if (process?.isAlive != true) return
        process.destroy()
        if (!runCatching { process.waitFor(PROCESS_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
            process.destroyForcibly()
            runCatching { process.waitFor(PROCESS_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) }
        }
    }

    private fun openUnixServer(path: Path): ServerSocketChannel =
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            try {
                bind(UnixDomainSocketAddress.of(path))
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (!Thread.currentThread().isInterrupted) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    private sealed interface RelayResult {
        data class StreamEnded(val name: String) : RelayResult
        data class ProcessExited(val status: Int) : RelayResult
    }

    private companion object {
        const val MAX_CLIPBOARD_BYTES = 256 * 1024
        const val HELPER_CHALLENGE_BYTES = 32
        const val HELPER_AUTH_FRAME_BYTES = 4 + 1 + Long.SIZE_BYTES + HELPER_CHALLENGE_BYTES
        val HELPER_AUTH_MAGIC = byteArrayOf('N'.code.toByte(), 'S'.code.toByte(), 'I'.code.toByte(), 'P'.code.toByte())
        const val HELPER_VIDEO_CHANNEL: Byte = 1
        const val HELPER_CONTROL_CHANNEL: Byte = 2
        val CONNECTION_POLL: Duration = Duration.ofMillis(10)
        val AUTHENTICATION_READ_TIMEOUT: Duration = Duration.ofMillis(250)
        val PROCESS_STOP_TIMEOUT: Duration = Duration.ofMillis(500)
    }
}

private fun resolveNativeHelper(): Path {
    System.getProperty("notisync.screenHelper")?.takeIf(String::isNotBlank)?.let(Path::of)?.let { configured ->
        require(Files.isExecutable(configured)) { "configured screen helper is not executable: $configured" }
        return configured
    }
    runCatching {
        val location = NativeHelperBridge::class.java.protectionDomain.codeSource.location.toURI()
        val codeSource = Path.of(location)
        val distribution = if (Files.isDirectory(codeSource)) codeSource else codeSource.parent?.parent
        distribution?.resolve("bin/notisync-screen-helper")?.takeIf(Files::isExecutable)
    }.getOrNull()?.let { return it }
    for (directory in System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar)) {
        if (directory.isBlank()) continue
        Path.of(directory).resolve("notisync-screen-helper").takeIf(Files::isExecutable)?.let { return it }
    }
    throw IllegalStateException(
        "notisync-screen-helper was not found; reinstall the desktop distribution or set -Dnotisync.screenHelper",
    )
}
