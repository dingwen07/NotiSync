package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Store-level wiring around the trust state machine (DataStore-backed, file under the JVM temp dir). */
class TrustStoreTest {

    private fun newStore(selfId: net.extrawdw.notisync.protocol.ClientId): TrustStore {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        // Unique non-existent path per store — DataStore allows only one active instance per file.
        val file = File.createTempFile("truststore-${System.nanoTime()}", ".preferences_pb").also { it.delete() }
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        return TrustStore(ds, scope, selfId)
    }

    private fun signedCard(signer: IdentitySigner): SignedBlob {
        val hpke = Hpke.generateKeyPair()
        val card = ClientCard(
            clientId = signer.clientId,
            identityPublicKey = signer.publicKeySpki,
            hpkePublicKeyset = hpke.publicKeyset,
            displayName = "Other",
            platform = "android",
            capabilities = emptyList(),
            createdAt = 1L,
        )
        val payload = ProtocolCodec.encodeToCbor(card)
        return SignedBlob(SignedType.CLIENT_CARD, signerId = signer.clientId, payload = payload, sig = signer.sign(payload))
    }

    /** Regression: deleting an "other" device must revoke it (tombstone) WITHOUT flipping it to an own device. */
    @Test
    fun revokingOtherDevice_keepsItClassifiedAsOther() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val store = newStore(self.clientId)

        assertTrue(store.addLocal(signedCard(other), now = 10L, ownDevice = false))
        val added = store.roster.value.single { it.clientId == other.clientId }
        assertFalse("paired as an other-device", added.ownDevice)
        assertEquals(TrustStatus.TRUSTED, added.status)

        store.revokeLocal(other.clientId, now = 20L)

        val tombstone = store.roster.value.single { it.clientId == other.clientId }
        assertEquals(TrustStatus.REVOKED, tombstone.status)
        assertFalse("a revoked other-device must stay in the Other list, not flip to My devices", tombstone.ownDevice)
        // ...and it is broadcast as an other-device tombstone, so fresh peers don't miscategorize it.
        assertFalse(store.buildTrustTable().entries.single { it.clientId == other.clientId }.ownDevice)
    }

    /** Regression: removing a renamed device must keep its live (overlay) name on the still-shown tombstone,
     *  not revert to the card's original pairing-time name. */
    @Test
    fun revokingRenamedDevice_keepsLiveNameOnTombstone() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val store = newStore(self.clientId)

        assertTrue(store.addLocal(signedCard(other), now = 10L, ownDevice = false))
        // The peer renames itself: a profile update lifts the displayed name above the card's "Other".
        assertTrue(store.applyProfile(ProfileUpdate(other.clientId, "Renamed", "android", emptyList(), updatedAt = 15L)))
        assertEquals("Renamed", store.roster.value.single { it.clientId == other.clientId }.displayName)

        store.revokeLocal(other.clientId, now = 20L)

        val tombstone = store.roster.value.single { it.clientId == other.clientId }
        assertEquals(TrustStatus.REVOKED, tombstone.status)
        assertEquals("the tombstone must keep the live name, not revert to the card's original", "Renamed", tombstone.displayName)
    }
}
