package net.extrawdw.notisync.sshagent.endpoint

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PreferredAgentEndpointTest {
    @Test
    fun `falls back only when preferred endpoint fails before readiness`() {
        val preferred = FakeEndpoint(failure = NamedPipeConflictException("preferred", 5, "occupied"))
        val fallback = FakeEndpoint()
        val endpoint = PreferredAgentEndpoint(preferred, fallback) { it is NamedPipeConflictException }
        var readinessCalls = 0

        endpoint.run { readinessCalls++ }

        assertEquals(1, preferred.runCalls)
        assertEquals(1, fallback.runCalls)
        assertEquals(1, readinessCalls)
        assertEquals(PreferredAgentEndpoint.Selection.FALLBACK, endpoint.selection)
    }

    @Test
    fun `does not fall back after preferred endpoint becomes ready`() {
        val preferred = FakeEndpoint(signalReady = true, failure = IOException("listener stopped"))
        val fallback = FakeEndpoint()
        val endpoint = PreferredAgentEndpoint(preferred, fallback) { true }

        assertThrows(IOException::class.java) { endpoint.run {} }

        assertEquals(0, fallback.runCalls)
        assertEquals(PreferredAgentEndpoint.Selection.PREFERRED, endpoint.selection)
    }

    private class FakeEndpoint(
        private val signalReady: Boolean = false,
        private val failure: Throwable? = null,
    ) : AgentEndpoint {
        var runCalls = 0

        override fun run(onReady: () -> Unit) {
            runCalls++
            if (signalReady || failure == null) onReady()
            failure?.let { throw it }
        }

        override fun close() = Unit
    }
}
