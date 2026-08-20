package net.extrawdw.notisync.peer.trust

import java.util.Base64
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommand
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommandDecodeResult
import net.extrawdw.notisync.peer.ports.IncomingTrustPolicy
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Purpose
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.TrustTableEntry
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationTrustSnapshotReducerTest {
    private val reducer = FoundationTrustSnapshotReducer(IncomingTrustPolicy.MANUAL)

    @Test
    fun `profile reduction matches shipped store and stale duplicate preserves exact snapshot`() {
        val self = SoftwareIdentitySigner.generate()
        val peer = SoftwareIdentitySigner.generate()
        val setup = RecordingPersistence()
        val setupStore = TrustStore(setup, self, clock = { 100 })
        assertTrue(setupStore.addLocal(signedCard(peer, createdAt = 10, displayName = "before"), now = 10))
        val initial = setup.toSnapshot()
        val update = ProfileUpdate(peer.clientId, "after", "android", emptyList(), updatedAt = 20)

        val reduced = ready(
            reducer.reduce(
                initial,
                self,
                context(peer, ownDevice = false, signerEpoch = 1, signedCreatedAt = 30),
                command(DataSync(DataSyncKind.PROFILE, profile = update)),
            ),
        )
        assertTrue(reduced.changed)
        assertEquals(FoundationTrustEffect.ProfileChanged(peer.clientId, 20), reduced.effect)

        val baseline = RecordingPersistence(setup.valuesCopy())
        assertTrue(TrustStore(baseline, self, clock = { 30 }).applyProfile(update))
        assertSameSections(baseline.toSnapshot(), reduced.snapshotCopy(), self)

        val duplicate = ready(
            reducer.reduce(
                reduced.snapshotCopy(),
                self,
                context(peer, ownDevice = false, signerEpoch = 1, signedCreatedAt = 31),
                command(DataSync(DataSyncKind.PROFILE, profile = update)),
            ),
        )
        assertFalse(duplicate.changed)
        assertExactSnapshot(reduced.snapshotCopy(), duplicate.snapshotCopy())
    }

    @Test
    fun `trust fold matches shipped state machine for pending stale and conflict transitions`() {
        val self = SoftwareIdentitySigner.generate()
        val sender = SoftwareIdentitySigner.generate()
        val subject = SoftwareIdentitySigner.generate()
        val setup = RecordingPersistence()
        val setupStore = TrustStore(setup, self, clock = { 100 })
        assertTrue(setupStore.addLocal(signedCard(subject, 10), now = 10))
        val revoke = TrustTable(
            listOf(TrustTableEntry(subject.clientId, TrustStatus.REVOKED, 20, keyAvailable = true)),
        )
        val first = ready(
            reducer.reduce(
                setup.toSnapshot(),
                self,
                context(sender, ownDevice = true, signerEpoch = 0, signedCreatedAt = 30),
                command(DataSync(DataSyncKind.TRUST, trust = revoke)),
            ),
        )
        assertTrue(first.changed)
        val firstEffect = first.effect as FoundationTrustEffect.TrustChanged
        assertEquals(1, firstEffect.promptCount)
        assertFalse(firstEffect.hasConflict)
        assertEquals(
            TrustStatus.PENDING_REVOKE,
            TrustStore(RecordingPersistence(first.snapshotCopy().toValues()), self).statusOf(subject.clientId),
        )

        val reTrust = TrustTable(
            listOf(TrustTableEntry(subject.clientId, TrustStatus.TRUSTED, 21, keyAvailable = true)),
        )
        val conflict = ready(
            reducer.reduce(
                first.snapshotCopy(),
                self,
                context(sender, ownDevice = true, signerEpoch = 0, signedCreatedAt = 31),
                command(DataSync(DataSyncKind.TRUST, trust = reTrust)),
            ),
        )
        assertTrue((conflict.effect as FoundationTrustEffect.TrustChanged).hasConflict)
        assertEquals(
            TrustStatus.PENDING_REVOKE,
            TrustStore(RecordingPersistence(conflict.snapshotCopy().toValues()), self).statusOf(subject.clientId),
        )

        val stale = ready(
            reducer.reduce(
                conflict.snapshotCopy(),
                self,
                context(sender, ownDevice = true, signerEpoch = 0, signedCreatedAt = 32),
                command(DataSync(DataSyncKind.TRUST, trust = reTrust)),
            ),
        )
        assertFalse(stale.changed)
        assertExactSnapshot(conflict.snapshotCopy(), stale.snapshotCopy())
    }

    @Test
    fun `automatic trust decision matches shipped fold and uses authenticated chronology`() {
        val self = SoftwareIdentitySigner.generate()
        val sender = SoftwareIdentitySigner.generate()
        val subject = SoftwareIdentitySigner.generate()
        val initial = emptySnapshot(self)
        val table = TrustTable(
            listOf(
                TrustTableEntry(
                    subject.clientId,
                    TrustStatus.TRUSTED,
                    updatedAt = 20,
                    keyAvailable = false,
                    ownDevice = true,
                ),
            ),
        )
        val automaticReducer = FoundationTrustSnapshotReducer(IncomingTrustPolicy.TRUSTED_OWN_DEVICES)

        val reduced = ready(
            automaticReducer.reduce(
                initial,
                self,
                context(sender, ownDevice = true, signerEpoch = 0, signedCreatedAt = 30),
                command(DataSync(DataSyncKind.TRUST, trust = table)),
            ),
        )

        assertTrue(reduced.changed)
        assertEquals(30L, (reduced.effect as FoundationTrustEffect.TrustChanged).highestRevision)
        val baseline = RecordingPersistence(initial.toValues())
        val baselineStore = TrustStore(baseline, self)
        baselineStore.applyIncomingTable(sender.clientId, table, decisionTime = 30) { _, _ -> true }
        assertSameSections(baseline.toSnapshot(), reduced.snapshotCopy(), self)
    }

    @Test
    fun `card and epoch material apply independently with shipped verification and monotonic semantics`() {
        val self = SoftwareIdentitySigner.generate()
        val sender = SoftwareIdentitySigner.generate()
        val subject = SoftwareIdentitySigner.generate()
        val setup = RecordingPersistence()
        val initialStore = TrustStore(setup, self, clock = { 100 })
        val initialCard = signedCard(subject, createdAt = 10, displayName = "before")
        assertTrue(initialStore.addLocal(initialCard, now = 10))
        val newerCard = signedCard(subject, createdAt = 20, displayName = "after")
        val epoch = signedEpoch(subject, epoch = 1, minEpoch = 1)
        val delivery = CardDelivery(subject.clientId, card = newerCard, epochBlob = epoch)

        val reduced = ready(
            reducer.reduce(
                setup.toSnapshot(),
                self,
                context(sender, ownDevice = true, signerEpoch = 1, signedCreatedAt = 30),
                command(DataSync(DataSyncKind.CARD, card = delivery)),
            ),
        )
        assertTrue(reduced.changed)
        assertEquals(FoundationTrustEffect.None, reduced.effect)

        val baseline = RecordingPersistence(setup.valuesCopy())
        val baselineStore = TrustStore(baseline, self, clock = { 30 })
        assertTrue(baselineStore.applyCard(subject.clientId, newerCard))
        assertTrue(baselineStore.applyKeyEpoch(subject.clientId, epoch))
        assertSameSections(baseline.toSnapshot(), reduced.snapshotCopy(), self)

        val invalidCard = newerCard.copy(sig = newerCard.sig.copyOf().also { it[0] = (it[0] + 1).toByte() })
        val noOp = ready(
            reducer.reduce(
                reduced.snapshotCopy(),
                self,
                context(sender, ownDevice = true, signerEpoch = 1, signedCreatedAt = 31),
                command(DataSync(DataSyncKind.CARD, card = CardDelivery(subject.clientId, card = invalidCard))),
            ),
        )
        assertFalse(noOp.changed)
        assertExactSnapshot(reduced.snapshotCopy(), noOp.snapshotCopy())
    }

    @Test
    fun `unauthorized sender signer policy and profile substitution fail closed`() {
        val self = SoftwareIdentitySigner.generate()
        val sender = SoftwareIdentitySigner.generate()
        val other = SoftwareIdentitySigner.generate()
        val snapshot = emptySnapshot(self)
        val trust = command(
            DataSync(
                DataSyncKind.TRUST,
                trust = TrustTable(emptyList()),
            ),
        )
        assertEquals(
            FoundationTrustSecurityReason.UNAUTHORIZED_SENDER,
            blocked(reducer.reduce(snapshot, self, context(sender, false, 0, 10), trust)).reason,
        )
        assertEquals(
            FoundationTrustSecurityReason.SIGNER_POLICY_MISMATCH,
            blocked(reducer.reduce(snapshot, self, context(sender, true, 1, 10), trust)).reason,
        )

        val profile = command(
            DataSync(
                DataSyncKind.PROFILE,
                profile = ProfileUpdate(other.clientId, "name", "test", emptyList(), 10),
            ),
        )
        assertEquals(
            FoundationTrustSecurityReason.PROFILE_SUBJECT_MISMATCH,
            blocked(reducer.reduce(snapshot, self, context(sender, false, 1, 10), profile)).reason,
        )

        val ownProfile = command(
            DataSync(
                DataSyncKind.PROFILE,
                profile = ProfileUpdate(sender.clientId, "name", "test", emptyList(), 10),
            ),
        )
        assertEquals(
            FoundationTrustSecurityReason.SIGNER_POLICY_MISMATCH,
            blocked(reducer.reduce(snapshot, self, context(sender, false, 0, 10), ownProfile)).reason,
        )
    }

    @Test
    fun `bad authority signature blocks while bad self authenticating material is a terminal no op`() {
        val self = SoftwareIdentitySigner.generate()
        val sender = SoftwareIdentitySigner.generate()
        val snapshot = emptySnapshot(self)
        val badSignature = SignedTrustSnapshot(
            snapshot.format,
            snapshot.entriesUtf8Copy(),
            snapshot.cardsUtf8Copy(),
            snapshot.overlaysUtf8Copy(),
            snapshot.epochsUtf8CopyOrNull(),
            snapshot.signatureBase64UrlUtf8Copy().also { it[0] = if (it[0] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte() },
        )
        val emptyCard = command(
            DataSync(DataSyncKind.CARD, card = CardDelivery(sender.clientId)),
        )
        assertEquals(
            FoundationTrustSecurityReason.INVALID_SIGNED_SNAPSHOT,
            blocked(reducer.reduce(badSignature, self, context(sender, true, 1, 10), emptyCard)).reason,
        )

        val subject = SoftwareIdentitySigner.generate()
        val invalid = signedCard(subject, 1).copy(sig = byteArrayOf(1, 2, 3))
        val noOp = ready(
            reducer.reduce(
                snapshot,
                self,
                context(sender, true, 1, 10),
                command(DataSync(DataSyncKind.CARD, card = CardDelivery(subject.clientId, card = invalid))),
            ),
        )
        assertFalse(noOp.changed)
        assertExactSnapshot(snapshot, noOp.snapshotCopy())
    }

    @Test
    fun `three section grammar remains byte exact on no op and upgrades only on mutation`() {
        val self = SoftwareIdentitySigner.generate()
        val peer = SoftwareIdentitySigner.generate()
        val entries = ProtocolCodec.encodeToJson(
            listOf(TrustEntry(peer.clientId, TrustStatus.TRUSTED, 10, ownDevice = true)),
        )
        val card = signedCard(peer, 10)
        val cards = ProtocolCodec.encodeToJson(
            mapOf(peer.clientId.value to Base64.getEncoder().encodeToString(ProtocolCodec.encodeToCbor(card))),
        )
        val overlays = "{}"
        val signature = signLegacyThree(self, entries, cards, overlays)
        val three = SignedTrustSnapshot(
            SignedTrustSnapshotFormat.THREE_SECTION,
            entries.encodeToByteArray(),
            cards.encodeToByteArray(),
            overlays.encodeToByteArray(),
            null,
            signature.encodeToByteArray(),
        )
        val stale = command(
            DataSync(
                DataSyncKind.PROFILE,
                profile = ProfileUpdate(peer.clientId, "same", "test", emptyList(), updatedAt = 10),
            ),
        )
        val noOp = ready(reducer.reduce(three, self, context(peer, false, 1, 20), stale))
        assertFalse(noOp.changed)
        assertEquals(SignedTrustSnapshotFormat.THREE_SECTION, noOp.snapshotCopy().format)
        assertExactSnapshot(three, noOp.snapshotCopy())

        val update = command(
            DataSync(
                DataSyncKind.PROFILE,
                profile = ProfileUpdate(peer.clientId, "new", "test", emptyList(), updatedAt = 11),
            ),
        )
        val changed = ready(reducer.reduce(three, self, context(peer, false, 1, 20), update))
        assertTrue(changed.changed)
        assertEquals(SignedTrustSnapshotFormat.FOUR_SECTION, changed.snapshotCopy().format)
        assertSnapshotVerifies(changed.snapshotCopy(), self)
    }

    private fun context(
        sender: IdentitySigner,
        ownDevice: Boolean,
        signerEpoch: Int,
        signedCreatedAt: Long,
    ) = FoundationTrustCommandContext(sender.clientId, ownDevice, signerEpoch, signedCreatedAt)

    private fun command(sync: DataSync): FoundationTrustCommand =
        (FoundationTrustCommand.decode(ProtocolCodec.encodeToCbor(sync)) as FoundationTrustCommandDecodeResult.Ready)
            .command

    private fun emptySnapshot(signer: IdentitySigner): SignedTrustSnapshot {
        val entries = "[]"
        val cards = "{}"
        val overlays = "{}"
        val epochs = ProtocolCodec.encodeToJson(EpochSection())
        return SignedTrustSnapshot(
            SignedTrustSnapshotFormat.FOUR_SECTION,
            entries.encodeToByteArray(),
            cards.encodeToByteArray(),
            overlays.encodeToByteArray(),
            epochs.encodeToByteArray(),
            TrustStoreSigning.sign(signer, entries, cards, overlays, epochs).encodeToByteArray(),
        )
    }

    private fun signedCard(
        signer: IdentitySigner,
        createdAt: Long,
        displayName: String = "Peer",
    ): SignedBlob {
        val card = ClientCard(
            clientId = signer.clientId,
            identityPublicKey = signer.publicKeySpki,
            displayName = displayName,
            platform = "test",
            capabilities = emptyList(),
            createdAt = createdAt,
        )
        val payload = ProtocolCodec.encodeToCbor(card)
        return SignedBlob(SignedType.CLIENT_CARD, signerId = signer.clientId, payload = payload, sig = signer.sign(payload))
    }

    private fun signedEpoch(identity: IdentitySigner, epoch: Int, minEpoch: Int): SignedBlob {
        val operational = SoftwareOperationalSigner.generate(identity.clientId, epoch)
        val value = ClientKeyEpoch(
            clientId = identity.clientId,
            identityPublicKey = identity.publicKeySpki,
            epoch = epoch,
            operationalSigningKey = operational.operationalPublicKeySpki,
            hpkePublicKey = ByteArray(32) { (it + epoch).toByte() },
            purposes = listOf(Purpose.ENVELOPE_SIGN, Purpose.HPKE_SEAL),
            notBefore = 1,
            notAfter = 1_000,
            minEpoch = minEpoch,
        )
        val payload = ProtocolCodec.encodeToCbor(value)
        return SignedBlob(SignedType.KEY_EPOCH, signerId = identity.clientId, payload = payload, sig = identity.sign(payload))
    }

    private fun ready(result: FoundationTrustReductionResult) =
        result as FoundationTrustReductionResult.Ready

    private fun blocked(result: FoundationTrustReductionResult) =
        result as FoundationTrustReductionResult.SecurityBlocked

    private fun assertSameSections(
        expected: SignedTrustSnapshot,
        actual: SignedTrustSnapshot,
        signer: IdentitySigner,
    ) {
        assertEquals(expected.format, actual.format)
        assertArrayEquals(expected.entriesUtf8Copy(), actual.entriesUtf8Copy())
        assertArrayEquals(expected.cardsUtf8Copy(), actual.cardsUtf8Copy())
        assertArrayEquals(expected.overlaysUtf8Copy(), actual.overlaysUtf8Copy())
        assertArrayEquals(expected.epochsUtf8CopyOrNull(), actual.epochsUtf8CopyOrNull())
        assertSnapshotVerifies(actual, signer)
    }

    private fun assertExactSnapshot(expected: SignedTrustSnapshot, actual: SignedTrustSnapshot) {
        assertSameSectionsWithoutVerification(expected, actual)
        assertArrayEquals(expected.signatureBase64UrlUtf8Copy(), actual.signatureBase64UrlUtf8Copy())
    }

    private fun assertSameSectionsWithoutVerification(expected: SignedTrustSnapshot, actual: SignedTrustSnapshot) {
        assertEquals(expected.format, actual.format)
        assertArrayEquals(expected.entriesUtf8Copy(), actual.entriesUtf8Copy())
        assertArrayEquals(expected.cardsUtf8Copy(), actual.cardsUtf8Copy())
        assertArrayEquals(expected.overlaysUtf8Copy(), actual.overlaysUtf8Copy())
        assertArrayEquals(expected.epochsUtf8CopyOrNull(), actual.epochsUtf8CopyOrNull())
    }

    private fun assertSnapshotVerifies(snapshot: SignedTrustSnapshot, signer: IdentitySigner) {
        val entries = snapshot.entriesUtf8Copy().decodeToString()
        val cards = snapshot.cardsUtf8Copy().decodeToString()
        val overlays = snapshot.overlaysUtf8Copy().decodeToString()
        val signature = snapshot.signatureBase64UrlUtf8Copy().decodeToString()
        val verifies = when (snapshot.format) {
            SignedTrustSnapshotFormat.THREE_SECTION -> TrustStoreSigning.verifyLegacyThreeSection(
                signer.publicKeySpki,
                signer.clientId,
                entries,
                cards,
                overlays,
                signature,
            )
            SignedTrustSnapshotFormat.FOUR_SECTION -> TrustStoreSigning.verify(
                signer.publicKeySpki,
                signer.clientId,
                entries,
                cards,
                overlays,
                requireNotNull(snapshot.epochsUtf8CopyOrNull()).decodeToString(),
                signature,
            )
        }
        assertTrue(verifies)
    }

    private fun signLegacyThree(
        signer: IdentitySigner,
        entries: String,
        cards: String,
        overlays: String,
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun digest(value: String) = encoder.encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()),
        )
        val canonical = buildString {
            append(TrustStoreSigning.VERSION).append('\n')
            append(signer.clientId.value).append('\n')
            append(digest(entries)).append('\n')
            append(digest(cards)).append('\n')
            append(digest(overlays))
        }.encodeToByteArray()
        return encoder.encodeToString(signer.sign(canonical))
    }

    private class RecordingPersistence(initial: Map<String, String> = emptyMap()) : TrustPersistence {
        private val values = LinkedHashMap(initial)

        override fun read(key: String): String? = values[key]

        override fun write(values: Map<String, String?>) {
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
        }

        fun valuesCopy(): Map<String, String> = LinkedHashMap(values)

        fun toSnapshot(): SignedTrustSnapshot {
            val epochs = values[TrustStore.EPOCHS_KEY]
            return SignedTrustSnapshot(
                format = if (epochs == null) SignedTrustSnapshotFormat.THREE_SECTION else SignedTrustSnapshotFormat.FOUR_SECTION,
                entriesUtf8 = requireNotNull(values[TrustStore.ENTRIES_KEY]).encodeToByteArray(),
                cardsUtf8 = requireNotNull(values[TrustStore.CARDS_KEY]).encodeToByteArray(),
                overlaysUtf8 = requireNotNull(values[TrustStore.OVERLAYS_KEY]).encodeToByteArray(),
                epochsUtf8 = epochs?.encodeToByteArray(),
                signatureBase64UrlUtf8 = requireNotNull(values[TrustStore.SIGNATURE_KEY]).encodeToByteArray(),
            )
        }
    }
}

private fun SignedTrustSnapshot.toValues(): Map<String, String> = buildMap {
    put(TrustStore.ENTRIES_KEY, entriesUtf8Copy().decodeToString())
    put(TrustStore.CARDS_KEY, cardsUtf8Copy().decodeToString())
    put(TrustStore.OVERLAYS_KEY, overlaysUtf8Copy().decodeToString())
    epochsUtf8CopyOrNull()?.let { put(TrustStore.EPOCHS_KEY, it.decodeToString()) }
    put(TrustStore.SIGNATURE_KEY, signatureBase64UrlUtf8Copy().decodeToString())
}
