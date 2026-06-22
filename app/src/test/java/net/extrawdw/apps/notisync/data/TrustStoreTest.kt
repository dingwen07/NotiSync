package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Store-level wiring around the trust state machine (DataStore-backed, file under the JVM temp dir). */
class TrustStoreTest {

    private fun newStore(self: IdentitySigner): TrustStore {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        // Unique non-existent path per store — DataStore allows only one active instance per file.
        val file = File.createTempFile("truststore-${System.nanoTime()}", ".preferences_pb").also { it.delete() }
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        return TrustStore(ds, scope, self)
    }

    private fun trusted(signer: IdentitySigner) =
        TrustEntry(signer.clientId, TrustStatus.TRUSTED, updatedAt = 10L, introducedBy = null, ownDevice = false)

    /**
     * Seed a DataStore on disk with [entries] + [cards] (overlays empty), optionally signed by [sigSigner],
     * then open a [TrustStore] over it. Writing the preferences directly (not via the async persist path)
     * keeps the load/quarantine assertions deterministic. [sigSigner] = self -> valid signature; null -> no
     * signature (legacy/stripped); a different signer -> a present-but-invalid signature.
     */
    private fun seedStore(
        self: IdentitySigner,
        entries: List<TrustEntry>,
        cards: Map<ClientId, SignedBlob>,
        sigSigner: IdentitySigner?,
    ): TrustStore {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val file = File.createTempFile("truststore-${System.nanoTime()}", ".preferences_pb").also { it.delete() }
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val b64 = Base64.getEncoder()
        val entriesJson = ProtocolCodec.encodeToJson(entries)
        val cardsJson = ProtocolCodec.encodeToJson(cards.mapKeys { it.key.value }.mapValues { b64.encodeToString(ProtocolCodec.encodeToCbor(it.value)) })
        val overlaysJson = ProtocolCodec.encodeToJson(emptyMap<String, ProfileOverlay>())
        runBlocking {
            ds.edit {
                it[stringPreferencesKey("trust_entries_json")] = entriesJson
                it[stringPreferencesKey("trust_cards_json")] = cardsJson
                it[stringPreferencesKey("trust_overlays_json")] = overlaysJson
                if (sigSigner != null) {
                    it[stringPreferencesKey("trust_sig")] = TrustStoreSigning.sign(sigSigner, entriesJson, cardsJson, overlaysJson)
                }
            }
        }
        return TrustStore(ds, scope, self)
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
        val store = newStore(self)

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
        val store = newStore(self)

        assertTrue(store.addLocal(signedCard(other), now = 10L, ownDevice = false))
        // The peer renames itself: a profile update lifts the displayed name above the card's "Other".
        assertTrue(store.applyProfile(ProfileUpdate(other.clientId, "Renamed", "android", emptyList(), updatedAt = 15L)))
        assertEquals("Renamed", store.roster.value.single { it.clientId == other.clientId }.displayName)

        store.revokeLocal(other.clientId, now = 20L)

        val tombstone = store.roster.value.single { it.clientId == other.clientId }
        assertEquals(TrustStatus.REVOKED, tombstone.status)
        assertEquals("the tombstone must keep the live name, not revert to the card's original", "Renamed", tombstone.displayName)
    }

    // ---- tamper quarantine ----

    @Test
    fun validlySignedRoster_loadsActiveAndNotQuarantined() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val store = seedStore(self, listOf(trusted(other)), mapOf(other.clientId to signedCard(other)), sigSigner = self)

        assertFalse(store.quarantined.value)
        assertEquals("trusted device with a held card is an active peer", 1, store.activePeers.value.size)
    }

    @Test
    fun emptyUnsignedStore_isNotQuarantined() {
        val self = SoftwareIdentitySigner.generate()
        val store = seedStore(self, emptyList(), emptyMap(), sigSigner = null)

        assertFalse("nothing to protect -> no quarantine", store.quarantined.value)
    }

    @Test
    fun unsignedNonEmptyRoster_quarantinesAndFreezesSyncButKeepsRosterVisible() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val store = seedStore(self, listOf(trusted(other)), mapOf(other.clientId to signedCard(other)), sigSigner = null)

        assertTrue("a populated store with no signature is unverifiable", store.quarantined.value)
        // The single gate: empty activePeers => nothing to seal to (outgoing) and lookup() drops inbound.
        assertTrue("frozen: no active peers", store.activePeers.value.isEmpty())
        // ...but the roster stays visible so the user can review it under the banner.
        assertEquals(1, store.roster.value.size)
    }

    @Test
    fun signatureFromAnotherDevice_quarantines() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        // A present, well-formed signature — but made by a different identity key (e.g. a roster lifted
        // from another device). It must not verify against ours.
        val store = seedStore(self, listOf(trusted(other)), mapOf(other.clientId to signedCard(other)), sigSigner = other)

        assertTrue(store.quarantined.value)
        assertTrue(store.activePeers.value.isEmpty())
    }

    @Test
    fun quarantine_blocksAllMutators() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val third = SoftwareIdentitySigner.generate()
        val store = seedStore(self, listOf(trusted(other)), mapOf(other.clientId to signedCard(other)), sigSigner = null)
        assertTrue(store.quarantined.value)

        assertFalse("addLocal must no-op while quarantined", store.addLocal(signedCard(third), now = 30L, ownDevice = false))
        assertFalse("applyCard must no-op while quarantined", store.applyCard(third.clientId, signedCard(third)))
        assertFalse("revokeLocal must no-op while quarantined", store.revokeLocal(other.clientId, now = 40L))
        // No accidental mutation: the original single device is still the only roster row.
        assertEquals(1, store.roster.value.size)
        assertEquals(TrustStatus.TRUSTED, store.roster.value.single().status)
    }

    @Test
    fun approveQuarantine_resumesAndKeepsRoster() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val store = seedStore(self, listOf(trusted(other)), mapOf(other.clientId to signedCard(other)), sigSigner = null)
        assertTrue(store.quarantined.value)

        store.approveQuarantine()

        assertFalse(store.quarantined.value)
        assertEquals("the kept roster is active again", 1, store.activePeers.value.size)
    }

    @Test
    fun clearQuarantine_wipesRosterAndResumes() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val store = seedStore(self, listOf(trusted(other)), mapOf(other.clientId to signedCard(other)), sigSigner = null)
        assertTrue(store.quarantined.value)

        store.clearQuarantine()

        assertFalse(store.quarantined.value)
        assertTrue("wiped", store.roster.value.isEmpty())
        assertTrue(store.activePeers.value.isEmpty())
    }
}
