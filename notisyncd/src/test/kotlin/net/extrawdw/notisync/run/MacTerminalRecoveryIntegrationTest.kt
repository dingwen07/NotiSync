package net.extrawdw.notisync.run

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.config.NSRunConfig
import net.extrawdw.notisync.desktop.config.PtyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MacTerminalRecoveryIntegrationTest {
    @Test
    fun `externally terminated real PTY restores termios and only outstanding presentation modes`() {
        assumeTrue(System.getProperty("os.name").contains("mac", ignoreCase = true))
        val root = Files.createTempDirectory("nsrun-real-terminal").toRealPath()
        val transcript = root.resolve("typescript")
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val process = ProcessBuilder(
            "/usr/bin/script", "-q", transcript.toString(),
            java.toString(), "-cp", System.getProperty("java.class.path"),
            MacTerminalRecoveryProbe::class.java.name, root.toString(),
        ).redirectErrorStream(true).start()
        try {
            val completed = process.waitFor(20, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            assertTrue("terminal recovery probe timed out", completed)
            val processOutput = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            assertEquals("probe output: $processOutput", 0, process.exitValue())

            val output = Files.readAllBytes(transcript).toString(Charsets.UTF_8)
            assertTrue(output, output.contains("PROBE_EXIT=143"))
            assertTrue(output, output.contains("PROBE_STTY_MATCH=true"))
            assertOrdered(output, "\u001B[?1049h", "\u001B[?1049l")
            assertOrdered(output, "\u001B[?25l", "\u001B[?25h")
            assertOrdered(output, "\u001B[?2004h", "\u001B[?2004l")
            assertOrdered(output, "\u001B[31m", "\u001B[0m")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun assertOrdered(output: String, enabled: String, recovered: String) {
        val enabledAt = output.indexOf(enabled)
        val recoveredAt = output.indexOf(recovered)
        assertTrue(output, enabledAt >= 0 && recoveredAt > enabledAt)
    }
}

/** Runs inside a real pseudo-terminal allocated by macOS script(1). */
object MacTerminalRecoveryProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val root = Path.of(arguments.single()).toRealPath()
        val before = sttyState()
        val childScript = buildString {
            append("printf '\\033[?1049h\\033[?25l\\033[?2004h\\033[31m'; ")
            append("trap 'exit 143' TERM; ")
            append("(sleep 0.15; kill -TERM ${'$'}${'$'}) & ")
            append("while :; do sleep 1; done")
        }
        val runner = NSRunRunner(
            paths = DesktopPaths(root),
            daemonConnector = { error("offline for integration probe") },
            reportingSetupTimeout = Duration.ofMillis(50),
        )
        val exitCode = runner.run(
            RunOptions(
                command = listOf("/bin/sh", "-c", childScript),
                llmEnabled = false,
                updateInterval = Duration.ofSeconds(30),
                stuckAfter = Duration.ofMinutes(5),
                ptyMode = PtyMode.ALWAYS,
                config = NSRunConfig(),
            ),
        )
        val after = sttyState()
        println("PROBE_EXIT=$exitCode")
        // Darwin may transiently add PENDIN while the restored canonical discipline reprocesses
        // typeahead. It is kernel state, clears on the next read, and must not be force-cleared.
        println("PROBE_STTY_MATCH=${withoutPendingInput(before) == withoutPendingInput(after)}")
        println("PROBE_STTY_BEFORE=$before")
        println("PROBE_STTY_AFTER=$after")
    }

    private fun sttyState(): String {
        val process = ProcessBuilder("/bin/stty", "-f", "/dev/tty", "-g")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
        check(process.waitFor() == 0) { "stty failed: $output" }
        return output
    }

    private fun withoutPendingInput(state: String): String = LFLAG.replace(state) { match ->
        val flags = match.groupValues[1].toLong(16) and PENDIN.inv()
        "lflag=${flags.toString(16)}"
    }

    private val LFLAG = Regex("lflag=([0-9a-fA-F]+)")
    private const val PENDIN = 0x20000000L
}
