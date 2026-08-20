package net.extrawdw.notisync.peer.foundation

import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.TrustTableEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationTrustCommandTest {
    @Test
    fun `fromDecoded accepts only one matching supported body`() {
        val peer = ClientId("peer-1")
        val profile = ProfileUpdate(peer, "name", "android", emptyList(), 1)
        val trust = TrustTable(emptyList())
        val card = CardDelivery(peer)

        assertEquals(
            FoundationTrustCommandKind.PROFILE,
            ready(FoundationTrustCommand.fromDecoded(DataSync(DataSyncKind.PROFILE, profile = profile))).kind,
        )
        assertEquals(
            FoundationTrustCommandKind.TRUST,
            ready(FoundationTrustCommand.fromDecoded(DataSync(DataSyncKind.TRUST, trust = trust))).kind,
        )
        assertEquals(
            FoundationTrustCommandKind.CARD,
            ready(FoundationTrustCommand.fromDecoded(DataSync(DataSyncKind.CARD, card = card))).kind,
        )
        assertTrue(
            FoundationTrustCommand.fromDecoded(DataSync(DataSyncKind.PROFILE)) is
                FoundationTrustCommandDecodeResult.Malformed,
        )
        assertTrue(
            FoundationTrustCommand.fromDecoded(
                DataSync(DataSyncKind.PROFILE, profile = profile, trust = trust),
            ) is FoundationTrustCommandDecodeResult.Malformed,
        )
        assertTrue(
            FoundationTrustCommand.fromDecoded(
                DataSync(DataSyncKind.FILTER, filter = FilterSync(emptyList(), 1)),
            ) is FoundationTrustCommandDecodeResult.Unsupported,
        )
    }

    @Test
    fun `fromDecoded defensively owns nested collections and signed bytes`() {
        val peer = ClientId("peer-1")
        val entries = mutableListOf(
            TrustTableEntry(peer, TrustStatus.TRUSTED, updatedAt = 1, keyAvailable = true),
        )
        val trust = ready(
            FoundationTrustCommand.fromDecoded(
                DataSync(DataSyncKind.TRUST, trust = TrustTable(entries)),
            ),
        ) as FoundationTrustCommand.Trust
        entries.clear()
        assertEquals(1, trust.tableCopy().entries.size)

        val payload = byteArrayOf(1, 2, 3)
        val signature = byteArrayOf(4, 5, 6)
        val card = ready(
            FoundationTrustCommand.fromDecoded(
                DataSync(
                    DataSyncKind.CARD,
                    card = CardDelivery(
                        peer,
                        card = SignedBlob(
                            typ = SignedType.CLIENT_CARD,
                            signerId = peer,
                            payload = payload,
                            sig = signature,
                        ),
                    ),
                ),
            ),
        ) as FoundationTrustCommand.Card
        payload.fill(9)
        signature.fill(9)
        val retained = requireNotNull(card.deliveryCopy().card)
        assertArrayEquals(byteArrayOf(1, 2, 3), retained.payload)
        assertArrayEquals(byteArrayOf(4, 5, 6), retained.sig)
    }

    private fun ready(result: FoundationTrustCommandDecodeResult): FoundationTrustCommand =
        (result as FoundationTrustCommandDecodeResult.Ready).command
}
