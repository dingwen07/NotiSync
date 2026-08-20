package net.extrawdw.apps.notisync.data.storage.core

import net.extrawdw.notisync.protocol.crypto.ClientIds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreRepositoryBoundaryTest {
    @Test
    fun groupIdentityValidationAllowsExplicitClearAndRejectsUnsafeValues() {
        validateCoreGroupId(null)
        validateCoreGroupId("trusted-group")
        listOf("", " ", "line\nbreak", "x".repeat(MAX_CORE_GROUP_ID_CHARS + 1)).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { validateCoreGroupId(value) }
        }
        assertTrue(MAX_CORE_GROUP_ID_CHARS > 0)
    }

    @Test
    fun entityMappingsCopyMutableByteArrays() {
        val publicSpki = byteArrayOf(1, 2)
        val identityRow = IdentityMetadataEntity(
            keyAlias = "identity",
            keyAliasVersion = 1,
            publicSpki = publicSpki,
            clientId = ClientIds.derive(publicSpki).value,
            securityLevel = IdentitySecurityLevel.TRUSTED_ENVIRONMENT,
            lifecycleState = IdentityLifecycleState.ACTIVE,
            createdAt = 1,
            updatedAt = 2,
        )

        val snapshot = identityRow.toSnapshot()
        publicSpki[0] = 9

        assertArrayEquals(byteArrayOf(1, 2), snapshot.publicSpki)

        snapshot.publicSpki[1] = 8
        assertArrayEquals(byteArrayOf(9, 2), identityRow.publicSpki)
    }

    @Test
    fun identityProjectionFailsClosedWhenDerivedClientIdDiverges() {
        val row = IdentityMetadataEntity(
            keyAlias = "identity",
            keyAliasVersion = 1,
            publicSpki = byteArrayOf(1, 2),
            clientId = "wrong-client",
            securityLevel = IdentitySecurityLevel.TRUSTED_ENVIRONMENT,
            lifecycleState = IdentityLifecycleState.ACTIVE,
            createdAt = 1,
            updatedAt = 2,
        )

        assertThrows(IllegalStateException::class.java) { row.toSnapshot() }
    }

    @Test
    fun allOpaqueAggregateMappingsCopyEveryByteArray() {
        val trustRow = TrustSnapshotEntity(
            signatureFormat = TrustSignatureFormat.TRUSTSTORE_V1_FOUR_SECTION.token,
            entriesUtf8 = byteArrayOf(1),
            cardsUtf8 = byteArrayOf(2),
            overlaysUtf8 = byteArrayOf(3),
            epochsUtf8 = byteArrayOf(4),
            signatureBase64UrlUtf8 = byteArrayOf(5),
            snapshotDigest = ByteArray(TRUST_SNAPSHOT_DIGEST_BYTES) { 6 },
            updatedAt = 7,
        )
        val trust = trustRow.toSnapshot(TrustSignatureFormat.TRUSTSTORE_V1_FOUR_SECTION)
        trust.entriesUtf8[0] = 11
        trust.cardsUtf8[0] = 12
        trust.overlaysUtf8[0] = 13
        trust.epochsUtf8!![0] = 14
        trust.signatureBase64UrlUtf8[0] = 15
        trust.snapshotDigest[0] = 16

        assertArrayEquals(byteArrayOf(1), trustRow.entriesUtf8)
        assertArrayEquals(byteArrayOf(2), trustRow.cardsUtf8)
        assertArrayEquals(byteArrayOf(3), trustRow.overlaysUtf8)
        assertArrayEquals(byteArrayOf(4), trustRow.epochsUtf8)
        assertArrayEquals(byteArrayOf(5), trustRow.signatureBase64UrlUtf8)
        assertArrayEquals(ByteArray(TRUST_SNAPSHOT_DIGEST_BYTES) { 6 }, trustRow.snapshotDigest)
    }

    @Test
    fun onlyIntactContinuityAndEstablishedFencePermitRuntime() {
        assertTrue(ReplayFenceState.CONTINUITY_INTACT.isRuntimeReady)
        assertTrue(ReplayFenceState.ESTABLISHED.isRuntimeReady)
        assertFalse(ReplayFenceState.FENCE_REQUIRED.isRuntimeReady)
        assertFalse(ReplayFenceState.ESTABLISHING.isRuntimeReady)
        assertFalse(ReplayFenceState.BLOCKED.isRuntimeReady)
    }

    @Test
    fun storedTransportProjectionRejectsEveryIncompleteContinuityCombination() {
        val valid = CoreTransportStateEntity(
            brokerUrl = "https://broker.example.test",
            routeEpoch = 0,
            operationalGeneration = INITIAL_OPERATIONAL_GENERATION,
            operationalIncarnationId = "incarnation-1",
            replayFenceState = ReplayFenceState.CONTINUITY_INTACT,
            continuityOrigin = OperationalContinuityOrigin.FRESH_IDENTITY,
            updatedAt = 1,
        )
        assertEquals(OperationalContinuityOrigin.FRESH_IDENTITY, valid.toSnapshot().continuityOrigin)

        listOf(
            valid.copy(operationalGeneration = 0),
            valid.copy(continuityOrigin = null),
            valid.copy(replayFenceId = "invented"),
            valid.copy(replayFenceState = ReplayFenceState.FENCE_REQUIRED),
            valid.copy(
                replayFenceState = ReplayFenceState.ESTABLISHED,
                continuityOrigin = null,
            ),
            valid.copy(
                replayFenceState = ReplayFenceState.ESTABLISHED,
                continuityOrigin = null,
                replayFenceId = "fence",
                replayFenceEpoch = 0,
            ),
        ).forEach { invalid ->
            assertThrows(IllegalStateException::class.java) { invalid.toSnapshot() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(operationalIncarnationId = "invalid incarnation").toSnapshot()
        }

        val established = valid.copy(
            operationalGeneration = 2,
            replayFenceState = ReplayFenceState.ESTABLISHED,
            continuityOrigin = null,
            replayFenceId = "fence-2",
            replayFenceEpoch = 2,
        ).toSnapshot()
        assertTrue(established.replayFenceState.isRuntimeReady)
        assertEquals("incarnation-1", established.operationalIncarnationId)
    }

    @Test
    fun operationalStorageBindingAcceptsOnlyPositiveGenerationAndSafeIncarnation() {
        val binding = OperationalStorageBinding(
            operationalGeneration = INITIAL_OPERATIONAL_GENERATION,
            storageIncarnationId = "incarnation-1",
        )
        assertEquals(INITIAL_OPERATIONAL_GENERATION, binding.operationalGeneration)
        assertEquals("incarnation-1", binding.storageIncarnationId)

        assertThrows(IllegalArgumentException::class.java) {
            OperationalStorageBinding(operationalGeneration = 0, storageIncarnationId = "incarnation-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OperationalStorageBinding(
                operationalGeneration = INITIAL_OPERATIONAL_GENERATION,
                storageIncarnationId = "invalid incarnation",
            )
        }
    }
}
