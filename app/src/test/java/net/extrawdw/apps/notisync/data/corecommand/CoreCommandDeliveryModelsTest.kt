package net.extrawdw.apps.notisync.data.corecommand

import java.security.MessageDigest
import net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode
import net.extrawdw.apps.notisync.data.relay.AuthenticatedRelayToken
import net.extrawdw.apps.notisync.data.storage.core.CoreTrustCommand
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshotInput
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommand
import net.extrawdw.notisync.peer.foundation.FoundationTrustCommandDecodeResult
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.ProfileUpdate
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.TrustTable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreCommandDeliveryModelsTest {
    @Test
    fun deliveryOwnsCanonicalBytesAndDerivesItsDigest() {
        val canonical = byteArrayOf(1, 2, 3)
        val delivery = delivery(canonical)
        canonical[0] = 9

        val exposedCanonical = delivery.canonicalCommandCopy()
        val exposedDigest = delivery.commandDigestCopy()
        exposedCanonical[1] = 9
        exposedDigest[0] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3), delivery.canonicalCommandCopy())
        assertArrayEquals(sha256(byteArrayOf(1, 2, 3)), delivery.commandDigestCopy())
        assertFalse(delivery.toString().contains("1, 2, 3"))
    }

    @Test
    fun deliveryRequiresMessageBoundIdsAndMatchingTypedCommand() {
        assertFailsWith<IllegalArgumentException> { delivery(commandId = "other-command") }
        assertFailsWith<IllegalArgumentException> { delivery(requestId = "other-request") }
        assertFailsWith<IllegalArgumentException> {
            delivery(decodedCommand = decodedTrustCommand())
        }
    }

    @Test
    fun bindingRejectsDecodedIdentityAndReducerContinuityRebinding() {
        val delivery = delivery()
        assertFailsWith<IllegalArgumentException> {
            CoreCommandBinding.bind(
                delivery,
                DecodedCoreCommandIdentity(
                    "other-command",
                    delivery.authenticatedRequestId,
                    delivery.commandType,
                    decodedProfileCommand(),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CoreCommandBinding.bind(
                delivery,
                DecodedCoreCommandIdentity(
                    delivery.commandId,
                    delivery.authenticatedRequestId,
                    delivery.commandType,
                    decodedProfileCommand(),
                ),
            )
        }

        val binding = binding(delivery)
        val wrongContinuityCommand = command(binding, incarnation = "other-incarnation")
        assertFailsWith<IllegalArgumentException> {
            BoundCoreTrustCommand.bind(binding, wrongContinuityCommand)
        }
    }

    @Test
    fun bindingAndReceiptIdentityDefensivelyOwnDigestAndCanonicalBytes() {
        val binding = binding(delivery())
        val digest = binding.commandDigestCopy()
        val canonical = binding.canonicalCommandCopy()
        digest[0] = 9
        canonical[0] = 9
        val reference = binding.toReceiptIdentity()
        val referenceDigest = reference.commandDigestCopy()
        referenceDigest[0] = 8

        assertArrayEquals(sha256(byteArrayOf(1, 2, 3)), binding.commandDigestCopy())
        assertArrayEquals(byteArrayOf(1, 2, 3), binding.canonicalCommandCopy())
        assertArrayEquals(sha256(byteArrayOf(1, 2, 3)), reference.commandDigestCopy())
        assertTrue(binding.toString().contains("canonical=<3 bytes>"))
    }

    private companion object {
        const val MESSAGE_ID = "message-1"

        fun delivery(
            canonical: ByteArray = byteArrayOf(1, 2, 3),
            commandId: String = MESSAGE_ID,
            requestId: String = MESSAGE_ID,
            decodedCommand: FoundationTrustCommand = decodedProfileCommand(),
        ) = AuthenticatedCoreCommandDelivery(
            messageId = MESSAGE_ID,
            commandId = commandId,
            authenticatedRequestId = requestId,
            commandType = CoreCommandKind.DATA_SYNC_PROFILE,
            senderId = "sender-1",
            senderOwnDevice = true,
            signerEpoch = 1,
            signedCreatedAt = 10,
            deliveryMode = ActivityDeliveryMode.RELAY_DRAIN,
            decodedCommand = decodedCommand,
            canonicalCommand = canonical,
            authenticatedToken = AuthenticatedRelayToken.of(ByteArray(32) { 4 }),
            continuity = OperationalStorageContinuity(1, "incarnation-1"),
        )

        fun binding(delivery: AuthenticatedCoreCommandDelivery) = CoreCommandBinding.bind(
            delivery,
            DecodedCoreCommandIdentity(
                delivery.commandId,
                delivery.authenticatedRequestId,
                delivery.commandType,
                delivery.decodedCommand,
            ),
        )

        fun decodedProfileCommand(): FoundationTrustCommand =
            (FoundationTrustCommand.decode(
                ProtocolCodec.encodeToCbor(
                    DataSync(
                        kind = DataSyncKind.PROFILE,
                        profile = ProfileUpdate(ClientId("sender-1"), "name", "android", emptyList(), 10),
                    ),
                ),
            ) as FoundationTrustCommandDecodeResult.Ready).command

        fun decodedTrustCommand(): FoundationTrustCommand =
            (FoundationTrustCommand.fromDecoded(
                DataSync(DataSyncKind.TRUST, trust = TrustTable(emptyList())),
            ) as FoundationTrustCommandDecodeResult.Ready).command

        fun command(binding: CoreCommandBinding, incarnation: String) = CoreTrustCommand(
            commandId = binding.commandId,
            authenticatedRequestId = binding.authenticatedRequestId,
            canonicalCommand = binding.canonicalCommandCopy(),
            commandType = binding.commandType.toCoreType(),
            expectedOperationalGeneration = binding.expectedOperationalGeneration,
            expectedOperationalIncarnationId = incarnation,
            expectedSnapshotDigest = null,
            candidateSnapshot = TrustSnapshotInput.ThreeSection(
                entriesUtf8 = "[]".encodeToByteArray(),
                cardsUtf8 = "{}".encodeToByteArray(),
                overlaysUtf8 = "{}".encodeToByteArray(),
                signatureBase64UrlUtf8 = "signature".encodeToByteArray(),
            ),
        )

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T = try {
            block()
            throw AssertionError("Expected ${T::class.java.name}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
            failure
        }
    }
}
