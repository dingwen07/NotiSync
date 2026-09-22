package net.extrawdw.apps.notisync.run

import android.content.ContentValues
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.extrawdw.apps.notisync.data.storage.operational.transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.apps.notisync.data.storage.operational.OperationalSQLiteOpenHelper
import net.extrawdw.apps.notisync.data.storage.operational.RunStateStorage
import net.extrawdw.apps.notisync.data.HistoryPage
import net.extrawdw.apps.notisync.data.HistoryDirection

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

/** One complete snapshot received from the host; revisions are retained independently of presentation. */
data class StoredRunRevision(val state: RunState, val receivedAt: Long)

data class RunHistoryCursor(val updatedAt: Long, val hostClientId: String, val runId: String) {
    companion object {
        fun after(run: StoredRun) = RunHistoryCursor(run.state.updatedAt, run.key.hostClientId, run.key.runId)
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
    fun inactiveKeys(): List<RunKey> = runs.value.filterNot { it.active }.map { it.key }
    fun inactiveRemoteActiveKeys(): List<RunKey> = runs.value.filter {
        !it.active && (it.state.phase == RunPhase.RUNNING || it.state.phase == RunPhase.BLOCKED)
    }.map { it.key }
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
) : OperationalSQLiteOpenHelper(context), RunRepository {
    private val _runs = MutableStateFlow<List<StoredRun>>(emptyList())
    /** Active sessions and uncheckpointed notifications, independent of the visible history page. */
    override val runs: StateFlow<List<StoredRun>> = _runs.asStateFlow()
    private val _changeVersion = MutableStateFlow(0L)
    val changeVersion: StateFlow<Long> = _changeVersion.asStateFlow()

    private var compactionPending = false

    init {
        // Operational Room owns the file and keeps it in WAL; opt in before opening the shared database.
        setWriteAheadLoggingEnabled(true)
        _runs.value = readRelevant()
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
        var applied: StoredRun? = null
        var insertedRevision = false
        val result = db.transaction {
            val existing = rawQuery(
                "SELECT current_revision, presented_revision FROM runs WHERE host_client = ? AND run_id = ?",
                arrayOf(state.hostClientId.value, state.runId),
            ).use { cursor ->
                if (cursor.moveToFirst()) ExistingRun(cursor.getLong(0), cursor.getLong(1)) else null
            }
            if (existing?.revision == state.revision) return@transaction RunApplyResult.EQUAL

            val alreadyReceived = rawQuery(
                "SELECT 1 FROM run_revisions WHERE host_client = ? AND run_id = ? AND revision = ?",
                arrayOf(state.hostClientId.value, state.runId, state.revision.toString()),
            ).use { it.moveToFirst() }
            if (alreadyReceived) {
                check(existing != null && existing.revision > state.revision) { "Run current revision is inconsistent" }
                return@transaction RunApplyResult.OLDER
            }
            val receivedAt = now()
            val stored = StoredRun(
                state, receivedAt, existing?.presentedRevision ?: StoredRun.NO_PRESENTED_REVISION,
            )
            // The parent is inserted first for the revision's foreign key. Never replace a parent:
            // SQLite's REPLACE would cascade-delete every previously received revision.
            if (existing == null) {
                insertOrThrow("runs", null, RunStateStorage.contentValues(RunStateStorage.sessionValues(stored)))
            }
            insertOrThrow(
                "run_revisions", null,
                RunStateStorage.contentValues(RunStateStorage.revisionValues(state, receivedAt)),
            )
            insertedRevision = true
            if (existing != null && existing.revision > state.revision) return@transaction RunApplyResult.OLDER
            if (existing != null) {
                check(update(
                    "runs", RunStateStorage.contentValues(RunStateStorage.sessionValues(stored)),
                    "host_client = ? AND run_id = ?", arrayOf(state.hostClientId.value, state.runId),
                ) == 1) { "could not update Run session" }
            }
            applied = stored
            if (existing == null) RunApplyResult.INSERTED else RunApplyResult.UPDATED
        }
        applied?.let { stored ->
            _runs.value = (_runs.value.filterNot { it.key == stored.key } + stored).sortedWith(RUN_ORDER)
        }
        if (insertedRevision) notifyChanged()
        return result
    }

    @Synchronized
    override fun find(key: RunKey): StoredRun? = runs.value.firstOrNull { it.key == key }
        ?: readableDatabase.rawQuery(
            "$CURRENT_QUERY WHERE session.host_client = ? AND session.run_id = ?",
            arrayOf(key.hostClientId, key.runId),
        ).use { if (it.moveToFirst()) RunStateStorage.readCurrent(it) else null }

    /** Current snapshots of inactive sessions, fetched with a stable cursor rather than OFFSET. */
    @Synchronized
    fun history(limit: Int = 50, after: RunHistoryCursor? = null): List<StoredRun> {
        require(limit in 1..1_001) { "Run history page size must be between 1 and 1001" }
        return historyRows(limit, after, HistoryDirection.OLDER, includeCursor = false)
    }

    private fun historyRows(
        limit: Int,
        cursor: RunHistoryCursor?,
        direction: HistoryDirection,
        includeCursor: Boolean,
    ): List<StoredRun> {
        val arguments = buildList {
            cursor?.let {
                add(it.updatedAt.toString())
                add(it.updatedAt.toString())
                add(it.updatedAt.toString())
                add(it.hostClientId)
                add(it.hostClientId)
                add(it.runId)
            }
            add(limit.toString())
        }.toTypedArray()
        return readableDatabase.rawQuery(
            historyQuery(cursor, direction, includeCursor),
            arguments,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(RunStateStorage.readCurrent(cursor)) } }
    }

