package net.extrawdw.apps.notisync.data.run

import kotlinx.coroutines.flow.Flow
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState

/** Stable identity of one Run. A host scopes its own run identifiers. */
data class RunKey(
    val hostClientId: ClientId,
    val runId: String,
) {
    init {
        requireRunIdentifier(hostClientId.value, "Run host client id")
        requireRunIdentifier(runId, "Run id")
    }

    /** Stable delimiter-safe identity used by notification routes and local navigation state. */
    fun encoded(): String = "${hostClientId.value}\u0000$runId"

    companion object {
        fun decode(value: String): RunKey? {
            val split = value.indexOf('\u0000')
            if (split <= 0 || split == value.lastIndex) return null
            return runCatching {
                RunKey(
                    hostClientId = ClientId(value.substring(0, split)),
                    runId = value.substring(split + 1),
                )
            }.getOrNull()
        }
    }
}

/** Storage-independent latest snapshot plus its local presentation and retention projections. */
@ConsistentCopyVisibility
data class StoredRun internal constructor(
    val state: RunState,
    val receivedAt: Long,
    val presentedRevision: Long = NO_PRESENTED_REVISION,
    /** May be false for an active remote phase after the user moves that snapshot to local history. */
    val active: Boolean = state.phase == RunPhase.RUNNING || state.phase == RunPhase.BLOCKED,
) {
    init {
        RunKey(state.hostClientId, state.runId)
        require(receivedAt > 0) { "Run receipt time must be positive" }
        require(presentedRevision == NO_PRESENTED_REVISION || presentedRevision in 0..state.revision) {
            "Run presented revision is invalid"
        }
        require(!active || state.phase == RunPhase.RUNNING || state.phase == RunPhase.BLOCKED) {
            "Run active projection contradicts its phase"
        }
    }

    val key: RunKey get() = RunKey(state.hostClientId, state.runId)
    val presentationPending: Boolean get() = presentedRevision < state.revision

    companion object {
        const val NO_PRESENTED_REVISION: Long = -1L
    }
}

/** Complete compare-and-upsert result. Conflicts and capacity failures are never softened into duplicates. */
enum class RunApplyResult {
    INSERTED,
    UPDATED,
    EQUAL,
    OLDER,
    CONFLICT,
    CAPACITY_EXCEEDED,
}

/** Domain-facing owner of the latest Run snapshots, local presentation state, and bounded history. */
interface RunRepository {
    fun observeAll(): Flow<List<StoredRun>>

    suspend fun find(key: RunKey): StoredRun?

    /**
     * Applies an already-decoded protocol [RunState]. Implementations defensively snapshot collection-backed
     * state and canonical-encode it once before suspension, then derive every query projection from that snapshot.
     */
    suspend fun apply(state: RunState): RunApplyResult

    /** Returns true only when the durable presentation checkpoint advanced. */
    suspend fun markPresented(key: RunKey, revision: Long): Boolean

    /** Moves an active local projection to history without changing the authenticated payload. */
    suspend fun markInactive(key: RunKey): Boolean

    /** Deletes inactive history only and returns the number of deleted rows. */
    suspend fun clearHistory(): Int

    /** Applies stale-active, age, row-count, and byte-budget retention; returns deleted rows only. */
    suspend fun prune(now: Long): Int
}

private const val MAX_RUN_IDENTIFIER_CHARS = 256

private fun requireRunIdentifier(value: String, name: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value.length <= MAX_RUN_IDENTIFIER_CHARS) { "$name is too long" }
    require(value.none(Char::isISOControl)) { "$name contains a control character" }
}
