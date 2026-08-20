package net.extrawdw.apps.notisync.data.corecommand.preparation

import java.util.Base64
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.activity.ActivityAction
import net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode
import net.extrawdw.apps.notisync.data.corecommand.AuthenticatedCoreCommandDelivery
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandBinding
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandIdentityPreparationResult
import net.extrawdw.apps.notisync.data.corecommand.CoreCommandKind
import net.extrawdw.apps.notisync.data.corecommand.CoreTrustCommandPreparationResult
import net.extrawdw.apps.notisync.data.corecommand.OperationalStorageContinuity
import net.extrawdw.apps.notisync.data.corecommand.toCoreType
import net.extrawdw.apps.notisync.data.relay.AuthenticatedRelayToken
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshotInput
import net.extrawdw.apps.notisync.data.storage.core.computeTrustSnapshotDigest
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommand
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommandDecodeResult
import net.extrawdw.notisync.peer.ports.IncomingTrustPolicy
import net.extrawdw.notisync.peer.trust.EpochSection
import net.extrawdw.notisync.peer.trust.SignedTrustSnapshot
import net.extrawdw.notisync.peer.trust.SignedTrustSnapshotFormat
import net.extrawdw.notisync.peer.trust.TrustEntry
import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.TrustStatus
import net.extrawdw.notisync.protocol.TrustTable
import net.extrawdw.notisync.protocol.crypto.IdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCoreCommandPreparationTest {
    @Test
    fun appliedProfileBindsExactDeliverySnapshotSignerAndPrivacySafeActivity() = runTest {
        val fixture = fixture()
        val delivery = fixture.profileDelivery(revision = 20)
        val binding = fixture.decodeAndBind(delivery)

        val result = fixture.preparation.reduceAndSign(delivery, binding)

        val command = (result as CoreTrustCommandPreparationResult.Ready).command.commandForAuthority()
        assertEquals(binding.commandId, command.commandId)
        assertEquals(binding.authenticatedRequestId, command.authenticatedRequestId)
        assertEquals(binding.commandType.toCoreType(), command.commandType)
        assertEquals(binding.expectedOperationalGeneration, command.expectedOperationalGeneration)
        assertEquals(binding.expectedOperationalIncarnationId, command.expectedOperationalIncarnationId)
        assertArrayEquals(delivery.canonicalCommandCopy(), command.canonicalCommandCopy())
        assertArrayEquals(fixture.snapshot.trustSnapshotDigestCopy(), command.expectedSnapshotDigestCopy())
        assertEquals(ActivityAction.APPLIED, command.activity?.action)
        assertEquals(fixture.peer.clientId.value, command.activity?.peerClientId)
        assertEquals(ActivityDeliveryMode.RELAY_DRAIN, command.activity?.deliveryMode)
        assertEquals(20L, command.activity?.renderArgs?.revision)
        assertEquals(SIGNED_CREATED_AT, command.activity?.occurredAt)
        val exact = command.candidateSnapshot.exactBytes()
        assertTrue(
            TrustStoreSigning.verify(
                fixture.self.publicKeySpki,
                fixture.self.clientId,
                exact.entriesUtf8.decodeToString(),
                exact.cardsUtf8.decodeToString(),
                exact.overlaysUtf8.decodeToString(),
                requireNotNull(exact.epochsUtf8).decodeToString(),
                exact.signatureBase64UrlUtf8.decodeToString(),
            ),
        )
    }

    @Test
    fun staleProfileProducesExactSupersededCandidateWithoutActivityOrResigning() = runTest {
        val fixture = fixture()
        val delivery = fixture.profileDelivery(revision = 10)
        val binding = fixture.decodeAndBind(delivery)

        val result = fixture.preparation.reduceAndSign(delivery, binding)

        val command = (result as CoreTrustCommandPreparationResult.Ready).command.commandForAuthority()
        assertNull(command.activity)
        val candidate = command.candidateSnapshot.exactBytes()
        val original = fixture.snapshot.trustSnapshotCopy()
        assertArrayEquals(original.entriesUtf8Copy(), candidate.entriesUtf8)
        assertArrayEquals(original.cardsUtf8Copy(), candidate.cardsUtf8)
        assertArrayEquals(original.overlaysUtf8Copy(), candidate.overlaysUtf8)
        assertArrayEquals(original.epochsUtf8CopyOrNull(), candidate.epochsUtf8)
        assertArrayEquals(original.signatureBase64UrlUtf8Copy(), candidate.signatureBase64UrlUtf8)
    }

    @Test
    fun decodeIdentityUsesTheCarriedTypedCommandWithoutRedecodingCanonicalBytesOrReadingStorage() = runTest {
        val fixture = fixture()
        val typedCommand = foundationCommand(
            DataSync(
                DataSyncKind.PROFILE,
                profile = ProfileUpdate(fixture.peer.clientId, "name", "test", emptyList(), 20),
            ),
        )
        val delivery = fixture.delivery(
            CoreCommandKind.DATA_SYNC_PROFILE,
            // Deliberately not CBOR: the authenticated router owns the sole decode before this boundary.
            byteArrayOf(1),
            signerEpoch = 1,
            decodedCommand = typedCommand,
        )

        val result = fixture.preparation.decodeIdentity(delivery) as CoreCommandIdentityPreparationResult.Ready

        assertSame(typedCommand, result.identity.decodedCommand)
        assertEquals(0, fixture.readerCalls)
    }

    @Test
    fun identityAndSnapshotIntegrityFailuresBlockWhileMissingExistingAliasRetries() = runTest {
        val fixture = fixture()
        val delivery = fixture.profileDelivery(20)
        val binding = fixture.decodeAndBind(delivery)

        fixture.loadedSigner = null
        assertTrue(
            fixture.preparation.reduceAndSign(delivery, binding) is CoreTrustCommandPreparationResult.Retryable,
        )

        fixture.loadedSigner = SoftwareIdentitySigner.generate()
        assertTrue(
            fixture.preparation.reduceAndSign(delivery, binding) is CoreTrustCommandPreparationResult.SecurityBlocked,
        )

        fixture.loadedSigner = fixture.self
        val original = fixture.snapshot.trustSnapshotCopy()
        val tampered = SignedTrustSnapshot(
            original.format,
            original.entriesUtf8Copy(),
            original.cardsUtf8Copy(),
            original.overlaysUtf8Copy(),
            original.epochsUtf8CopyOrNull(),
            original.signatureBase64UrlUtf8Copy().also { it[0] = if (it[0] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte() },
        )
        fixture.readResult = CoreTrustPreparationSnapshotResult.Ready(
            CoreTrustPreparationSnapshot(
                fixture.snapshot.identityAlias,
                fixture.snapshot.identityClientId,
                fixture.snapshot.identityPublicSpkiCopy(),
                tampered,
                fixture.snapshot.trustSnapshotDigestCopy(),
            ),
        )
        assertTrue(
            fixture.preparation.reduceAndSign(delivery, binding) is CoreTrustCommandPreparationResult.SecurityBlocked,
        )
    }

    @Test
    fun trustAndCardRejectNonOwnSenderAndProfileRejectsSubjectSubstitution() = runTest {
        val fixture = fixture()
        val trust = fixture.delivery(
            CoreCommandKind.DATA_SYNC_TRUST,
            ProtocolCodec.encodeToCbor(DataSync(DataSyncKind.TRUST, trust = TrustTable(emptyList()))),
            senderOwnDevice = false,
            signerEpoch = 0,
        )
        assertTrue(
            fixture.preparation.reduceAndSign(trust, fixture.decodeAndBind(trust)) is
                CoreTrustCommandPreparationResult.SecurityBlocked,
        )

        val card = fixture.delivery(
            CoreCommandKind.DATA_SYNC_CARD,
            ProtocolCodec.encodeToCbor(
                DataSync(DataSyncKind.CARD, card = CardDelivery(fixture.peer.clientId)),
            ),
            senderOwnDevice = false,
            signerEpoch = 1,
        )
        assertTrue(
            fixture.preparation.reduceAndSign(card, fixture.decodeAndBind(card)) is
                CoreTrustCommandPreparationResult.SecurityBlocked,
        )

        val spoof = fixture.delivery(
            CoreCommandKind.DATA_SYNC_PROFILE,
            ProtocolCodec.encodeToCbor(
                DataSync(
                    DataSyncKind.PROFILE,
                    profile = ProfileUpdate(fixture.peer.clientId, "spoof", "test", emptyList(), 20),
                ),
            ),
            senderId = fixture.self.clientId.value,
            senderOwnDevice = false,
            signerEpoch = 1,
        )
        assertTrue(
            fixture.preparation.reduceAndSign(spoof, fixture.decodeAndBind(spoof)) is
                CoreTrustCommandPreparationResult.SecurityBlocked,
        )
    }

    @Test
    fun changedAuthenticatedContextOrDecodedCommandCannotReuseAnExistingBinding() = runTest {
        val fixture = fixture()
        val delivery = fixture.profileDelivery(20)
        val binding = fixture.decodeAndBind(delivery)
        val substitutions = listOf(
            fixture.delivery(
                CoreCommandKind.DATA_SYNC_PROFILE,
                delivery.canonicalCommandCopy(),
                senderId = fixture.self.clientId.value,
                senderOwnDevice = false,
                signerEpoch = 1,
                decodedCommand = delivery.decodedCommand,
            ),
            fixture.delivery(
                CoreCommandKind.DATA_SYNC_PROFILE,
                delivery.canonicalCommandCopy(),
                senderOwnDevice = true,
                signerEpoch = 1,
                decodedCommand = delivery.decodedCommand,
            ),
            fixture.delivery(
                CoreCommandKind.DATA_SYNC_PROFILE,
                delivery.canonicalCommandCopy(),
                senderOwnDevice = false,
                signerEpoch = 2,
                decodedCommand = delivery.decodedCommand,
            ),
            fixture.delivery(
                CoreCommandKind.DATA_SYNC_PROFILE,
                delivery.canonicalCommandCopy(),
                senderOwnDevice = false,
                signerEpoch = 1,
                signedCreatedAt = SIGNED_CREATED_AT + 1,
                decodedCommand = delivery.decodedCommand,
            ),
            fixture.delivery(
                CoreCommandKind.DATA_SYNC_PROFILE,
                delivery.canonicalCommandCopy(),
                senderOwnDevice = false,
                signerEpoch = 1,
                deliveryMode = ActivityDeliveryMode.WEBSOCKET,
                decodedCommand = delivery.decodedCommand,
            ),
            fixture.delivery(
                CoreCommandKind.DATA_SYNC_PROFILE,
                delivery.canonicalCommandCopy(),
                senderOwnDevice = false,
                signerEpoch = 1,
            ),
        )

        substitutions.forEach { substituted ->
            assertTrue(
                fixture.preparation.reduceAndSign(substituted, binding) is
                    CoreTrustCommandPreparationResult.SecurityBlocked,
            )
        }
        assertEquals(0, fixture.readerCalls)
    }

    @Test
    fun cancellationFromReaderOrExistingSignerLoaderPropagatesUnchanged() = runTest {
        val fixture = fixture()
        val delivery = fixture.profileDelivery(20)
        val binding = fixture.decodeAndBind(delivery)
        val readCancellation = CancellationException("reader cancelled")
        fixture.readFailure = readCancellation
        assertSame(
            readCancellation,
            assertSuspendFailsWith<CancellationException> {
                fixture.preparation.reduceAndSign(delivery, binding)
            },
        )

        fixture.readFailure = null
        val signerCancellation = CancellationException("signer load cancelled")
        fixture.loaderFailure = signerCancellation
        assertSame(
            signerCancellation,
            assertSuspendFailsWith<CancellationException> {
                fixture.preparation.reduceAndSign(delivery, binding)
            },
        )
    }

    @Test
    fun readSnapshotAndDeliveryDefensivelyOwnEverySensitiveByteArray() {
        val self = SoftwareIdentitySigner.generate()
        val peer = SoftwareIdentitySigner.generate()
        val material = buildSnapshot(self, peer)
        val spki = self.publicKeySpki.copyOf()
        val digest = material.digest.copyOf()
        val snapshot = CoreTrustPreparationSnapshot(ALIAS, self.clientId.value, spki, material.snapshot, digest)
        spki[0] = (spki[0].toInt() xor 1).toByte()
        digest[0] = (digest[0].toInt() xor 1).toByte()
        val exposedSpki = snapshot.identityPublicSpkiCopy()
        val exposedDigest = snapshot.trustSnapshotDigestCopy()
        exposedSpki[0] = (exposedSpki[0].toInt() xor 1).toByte()
        exposedDigest[0] = (exposedDigest[0].toInt() xor 1).toByte()

        assertArrayEquals(self.publicKeySpki, snapshot.identityPublicSpkiCopy())
        assertArrayEquals(material.digest, snapshot.trustSnapshotDigestCopy())
        assertFalse(snapshot.toString().contains(ALIAS))
    }

    private fun fixture(): Fixture {
        val self = SoftwareIdentitySigner.generate()
        val peer = SoftwareIdentitySigner.generate()
        val material = buildSnapshot(self, peer)
        val snapshot = CoreTrustPreparationSnapshot(
            ALIAS,
            self.clientId.value,
            self.publicKeySpki,
            material.snapshot,
            material.digest,
        )
        return Fixture(self, peer, snapshot)
    }

    private class Fixture(
        val self: SoftwareIdentitySigner,
        val peer: SoftwareIdentitySigner,
        val snapshot: CoreTrustPreparationSnapshot,
    ) {
        var readResult: CoreTrustPreparationSnapshotResult = CoreTrustPreparationSnapshotResult.Ready(snapshot)
        var loadedSigner: IdentitySigner? = self
        var readFailure: Throwable? = null
        var loaderFailure: Throwable? = null
        var readerCalls = 0

        val preparation = DefaultCoreCommandPreparation(
            snapshotReader = CoreTrustPreparationSnapshotReader {
                readerCalls++
                readFailure?.let { throw it }
                readResult
            },
            identitySignerLoader = ExistingIdentitySignerLoader {
                loaderFailure?.let { throw it }
                loadedSigner
            },
            incomingTrustPolicy = IncomingTrustPolicy.MANUAL,
        )

        fun profileDelivery(revision: Long): AuthenticatedCoreCommandDelivery = delivery(
            CoreCommandKind.DATA_SYNC_PROFILE,
            ProtocolCodec.encodeToCbor(
                DataSync(
                    DataSyncKind.PROFILE,
                    profile = ProfileUpdate(peer.clientId, "after", "android", emptyList(), revision),
                ),
            ),
            senderId = peer.clientId.value,
            senderOwnDevice = false,
            signerEpoch = 1,
        )

        fun delivery(
            kind: CoreCommandKind,
            canonical: ByteArray,
            senderId: String = peer.clientId.value,
            senderOwnDevice: Boolean = true,
            signerEpoch: Int,
            signedCreatedAt: Long = SIGNED_CREATED_AT,
            deliveryMode: ActivityDeliveryMode = ActivityDeliveryMode.RELAY_DRAIN,
            decodedCommand: FoundationTrustCommand = foundationCommand(
                ProtocolCodec.decodeFromCbor<DataSync>(canonical),
            ),
        ) = AuthenticatedCoreCommandDelivery(
            messageId = MESSAGE_ID,
            commandId = MESSAGE_ID,
            authenticatedRequestId = MESSAGE_ID,
            commandType = kind,
            senderId = senderId,
            senderOwnDevice = senderOwnDevice,
            signerEpoch = signerEpoch,
            signedCreatedAt = signedCreatedAt,
            deliveryMode = deliveryMode,
            decodedCommand = decodedCommand,
            canonicalCommand = canonical,
            authenticatedToken = AuthenticatedRelayToken.of(ByteArray(32) { 7 }),
            continuity = OperationalStorageContinuity(3, "operational-incarnation-3"),
        )

        suspend fun decodeAndBind(delivery: AuthenticatedCoreCommandDelivery): CoreCommandBinding {
            val decoded = preparation.decodeIdentity(delivery) as CoreCommandIdentityPreparationResult.Ready
            return CoreCommandBinding.bind(delivery, decoded.identity)
        }
    }

    private data class SnapshotMaterial(
        val snapshot: SignedTrustSnapshot,
        val digest: ByteArray,
    )

    private companion object {
        const val ALIAS = "notisync.identity.v1"
        const val MESSAGE_ID = "message-1"
        const val SIGNED_CREATED_AT = 30L

        fun foundationCommand(sync: DataSync): FoundationTrustCommand =
            (FoundationTrustCommand.fromDecoded(sync) as FoundationTrustCommandDecodeResult.Ready).command

        fun buildSnapshot(
            self: SoftwareIdentitySigner,
            peer: SoftwareIdentitySigner,
        ): SnapshotMaterial {
            val card = signedCard(peer, createdAt = 10)
            val entries = ProtocolCodec.encodeToJson(
                listOf(TrustEntry(peer.clientId, TrustStatus.TRUSTED, updatedAt = 10, ownDevice = false)),
            )
            val cards = ProtocolCodec.encodeToJson(
                mapOf(peer.clientId.value to Base64.getEncoder().encodeToString(ProtocolCodec.encodeToCbor(card))),
            )
            val overlays = "{}"
            val epochs = ProtocolCodec.encodeToJson(EpochSection())
            val signature = TrustStoreSigning.sign(self, entries, cards, overlays, epochs)
            val snapshot = SignedTrustSnapshot(
                SignedTrustSnapshotFormat.FOUR_SECTION,
                entries.encodeToByteArray(),
                cards.encodeToByteArray(),
                overlays.encodeToByteArray(),
                epochs.encodeToByteArray(),
                signature.encodeToByteArray(),
            )
            val input = TrustSnapshotInput.FourSection(
                entries.encodeToByteArray(),
                cards.encodeToByteArray(),
                overlays.encodeToByteArray(),
                epochs.encodeToByteArray(),
                signature.encodeToByteArray(),
            )
            return SnapshotMaterial(
                snapshot,
                input.exactBytes().computeTrustSnapshotDigest(self.clientId.value),
            )
        }

        fun signedCard(signer: SoftwareIdentitySigner, createdAt: Long): SignedBlob {
            val card = ClientCard(
                clientId = signer.clientId,
                identityPublicKey = signer.publicKeySpki,
                displayName = "before",
                platform = "test",
                capabilities = emptyList(),
                createdAt = createdAt,
            )
            val payload = ProtocolCodec.encodeToCbor(card)
            return SignedBlob(
                SignedType.CLIENT_CARD,
                signerId = signer.clientId,
                payload = payload,
                sig = signer.sign(payload),
            )
        }

        suspend inline fun <reified T : Throwable> assertSuspendFailsWith(
            crossinline block: suspend () -> Unit,
        ): T = try {
            block()
            throw AssertionError("Expected ${T::class.java.name}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
            failure
        }
    }
}
