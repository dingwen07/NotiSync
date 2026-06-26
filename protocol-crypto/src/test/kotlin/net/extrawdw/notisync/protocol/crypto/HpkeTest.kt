package net.extrawdw.notisync.protocol.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The raw-X25519 HPKE wire form (§B.2): [Hpke.rawPublicKey] extracts the 32-byte key from a Tink keyset,
 * [Hpke.sealToRaw] seals to that raw key without a keyset, and [Hpke.seal] length-discriminates the two
 * forms — all opening with the device's unchanged Tink private keyset.
 */
class HpkeTest {

    private val ctx = "notisync:dek:ns2|CLIENT|1".toByteArray()
    private fun dek() = ByteArray(32) { it.toByte() }

    @Test
    fun rawPublicKeyIsThe32ByteWireKey() {
        val pair = Hpke.generateKeyPair()
        assertEquals(32, Hpke.rawPublicKey(pair.publicKeyset).size)
        // Stable across re-extraction (no internal state / randomness).
        assertArrayEquals(Hpke.rawPublicKey(pair.publicKeyset), Hpke.rawPublicKey(pair.publicKeyset))
    }

    @Test
    fun sealToRawOpensWithTheTinkPrivateKeyset() {
        val pair = Hpke.generateKeyPair()
        val raw = Hpke.rawPublicKey(pair.publicKeyset)
        val sealed = Hpke.sealToRaw(dek(), raw, ctx)
        // The published key is raw, but the device still holds (and opens with) its Tink private keyset.
        assertArrayEquals(dek(), Hpke.open(sealed, pair.privateKeyset, ctx))
    }

    @Test
    fun sealDispatchesByLengthAndBothFormsRoundTrip() {
        val pair = Hpke.generateKeyPair()
        val raw = Hpke.rawPublicKey(pair.publicKeyset)
        // 32-byte recipient key → raw path; the full keyset → legacy path. Both open with the same key.
        assertArrayEquals(dek(), Hpke.open(Hpke.seal(dek(), raw, ctx), pair.privateKeyset, ctx))
        assertArrayEquals(dek(), Hpke.open(Hpke.seal(dek(), pair.publicKeyset, ctx), pair.privateKeyset, ctx))
    }

    @Test
    fun sealToRawBindsTheContextInfo() {
        val pair = Hpke.generateKeyPair()
        val sealed = Hpke.sealToRaw(dek(), Hpke.rawPublicKey(pair.publicKeyset), ctx)
        assertThrows(Exception::class.java) {
            Hpke.open(sealed, pair.privateKeyset, "notisync:dek:ns2|CLIENT|2".toByteArray())
        }
    }

    @Test
    fun aRawKeyFromOnePairCannotBeOpenedByAnother() {
        val a = Hpke.generateKeyPair()
        val b = Hpke.generateKeyPair()
        val sealed = Hpke.sealToRaw(dek(), Hpke.rawPublicKey(a.publicKeyset), ctx)
        assertThrows(Exception::class.java) { Hpke.open(sealed, b.privateKeyset, ctx) }
    }
}
