package net.extrawdw.apps.notisync.data.run

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.extrawdw.apps.notisync.data.storage.operational.OperationalRetention
import net.extrawdw.apps.notisync.data.storage.operational.RunCompareUpsertResult
import net.extrawdw.apps.notisync.data.storage.operational.RunDao
import net.extrawdw.apps.notisync.data.storage.operational.RunPhaseToken
import net.extrawdw.apps.notisync.data.storage.operational.RunStateEntity
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState

/** Sole Room adapter for [RunRepository]. DAO/entity types do not escape this file. */
internal class RoomRunRepository(
    private val dao: RunDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RunRepository {
    override fun observeAll(): Flow<List<StoredRun>> =
        dao.observeAll().map { rows -> rows.map(RunStateEntity::toStoredRun) }

    override suspend fun find(key: RunKey): StoredRun? =
        dao.find(key.hostClientId.value, key.runId)?.toStoredRun()

    override suspend fun apply(state: RunState): RunApplyResult {
        // Own and validate the potentially large value before RunDao opens its short write transaction.
        val candidate = prepareRunStateEntity(
            state = state,
            receivedAt = nowMillis(),
        )
        return dao.compareAndUpsert(candidate, activity = null).toDomainApplyResult()
    }

    override suspend fun markPresented(key: RunKey, revision: Long): Boolean {
        require(revision > 0) { "Run presentation revision must be positive" }
        return dao.markPresented(key.hostClientId.value, key.runId, revision) == 1
    }

    override suspend fun markInactive(key: RunKey): Boolean =
        dao.markInactive(key.hostClientId.value, key.runId) == 1

    override suspend fun clearHistory(): Int = dao.clearHistory()

    override suspend fun prune(now: Long): Int {
        require(now > 0) { "Run prune time must be positive" }
        return dao.pruneBatch(now)
    }
}

internal fun RunCompareUpsertResult.toDomainApplyResult(): RunApplyResult = when (this) {
    RunCompareUpsertResult.INSERTED -> RunApplyResult.INSERTED
    RunCompareUpsertResult.UPDATED -> RunApplyResult.UPDATED
    RunCompareUpsertResult.EQUAL -> RunApplyResult.EQUAL
    RunCompareUpsertResult.OLDER -> RunApplyResult.OLDER
    RunCompareUpsertResult.CONFLICT -> RunApplyResult.CONFLICT
    RunCompareUpsertResult.CAPACITY_EXCEEDED -> RunApplyResult.CAPACITY_EXCEEDED
}

/** Snapshots one decoded state, canonical-encodes once, and derives every SQL projection from that snapshot. */
internal fun prepareRunStateEntity(
    state: RunState,
    receivedAt: Long,
): RunStateEntity {
    require(receivedAt > 0) { "Run receipt time must be positive" }
    // RunState's only collection is argv. Copying it through the validated constructor prevents a caller-backed
    // mutable list from changing either projections or canonical bytes after this synchronous preparation step.
    val snapshot = state.copy(argv = state.argv.toList())
    RunKey(snapshot.hostClientId, snapshot.runId)
    require(snapshot.updatedAt > 0) { "Run update time must be positive" }
    snapshot.endedAt?.let { require(it > 0) { "Run end time must be positive" } }
    val ownedPayload = ProtocolCodec.encodeToCbor(snapshot)
    require(ownedPayload.isNotEmpty()) { "encoded RunState must not be empty" }
    require(ownedPayload.size.toLong() <= OperationalRetention.RUN_MAX_STORAGE_BYTES) {
        "encoded RunState exceeds the Run storage budget"
    }
    return RunStateEntity(
        hostClientId = snapshot.hostClientId.value,
        runId = snapshot.runId,
        revision = snapshot.revision,
        phase = snapshot.phase.toStorageToken(),
        presentedRevision = StoredRun.NO_PRESENTED_REVISION,
        active = snapshot.phase.isRemotelyActive(),
        updatedAt = snapshot.updatedAt,
        endedAt = snapshot.endedAt,
        receivedAt = receivedAt,
        payload = ownedPayload,
        payloadDigest = MessageDigest.getInstance(SHA_256).digest(ownedPayload),
    )
}

/** Hydrates one owned domain value and rejects any payload/digest/projection disagreement. */
internal fun RunStateEntity.toStoredRun(): StoredRun {
    try {
        val ownedPayload = payload.copyOf()
        val ownedDigest = payloadDigest.copyOf()
        require(ownedPayload.isNotEmpty())
        require(ownedPayload.size.toLong() <= OperationalRetention.RUN_MAX_STORAGE_BYTES)
        require(ownedDigest.size == SHA_256_BYTES)
        require(
            MessageDigest.isEqual(
                MessageDigest.getInstance(SHA_256).digest(ownedPayload),
                ownedDigest,
            ),
        )
        val state = ProtocolCodec.decodeFromCbor<RunState>(ownedPayload)
        require(ProtocolCodec.encodeToCbor(state).contentEquals(ownedPayload))
        require(hostClientId == state.hostClientId.value)
        require(runId == state.runId)
        require(revision == state.revision)
        require(phase == state.phase.toStorageToken())
        require(updatedAt == state.updatedAt)
        require(endedAt == state.endedAt)
        require(updatedAt > 0)
        endedAt?.let { require(it > 0) }
        require(receivedAt > 0)
        require(presentedRevision == StoredRun.NO_PRESENTED_REVISION || presentedRevision in 0..state.revision)
        require(!active || state.phase.isRemotelyActive())
        return StoredRun(
            state = state,
            receivedAt = receivedAt,
            presentedRevision = presentedRevision,
            active = active,
        )
    } catch (_: Exception) {
        throw IllegalStateException("Persisted Run state is invalid")
    }
}

private fun RunPhase.toStorageToken(): RunPhaseToken = when (this) {
    RunPhase.RUNNING -> RunPhaseToken.RUNNING
    RunPhase.BLOCKED -> RunPhaseToken.BLOCKED
    RunPhase.COMPLETED -> RunPhaseToken.COMPLETED
    RunPhase.FAILED_TO_START -> RunPhaseToken.FAILED_TO_START
}

private fun RunPhase.isRemotelyActive(): Boolean = this == RunPhase.RUNNING || this == RunPhase.BLOCKED

private const val SHA_256 = "SHA-256"
private const val SHA_256_BYTES = 32
