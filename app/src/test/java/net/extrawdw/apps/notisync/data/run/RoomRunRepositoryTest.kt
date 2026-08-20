package net.extrawdw.apps.notisync.data.run

import java.security.MessageDigest
import net.extrawdw.apps.notisync.data.storage.operational.RunCompareUpsertResult
import net.extrawdw.apps.notisync.data.storage.operational.RunPhaseToken
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomRunRepositoryTest {
    @Test
    fun preparationSnapshotsCollectionsAndPersistsOneCanonicalEncoding() {
        val mutableArgv = mutableListOf("make")
        val state = running(revision = 7).copy(argv = mutableArgv)
        val expectedState = state.copy(argv = mutableArgv.toList())
        val expectedCanonical = ProtocolCodec.encodeToCbor(expectedState)

        val entity = prepareRunStateEntity(state, receivedAt = 9_000)

        assertNotSame(expectedCanonical, entity.payload)
        assertArrayEquals(expectedCanonical, entity.payload)
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(expectedCanonical),
            entity.payloadDigest,
        )
        assertEquals(state.hostClientId.value, entity.hostClientId)
        assertEquals(state.runId, entity.runId)
        assertEquals(state.revision, entity.revision)
        assertEquals(RunPhaseToken.RUNNING, entity.phase)
        assertEquals(StoredRun.NO_PRESENTED_REVISION, entity.presentedRevision)
        assertTrue(entity.active)
        assertEquals(state.updatedAt, entity.updatedAt)
        assertEquals(state.endedAt, entity.endedAt)
        assertEquals(9_000L, entity.receivedAt)

        mutableArgv += "caller-mutation"
        assertEquals(expectedState, ProtocolCodec.decodeFromCbor<RunState>(entity.payload))
    }

    @Test
    fun hydrationVerifiesDigestAndEveryPayloadDerivedProjection() {
        val entity = prepareRunStateEntity(
            running(revision = 3),
            receivedAt = 4_000,
        )

        val inactive = entity.copy(active = false).toStoredRun()
        assertFalse(inactive.active)
        assertEquals(3L, inactive.state.revision)
        assertTrue(inactive.presentationPending)

        assertPersistedInvalid(entity.copy(hostClientId = "different-host"))
        assertPersistedInvalid(entity.copy(runId = "different-run"))
        assertPersistedInvalid(entity.copy(revision = 4))
        assertPersistedInvalid(entity.copy(phase = RunPhaseToken.BLOCKED))
        assertPersistedInvalid(entity.copy(updatedAt = entity.updatedAt + 1))
        assertPersistedInvalid(entity.copy(endedAt = 5_000))
        assertPersistedInvalid(
            entity.copy(
                payloadDigest = entity.payloadDigest.copyOf().also {
                    it[0] = (it[0].toInt() xor 0xff).toByte()
                },
            ),
        )
        val nonCanonicalPayload = entity.payload.withUnknownCborMapField()
        assertPersistedInvalid(
            entity.copy(
                payload = nonCanonicalPayload,
                payloadDigest = MessageDigest.getInstance("SHA-256").digest(nonCanonicalPayload),
            ),
        )

        val completed = prepareRunStateEntity(
            completed(runId = "done", revision = 1),
            receivedAt = 5_000,
        )
        assertPersistedInvalid(completed.copy(active = true))
    }

    @Test
    fun hydratedDomainStateDoesNotAliasEntityBlobs() {
        val entity = prepareRunStateEntity(
            running(revision = 1),
            receivedAt = 2_000,
        )
        val hydrated = entity.toStoredRun()

        entity.payload.fill(0)
        entity.payloadDigest.fill(0)

        assertEquals("run-1", hydrated.state.runId)
        assertEquals(listOf("make"), hydrated.state.argv)
    }

    @Test
    fun invalidInputIsRejectedWithoutPersistedFallback() {
        val zeroTimestamp = running(revision = 1).copy(startedAt = 0, updatedAt = 0)
        assertThrows(IllegalArgumentException::class.java) {
            prepareRunStateEntity(zeroTimestamp, receivedAt = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepareRunStateEntity(running(revision = 1), receivedAt = 0)
        }
    }

    @Test
    fun daoOutcomesMapWithoutWeakeningConflictOrCapacity() {
        val expected = mapOf(
            RunCompareUpsertResult.INSERTED to RunApplyResult.INSERTED,
            RunCompareUpsertResult.UPDATED to RunApplyResult.UPDATED,
            RunCompareUpsertResult.EQUAL to RunApplyResult.EQUAL,
            RunCompareUpsertResult.OLDER to RunApplyResult.OLDER,
            RunCompareUpsertResult.CONFLICT to RunApplyResult.CONFLICT,
            RunCompareUpsertResult.CAPACITY_EXCEEDED to RunApplyResult.CAPACITY_EXCEEDED,
        )

        assertEquals(expected, RunCompareUpsertResult.entries.associateWith { it.toDomainApplyResult() })
    }

    private fun assertPersistedInvalid(entity: net.extrawdw.apps.notisync.data.storage.operational.RunStateEntity) {
        assertThrows(IllegalStateException::class.java) { entity.toStoredRun() }
    }
}

internal fun running(
    runId: String = "run-1",
    revision: Long,
    terminalText: String = "",
): RunState = RunState(
    hostClientId = ClientId("host-1"),
    runId = runId,
    revision = revision,
    phase = RunPhase.RUNNING,
    updateReason = if (revision == 1L) RunUpdateReason.INITIAL else RunUpdateReason.PERIODIC,
    startedAt = 1_000,
    updatedAt = 2_000 + revision,
    argv = listOf("make"),
    cwd = "/work",
    usesPty = false,
    terminal = RunTerminalSnapshot(
        text = terminalText,
        truncated = false,
        rawBytesSeen = terminalText.length.toLong(),
    ),
)

internal fun completed(runId: String, revision: Long, updatedAt: Long = 4_000 + revision): RunState =
    RunState(
        hostClientId = ClientId("host-1"),
        runId = runId,
        revision = revision,
        phase = RunPhase.COMPLETED,
        updateReason = RunUpdateReason.COMPLETED,
        startedAt = 1_000,
        updatedAt = updatedAt,
        argv = listOf("make"),
        cwd = "/work",
        usesPty = false,
        terminal = RunTerminalSnapshot("done", truncated = false, rawBytesSeen = 4),
        endedAt = updatedAt - 1,
        exitCode = 0,
    )

/** Adds a valid ignored key/value to a small definite-length CBOR map without changing decoded RunState. */
internal fun ByteArray.withUnknownCborMapField(): ByteArray {
    require(isNotEmpty())
    val head = first().toInt() and 0xff
    require(head in 0xa0..0xb6) { "test RunState did not use a small definite-length CBOR map" }
    val nextHead = (head + 1).toByte()
    // Unsigned key 99 (0x18, 0x63), unsigned value 7. RunState labels currently occupy 0..20.
    return byteArrayOf(nextHead) + copyOfRange(1, size) + byteArrayOf(0x18, 0x63, 0x07)
}
