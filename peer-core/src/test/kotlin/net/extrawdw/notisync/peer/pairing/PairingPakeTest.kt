package net.extrawdw.notisync.peer.pairing

import net.extrawdw.notisync.protocol.BrokerPairing
import net.extrawdw.notisync.protocol.PairingPakeFrame
import net.extrawdw.notisync.protocol.ProtocolCodec
import org.junit.Assert.*
import org.junit.Test

class PairingPakeTest {
    private val link = BrokerPairingLink("abcdefghijklmnopqrstuv", "A".repeat(43), "a".repeat(32))

    @Test fun `confirmed keys exchange confidential cards in both directions once`() {
        PairingPake(link, PairingRole.HOST).use { a ->
            PairingPake(link, PairingRole.CLIENT).use { b ->
                confirm(a, b)
                val hostCard = a.encryptCard("signed-host-CARD")
                val clientCard = b.encryptCard("signed-client-CARD")
                assertFalse(hostCard.toString(Charsets.UTF_8).contains("signed-host-CARD"))
                assertEquals("signed-host-CARD", b.decryptCard(hostCard))
                assertEquals("signed-client-CARD", a.decryptCard(clientCard))
                assertThrows(IllegalStateException::class.java) { b.decryptCard(hostCard) }
                assertThrows(IllegalStateException::class.java) { a.encryptCard("second CARD") }
            }
        }
    }

    @Test fun `incorrect QR secret fails mutual confirmation and cannot release cards or retry`() {
        val a = PairingPake(link, PairingRole.HOST)
        val b = PairingPake(link.copy(secret = "B".repeat(42) + "A"), PairingRole.CLIENT)
        val (a3, b3) = rounds(a, b)
        assertThrows(Exception::class.java) { a.confirm(b3) }
        assertThrows(Exception::class.java) { b.confirm(a3) }
        assertThrows(IllegalStateException::class.java) { a.encryptCard("secret") }
        assertThrows(IllegalStateException::class.java) { b.encryptCard("secret") }
        assertThrows(IllegalStateException::class.java) { a.round1() }
    }

    @Test fun `CARD cannot be sent before key confirmation`() {
        PairingPake(link, PairingRole.HOST).use { a ->
            assertThrows(IllegalStateException::class.java) { a.encryptCard("CARD") }
        }
    }

    @Test fun `session role host identity and broker substitution are rejected`() {
        listOf(
            link.copy(sessionId = "abcdefghijklmnopqrstuw") to PairingRole.CLIENT,
            link.copy(brokerUrl = "https://other.example") to PairingRole.CLIENT,
            link.copy(hostId = "b".repeat(32)) to PairingRole.CLIENT,
            link to PairingRole.HOST,
        ).forEach { (otherLink, role) ->
            PairingPake(link, PairingRole.HOST).use { a ->
                PairingPake(otherLink, role).use { b ->
                    a.round1()
                    assertThrows(IllegalArgumentException::class.java) { a.round2(b.round1()) }
                }
            }
        }
    }

    @Test fun `proof tampering and invalid group elements are rejected`() {
        val a = PairingPake(link, PairingRole.HOST)
        val b = PairingPake(link, PairingRole.CLIENT)
        a.round1()
        val original = ProtocolCodec.decodeFromCbor<PairingPakeFrame>(b.round1())
        val altered = original.copy(values = original.values.toMutableList().also { it[1] = byteArrayOf(1) })
        assertThrows(Exception::class.java) { a.round2(ProtocolCodec.encodeToCbor(altered)) }
        assertThrows(IllegalStateException::class.java) { a.round2(ProtocolCodec.encodeToCbor(original)) }
    }

    @Test fun `altered ciphertext and reflection fail authentication`() {
        val a = PairingPake(link, PairingRole.HOST)
        val b = PairingPake(link, PairingRole.CLIENT)
        confirm(a, b)
        val card = a.encryptCard("CARD")
        assertThrows(Exception::class.java) { a.decryptCard(card) }
        card[card.lastIndex - 2] = (card[card.lastIndex - 2].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { b.decryptCard(card) }
    }

    @Test fun `captured ciphertext cannot be replayed in a fresh exchange with the same QR secret`() {
        val a = PairingPake(link, PairingRole.HOST)
        val b = PairingPake(link, PairingRole.CLIENT)
        confirm(a, b)
        val captured = a.encryptCard("CARD")
        val nextA = PairingPake(link, PairingRole.HOST)
        val nextB = PairingPake(link, PairingRole.CLIENT)
        confirm(nextA, nextB)
        assertThrows(Exception::class.java) { nextB.decryptCard(captured) }
    }

    @Test fun `oversized and malformed secret inputs rejected`() {
        listOf("123456", "A".repeat(42), "A".repeat(44), "A".repeat(42) + "B").forEach { secret ->
            assertThrows(IllegalArgumentException::class.java) { link.copy(secret = secret) }
        }
        val a = PairingPake(link, PairingRole.HOST)
        a.round1()
        assertThrows(IllegalArgumentException::class.java) { a.round2(ByteArray(BrokerPairing.MAX_FRAME_BYTES + 1)) }
        repeat(20) { assertTrue(BrokerPairingLink.validSecret(BrokerPairingLink.generateSecret())) }
    }

    private fun rounds(a: PairingPake, b: PairingPake): Pair<ByteArray, ByteArray> {
        val a1 = a.round1(); val b1 = b.round1()
        val a2 = a.round2(b1); val b2 = b.round2(a1)
        return a.round3(b2) to b.round3(a2)
    }

    private fun confirm(a: PairingPake, b: PairingPake) {
        val (a3, b3) = rounds(a, b)
        a.confirm(b3); b.confirm(a3)
    }
}
