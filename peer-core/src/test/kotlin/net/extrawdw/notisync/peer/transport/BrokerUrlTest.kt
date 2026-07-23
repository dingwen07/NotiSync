package net.extrawdw.notisync.peer.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class BrokerUrlTest {
    @Test
    fun `https broker base becomes wss only for live delivery`() {
        assertEquals(
            "https://notisync-api-v2.extrawdw.net",
            brokerHttpBase(" https://notisync-api-v2.extrawdw.net/ "),
        )
        assertEquals(
            "wss://notisync-api-v2.extrawdw.net",
            brokerWebSocketBase(" https://notisync-api-v2.extrawdw.net/ "),
        )
    }

    @Test
    fun `legacy websocket spelling remains accepted`() {
        assertEquals("https://example.test/base", brokerHttpBase("wss://example.test/base/"))
        assertEquals("wss://example.test/base", brokerWebSocketBase("wss://example.test/base/"))
        assertEquals("http://127.0.0.1:8080", brokerHttpBase("ws://127.0.0.1:8080"))
        assertEquals("ws://127.0.0.1:8080", brokerWebSocketBase("http://127.0.0.1:8080"))
    }
}
