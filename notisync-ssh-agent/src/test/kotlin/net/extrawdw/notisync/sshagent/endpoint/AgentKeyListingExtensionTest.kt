package net.extrawdw.notisync.sshagent.endpoint

import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.sshagent.cache.CachedProviderKeyRow
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentKeyListingExtensionTest {
    @Test
    fun `response round trips every physical provider row`() {
        val rows = listOf(
            CachedProviderKeyRow(ClientId("provider-a"), "key-a", "SHA256:first", "Main"),
            CachedProviderKeyRow(ClientId("provider-b"), "key-b", "SHA256:first", "Main"),
            CachedProviderKeyRow(ClientId("provider-b"), "key-c", "SHA256:second", "NotiSync SSH Key"),
        )

        assertEquals(rows, AgentKeyListingExtension.decodeResponse(AgentKeyListingExtension.response(rows)))
    }
}
