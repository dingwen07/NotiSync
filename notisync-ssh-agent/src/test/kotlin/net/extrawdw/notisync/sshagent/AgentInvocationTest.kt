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
}
