package net.extrawdw.notisync.screen.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHelperBridgeTest {
    @Test(timeout = 5_000)
    fun `remote video EOF is half-closed to helper and cannot deadlock cleanup`() {
        val marker = Files.createTempFile("notisync-helper-marker-", ".txt")
        Files.deleteIfExists(marker)
        val controlInput = PipedInputStream()
        val controlWriter = PipedOutputStream(controlInput)
        val closes = AtomicInteger()
        val bridge = bridge("eof", marker)
        try {
            bridge.runStreams(
                NativeBridgeStreams(
                    videoInput = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                    controlInput = controlInput,
                    controlOutput = ByteArrayOutputStream(),
                    controlEnabled = true,
                    clipboardEnabled = true,
                    closeSession = {
                        closes.incrementAndGet()
                        runCatching { controlInput.close() }
                        runCatching { controlWriter.close() }
                    },
                ),
                ScreenMirrorCodec.H264,
                "test",
            )

            assertEquals("eof", Files.readString(marker))
            assertTrue(closes.get() >= 1)
        } finally {
            controlWriter.close()
            controlInput.close()
            Files.deleteIfExists(marker)
        }
    }

    @Test(timeout = 5_000)
    fun `helper exit before IPC connection is reported promptly`() {
        val marker = Files.createTempFile("notisync-helper-marker-", ".txt")
        val error = try {
            assertThrows(IllegalStateException::class.java) {
                bridge("exit", marker).runStreams(
                    NativeBridgeStreams(
                        videoInput = ByteArrayInputStream(byteArrayOf()),
                        controlInput = ByteArrayInputStream(byteArrayOf()),
                        controlOutput = ByteArrayOutputStream(),
                        controlEnabled = true,
                        clipboardEnabled = true,
                        closeSession = {},
                    ),
                    ScreenMirrorCodec.H264,
                    "test",
                )
            }
        } finally {
            Files.deleteIfExists(marker)
        }
        assertTrue(error.message.orEmpty().contains("status 23"))
    }

    @Test(timeout = 5_000)
    fun `unauthenticated first connectors are rejected without stealing helper channels`() {
        val marker = Files.createTempFile("notisync-helper-marker-", ".txt")
        Files.deleteIfExists(marker)
        try {
            bridge("race", marker).runStreams(
                NativeBridgeStreams(
                    videoInput = ByteArrayInputStream(byteArrayOf(1)),
                    controlInput = ByteArrayInputStream(byteArrayOf()),
                    controlOutput = ByteArrayOutputStream(),
                    controlEnabled = true,
                    clipboardEnabled = true,
                    closeSession = {},
                ),
                ScreenMirrorCodec.H264,
                "test",
            )
            assertEquals("eof", Files.readString(marker))
        } finally {
            Files.deleteIfExists(marker)
        }
    }

    @Test(timeout = 5_000)
    fun `session feature flags are passed explicitly to helper`() {
        val marker = Files.createTempFile("notisync-helper-marker-", ".txt")
        val captured = AtomicReference<List<String>>()
        try {
            assertThrows(IllegalStateException::class.java) {
                bridge("exit", marker, captured::set).runStreams(
                    NativeBridgeStreams(
                        videoInput = ByteArrayInputStream(byteArrayOf()),
                        controlInput = ByteArrayInputStream(byteArrayOf()),
                        controlOutput = ByteArrayOutputStream(),
                        controlEnabled = false,
                        clipboardEnabled = true,
                        closeSession = {},
                    ),
                    ScreenMirrorCodec.H264,
                    "test",
                )
            }

            assertEquals("false", argument(captured.get(), "--control"))
            assertEquals("true", argument(captured.get(), "--clipboard"))
        } finally {
            Files.deleteIfExists(marker)
        }
    }

    private fun bridge(
        mode: String,
        marker: Path,
        inspectCommand: (List<String>) -> Unit = {},
    ): NativeHelperBridge = NativeHelperBridge(
        executableResolver = { javaExecutable() },
        launcher = NativeHelperLauncher {
            inspectCommand(it)
            helperCommand(it, mode, marker).let(::ProcessBuilder).start()
        },
        connectTimeout = Duration.ofSeconds(2),
        gracefulExitTimeout = Duration.ofSeconds(2),
    )

    private fun helperCommand(original: List<String>, mode: String, marker: Path): List<String> = listOf(
        javaExecutable().toString(),
        "-cp",
        Path.of(NativeHelperStub::class.java.protectionDomain.codeSource.location.toURI()).toString(),
        NativeHelperStub::class.java.name,
        mode,
        argument(original, "--video-socket"),
        argument(original, "--control-socket"),
        marker.toString(),
    )

    private fun argument(command: List<String>, name: String): String {
        val index = command.indexOf(name)
        require(index >= 0 && index + 1 < command.size)
        return command[index + 1]
    }

    private fun javaExecutable(): Path = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
    )
}
