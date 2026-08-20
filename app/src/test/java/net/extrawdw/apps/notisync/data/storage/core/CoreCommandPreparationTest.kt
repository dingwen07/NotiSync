package net.extrawdw.apps.notisync.data.storage.core

import java.security.MessageDigest
import net.extrawdw.apps.notisync.data.activity.ActivityAction
import net.extrawdw.apps.notisync.data.activity.ActivityDeliveryMode
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgs
import net.extrawdw.apps.notisync.data.activity.ActivityRenderArgsCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreCommandPreparationTest {
    @Test
    fun acceptedInboundTypeSetAndPersistedTokensAreClosedAndStable() {
        assertEquals(
            listOf("data_sync.profile", "data_sync.trust", "data_sync.card"),
            CoreTrustCommandType.entries.map(CoreTrustCommandType::token),
        )
    }

    @Test
    fun canonicalDigestAndExpectedSnapshotDigestUseDefensiveCopies() {
        val canonical = byteArrayOf(1, 2, 3)
        val expected = ByteArray(TRUST_SNAPSHOT_DIGEST_BYTES) { 4 }
        val command = command(canonical = canonical, expectedDigest = expected)
        canonical[0] = 9
        expected[0] = 9

        val identity = command.prepareIdentity()
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1, 2, 3)),
            identity.commandDigest,
        )
        assertArrayEquals(ByteArray(TRUST_SNAPSHOT_DIGEST_BYTES) { 4 }, command.expectedSnapshotDigestCopy())

        val firstDigest = identity.commandDigest
        firstDigest[0] = (firstDigest[0].toInt() xor 0x7f).toByte()
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1, 2, 3)),
            identity.commandDigest,
        )
    }

    @Test
    fun activityProjectionIsTypedBoundedAndDeterministic() {
        val profile = command(
            commandId = "command-1",
            commandType = CoreTrustCommandType.DATA_SYNC_PROFILE,
            activity = CoreCommandActivity(
                action = ActivityAction.APPLIED,
                peerClientId = "peer-1",
                deliveryMode = ActivityDeliveryMode.FCM_RELAY_FETCH,
                renderArgs = ActivityRenderArgs.V1(revision = 7),
                occurredAt = 11,
            ),
        ).prepareActivity()!!
        assertEquals("profile", profile.feature)
        assertEquals("applied", profile.semanticAction)
        assertEquals("inbound", profile.direction)
        assertEquals("success", profile.outcome)
        assertEquals("request-1", profile.correlationId)
        assertEquals("fcm_relay_fetch", profile.deliveryMode)
        assertEquals(
            coreCommandActivityEventId(CoreTrustCommandType.DATA_SYNC_PROFILE, "command-1"),
            profile.eventId,
        )
        assertArrayEquals(
            ActivityRenderArgsCodec.encode(ActivityRenderArgs.V1(revision = 7)),
            profile.renderArgs,
        )

        val trustEvent = coreCommandActivityEventId(CoreTrustCommandType.DATA_SYNC_TRUST, "command-1")
        val cardEvent = coreCommandActivityEventId(CoreTrustCommandType.DATA_SYNC_CARD, "command-1")
        assertNotEquals(profile.eventId, trustEvent)
        assertNotEquals(trustEvent, cardEvent)

        val mutableProjection = profile.renderArgs
        mutableProjection[0] = 0
        assertArrayEquals(
            ActivityRenderArgsCodec.encode(ActivityRenderArgs.V1(revision = 7)),
            profile.renderArgs,
        )
    }

    @Test
    fun malformedOrUnboundedCommandIdentityIsRejectedBeforeStorage() {
        listOf("", " ", "has whitespace", "line\nbreak", "x".repeat(257)).forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                command(commandId = id).prepareIdentity()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            command(canonical = ByteArray(0)).prepareIdentity()
        }
        assertThrows(IllegalArgumentException::class.java) {
            command(canonical = ByteArray(MAX_CORE_COMMAND_CANONICAL_BYTES + 1)).prepareIdentity()
        }
        assertThrows(IllegalArgumentException::class.java) {
            command(expectedDigest = ByteArray(TRUST_SNAPSHOT_DIGEST_BYTES - 1)).prepareIdentity()
        }
        assertThrows(IllegalArgumentException::class.java) {
            command(expectedGeneration = 0).prepareIdentity()
        }
        assertThrows(IllegalArgumentException::class.java) {
            command(expectedIncarnationId = "not:portable").prepareIdentity()
        }
    }

    @Test
    fun durableResultBlobsHaveDefensiveAccessAndContentValueSemantics() {
        val digest = ByteArray(CORE_COMMAND_DIGEST_BYTES) { it.toByte() }
        val command = CoreCommandSnapshot(
            commandId = "command-1",
            authenticatedRequestId = "request-1",
            commandDigest = digest,
            commandType = CoreTrustCommandType.DATA_SYNC_PROFILE.token,
            outcome = CoreCommandOutcome.APPLIED,
            coreRevision = 1,
            appliedAt = 2,
        )
        val equalCommand = CoreCommandSnapshot(
            commandId = "command-1",
            authenticatedRequestId = "request-1",
            commandDigest = ByteArray(CORE_COMMAND_DIGEST_BYTES) { it.toByte() },
            commandType = CoreTrustCommandType.DATA_SYNC_PROFILE.token,
            outcome = CoreCommandOutcome.APPLIED,
            coreRevision = 1,
            appliedAt = 2,
        )
        digest[0] = 99
        val exposedDigest = command.commandDigest
        exposedDigest[1] = 99
        assertEquals(equalCommand, command)
        assertEquals(equalCommand.hashCode(), command.hashCode())
        assertEquals(0, command.commandDigest[0].toInt())
        assertEquals(1, command.commandDigest[1].toInt())
        assertTrue(command.toString().contains("commandDigest=<32 bytes>"))

        val args = byteArrayOf(1, 2, 3)
        val activity = CoreActivitySnapshot(
            commandId = "command-1",
            eventId = "event-1",
            operationalGeneration = 1,
            feature = "profile",
            semanticAction = "applied",
            direction = "inbound",
            outcome = "success",
            peerClientId = "peer-1",
            correlationId = "request-1",
            deliveryMode = "websocket",
            argsVersion = 1,
            renderArgs = args,
            occurredAt = 1,
            createdAt = 2,
        )
        val equalActivity = CoreActivitySnapshot(
            commandId = "command-1",
            eventId = "event-1",
            operationalGeneration = 1,
            feature = "profile",
            semanticAction = "applied",
            direction = "inbound",
            outcome = "success",
            peerClientId = "peer-1",
            correlationId = "request-1",
            deliveryMode = "websocket",
            argsVersion = 1,
            renderArgs = byteArrayOf(1, 2, 3),
            occurredAt = 1,
            createdAt = 2,
        )
        args[0] = 99
        val exposedArgs = activity.renderArgs
        exposedArgs[1] = 99
        assertEquals(equalActivity, activity)
        assertEquals(equalActivity.hashCode(), activity.hashCode())
        assertArrayEquals(byteArrayOf(1, 2, 3), activity.renderArgs)
        assertTrue(activity.toString().contains("renderArgs=<3 bytes>"))
    }

    private fun command(
        commandId: String = "command-1",
        canonical: ByteArray = byteArrayOf(1),
        commandType: CoreTrustCommandType = CoreTrustCommandType.DATA_SYNC_PROFILE,
        expectedGeneration: Long = 1,
        expectedIncarnationId: String = "test-operational-incarnation-1",
        expectedDigest: ByteArray? = ByteArray(TRUST_SNAPSHOT_DIGEST_BYTES),
        activity: CoreCommandActivity? = null,
    ): CoreTrustCommand = CoreTrustCommand(
        commandId = commandId,
        authenticatedRequestId = "request-1",
        canonicalCommand = canonical,
        commandType = commandType,
        expectedOperationalGeneration = expectedGeneration,
        expectedOperationalIncarnationId = expectedIncarnationId,
        expectedSnapshotDigest = expectedDigest,
        candidateSnapshot = TrustSnapshotInput.ThreeSection(
            entriesUtf8 = "[]".encodeToByteArray(),
            cardsUtf8 = "{}".encodeToByteArray(),
            overlaysUtf8 = "{}".encodeToByteArray(),
            signatureBase64UrlUtf8 = "signature".encodeToByteArray(),
        ),
        activity = activity,
    )
}
