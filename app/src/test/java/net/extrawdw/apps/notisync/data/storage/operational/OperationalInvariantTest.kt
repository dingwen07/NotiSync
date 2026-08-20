package net.extrawdw.apps.notisync.data.storage.operational

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalInvariantTest {
    @Test
    fun persistedTokensRoundTripAndUnknownValuesFailClosed() {
        assertEquals(
            MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT,
            MessageDedupEvidenceKind.decode("authenticated_fingerprint"),
        )
        assertEquals(AndroidPolicyScope.GROUP, AndroidPolicyScope.decode("group"))
        assertEquals(
            RelayBatchPresentationKind.DISMISSAL,
            RelayBatchPresentationKind.decode("dismissal"),
        )
        assertEquals(
            SshLifecycleCandidatePurpose.OPERATIONAL,
            SshLifecycleCandidatePurpose.decode("operational"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SealResponsePayloadFormat.decode("future_state")
        }
    }

    @Test
    fun relayBatchScratchRequiresCanonicalAuthenticatedIdentity() {
        RelayBatchStageEntity(
            "message",
            ByteArray(OperationalStorageLimits.SHA256_BYTES),
            conflict = false,
            RelayBatchPresentationKind.NONE,
        ).requireValid()
        assertThrows(IllegalArgumentException::class.java) {
            RelayBatchStageEntity(
                "message",
                ByteArray(31),
                conflict = false,
                RelayBatchPresentationKind.NONE,
            ).requireValid()
        }
    }

    @Test
    fun legacyDedupNeverSynthesizesFingerprintAndCannotBeANewOutcome() {
        val legacy = handled(
            fingerprint = null,
            evidenceKind = MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY,
        )
        legacy.requireValid(allowLegacyMessageIdOnly = true)
        assertThrows(IllegalArgumentException::class.java) {
            legacy.requireValid(allowLegacyMessageIdOnly = false)
        }

        assertThrows(IllegalArgumentException::class.java) {
            handled(
                fingerprint = byteArrayOf(1),
                evidenceKind = MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY,
            ).requireValid(allowLegacyMessageIdOnly = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            handled(
                fingerprint = null,
                evidenceKind = MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT,
            ).requireValid(allowLegacyMessageIdOnly = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            handled(
                fingerprint = byteArrayOf(1),
                evidenceKind = MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT,
            ).requireValid(allowLegacyMessageIdOnly = false)
        }
        handled(
            fingerprint = ByteArray(OperationalStorageLimits.SHA256_BYTES) { 1 },
            evidenceKind = MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT,
        ).requireValid(allowLegacyMessageIdOnly = false)
    }

    @Test
    fun maxRunPayloadIsOwnedAndValidatedBeforeTheRoomTransaction() {
        val payload = ByteArray(OperationalRetention.RUN_MAX_STORAGE_BYTES.toInt()).apply { fill(7) }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        val prepared = PreparedRunUpsert.prepare(
            candidate = RunStateEntity(
                hostClientId = "host",
                runId = "run",
                revision = 1,
                phase = RunPhaseToken.RUNNING,
                presentedRevision = 0,
                active = true,
                updatedAt = 1,
                endedAt = null,
                receivedAt = 1,
                payload = payload,
                payloadDigest = digest,
            ),
            activity = null,
        )

        assertNotSame(payload, prepared.candidate.payload)
        assertNotSame(digest, prepared.candidate.payloadDigest)
        payload[0] = 3
        digest[0] = (digest[0].toInt() xor 0xff).toByte()
        assertEquals(7, prepared.candidate.payload[0].toInt())
        requireSha256Projection(
            prepared.candidate.payload,
            prepared.candidate.payloadDigest,
            "prepared Run payload digest",
        )
    }

    @Test
    fun preparedFeatureReceiptOwnsCanonicalModernEvidenceAndRejectsUnsafeIdentity() {
        val fingerprint = ByteArray(OperationalStorageLimits.SHA256_BYTES) { 4 }
        val renderArgs = byteArrayOf(1, 2, 3)
        val coalescing = ByteArray(OperationalStorageLimits.SHA256_BYTES) { 5 }
        val prepared = PreparedOperationalReceipt.prepare(
            handled = MessageDedupEntity(
                messageId = "feature-message",
                authenticatedFingerprint = fingerprint,
                evidenceKind = MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT,
                handledAt = 1,
            ),
            expectedOperationalGeneration = 1,
            expectedStorageIncarnationId = "incarnation-1",
            activity = ActivityEventEntity(
                eventId = "feature-event",
                occurredAt = 1,
                recordedAt = 1,
                feature = ActivityFeature.NOTIFICATION,
                semanticAction = ActivityAction.APPLIED,
                direction = ActivityDirection.INBOUND,
                outcome = ActivityOutcome.SUCCESS,
                peerClientId = "peer",
                correlationId = "correlation",
                deliveryMode = OperationalDeliveryMode.RELAY_DRAIN,
                renderArgsVersion = 1,
                renderArgs = renderArgs,
                coalescingKeyToken = coalescing,
                coalescedCount = 1,
            ),
        )

        fingerprint.fill(9)
        renderArgs.fill(9)
        coalescing.fill(9)
        val preparedActivity = requireNotNull(prepared.activity)
        assertArrayEquals(ByteArray(OperationalStorageLimits.SHA256_BYTES) { 4 }, prepared.handled.authenticatedFingerprint)
        assertArrayEquals(byteArrayOf(1, 2, 3), preparedActivity.renderArgs)
        assertArrayEquals(
            ByteArray(OperationalStorageLimits.SHA256_BYTES) { 5 },
            preparedActivity.coalescingKeyToken,
        )

        assertThrows(IllegalArgumentException::class.java) {
            PreparedOperationalReceipt.prepare(
                handled = handled(null, MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY),
                expectedOperationalGeneration = 1,
                expectedStorageIncarnationId = "incarnation-1",
                activity = preparedActivity,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreparedOperationalReceipt.prepare(
                handled = prepared.handled,
                expectedOperationalGeneration = 1,
                expectedStorageIncarnationId = "not allowed",
                activity = preparedActivity,
            )
        }
        PreparedOperationalReceipt.prepare(
            handled = prepared.handled,
            expectedOperationalGeneration = 1,
            expectedStorageIncarnationId = "incarnation-1",
            activity = null,
        )
    }

    private fun handled(
        fingerprint: ByteArray?,
        evidenceKind: MessageDedupEvidenceKind,
    ) = MessageDedupEntity(
        messageId = "message",
        authenticatedFingerprint = fingerprint,
        evidenceKind = evidenceKind,
        handledAt = 1,
    )
}
