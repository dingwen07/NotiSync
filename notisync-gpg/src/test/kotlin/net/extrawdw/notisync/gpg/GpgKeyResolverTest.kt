package net.extrawdw.notisync.gpg

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpgKeyResolverTest {
    private val resolver = GpgKeyResolver(Path.of("unused-gpg"))
    private val primaryFingerprint = "0123456789ABCDEF0123456789ABCDEF89ABCDEF"
    private val subkeyFingerprint = "FEDCBA9876543210FEDCBA987654321001234567"
    private val listing = """
        pub:u:255:22:89ABCDEF89ABCDEF:1700000000:0::u:::scESC:::::ed25519:::0:
        fpr:::::::::$primaryFingerprint:
        uid:u::::1700000000::hash::Example <example@example.com>::::::::::0:
        sub:u:255:22:0123456701234567:1700000000:0:::::s:::::ed25519::
        fpr:::::::::$subkeyFingerprint:
    """.trimIndent()

    @Test
    fun primaryLongIdAndFingerprintResolveToSameCertificate() {
        val byId = resolver.resolveFromColonListing("89ABCDEF89ABCDEF", listing)
        val byFingerprint = resolver.resolveFromColonListing(primaryFingerprint, listing)

        assertEquals(byId, byFingerprint)
        assertEquals(primaryFingerprint, byId.primaryFingerprint)
        assertEquals("89ABCDEF89ABCDEF", byId.primaryKeyId)
        assertFalse(byId.selectorNamedSubkey)
    }

    @Test
    fun subkeyLongIdAndFingerprintNormalizeToPrimaryCertificate() {
        val byId = resolver.resolveFromColonListing("0123456701234567", listing)
        val byFingerprint = resolver.resolveFromColonListing(subkeyFingerprint, listing)

        assertEquals("89ABCDEF89ABCDEF", byId.primaryKeyId)
        assertEquals(primaryFingerprint, byFingerprint.primaryFingerprint)
        assertTrue(byId.selectorNamedSubkey)
        assertTrue(byFingerprint.selectorNamedSubkey)
    }
}
