package net.extrawdw.apps.notisync.data.corecommand.preparation

import net.extrawdw.apps.notisync.data.storage.core.TrustSignatureFormat
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshot
import net.extrawdw.notisync.peer.trust.SignedTrustSnapshotFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomCoreTrustPreparationAdaptersTest {
    @Test
    fun threeSectionMappingPreservesPhysicalEpochAbsenceAndCopiesBytes() {
        val entries = "{}".encodeToByteArray()
        val cards = "{}".encodeToByteArray()
        val overlays = "{}".encodeToByteArray()
        val signature = "signature".encodeToByteArray()
        val digest = ByteArray(32) { it.toByte() }
        val source = TrustSnapshot(
            signatureFormat = TrustSignatureFormat.TRUSTSTORE_V1_THREE_SECTION,
            entriesUtf8 = entries,
            cardsUtf8 = cards,
            overlaysUtf8 = overlays,
            epochsUtf8 = null,
            signatureBase64UrlUtf8 = signature,
            snapshotDigest = digest,
            updatedAt = 1L,
        )

        val mapped = source.toPreparationSignedSnapshot()
        entries.fill(0)
        cards.fill(0)
        overlays.fill(0)
        signature.fill(0)

        assertEquals(SignedTrustSnapshotFormat.THREE_SECTION, mapped.format)
        assertArrayEquals("{}".encodeToByteArray(), mapped.entriesUtf8Copy())
        assertArrayEquals("{}".encodeToByteArray(), mapped.cardsUtf8Copy())
        assertArrayEquals("{}".encodeToByteArray(), mapped.overlaysUtf8Copy())
        assertNull(mapped.epochsUtf8CopyOrNull())
        assertArrayEquals("signature".encodeToByteArray(), mapped.signatureBase64UrlUtf8Copy())
    }

    @Test
    fun fourSectionMappingPreservesExactEpochBytes() {
        val source = TrustSnapshot(
            signatureFormat = TrustSignatureFormat.TRUSTSTORE_V1_FOUR_SECTION,
            entriesUtf8 = "{}".encodeToByteArray(),
            cardsUtf8 = "{}".encodeToByteArray(),
            overlaysUtf8 = "{}".encodeToByteArray(),
            epochsUtf8 = "{\"self\":1}".encodeToByteArray(),
            signatureBase64UrlUtf8 = "signature".encodeToByteArray(),
            snapshotDigest = ByteArray(32) { (it + 1).toByte() },
            updatedAt = 2L,
        )

        val mapped = source.toPreparationSignedSnapshot()

        assertEquals(SignedTrustSnapshotFormat.FOUR_SECTION, mapped.format)
        assertArrayEquals("{\"self\":1}".encodeToByteArray(), mapped.epochsUtf8CopyOrNull())
    }
}
