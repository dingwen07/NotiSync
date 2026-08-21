package net.extrawdw.apps.notisync.run

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase

/** Stable local identity for one run. Run ids are scoped by their authenticated host. */
data class RunKey(val hostClientId: String, val runId: String) {
    fun encoded(): String = "$hostClientId\u0000$runId"

    companion object {
        fun decode(value: String): RunKey? {
            val split = value.indexOf('\u0000')
            if (split <= 0 || split == value.lastIndex) return null
            return RunKey(value.substring(0, split), value.substring(split + 1))
        }
    }
}

data class StoredRun(
    val state: RunState,
    val receivedAt: Long,
    val presentedRevision: Long = NO_PRESENTED_REVISION,
    /** Local presentation state. A newer remote revision replaces this with the phase-derived value. */
    val active: Boolean = state.phase == RunPhase.RUNNING || state.phase == RunPhase.BLOCKED,
) {
    val key: RunKey get() = RunKey(state.hostClientId.value, state.runId)
    val presentationPending: Boolean get() = presentedRevision < state.revision

    companion object {
        const val NO_PRESENTED_REVISION = -1L
    }
}

enum class RunApplyResult { INSERTED, UPDATED, EQUAL, OLDER }

interface RunRepository {
    val runs: StateFlow<List<StoredRun>>
    fun apply(state: RunState): RunApplyResult
    fun find(key: RunKey): StoredRun?
    fun markPresented(key: RunKey, revision: Long)
    fun markInactive(key: RunKey): Boolean
    fun clearHistory()
    fun prune()
}

/**
 * Private, device-local Run history. It deliberately does not share [net.extrawdw.apps.notisync.data.MessageStore]:
 * the latter is a small delivery ledger with relay-TTL retention, while Run history uses deliberately high age and
 * row-count limits. Operational storage is shared, so Run does not enforce a whole-database size limit.
 *
 * Incoming full snapshots commit synchronously. Equal revisions are identified separately because delivery may be
 * retrying after the state committed but before its notification rendered. Database errors escape so the receive
 * handler can retain the relay item for retry instead of acknowledging data that was never persisted.
 */
