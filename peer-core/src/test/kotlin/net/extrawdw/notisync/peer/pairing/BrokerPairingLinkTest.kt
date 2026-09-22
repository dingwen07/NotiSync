package net.extrawdw.notisync.peer.pairing

import java.net.URI
import net.extrawdw.notisync.protocol.BrokerPairing
import org.junit.Assert.*
import org.junit.Test

class BrokerPairingLinkTest {
    private val session = "abcdefghijklmnopqrstuv"
    private val secret = "A".repeat(43)
    private val identity = "a".repeat(32)
    private val link = BrokerPairingLink(session, secret, identity)
    private val fragment = "#i=$identity&k=$secret"

    @Test fun `default broker omitted and secret with identity stays in the fragment`() {
        assertEquals(BrokerPairing.DEFAULT_BROKER, link.brokerUrl)
        assertEquals("https://notisync.apps.extrawdw.net/pair?pair=1.$session$fragment", link.encode())
        assertEquals("pair=1.$session", URI(link.encode()).rawQuery)
        assertTrue(link.encode().length <= 160)
        assertFalse(link.encode().contains("payload"))
        assertFalse(link.toString().contains(secret))
        assertEquals(link, BrokerPairingLink.parse(link.encode()))
        assertEquals(link, BrokerPairingLink.parse("notisync://pair?pair=1.$session$fragment"))
    }

    @Test fun `incomplete and earlier draft broker links are rejected`() {
        listOf(
            "notisync://pair?pair=1.$session",
            "notisync://pair?pair=1.$session#k=$secret",
            "notisync://pair?pair=1.$session#i=$identity",
            "notisync://pair#v=1&s=$session",
            "notisync://pair?v=1&s=$session",
        ).forEach { assertNull(BrokerPairingLink.parse(it)) }
    }

    @Test fun `custom configured broker and path round trip without changing settings`() {
        val custom = link.copy(brokerUrl = "https://example.net:9443/notisync")
        assertTrue(custom.encode().contains("&b="))
        assertEquals(custom, BrokerPairingLink.parse(custom.encode()))
        assertEquals("https://example.net/api", BrokerPairingLink.normalizeBroker("wss://example.net/api/"))
    }

    @Test fun `malformed links do not crash legacy deep link handler`() {
        listOf("notisync://pair", link.encode(), "https://notisync.apps.extrawdw.net/pair?payload=%XX",
            "notisync://pair?pair=1.$session&payload=legacy$fragment",
            "notisync://pair?payload=legacy$fragment",
            "notisync://pair?payload=legacy&pair=1.$session")
            .forEach { assertNull(PairingDeepLinks.payloadFrom(it)) }
        assertEquals("legacy", PairingDeepLinks.payloadFrom("notisync://pair?payload=legacy"))
    }

    @Test fun `ambiguous unsafe and unknown version links rejected`() {
        listOf(
            "notisync://pair?pair=2.$session$fragment",
            "notisync://pair?pair=$session$fragment",
            "notisync://pair?pair=01.$session$fragment",
            "notisync://pair?pair=1.short$fragment",
            "notisync://pair?pair=1.$session.extra$fragment",
            "notisync://pair?pair=1.$session&pair=1.$session$fragment",
            "notisync://pair?pair=1.$session&payload=card$fragment",
            "notisync://pair?pair=1.$session&k=$secret#i=$identity",
            "notisync://pair?pair=1.$session&i=$identity#k=$secret",
            "notisync://pair?pair=1.$session#i=$identity&k=$secret&k=$secret",
            "notisync://pair?pair=1.$session#i=$identity&i=$identity&k=$secret",
            "notisync://pair?pair=1.$session#i=$identity&k=${"A".repeat(42)}B",
            "notisync://pair?pair=1.$session#i=$identity&k=$secret%0A",
            "notisync://pair?pair=1.$session#i=$identity%0A&k=$secret",
            "notisync://pair?pair=1.$session&b=http%3A%2F%2Fexample.net$fragment",
            "notisync://pair?pair=1.$session&b=https%3A%2F%2Fuser%40example.net$fragment",
            "https://evil.example/pair?pair=1.$session$fragment",
            "https://notisync.apps.extrawdw.net:444/pair?pair=1.$session$fragment",
        ).forEach { assertNull(BrokerPairingLink.parse(it)) }
    }
}
