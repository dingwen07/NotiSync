package net.extrawdw.notisync.sshagent.endpoint

import java.nio.file.Path
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.sshagent.AgentConfig
import net.extrawdw.notisync.sshagent.AgentEndpointMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointAddressTest {
    @Test
    fun `custom Windows address is stable for one user data directory`() {
        val paths = DesktopPaths(Path.of("C:/Users/tester/AppData/Local/NotiSync"))

        val config = AgentConfig(endpointMode = AgentEndpointMode.CUSTOM)
        val first = agentEndpointAddresses(paths, config, windows = true).single()
        val second = agentEndpointAddresses(paths, config, windows = true).single()
        val isolated = agentEndpointAddresses(
            DesktopPaths(Path.of("C:/Users/tester/AppData/Local/NotiSync-test")),
            config,
            windows = true,
        ).single()

        assertEquals(first, second)
        assertNotEquals(first, isolated)
        assertTrue(first.startsWith("\\\\.\\pipe\\notisync-ssh-agent-"))
    }

    @Test
    fun `automatic Windows mode prefers the OpenSSH pipe`() {
        val paths = DesktopPaths(Path.of("C:/Users/tester/AppData/Local/NotiSync"))

        assertEquals(listOf(WINDOWS_OPENSSH_PIPE), agentEndpointAddresses(paths, AgentConfig(), windows = true))
    }

    @Test
    fun `OpenSSH compatible mode selects the fixed Windows pipe`() {
        val paths = DesktopPaths(Path.of("C:/Users/tester/AppData/Local/NotiSync"))

        assertEquals(
            listOf(WINDOWS_OPENSSH_PIPE),
            agentEndpointAddresses(
                paths,
                AgentConfig(endpointMode = AgentEndpointMode.OPENSSH_COMPATIBLE),
                windows = true,
            ),
        )
    }

    @Test
    fun `explicit Windows addresses override configuration and may be repeated`() {
        val paths = DesktopPaths(Path.of("C:/Users/tester/AppData/Local/NotiSync"))
        val explicit = listOf("\\\\.\\pipe\\ns-ssh-agent", WINDOWS_OPENSSH_PIPE)

        assertEquals(explicit, agentEndpointAddresses(paths, AgentConfig(), explicit, windows = true))
        assertThrows(IllegalArgumentException::class.java) {
            agentEndpointAddresses(paths, AgentConfig(), explicit + explicit.first(), windows = true)
        }
    }

    @Test
    fun `Windows accepts an explicit absolute AF_UNIX address`() {
        val paths = DesktopPaths(Path.of("C:/Users/tester/AppData/Local/NotiSync"))
        val socket = Path.of(System.getProperty("java.io.tmpdir"), "ns-ssh-agent-test.sock")
            .toAbsolutePath().normalize().toString()

        assertEquals(
            listOf(socket),
            agentEndpointAddresses(paths, AgentConfig(), listOf(socket), windows = true),
        )
    }

    @Test
    fun `Windows rejects a relative AF_UNIX address`() {
        val paths = DesktopPaths(Path.of("C:/Users/tester/AppData/Local/NotiSync"))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            agentEndpointAddresses(paths, AgentConfig(), listOf("S.ssh-agent"), windows = true)
        }

        assertTrue(failure.message.orEmpty().contains("must be absolute"))
    }
}