class RunStore(
    context: Context,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val completedRetentionMs: Long = COMPLETED_RETENTION_MS,
    private val maxCompletedRuns: Int = MAX_COMPLETED_RUNS,
) : SQLiteOpenHelper(
    context.applicationContext,
    OperationalDatabase.DATABASE_NAME,
    null,
    OperationalDatabase.VERSION,
), RunRepository {
    private val _runs = MutableStateFlow<List<StoredRun>>(emptyList())
    override val runs: StateFlow<List<StoredRun>> = _runs.asStateFlow()

    init {
        // Operational Room owns the file and keeps it in WAL; opt in before opening the shared database.
        setWriteAheadLoggingEnabled(true)
        _runs.value = readAll()
        // Enforce the long-horizon history bounds on cold start too.
        runCatching { prune() }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase): Nothing = roomMustOwnRunSchema()

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Nothing =
        roomMustOwnRunSchema()

    @Synchronized
    override fun apply(state: RunState): RunApplyResult {
        val db = writableDatabase
        val existing = db.rawQuery(
            "SELECT revision, presented_revision FROM runs WHERE host_client = ? AND run_id = ?",
            arrayOf(state.hostClientId.value, state.runId),
        ).use { cursor ->
            if (cursor.moveToFirst()) ExistingRun(cursor.getLong(0), cursor.getLong(1)) else null
        }
        if (existing != null) {
            if (existing.revision == state.revision) return RunApplyResult.EQUAL
            if (existing.revision > state.revision) return RunApplyResult.OLDER
        }

        val result = if (existing == null) RunApplyResult.INSERTED else RunApplyResult.UPDATED
        val receivedAt = now()
        db.transaction {
            db.insertWithOnConflict(
                "runs",
                null,
                ContentValues().apply {
                    put("host_client", state.hostClientId.value)
                    put("run_id", state.runId)
                    put("revision", state.revision)
                    put("presented_revision", existing?.presentedRevision ?: StoredRun.NO_PRESENTED_REVISION)
                    put("active", if (state.isActive()) 1 else 0)
                    put("updated_at", state.updatedAt)
                    state.endedAt?.let { put("ended_at", it) } ?: putNull("ended_at")
                    put("received_at", receivedAt)
                    put("payload", ProtocolCodec.encodeToCbor(state))
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            ).also { if (it == -1L) error("could not persist Run state") }
        }
        val key = RunKey(state.hostClientId.value, state.runId)
        val retained = _runs.value.filterNot { it.key == key }
        _runs.value = (retained + StoredRun(state, receivedAt, existing?.presentedRevision
            ?: StoredRun.NO_PRESENTED_REVISION))
            .sortedWith(RUN_ORDER)
        // Retention is best effort after the authoritative snapshot has committed. A later maintenance pass will
        // retry any checkpoint/compaction failure. Protect this just-applied row until its renderer checkpoints it.
        runCatching { prune(protectedKey = key) }
        return result
    }

    override fun find(key: RunKey): StoredRun? =
        runs.value.firstOrNull { it.key == key }

    @Synchronized
    override fun markPresented(key: RunKey, revision: Long) {
        val db = writableDatabase
        db.update(
            "runs",
            ContentValues().apply { put("presented_revision", revision) },
            "host_client = ? AND run_id = ? AND revision = ? AND presented_revision < ?",
            arrayOf(key.hostClientId, key.runId, revision.toString(), revision.toString()),
        )
        _runs.value = _runs.value.map { stored ->
            if (stored.key == key && stored.state.revision == revision && stored.presentedRevision < revision) {
                stored.copy(presentedRevision = revision)
            } else stored
        }
    }

    /**
     * Move an active snapshot to local history without changing the authenticated remote payload. Marking its
     * current revision presented also prevents startup reconciliation from recreating an ongoing notification.
     */
    @Synchronized
    override fun markInactive(key: RunKey): Boolean {
        val stored = find(key) ?: return false
        if (!stored.active) return false
        val changed = writableDatabase.update(
            "runs",
            ContentValues().apply {
                put("active", 0)
                put("presented_revision", stored.state.revision)
            },
            "host_client = ? AND run_id = ? AND active = 1",
            arrayOf(key.hostClientId, key.runId),
        ) > 0
        if (changed) {
            _runs.value = _runs.value.map { candidate ->
                if (candidate.key == key) {
                    candidate.copy(active = false, presentedRevision = candidate.state.revision)
                } else candidate
            }.sortedWith(RUN_ORDER)
        }
        return changed
    }

    /** Delete every locally historical row while leaving active work intact. */
    @Synchronized
    override fun clearHistory() {
        if (_runs.value.none { !it.active }) return
        val db = writableDatabase
        try {
            db.delete("runs", "active = 0", null)
        } finally {
            // SQLite may have committed before reporting a later failure; reload to keep the observable cache exact.
            _runs.value = readAll()
        }
        // Logical deletion is authoritative even if physical compaction must be retried by later maintenance.
        runCatching { checkpointAndCompact(db, vacuum = true) }
    }

    /** Apply the high age and completed-row bounds; active Runs remain exempt. */
    @Synchronized
    override fun prune() = prune(protectedKey = null)

    private fun prune(protectedKey: RunKey?) {
        val db = writableDatabase
        val removed = linkedSetOf<RunKey>()
        try {
            markStaleRunsInactive(db, now() - ACTIVE_STALE_AFTER_MS)

            val expired = completedBefore(db, now() - completedRetentionMs)
                .filterNot { it == protectedKey }
            if (expired.isNotEmpty()) {
                deleteKeys(db, expired)
                removed += expired
                checkpointAndCompact(db, vacuum = true)
            }

            // Active Runs are exempt; among completed Runs retain the configured newest entries.
            val overCount = completedBeyondLogLimit(db, protectedKey)
            if (overCount.isNotEmpty()) {
                deleteKeys(db, overCount)
                removed += overCount
                checkpointAndCompact(db, vacuum = true)
            }

        } finally {
            // Deletions may already have committed even if checkpoint/VACUUM failed; keep the observable cache in
            // lock-step with SQLite and allow future periodic maintenance to retry the physical compaction.
            if (removed.isNotEmpty()) _runs.value = _runs.value.filterNot { it.key in removed }
        }
    }

    /** Local receipt time avoids trusting a host clock and advances only for a genuinely newer revision. */
    private fun markStaleRunsInactive(db: SQLiteDatabase, cutoff: Long) {
        val staleKeys = db.rawQuery(
            "SELECT host_client, run_id FROM runs WHERE active = 1 AND received_at < ?",
            arrayOf(cutoff.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(RunKey(cursor.getString(0), cursor.getString(1)))
            }
        }
        if (staleKeys.isEmpty()) return
        db.execSQL(
            "UPDATE runs SET active = 0, presented_revision = revision " +
                "WHERE active = 1 AND received_at < ?",
            arrayOf(cutoff),
        )
        val stale = staleKeys.toSet()
        _runs.value = _runs.value.map { stored ->
            if (stored.key in stale) {
                stored.copy(active = false, presentedRevision = stored.state.revision)
            } else stored
        }.sortedWith(RUN_ORDER)
    }

    private fun completedBefore(db: SQLiteDatabase, cutoff: Long): List<RunKey> = db.rawQuery(
        "SELECT host_client, run_id FROM runs " +
            "WHERE active = 0 AND received_at < ? ORDER BY received_at, updated_at",
        arrayOf(cutoff.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(RunKey(cursor.getString(0), cursor.getString(1)))
            }
        }
    }

    private fun completedBeyondLogLimit(db: SQLiteDatabase, protectedKey: RunKey?): List<RunKey> {
        val newestFirst = db.rawQuery(
            "SELECT host_client, run_id FROM runs " +
                "WHERE active = 0 ORDER BY received_at DESC, updated_at DESC, host_client, run_id",
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(RunKey(cursor.getString(0), cursor.getString(1)))
            }
        }
        if (newestFirst.size <= maxCompletedRuns) return emptyList()

        // A just-committed snapshot must survive until its renderer checkpoints it, even under a clock tie.
        val protectedCompleted = protectedKey?.takeIf { it in newestFirst }
        val retained = buildList {
            protectedCompleted?.let(::add)
            newestFirst.asSequence()
                .filterNot { it == protectedCompleted }
                .take(maxCompletedRuns - if (protectedCompleted == null) 0 else 1)
                .forEach(::add)
        }.toSet()
        return newestFirst.filterNot { it in retained }
    }

    private fun deleteKeys(db: SQLiteDatabase, keys: List<RunKey>) {
        db.transaction {
            keys.forEach { key ->
                db.delete(
                    "runs",
                    "host_client = ? AND run_id = ?",
                    arrayOf(key.hostClientId, key.runId),
                )
            }
        }
    }

    private fun checkpointAndCompact(db: SQLiteDatabase, vacuum: Boolean) {
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) Unit
        }
        if (vacuum) db.execSQL("VACUUM")
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) Unit
        }
    }

    private fun readAll(): List<StoredRun> = runCatching {
        readableDatabase.rawQuery(
            "SELECT payload, received_at, presented_revision, active " +
                "FROM runs ORDER BY active DESC, updated_at DESC",
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val state = runCatching {
                        ProtocolCodec.decodeFromCbor<RunState>(cursor.getBlob(0))
                    }.getOrNull() ?: continue
                    add(
                        StoredRun(
                            state = state,
                            receivedAt = cursor.getLong(1),
                            presentedRevision = cursor.getLong(2),
                            active = cursor.getInt(3) != 0,
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun RunState.isActive(): Boolean = phase == RunPhase.RUNNING || phase == RunPhase.BLOCKED

    private data class ExistingRun(val revision: Long, val presentedRevision: Long)

    companion object {
        internal const val ACTIVE_STALE_AFTER_MS = 3L * 60 * 60 * 1000
        private const val COMPLETED_RETENTION_MS = 50L * 365 * 24 * 60 * 60 * 1000
        private const val MAX_COMPLETED_RUNS = 1_000_000
        private val RUN_ORDER = compareByDescending<StoredRun> { it.active }
            .thenByDescending { it.state.updatedAt }
    }
}

private fun roomMustOwnRunSchema(): Nothing =
    error("Room must create and migrate Operational storage before RunStore opens")
