package net.extrawdw.notisync.peer.trust

import java.io.IOException
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
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

        override fun read(key: String): String? = values[key]

        override fun write(values: Map<String, String?>) {
            if (failWrites) throw IOException("disk unavailable")
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
        }
    }
}
