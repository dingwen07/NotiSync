package net.extrawdw.notisync.sshagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentInvocationTest {
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
