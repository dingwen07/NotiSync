package net.extrawdw.notisync.protocol.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustStoreSigningTest {
    private val entries = """[{"clientId":"AAAA","status":"TRUSTED","updatedAt":10}]"""
    private val cards = """{"AAAA":"q1Rh+base64ish"}"""
    private val overlays = "{}"
    private val epochs = """{"selfEpoch":1,"peers":{"AAAA":{"ringB64":["abc"],"floor":2}}}"""

    @Test
    fun signThenVerify_roundTrips() {
        val signer = SoftwareIdentitySigner.generate()
        val sig = TrustStoreSigning.sign(signer, entries, cards, overlays, epochs)
        assertTrue(TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, entries, cards, overlays, epochs, sig))
    }

    @Test
    fun tamperingAnySection_failsVerification() {
        val signer = SoftwareIdentitySigner.generate()
        val sig = TrustStoreSigning.sign(signer, entries, cards, overlays, epochs)
        assertFalse("entries changed", TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, "$entries ", cards, overlays, epochs, sig))
        assertFalse("cards changed", TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, entries, "{}", overlays, epochs, sig))
        assertFalse("overlays changed", TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, entries, cards, """{"x":1}""", epochs, sig))
        // The epoch floor section is covered too: dragging the floor down (or stripping the ring) is tamper.
        assertFalse("epoch floor changed", TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, entries, cards, overlays, """{"selfEpoch":1,"peers":{}}""", sig))
    }

    @Test
    fun anotherDevicesKeyOrId_failsVerification() {
        val signer = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val sig = TrustStoreSigning.sign(signer, entries, cards, overlays, epochs)
        // Wrong public key (roster lifted to / re-signed by another device).
        assertFalse(TrustStoreSigning.verify(other.publicKeySpki, signer.clientId, entries, cards, overlays, epochs, sig))
        // Right key but the bound id differs from what was signed.
        assertFalse(TrustStoreSigning.verify(signer.publicKeySpki, other.clientId, entries, cards, overlays, epochs, sig))
    }

    @Test
    fun malformedSignature_failsClosed() {
        val signer = SoftwareIdentitySigner.generate()
        assertFalse(TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, entries, cards, overlays, epochs, "!!!not base64!!!"))
        assertFalse(TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, entries, cards, overlays, epochs, ""))
    }

    @Test
    fun legacyThreeSectionRoster_migratesNotQuarantines() {
        // A pre-NS2 roster was signed over only (entries, cards, overlays). On upgrade the loader verifies it
        // via the legacy fallback (so it is NOT falsely quarantined) and re-signs it as four sections.
        val signer = SoftwareIdentitySigner.generate()
        val legacyCanonical = buildString {
            append(TrustStoreSigning.VERSION).append('\n')
            append(signer.clientId.value).append('\n')
            append(sha(entries)).append('\n')
            append(sha(cards)).append('\n')
            append(sha(overlays))
        }.toByteArray(Charsets.UTF_8)
        val legacySig = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign(legacyCanonical))
        assertTrue(TrustStoreSigning.verifyLegacyThreeSection(signer.publicKeySpki, signer.clientId, entries, cards, overlays, legacySig))
        // A four-section verify of the same legacy signature must FAIL (so the loader knows to fall back, not
        // that a four-section store with empty epochs would pass under a legacy sig).
        assertFalse(TrustStoreSigning.verify(signer.publicKeySpki, signer.clientId, entries, cards, overlays, "{}", legacySig))
    }

    private fun sha(s: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))
}
