package net.extrawdw.notisync.peer.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import net.extrawdw.notisync.protocol.ClientId
import org.junit.Test

class PairingDeepLinksTest {
    private val codec = PairingPayloadCodec(ClientId("a".repeat(32)))

    @Test
    fun `external link without payload is rejected without throwing during activity startup`() {
        // MainActivity calls these two parsers from both onCreate and onNewIntent, outside CARD inspection.
        listOf(
            "notisync://pair", "notisync://pair?payload", "notisync://pair?payload=",
            "notisync://pair?pair=1.abcdefghijklmnopqrstuv",
            "https://notisync.apps.extrawdw.net/pair?pair=1.abcdefghijklmnopqrstuv",
            "notisync://pair?pair=2.abcdefghijklmnopqrstuv#i=${"a".repeat(32)}&k=${"A".repeat(43)}",
        ).forEach { link ->
            assertNull(BrokerPairingLink.parse(link))
            assertNull(PairingDeepLinks.payloadFrom(link))
            assertTrue(codec.inspect(link).isFailure)
        }
    }

    @Test
    fun `malformed links are rejected through both external and in app parser paths`() {
        listOf(
            "", "https://[", "notisync://pair?payload=%", "notisync://pair?payload=%XX",
            "notisync://pair?payload=%00", "notisync://pair?payload=%20",
            "notisync://pair?payload=one&payload=two", "notisync://pair?payload=card#i=pin&k=secret",
            "notisync://pair?payload=card&pair=1.abcdefghijklmnopqrstuv",
            "notisync://pair?pair=1.abcdefghijklmnopqrstuv&b=%XX#i=pin&k=secret",
            "notisync://user@pair?payload=card", "notisync://pair:123?payload=card",
            "notisync://pair/unexpected?payload=card", "https://evil.example/pair?payload=card",
            "https://notisync.apps.extrawdw.net:444/pair?payload=card",
            "notisync://pair?payload=${"A".repeat(PairingDeepLinks.MAX_INPUT_CHARS)}",
        ).forEach { link ->
            assertNull(BrokerPairingLink.parse(link))
            val extracted = PairingDeepLinks.payloadFrom(link)
            // A structurally valid legacy URL still must pass CARD decoding and signature checks.
            if (extracted != null) assertTrue(codec.inspect(extracted).isFailure)
            assertTrue(codec.inspect(link).isFailure)
        }
    }

    @Test
    fun `invalid legacy CARD data returns failures instead of exceptions`() {
        listOf("not-base64!", "AA", "e30", "____", "A".repeat(PairingDeepLinks.MAX_INPUT_CHARS + 1)).forEach { payload ->
            assertTrue(codec.inspect(payload).isFailure)
            val link = PairingDeepLinks.create(payload)
            val extracted = PairingDeepLinks.payloadFrom(link)
            if (extracted != null) assertTrue(codec.inspect(extracted).isFailure)
            assertTrue(codec.inspect(link).isFailure)
        }
    }

    @Test
    fun `valid secure and legacy links still reach the appropriate handler`() {
        val secure = BrokerPairingLink("abcdefghijklmnopqrstuv", "A".repeat(43), "b".repeat(32))
        listOf(secure.encode(), secure.encode().replace("https://notisync.apps.extrawdw.net/pair", "notisync://pair"))
            .forEach { link ->
                assertEquals(secure, BrokerPairingLink.parse(link))
                assertNull(PairingDeepLinks.payloadFrom(link))
            }
        assertEquals("compact-payload", PairingDeepLinks.payloadFrom("notisync://pair?payload=compact-payload"))
        assertEquals("compact-payload", PairingDeepLinks.payloadFrom(PairingDeepLinks.create("compact-payload")))
    }

    @Test
    fun `desktop parser accepts pairing URL with or without trailing slash`() {
        assertEquals(
            "compact-payload",
            PairingDeepLinks.extractPayload(
                "https://notisync.apps.extrawdw.net/pair?payload=compact-payload",
            ),
        )
        assertEquals(
            "compact-payload",
            PairingDeepLinks.extractPayload(
                "https://notisync.apps.extrawdw.net/pair/?payload=compact-payload",
            ),
        )
    }
}
