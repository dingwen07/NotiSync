package net.extrawdw.notisync.protocol.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustStoreSigningTest {
    private val entries = """[{"clientId":"AAAA","status":"TRUSTED","updatedAt":10}]"""
    private val cards = """{"AAAA":"q1Rh+base64ish"}"""
    private val overlays = "{}"

    @Test
    fun signThenVerify_roundTrips() {
        val signer = SoftwareIdentitySigner.generate()
        val sig = TrustStoreSigning.sign(signer, entries, cards, overlays)
        assertTrue(TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, entries, cards, overlays, sig))
    }

    @Test
    fun tamperingAnySection_failsVerification() {
        val signer = SoftwareIdentitySigner.generate()
        val sig = TrustStoreSigning.sign(signer, entries, cards, overlays)
        assertFalse("entries changed", TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, "$entries ", cards, overlays, sig))
        assertFalse("cards changed", TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, entries, "{}", overlays, sig))
        assertFalse("overlays changed", TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, entries, cards, """{"x":1}""", sig))
    }

    @Test
    fun anotherDevicesKeyOrId_failsVerification() {
        val signer = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val sig = TrustStoreSigning.sign(signer, entries, cards, overlays)
        // Wrong public key (roster lifted to / re-signed by another device).
        assertFalse(TrustStoreSigning.verify(other.publicKeySpki, signer.clientId, entries, cards, overlays, sig))
        // Right key but the bound id differs from what was signed.
        assertFalse(TrustStoreSigning.verify(signer.publicKeySpki, other.clientId, entries, cards, overlays, sig))
    }

    @Test
    fun malformedSignature_failsClosed() {
        val signer = SoftwareIdentitySigner.generate()
        assertFalse(TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, entries, cards, overlays, "!!!not base64!!!"))
        assertFalse(TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, entries, cards, overlays, ""))
    }
}
