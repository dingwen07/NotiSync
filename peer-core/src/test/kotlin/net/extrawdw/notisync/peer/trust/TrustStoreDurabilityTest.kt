package net.extrawdw.notisync.peer.trust

import java.io.IOException
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.TrustTableEntry
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustStoreDurabilityTest {
    @Test
    fun `failed signed write does not publish mutation in memory`() {
        val persistence = FailingPersistence()
        val store = TrustStore(persistence, SoftwareIdentitySigner.generate())
        val peer = SoftwareIdentitySigner.generate()
        persistence.failWrites = true

        assertThrows(IOException::class.java) {
            store.addLocal(signedCard(peer), now = 10L)
        }

        assertNull(store.statusOf(peer.clientId))
        assertTrue(store.roster.value.isEmpty())
        assertTrue(store.activePeers.value.isEmpty())
    }

    @Test
    fun `automatic trust fold is one durable transaction and retries after failure`() {
        val persistence = FailingPersistence()
        val store = TrustStore(persistence, SoftwareIdentitySigner.generate())
        val sender = SoftwareIdentitySigner.generate()
        val first = SoftwareIdentitySigner.generate()
        val second = SoftwareIdentitySigner.generate()
        val table = TrustTable(
            listOf(
                TrustTableEntry(first.clientId, TrustStatus.TRUSTED, 20L, keyAvailable = false),
                TrustTableEntry(second.clientId, TrustStatus.TRUSTED, 21L, keyAvailable = false),
            )
        )
        persistence.failWrites = true

        assertThrows(IOException::class.java) {
            store.applyIncomingTable(sender.clientId, table, decisionTime = 30L) { _, _ -> true }
        }

        assertNull(store.statusOf(first.clientId))
        assertNull(store.statusOf(second.clientId))
        assertTrue(store.roster.value.isEmpty())

        persistence.failWrites = false
        val result = store.applyIncomingTable(
            sender.clientId,
            table,
            decisionTime = 30L,
        ) { _, _ -> true }

        assertEquals(TrustStatus.TRUSTED, store.statusOf(first.clientId))
        assertEquals(TrustStatus.TRUSTED, store.statusOf(second.clientId))
        assertEquals(
            setOf(
                first.clientId to TrustPrompt.NEW_TRUST,
                second.clientId to TrustPrompt.NEW_TRUST,
            ),
            result.automaticallyAppliedPrompts,
        )
        assertEquals(1, persistence.successfulWrites)
    }

    @Test
    fun `automatic revoke is committed with its incoming fold`() {
        val persistence = FailingPersistence()
        val store = TrustStore(persistence, SoftwareIdentitySigner.generate())
        val sender = SoftwareIdentitySigner.generate()
        val subject = SoftwareIdentitySigner.generate()
        assertTrue(store.addLocal(signedCard(subject), now = 10L))
        val writesBeforeFold = persistence.successfulWrites
        val table = TrustTable(
            listOf(
                TrustTableEntry(subject.clientId, TrustStatus.REVOKED, 20L, keyAvailable = true)
            )
        )

        val result = store.applyIncomingTable(
            sender.clientId,
            table,
            decisionTime = 30L,
        ) { _, _ -> true }

        assertEquals(TrustStatus.REVOKED, store.statusOf(subject.clientId))
        assertEquals(
            setOf(subject.clientId to TrustPrompt.NEW_REVOKE),
            result.automaticallyAppliedPrompts,
        )
        assertEquals(writesBeforeFold + 1, persistence.successfulWrites)
    }

    @Test
    fun `equal and stale tables keep repair results without signing or writing`() {
        val persistence = FailingPersistence()
        val signer = CountingIdentitySigner(SoftwareIdentitySigner.generate())
        val store = TrustStore(persistence, signer)
        val sender = SoftwareIdentitySigner.generate()
        val subject = SoftwareIdentitySigner.generate()
        assertTrue(store.applyCard(subject.clientId, signedCard(subject)))
        val table = TrustTable(
            listOf(
                TrustTableEntry(subject.clientId, TrustStatus.TRUSTED, 20L, keyAvailable = false)
            )
        )
        store.applyIncomingTable(sender.clientId, table)
        val signaturesAfterChange = signer.signCalls
        val writesAfterChange = persistence.successfulWrites

        val duplicate = store.applyIncomingTable(sender.clientId, table)
        val stale = store.applyIncomingTable(
            sender.clientId,
            TrustTable(
                listOf(
                    TrustTableEntry(
                        subject.clientId,
                        TrustStatus.TRUSTED,
                        19L,
                        keyAvailable = false,
                    )
                )
            ),
        )

        assertEquals(listOf(subject.clientId), duplicate.cardsToOffer.map { it.signerId })
        assertEquals(listOf(subject.clientId), stale.cardsToOffer.map { it.signerId })
        assertEquals(signaturesAfterChange, signer.signCalls)
        assertEquals(writesAfterChange, persistence.successfulWrites)

        store.applyIncomingTable(
            sender.clientId,
            TrustTable(
                listOf(
                    TrustTableEntry(
                        subject.clientId,
                        TrustStatus.TRUSTED,
                        21L,
                        keyAvailable = false,
                    )
                )
            ),
        )
        assertEquals(signaturesAfterChange + 1, signer.signCalls)
        assertEquals(writesAfterChange + 1, persistence.successfulWrites)
    }

    private fun signedCard(signer: IdentitySigner): SignedBlob {
        val card = ClientCard(
            clientId = signer.clientId,
            identityPublicKey = signer.publicKeySpki,
            displayName = "Peer",
            platform = "test",
            capabilities = emptyList(),
            createdAt = 1L,
        )
        val payload = ProtocolCodec.encodeToCbor(card)
        return SignedBlob(
            typ = SignedType.CLIENT_CARD,
            signerId = signer.clientId,
            payload = payload,
            sig = signer.sign(payload),
        )
    }

    private class FailingPersistence : TrustPersistence {
        private val values = linkedMapOf<String, String>()
        var failWrites = false
        var successfulWrites = 0
            private set

        override fun read(key: String): String? = values[key]

        override fun write(values: Map<String, String?>) {
            if (failWrites) throw IOException("disk unavailable")
            successfulWrites += 1
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
        }
    }

    private class CountingIdentitySigner(
        private val delegate: IdentitySigner,
    ) : IdentitySigner {
        override val publicKeySpki: ByteArray get() = delegate.publicKeySpki
        override val clientId = delegate.clientId
        var signCalls = 0
            private set

        override fun sign(data: ByteArray): ByteArray {
            signCalls += 1
            return delegate.sign(data)
        }
    }
}