    @Synchronized
    fun historyPage(
        limit: Int = 50,
        cursor: RunHistoryCursor? = null,
        direction: HistoryDirection = HistoryDirection.OLDER,
        includeCursor: Boolean = false,
    ): HistoryPage<StoredRun, RunHistoryCursor> {
        require(limit in 1..1_000) { "Run history page size must be between 1 and 1000" }
        val rows = historyRows(limit + 1, cursor, direction, includeCursor)
        val page = rows.take(limit).let { if (direction == HistoryDirection.NEWER) it.reversed() else it }
        val next = if (rows.size <= limit) null else RunHistoryCursor.after(
            if (direction == HistoryDirection.NEWER) page.first() else page.last(),
        )
        return HistoryPage(page, next)
    }

    @Synchronized
    override fun inactiveKeys(): List<RunKey> = readKeys("SELECT host_client, run_id FROM runs WHERE active = 0")

    @Synchronized
    override fun inactiveRemoteActiveKeys(): List<RunKey> = readKeys(
        "SELECT session.host_client, session.run_id FROM runs session JOIN run_revisions revision " +
            "ON revision.host_client=session.host_client AND revision.run_id=session.run_id " +
            "AND revision.revision=session.current_revision WHERE session.active=0 " +
            "AND revision.phase IN ('RUNNING', 'BLOCKED')",
    )

    private fun readKeys(query: String): List<RunKey> = readableDatabase.rawQuery(query, emptyArray()).use { cursor ->
        buildList { while (cursor.moveToNext()) add(RunKey(cursor.getString(0), cursor.getString(1))) }
    }

    /** A bounded newest-first page; the next page uses the last returned revision as its cursor. */
    @Synchronized
    fun revisions(key: RunKey, limit: Int = 100, beforeRevision: Long? = null): List<StoredRunRevision> {
        require(limit in 1..1_000) { "Run revision page size must be between 1 and 1000" }
        return revisionRows(key, limit, beforeRevision, HistoryDirection.OLDER, includeCursor = false)
    }

    @Synchronized
    fun revisionPage(
        key: RunKey,
        limit: Int = 50,
        cursor: Long? = null,
        direction: HistoryDirection = HistoryDirection.OLDER,
        includeCursor: Boolean = false,
    ): HistoryPage<StoredRunRevision, Long> {
        require(limit in 1..1_000) { "Run revision page size must be between 1 and 1000" }
        val rows = revisionRows(key, limit + 1, cursor, direction, includeCursor)
        val page = rows.take(limit).let { if (direction == HistoryDirection.NEWER) it.reversed() else it }
        val next = if (rows.size <= limit) null else
            (if (direction == HistoryDirection.NEWER) page.first() else page.last()).state.revision
        return HistoryPage(page, next)
    }

