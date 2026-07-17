package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.crypto.KeyFingerprint
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Purpose
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.TrustTableEntry
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** Store-level wiring around the trust state machine (DataStore-backed, file under the JVM temp dir). */
class TrustStoreTest {

    private fun newStore(self: IdentitySigner): TrustStore {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        // Unique non-existent path per store — DataStore allows only one active instance per file.
        val file = File.createTempFile("truststore-${System.nanoTime()}", ".preferences_pb")
            .also { it.delete() }
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        return TrustStore(ds, scope, self)
    }

    private fun trusted(signer: IdentitySigner) =
        TrustEntry(
            signer.clientId,
            TrustStatus.TRUSTED,
            updatedAt = 10L,
            introducedBy = null,
            ownDevice = false
        )

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
        epochs: EpochSection = EpochSection(),
    ): TrustStore {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val file = File.createTempFile("truststore-${System.nanoTime()}", ".preferences_pb")
            .also { it.delete() }
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val b64 = Base64.getEncoder()
        val entriesJson = ProtocolCodec.encodeToJson(entries)
        val cardsJson = ProtocolCodec.encodeToJson(cards.mapKeys { it.key.value }
            .mapValues { b64.encodeToString(ProtocolCodec.encodeToCbor(it.value)) })
        val overlaysJson = ProtocolCodec.encodeToJson(emptyMap<String, ProfileOverlay>())
        val epochsJson = ProtocolCodec.encodeToJson(epochs)
        runBlocking {
            ds.edit {
                it[stringPreferencesKey("trust_entries_json")] = entriesJson
                it[stringPreferencesKey("trust_cards_json")] = cardsJson
                it[stringPreferencesKey("trust_overlays_json")] = overlaysJson
                it[stringPreferencesKey("trust_epochs_json")] = epochsJson
                if (sigSigner != null) {
                    it[stringPreferencesKey("trust_sig")] = TrustStoreSigning.sign(
                        sigSigner,
                        entriesJson,
                        cardsJson,
                        overlaysJson,
                        epochsJson
                    )
                }
            }
        }
        return TrustStore(ds, scope, self)
    }

    /** Seed a store signed over ONLY the legacy three sections (no epoch section persisted) — a pre-NS2
     *  roster, to exercise the migration fallback in load(). */
    private fun seedLegacyStore(
        self: IdentitySigner,
        entries: List<TrustEntry>,
        cards: Map<ClientId, SignedBlob>
    ): TrustStore {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val file = File.createTempFile("truststore-${System.nanoTime()}", ".preferences_pb")
            .also { it.delete() }
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val b64 = Base64.getEncoder()
        val b64url = Base64.getUrlEncoder().withoutPadding()
        val entriesJson = ProtocolCodec.encodeToJson(entries)
        val cardsJson = ProtocolCodec.encodeToJson(cards.mapKeys { it.key.value }
            .mapValues { b64.encodeToString(ProtocolCodec.encodeToCbor(it.value)) })
        val overlaysJson = ProtocolCodec.encodeToJson(emptyMap<String, ProfileOverlay>())
        fun sha(s: String) = b64url.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        )

        val legacyCanonical =
            "${TrustStoreSigning.VERSION}\n${self.clientId.value}\n${sha(entriesJson)}\n${
                sha(cardsJson)
            }\n${sha(overlaysJson)}".toByteArray(Charsets.UTF_8)
        runBlocking {
            ds.edit {
                it[stringPreferencesKey("trust_entries_json")] = entriesJson
                it[stringPreferencesKey("trust_cards_json")] = cardsJson
                it[stringPreferencesKey("trust_overlays_json")] = overlaysJson
                it[stringPreferencesKey("trust_sig")] =
                    b64url.encodeToString(self.sign(legacyCanonical))
            }
        }
        return TrustStore(ds, scope, self)
    }

    /** A valid identity-signed KEY_EPOCH blob for [signer] (its own operational keys at [epoch]). */
    private fun keyEpochBlobFor(
        signer: IdentitySigner,
        epoch: Int = 1,
        minEpoch: Int = epoch,
        notAfter: Long = Long.MAX_VALUE,
        stripIdentity: Boolean = false
    ): SignedBlob {
        val hpke = Hpke.generateKeyPair()
        val op = SoftwareOperationalSigner.generate(signer.clientId, epoch)
        val ke = ClientKeyEpoch(
            clientId = signer.clientId,
            identityPublicKey = if (stripIdentity) ByteArray(0) else signer.publicKeySpki,
            epoch = epoch,
            operationalSigningKey = op.operationalPublicKeySpki,
            hpkePublicKey = hpke.publicKeyset,
            purposes = listOf(Purpose.ENVELOPE_SIGN, Purpose.REQUEST_AUTH, Purpose.HPKE_SEAL),
            notBefore = 0L,
            notAfter = notAfter,
            minEpoch = minEpoch,
        )
        val payload = ProtocolCodec.encodeToCbor(ke)
        return SignedBlob(
            SignedType.KEY_EPOCH,
            signerId = signer.clientId,
            payload = payload,
            sig = signer.sign(payload)
        )
    }

    private fun ringOf(blob: SignedBlob, floor: Int): PeerEpochs =
        PeerEpochs(
            listOf(Base64.getEncoder().encodeToString(ProtocolCodec.encodeToCbor(blob))),
            floor
        )

    private fun signedCard(signer: IdentitySigner): SignedBlob {
        val card = ClientCard(
            clientId = signer.clientId,
            identityPublicKey = signer.publicKeySpki,
            displayName = "Other",
            platform = "android",
            capabilities = emptyList(),
            createdAt = 1L,
        )
        val payload = ProtocolCodec.encodeToCbor(card)
        return SignedBlob(
            SignedType.CLIENT_CARD,
            signerId = signer.clientId,
            payload = payload,
            sig = signer.sign(payload)
        )
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
        assertFalse(
            "a revoked other-device must stay in the Other list, not flip to My devices",
            tombstone.ownDevice
        )
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
        assertTrue(
            store.applyProfile(
                ProfileUpdate(
                    other.clientId,
                    "Renamed",
                    "android",
                    emptyList(),
                    updatedAt = 15L
                )
            )
        )
        assertEquals(
            "Renamed",
            store.roster.value.single { it.clientId == other.clientId }.displayName
        )

        store.revokeLocal(other.clientId, now = 20L)

        val tombstone = store.roster.value.single { it.clientId == other.clientId }
        assertEquals(TrustStatus.REVOKED, tombstone.status)
        assertEquals(
            "the tombstone must keep the live name, not revert to the card's original",
            "Renamed",
            tombstone.displayName
        )
    }

    // ---- tamper quarantine ----

    @Test
    fun validlySignedRoster_loadsActiveAndNotQuarantined() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        // A trusted device is *active* (sealable) once we also hold a key-epoch for it (its operational keys).
        val epochs = EpochSection(
            peers = mapOf(
                other.clientId.value to ringOf(
                    keyEpochBlobFor(
                        other,
                        epoch = 1
                    ), floor = 1
                )
            )
        )
        val store = seedStore(
            self,
            listOf(trusted(other)),
            mapOf(other.clientId to signedCard(other)),
            sigSigner = self,
            epochs = epochs
        )

        assertFalse(store.quarantined.value)
        assertEquals(
            "trusted device with a held key-epoch is an active peer",
            1,
            store.activePeers.value.size
        )
        assertEquals(1, store.activePeers.value.single().currentEpoch)
    }

    @Test
    fun trustedDeviceWithoutKeyEpoch_isInRosterButNotActive() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        // A card pins identity + profile, but without a key-epoch there is no operational/HPKE key to seal
        // to — so the device shows in the roster yet is not an active (sealable) peer until it converges.
        val store = seedStore(
            self,
            listOf(trusted(other)),
            mapOf(other.clientId to signedCard(other)),
            sigSigner = self
        )

        assertFalse(store.quarantined.value)
        assertEquals(1, store.roster.value.size)
        assertTrue("no key-epoch held -> not sealable", store.activePeers.value.isEmpty())
    }

    @Test
    fun applyKeyEpoch_makesPeerSealable_andEnforcesMonotonicFloor() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val store = newStore(self)
        assertTrue(store.addLocal(signedCard(other), now = 10L, ownDevice = true))
        assertTrue("no key-epoch yet -> not active", store.activePeers.value.isEmpty())

        // Apply epoch 2 (floor 2): the peer becomes sealable at epoch 2.
        assertTrue(
            store.applyKeyEpoch(
                other.clientId,
                keyEpochBlobFor(other, epoch = 2, minEpoch = 2)
            )
        )
        assertEquals(1, store.activePeers.value.size)
        assertEquals(2, store.activePeers.value.single().currentEpoch)
        assertEquals(2, store.peerEpoch(other.clientId))
        // The roster row (the UI surface) reflects the held epoch too, so the device shows reachable + "epoch 2".
        assertEquals(2, store.roster.value.single { it.clientId == other.clientId }.currentEpoch)
        assertEquals(listOf(other.clientId), store.trustedClientIds())

        // A stale epoch 1 (below the floor) is rejected — the anti-rollback floor only ever rises.
        assertFalse(
            "epoch below floor must be rejected",
            store.applyKeyEpoch(other.clientId, keyEpochBlobFor(other, epoch = 1, minEpoch = 1))
        )
        assertEquals(2, store.peerEpoch(other.clientId))
    }

    @Test
    fun rosterDetails_exposeVerifiedIdentitySigningAndEncryptionFingerprints() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val store = newStore(self)
        assertTrue(store.addLocal(signedCard(other), now = 10L, ownDevice = true))
        val epochBlob = keyEpochBlobFor(other, epoch = 2, minEpoch = 2)
        val epoch = epochBlob.decode<ClientKeyEpoch>()

        assertTrue(store.applyKeyEpoch(other.clientId, epochBlob))

        val details = store.roster.value.single { it.clientId == other.clientId }
        assertTrue(details.verified)
        assertEquals("android", details.platform)
        assertEquals(KeyFingerprint.short(other.publicKeySpki), details.identityKeyFingerprint)
        assertEquals(2, details.keyEpoch?.epoch)
        assertEquals(
            KeyFingerprint.short(epoch.operationalSigningKey),
            details.keyEpoch?.signingKeyFingerprint,
        )
        assertEquals(
            "the encryption row must fingerprint the HPKE public key",
            KeyFingerprint.short(epoch.hpkePublicKey),
            details.keyEpoch?.encryptionKeyFingerprint,
        )
        assertEquals(0L, details.keyEpoch?.notBefore)
        assertEquals(Long.MAX_VALUE, details.keyEpoch?.notAfter)
        assertEquals(2, details.keyEpoch?.minEpoch)
    }

    @Test
    fun peersNeedingKeyEpoch_listsMissingThenExpired_butNotUsable() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val store = newStore(self)
        assertTrue(store.addLocal(signedCard(other), now = 10L, ownDevice = true))

        // No key-epoch held → needs one.
        assertEquals(listOf(other.clientId), store.peersNeedingKeyEpoch(now = 1_000L))
        // A non-expired key-epoch → usable, no longer needs one.
        assertTrue(store.applyKeyEpoch(other.clientId, keyEpochBlobFor(other, epoch = 1)))
        assertTrue(store.peersNeedingKeyEpoch(now = 1_000L).isEmpty())
        // A later, EXPIRED key-epoch (notAfter in the past) is "available but not usable" → needs a refetch.
        assertTrue(
            store.applyKeyEpoch(
                other.clientId,
                keyEpochBlobFor(other, epoch = 2, minEpoch = 1, notAfter = 500L)
            )
        )
        assertEquals(listOf(other.clientId), store.peersNeedingKeyEpoch(now = 1_000L))
    }

    @Test
    fun strippedKeyEpoch_fromPairing_isUsableViaCard_butFlaggedForUpgrade_andNotRelayed() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val store = newStore(self)
        // Pairing order: pin the card first (identity anchor), then apply the identity-stripped QR key-epoch.
        assertTrue(store.addLocal(signedCard(other), now = 10L, ownDevice = true))
        assertTrue(
            store.applyKeyEpoch(
                other.clientId,
                keyEpochBlobFor(other, epoch = 1, stripIdentity = true)
            )
        )

        // Sealable (active), anchored by the CARD's identity (the key-epoch carried none).
        val peer = store.activePeers.value.single { it.clientId == other.clientId }
        assertEquals(1, peer.currentEpoch)
        assertEquals(
            Base64.getEncoder().encodeToString(other.publicKeySpki),
            peer.identityPublicKeyB64
        )
        // Flagged for a background upgrade to the full, relayable copy...
        assertEquals(listOf(other.clientId), store.peersNeedingKeyEpoch(now = 1_000L))
        // ...and NOT relayable to repair others (a card-less peer couldn't verify a stripped key-epoch).
        assertNull(store.currentKeyEpochBlob(other.clientId))
    }

    @Test
    fun applyIncomingTable_offersHeldKeyEpoch_whenSenderAdvertisesLowerEpoch() {
        val self = SoftwareIdentitySigner.generate()
        val sender = SoftwareIdentitySigner.generate()
        val subject = SoftwareIdentitySigner.generate()
        val store = newStore(self)
        assertTrue(store.addLocal(signedCard(subject), now = 10L, ownDevice = true))
        assertTrue(
            store.applyKeyEpoch(
                subject.clientId,
                keyEpochBlobFor(subject, epoch = 2, minEpoch = 2)
            )
        )

        // The sender's roster advertises the subject at epoch 1 (behind our epoch 2) → we offer our key-epoch.
        val table = TrustTable(
            listOf(
                TrustTableEntry(
                    subject.clientId,
                    TrustStatus.TRUSTED,
                    5L,
                    keyAvailable = true,
                    epoch = 1
                )
            )
        )
        val result = store.applyIncomingTable(sender.clientId, table)

        assertEquals(listOf(subject.clientId), result.keyEpochsToOffer.map { it.signerId })
    }

    @Test
    fun applyKeyEpoch_rejectsIdentityMismatch() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val imposter = SoftwareIdentitySigner.generate()
        val store = newStore(self)
        assertTrue(store.addLocal(signedCard(other), now = 10L, ownDevice = true))

        // A key-epoch for a different identity cannot be applied under `other`'s id (clientId is the identity
        // fingerprint; the blob's carried identity won't match the pinned one) — no key swap.
        assertFalse(store.applyKeyEpoch(other.clientId, keyEpochBlobFor(imposter, epoch = 1)))
        assertTrue(store.activePeers.value.isEmpty())
    }

    @Test
    fun legacyThreeSectionRoster_migratesNotQuarantines() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        // A pre-NS2 roster was signed over only (entries, cards, overlays) — no epoch section. On upgrade it
        // must MIGRATE (load, then re-sign as four sections) instead of false-quarantining.
        val store = seedLegacyStore(
            self,
            listOf(trusted(other)),
            mapOf(other.clientId to signedCard(other))
        )

        assertFalse("a valid legacy roster migrates, not quarantines", store.quarantined.value)
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
        val store = seedStore(
            self,
            listOf(trusted(other)),
            mapOf(other.clientId to signedCard(other)),
            sigSigner = null
        )

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
        val store = seedStore(
            self,
            listOf(trusted(other)),
            mapOf(other.clientId to signedCard(other)),
            sigSigner = other
        )

        assertTrue(store.quarantined.value)
        assertTrue(store.activePeers.value.isEmpty())
    }

    @Test
    fun quarantine_blocksAllMutators() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val third = SoftwareIdentitySigner.generate()
        val store = seedStore(
            self,
            listOf(trusted(other)),
            mapOf(other.clientId to signedCard(other)),
            sigSigner = null
        )
        assertTrue(store.quarantined.value)

        assertFalse(
            "addLocal must no-op while quarantined",
            store.addLocal(signedCard(third), now = 30L, ownDevice = false)
        )
        assertFalse(
            "applyCard must no-op while quarantined",
            store.applyCard(third.clientId, signedCard(third))
        )
        assertFalse(
            "revokeLocal must no-op while quarantined",
            store.revokeLocal(other.clientId, now = 40L)
        )
        // No accidental mutation: the original single device is still the only roster row.
        assertEquals(1, store.roster.value.size)
        assertEquals(TrustStatus.TRUSTED, store.roster.value.single().status)
    }

    @Test
    fun approveQuarantine_resumesAndKeepsRoster() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val epochs = EpochSection(
            peers = mapOf(
                other.clientId.value to ringOf(
                    keyEpochBlobFor(
                        other,
                        epoch = 1
                    ), floor = 1
                )
            )
        )
        val store = seedStore(
            self,
            listOf(trusted(other)),
            mapOf(other.clientId to signedCard(other)),
            sigSigner = null,
            epochs = epochs
        )
        assertTrue(store.quarantined.value)

        store.approveQuarantine()

        assertFalse(store.quarantined.value)
        assertEquals("the kept roster is active again", 1, store.activePeers.value.size)
    }

    @Test
    fun clearQuarantine_wipesRosterAndResumes() {
        val self = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val store = seedStore(
            self,
            listOf(trusted(other)),
            mapOf(other.clientId to signedCard(other)),
            sigSigner = null
        )
        assertTrue(store.quarantined.value)

        store.clearQuarantine()

        assertFalse(store.quarantined.value)
        assertTrue("wiped", store.roster.value.isEmpty())
        assertTrue(store.activePeers.value.isEmpty())
    }
}
