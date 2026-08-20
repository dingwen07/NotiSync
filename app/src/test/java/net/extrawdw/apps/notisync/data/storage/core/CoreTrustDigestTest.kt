package net.extrawdw.apps.notisync.data.storage.core

import java.security.MessageDigest
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTrustDigestTest {
    @Test
    fun digestBindsExactSnapshotBytesToTheSpkiDerivedClientIdentity() {
        val signer = SoftwareIdentitySigner.generate()
        val otherIdentity = SoftwareIdentitySigner.generate()
        val entries = "[]"
        val cards = "{}"
        val overlays = "{\"peer\":true}"
        val epochs = "{\"selfEpoch\":1,\"peers\":{},\"pending\":null}"
        val signature = TrustStoreSigning.sign(signer, entries, cards, overlays, epochs)
        val exact = TrustSnapshotInput.FourSection(
            entriesUtf8 = entries.encodeToByteArray(),
            cardsUtf8 = cards.encodeToByteArray(),
            overlaysUtf8 = overlays.encodeToByteArray(),
            epochsUtf8 = epochs.encodeToByteArray(),
            signatureBase64UrlUtf8 = signature.encodeToByteArray(),
        ).exactBytes()

        assertTrue(
            TrustStoreSigning.verify(
                publicKeySpki = signer.publicKeySpki,
                selfId = signer.clientId,
                entriesJson = entries,
                cardsJson = cards,
                overlaysJson = overlays,
                epochsJson = epochs,
                signatureB64 = signature,
            ),
        )
        assertFalse(
            TrustStoreSigning.verify(
                publicKeySpki = otherIdentity.publicKeySpki,
                selfId = ClientId(otherIdentity.clientId.value),
                entriesJson = entries,
                cardsJson = cards,
                overlaysJson = overlays,
                epochsJson = epochs,
                signatureB64 = signature,
            ),
        )
        assertFalse(
            MessageDigest.isEqual(
                exact.computeTrustSnapshotDigest(signer.clientId.value),
                exact.computeTrustSnapshotDigest(otherIdentity.clientId.value),
            ),
        )
    }

    @Test
    fun digestDistinguishesPhysicalThreeSectionAbsenceFromFourSectionPresence() {
        val signer = SoftwareIdentitySigner.generate()
        val entries = "[]"
        val cards = "{}"
        val overlays = "{}"
        val threeSignature = TrustStoreSigning.signLegacyThreeSectionForTest(
            signer = signer,
            entries = entries,
            cards = cards,
            overlays = overlays,
        )
        val fourEpochs = "{}"
        val fourSignature = TrustStoreSigning.sign(signer, entries, cards, overlays, fourEpochs)

        val three = TrustSnapshotInput.ThreeSection(
            entries.encodeToByteArray(),
            cards.encodeToByteArray(),
            overlays.encodeToByteArray(),
            threeSignature.encodeToByteArray(),
        ).exactBytes()
        val four = TrustSnapshotInput.FourSection(
            entries.encodeToByteArray(),
            cards.encodeToByteArray(),
            overlays.encodeToByteArray(),
            fourEpochs.encodeToByteArray(),
            fourSignature.encodeToByteArray(),
        ).exactBytes()

        assertFalse(
            MessageDigest.isEqual(
                three.computeTrustSnapshotDigest(signer.clientId.value),
                four.computeTrustSnapshotDigest(signer.clientId.value),
            ),
        )
    }
}

private fun TrustStoreSigning.signLegacyThreeSectionForTest(
    signer: SoftwareIdentitySigner,
    entries: String,
    cards: String,
    overlays: String,
): String {
    val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
    fun digest(value: String): String = encoder.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()),
    )
    val canonical = buildString {
        append(VERSION).append('\n')
        append(signer.clientId.value).append('\n')
        append(digest(entries)).append('\n')
        append(digest(cards)).append('\n')
        append(digest(overlays))
    }.encodeToByteArray()
    return encoder.encodeToString(signer.sign(canonical))
}