    private fun revisionRows(
        key: RunKey,
        limit: Int,
        cursor: Long?,
        direction: HistoryDirection,
        includeCursor: Boolean,
    ): List<StoredRunRevision> {
        val comparison = if (direction == HistoryDirection.OLDER) "<" else ">"
        val bound = if (cursor == null) "" else " AND revision $comparison${if (includeCursor) "=" else ""} ?"
        val order = if (direction == HistoryDirection.OLDER) "DESC" else "ASC"
        val arguments = buildList {
            add(key.hostClientId)
            add(key.runId)
            cursor?.let { add(it.toString()) }
            add(limit.toString())
        }.toTypedArray()
        return readableDatabase.rawQuery(
            "SELECT * FROM run_revisions WHERE host_client = ? AND run_id = ?$bound ORDER BY revision $order LIMIT ?",
            arguments,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(RunStateStorage.readRevision(cursor))
                }
            }
        }
    }

    @Synchronized
    override fun markPresented(key: RunKey, revision: Long) {
        val db = writableDatabase
        val updated = db.update(
            "runs",
            ContentValues().apply { put("presented_revision", revision) },
            "host_client = ? AND run_id = ? AND current_revision = ? AND presented_revision < ?",
            arrayOf(key.hostClientId, key.runId, revision.toString(), revision.toString()),
        )
        _runs.value = _runs.value.map { stored ->
            if (stored.key == key && stored.state.revision == revision && stored.presentedRevision < revision) {
                stored.copy(presentedRevision = revision)
            } else stored
        }.filter { it.active || it.presentationPending }
        if (updated > 0) notifyChanged()
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
            }.filter { it.active || it.presentationPending }.sortedWith(RUN_ORDER)
            notifyChanged()
        }
        return changed
    }

    /** Delete every locally historical row while leaving active work intact. */
    @Synchronized
    override fun clearHistory() {
        val db = writableDatabase
        var removed = 0
        try {
            removed = db.delete("runs", "active = 0", null)
        } finally {
            // SQLite may have committed before reporting a later failure; reload to keep the observable cache exact.
            _runs.value = readRelevant()
        }
        if (removed == 0) return
        notifyChanged()
        // Logical deletion is authoritative even if physical compaction must be retried by later maintenance.
        compactionPending = true
        runCatching {
            checkpointAndCompact(db)
            compactionPending = false
        }
    }

    /** Apply the high age and completed-row bounds; active Runs remain exempt. */
    @Synchronized
    override fun prune() {
        val db = writableDatabase
        markStaleRunsInactive(db, now() - ACTIVE_STALE_AFTER_MS)
        val removed = db.transaction {
            val expired = db.delete(
                "runs",
                "active = 0 AND received_at < ?",
                arrayOf((now() - completedRetentionMs).toString()),
            )
            // SQLite selects only excess rows; do not materialize the entire completed history in Kotlin.
            val overCount = db.delete(
                "runs",
                "(host_client, run_id) IN (" +
                    "SELECT host_client, run_id FROM runs WHERE active = 0 " +
                    "ORDER BY received_at DESC, updated_at DESC, host_client, run_id LIMIT -1 OFFSET ?)",
                arrayOf(maxCompletedRuns.toString()),
            )
            expired + overCount
        }
        if (removed > 0) {
            _runs.value = readRelevant()
            compactionPending = true
            notifyChanged()
        }
        // Both retention bounds share one compaction. Retry a failed compaction on the next maintenance pass.
        if (compactionPending) {
            checkpointAndCompact(db)
            compactionPending = false
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
            "UPDATE runs SET active = 0, presented_revision = current_revision " +
                "WHERE active = 1 AND received_at < ?",
            arrayOf(cutoff),
        )
        val stale = staleKeys.toSet()
        _runs.value = _runs.value.map { stored ->
            if (stored.key in stale) {
                stored.copy(active = false, presentedRevision = stored.state.revision)
            } else stored
        }.filter { it.active || it.presentationPending }.sortedWith(RUN_ORDER)
        notifyChanged()
    }

    private fun checkpointAndCompact(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) Unit
        }
        db.execSQL("VACUUM")
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) Unit
        }
    }

    private fun readRelevant(): List<StoredRun> = readableDatabase.rawQuery(
        "$CURRENT_QUERY WHERE session.active = 1 OR session.presented_revision < session.current_revision " +
            "ORDER BY session.active DESC, session.updated_at DESC",
        emptyArray(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                runCatching { RunStateStorage.readCurrent(cursor) }.getOrNull()?.let(::add)
            }
        }
    }

    private data class ExistingRun(val revision: Long, val presentedRevision: Long)

    private fun notifyChanged() { _changeVersion.value++ }

    companion object {
        private const val CURRENT_QUERY = "SELECT revision.*, session.presented_revision, session.active " +
            "FROM runs session JOIN run_revisions revision ON " +
            "revision.host_client=session.host_client AND revision.run_id=session.run_id " +
            "AND revision.revision=session.current_revision"
        internal fun historyQuery(
            cursor: RunHistoryCursor?,
            direction: HistoryDirection = HistoryDirection.OLDER,
            includeCursor: Boolean = false,
        ): String {
            // The timestamp range lets SQLite seek into the index before checking mixed-direction ties.
            val newer = direction == HistoryDirection.NEWER
            val timeComparison = if (newer) ">" else "<"
            val identityComparison = if (newer) "<" else ">"
            val inclusive = if (includeCursor) "=" else ""
            val predicate = if (cursor == null) "" else
                " AND session.updated_at $timeComparison= ? AND (session.updated_at $timeComparison ? OR " +
                    "(session.updated_at = ? AND (session.host_client $identityComparison ? OR " +
                    "(session.host_client = ? AND session.run_id $identityComparison$inclusive ?))))"
            val order = if (newer) "session.updated_at ASC, session.host_client DESC, session.run_id DESC" else
                "session.updated_at DESC, session.host_client, session.run_id"
            return "$CURRENT_QUERY WHERE session.active = 0$predicate " +
                "ORDER BY $order LIMIT ?"
        }
        internal const val ACTIVE_STALE_AFTER_MS = 3L * 60 * 60 * 1000
        private const val COMPLETED_RETENTION_MS = 50L * 365 * 24 * 60 * 60 * 1000
        private const val MAX_COMPLETED_RUNS = 1_000_000
        private val RUN_ORDER = compareByDescending<StoredRun> { it.active }
            .thenByDescending { it.state.updatedAt }
    }
}

private fun roomMustOwnRunSchema(): Nothing =
    error("Room must create and migrate Operational storage before RunStore opens")
