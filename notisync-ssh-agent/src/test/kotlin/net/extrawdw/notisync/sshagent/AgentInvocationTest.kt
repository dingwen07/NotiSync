package net.extrawdw.notisync.sshagent

import java.nio.file.Path
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.sshagent.cache.CachedProviderKeyRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentInvocationTest {
    @Test
    fun `background launch retains native executable and custom paths with repeated binds`() {
        val paths = DesktopPaths(Path.of("test data"), Path.of("test logs"))
        val launcher = Path.of("distribution with spaces", "bin", "notisync-ssh-agent")
        val process = agentBackgroundProcess(paths, listOf("first", "second"), launcher)

        assertEquals(listOf(launcher.toString(), "-a", "first", "-a", "second", "foreground"), process.command())
        assertEquals(paths.dataDirectory.toAbsolutePath().normalize().toString(), process.environment()["NOTISYNC_DATA_DIR"])
        assertEquals(paths.logDirectory.toAbsolutePath().normalize().toString(), process.environment()["NOTISYNC_LOG_DIR"])
    }

    @Test
    fun `native background launch leaves automatic endpoints to child`() {
        val launcher = Path.of("bin", "notisync-ssh-agent")
        val process = agentBackgroundProcess(DesktopPaths(Path.of("test data")), emptyList(), launcher)

        assertEquals(listOf(launcher.toString(), "foreground"), process.command())
    }

    @Test
    fun `background launch falls back to java when current executable is not native or unknown`() {
        val paths = DesktopPaths(Path.of("test data"))
        for (executable in listOf(null, Path.of("bin", "java"), Path.of("bin", "java.exe"))) {
            val command = agentBackgroundProcess(paths, listOf("endpoint"), executable).command()
            assertEquals(
                listOf(
                    Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").lowercase().contains("windows")) "java.exe" else "java").toString(),
                    "-Dnotisync.dataDir=${paths.dataDirectory.toAbsolutePath().normalize()}",
                    "-Dnotisync.logDir=${paths.logDirectory.toAbsolutePath().normalize()}",
                    "-cp", System.getProperty("java.class.path"),
                    "net.extrawdw.notisync.sshagent.NotisyncSshAgentMainKt", "-a", "endpoint", "foreground",
                ),
                command,
            )
        }
    }

    @Test
    fun `key row format includes fingerprint comment and named device identity`() {
        val provider = ClientId("b".repeat(52))
        val row = CachedProviderKeyRow(provider, "1".repeat(32), "SHA256:fingerprint", "Work key")

        assertEquals(
            "SHA256:fingerprint\tWork key\tPixel 9 (${provider.value})",
            formatKeyRow(row, "Pixel 9"),
        )
        assertEquals(
            "SHA256:fingerprint\tWork key\t${provider.value}",
            formatKeyRow(row, null),
        )
    }

    @Test
    fun `bind addresses precede the command and may be repeated`() {
        assertEquals(
            AgentInvocation(
                command = "start",
                commandArguments = emptyList(),
                bindAddresses = listOf("first", "second"),
            ),
            parseAgentInvocation(listOf("-a", "first", "-a", "second", "start")),
        )
    }

    @Test
    fun `address-only invocation defaults to foreground`() {
        assertEquals(
            AgentInvocation("foreground", emptyList(), listOf("endpoint")),
            parseAgentInvocation(listOf("-a", "endpoint")),
        )
    }

    @Test
    fun `missing bind address fails closed`() {
        assertThrows(IllegalArgumentException::class.java) { parseAgentInvocation(listOf("-a")) }
    }

    @Test
    fun `startup failure detail ignores historical diagnostics`() {
        val historical = "notisync-ssh-agent: unsupported SSH Agent database schema 2\n"
        val current = "notisync-ssh-agent is already running\n"

        assertEquals(
            "notisync-ssh-agent is already running",
            startupFailureDetail((historical + current).encodeToByteArray(), historical.encodeToByteArray().size.toLong()),
        )
    }

    @Test
    fun `startup failure detail prefers current prefixed diagnostic over stack trace`() {
        val historical = "notisync-ssh-agent: historical failure\n"
        val current = "notisync-ssh-agent: current failure\n\tat example.Stack.frame(Stack.kt:1)\n"

        assertEquals(
            "current failure",
            startupFailureDetail((historical + current).encodeToByteArray(), historical.encodeToByteArray().size.toLong()),
        )
    }
}
