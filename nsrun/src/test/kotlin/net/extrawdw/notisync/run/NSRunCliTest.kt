package net.extrawdw.notisync.run

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import net.extrawdw.notisync.desktop.config.NSRunConfig
import net.extrawdw.notisync.desktop.config.NSRunConfigStore
import net.extrawdw.notisync.desktop.config.PtyMode
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.run.process.ChildProcessLauncher
import net.extrawdw.notisync.run.process.ChildSignal
import net.extrawdw.notisync.run.process.ManagedChild
import net.extrawdw.notisync.run.process.TerminalController
import java.util.concurrent.atomic.AtomicBoolean

class NSRunCliTest {
    @Test
    fun `CLI overrides file settings without a shell`() {
        val invocation = NSRunCli.parse(
            arrayOf("--update-interval", "12s", "--stuck-after", "off", "--pty", "never", "--", "git", "commit", "-a"),
            NSRunConfig(updateIntervalSeconds = 60, stuckAfterSeconds = 120, pty = PtyMode.ALWAYS),
        ) as CliInvocation.Run

        assertEquals(listOf("git", "commit", "-a"), invocation.options.command)
        assertEquals(12L, invocation.options.updateInterval.seconds)
        assertNull(invocation.options.stuckAfter)
        assertEquals(PtyMode.NEVER, invocation.options.ptyMode)
    }

    @Test
    fun `config command updates only nsrun file`() {
        val root = Files.createTempDirectory("nsrun-cli-config").toRealPath()
        val store = NSRunConfigStore(root.resolve("nsrun.conf"))
        NSRunConfigCommand(store).execute(listOf("set", "updateInterval", "45s"), StringBuilder())
        assertEquals(45L, store.load().updateIntervalSeconds)
        assertFalse(Files.exists(root.resolve("notisyncd.conf")))
    }

    @Test(expected = CliUsageException::class)
    fun `llm flag requires explicit configuration`() {
        NSRunCli.parse(arrayOf("--llm", "echo", "ok"), NSRunConfig())
    }

    @Test
    fun `malformed config is recovered for command invocation and child still runs`() {
        val root = Files.createTempDirectory("nsrun-invocation-recovery").toRealPath()
        val store = NSRunConfigStore(root.resolve("nsrun.conf"))
        Files.writeString(store.path, "this-option-does-not-exist yes\n")
        val alive = AtomicBoolean(true)
        val child = object : ManagedChild {
            override val pid = ProcessHandle.current().pid()
            override val input = ByteArrayOutputStream()
            override val mergedOutput = ByteArrayInputStream(ByteArray(0))
            override val usesPty = false
            override fun waitFor() = 0.also { alive.set(false) }
            override fun isAlive() = alive.get()
            override fun resize(columns: Int, rows: Int) = Unit
            override fun signal(signal: ChildSignal) = Unit
            override fun close() = Unit
        }
        val runner = NSRunRunner(
            paths = DesktopPaths(root),
            daemonConnector = { error("offline") },
            childLauncher = ChildProcessLauncher { _, _, _, _ -> child },
            terminalFactory = {
                object : TerminalController {
                    override fun enterRawMode() = Unit
                    override fun size() = null
                    override fun close() = Unit
                }
            },
            stderr = StringBuilder(),
            reportingSetupTimeout = Duration.ofMillis(20),
        )
        val warnings = StringBuilder()
        val app = NSRunApplication(store, { runner }, stderr = warnings)

        assertEquals(0, app.execute(arrayOf("fake")))
        assertTrue(warnings.contains("using safe defaults"))
        assertEquals(NSRunConfig(), store.load())
    }
}
