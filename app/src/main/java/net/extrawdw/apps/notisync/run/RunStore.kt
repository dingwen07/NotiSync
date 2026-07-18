package net.extrawdw.apps.notisync.run

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState

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
) {
    val key: RunKey get() = RunKey(state.hostClientId.value, state.runId)
    val active: Boolean get() = state.phase == RunPhase.RUNNING || state.phase == RunPhase.BLOCKED
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
    fun prune()
}

internal data class RunStorageUsage(
    val usedPageBytes: Long,
    val mainFileBytes: Long,
    val walFileBytes: Long,
    val shmFileBytes: Long,
) {
    val diskBytes: Long get() = mainFileBytes + walFileBytes + shmFileBytes
    val accountedBytes: Long get() = maxOf(diskBytes, usedPageBytes + walFileBytes + shmFileBytes)
}

/**
 * Private, device-local Run history. It deliberately does not share [net.extrawdw.apps.notisync.data.MessageStore]:
 * the latter is a small delivery ledger with relay-TTL retention, while Run history has product-visible retention.
 *
 * Incoming full snapshots commit synchronously. Equal revisions are identified separately because delivery may be
 * retrying after the state committed but before its notification rendered. Database errors escape so the receive
 * handler can retain the relay item for retry instead of acknowledging data that was never persisted.
 */
class RunStore(
    context: Context,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxStorageBytes: Long = MAX_STORAGE_BYTES,
) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, VERSION), RunRepository {
    private val databaseFile: File = context.applicationContext.getDatabasePath(DB_NAME)
    private val _runs = MutableStateFlow<List<StoredRun>>(emptyList())
    override val runs: StateFlow<List<StoredRun>> = _runs.asStateFlow()

    init {
        _runs.value = readAll()
        // Enforce history retention on cold start too; a device may receive no new Run after records age out.
        runCatching { prune() }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE runs (" +
                "host_client TEXT NOT NULL, run_id TEXT NOT NULL, revision INTEGER NOT NULL, " +
                "presented_revision INTEGER NOT NULL, " +
                "active INTEGER NOT NULL, updated_at INTEGER NOT NULL, ended_at INTEGER, " +
                "received_at INTEGER NOT NULL, payload BLOB NOT NULL, " +
                "PRIMARY KEY (host_client, run_id))"
        )
        db.execSQL("CREATE INDEX runs_order_idx ON runs(active DESC, updated_at DESC)")
        db.execSQL("CREATE INDEX runs_retention_idx ON runs(active, received_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Run history has never shipped. Until it does, schema changes replace this private cache directly.
        db.execSQL("DROP TABLE IF EXISTS runs")
        onCreate(db)
    }

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
        db.beginTransaction()
        try {
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
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
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

    /** Apply age retention, then enforce the cap against SQLite pages and the database/WAL/SHM files. */
    @Synchronized
    override fun prune() = prune(protectedKey = null)

    private fun prune(protectedKey: RunKey?) {
        val db = writableDatabase
        val removed = linkedSetOf<RunKey>()
        try {
            val expired = completedBefore(db, now() - COMPLETED_RETENTION_MS)
                .filterNot { it.key == protectedKey }
            if (expired.isNotEmpty()) {
                deleteKeys(db, expired.map { it.key })
                removed += expired.map { it.key }
                checkpointAndCompact(db, vacuum = true)
            }

            // The visible log is intentionally bounded independently of the byte/age policy. Active Runs are
            // exempt; among completed Runs retain the 50 most recently received entries.
            val overCount = completedBeyondLogLimit(db, protectedKey)
            if (overCount.isNotEmpty()) {
                deleteKeys(db, overCount)
                removed += overCount
                checkpointAndCompact(db, vacuum = true)
            }

            var usage = storageUsage(db)
            if (usage.accountedBytes > maxStorageBytes) {
                // A large WAL may be the only reason the cap is exceeded. Fold it into the main database before
                // deleting user-visible history, then reclaim any freelist left by a crash after a committed
                // delete but before VACUUM. Only then make the decision from measured files/pages again.
                checkpointAndCompact(db, vacuum = false)
                if (pragmaLong(db, "freelist_count") > 0) {
                    checkpointAndCompact(db, vacuum = true)
                }
                usage = storageUsage(db)
            }
            while (usage.accountedBytes > maxStorageBytes) {
                val target = (usage.accountedBytes - maxStorageBytes)
                    .coerceAtLeast(pragmaLong(db, "page_size"))
                val oldest = oldestCompletedForBudget(db, target, protectedKey)
                if (oldest.isEmpty()) break // Active Runs remain exempt even if they alone exceed the cap.
                deleteKeys(db, oldest.map { it.key })
                removed += oldest.map { it.key }
                checkpointAndCompact(db, vacuum = true)
                usage = storageUsage(db)
            }
        } finally {
            // Deletions may already have committed even if checkpoint/VACUUM failed; keep the observable cache in
            // lock-step with SQLite and allow future periodic maintenance to retry the physical compaction.
            if (removed.isNotEmpty()) _runs.value = _runs.value.filterNot { it.key in removed }
        }
    }

    @Synchronized
    internal fun storageUsage(): RunStorageUsage = storageUsage(writableDatabase)

    private fun storageUsage(db: SQLiteDatabase): RunStorageUsage {
        val pageSize = pragmaLong(db, "page_size")
        val usedPages = (pragmaLong(db, "page_count") - pragmaLong(db, "freelist_count")).coerceAtLeast(0)
        return RunStorageUsage(
            usedPageBytes = usedPages * pageSize,
            mainFileBytes = databaseFile.lengthOrZero(),
            walFileBytes = File(databaseFile.path + "-wal").lengthOrZero(),
            shmFileBytes = File(databaseFile.path + "-shm").lengthOrZero(),
        )
    }

    private fun completedBefore(db: SQLiteDatabase, cutoff: Long): List<SizedRunKey> = db.rawQuery(
        "SELECT host_client, run_id, LENGTH(payload) FROM runs " +
            "WHERE active = 0 AND received_at < ? ORDER BY received_at, updated_at",
        arrayOf(cutoff.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(SizedRunKey(RunKey(cursor.getString(0), cursor.getString(1)), cursor.getLong(2)))
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
        if (newestFirst.size <= MAX_COMPLETED_RUNS) return emptyList()

        // A just-committed snapshot must survive until its renderer checkpoints it, even under a clock tie.
        val protectedCompleted = protectedKey?.takeIf { it in newestFirst }
        val retained = buildList {
            protectedCompleted?.let(::add)
            newestFirst.asSequence()
                .filterNot { it == protectedCompleted }
                .take(MAX_COMPLETED_RUNS - if (protectedCompleted == null) 0 else 1)
                .forEach(::add)
        }.toSet()
        return newestFirst.filterNot { it in retained }
    }

    private fun oldestCompletedForBudget(
        db: SQLiteDatabase,
        targetBytes: Long,
        protectedKey: RunKey?,
    ): List<SizedRunKey> = db.rawQuery(
        "SELECT host_client, run_id, LENGTH(payload) FROM runs " +
            "WHERE active = 0 ORDER BY received_at, updated_at LIMIT $PRUNE_BATCH_LIMIT",
        emptyArray(),
    ).use { cursor ->
        buildList {
            var selectedBytes = 0L
            while (cursor.moveToNext() && (isEmpty() || selectedBytes < targetBytes)) {
                val key = RunKey(cursor.getString(0), cursor.getString(1))
                if (key == protectedKey) continue
                val bytes = cursor.getLong(2).coerceAtLeast(1)
                add(SizedRunKey(key, bytes))
                selectedBytes += bytes
            }
        }
    }

    private fun deleteKeys(db: SQLiteDatabase, keys: List<RunKey>) {
        db.beginTransaction()
        try {
            keys.forEach { key ->
                db.delete(
                    "runs",
                    "host_client = ? AND run_id = ?",
                    arrayOf(key.hostClientId, key.runId),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
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

    private fun pragmaLong(db: SQLiteDatabase, name: String): Long = db.rawQuery(
        "PRAGMA $name",
        emptyArray(),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun readAll(): List<StoredRun> = runCatching {
        readableDatabase.rawQuery(
            "SELECT payload, received_at, presented_revision FROM runs ORDER BY active DESC, updated_at DESC",
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val state = runCatching {
                        ProtocolCodec.decodeFromCbor<RunState>(cursor.getBlob(0))
                    }.getOrNull() ?: continue
                    add(StoredRun(state, cursor.getLong(1), cursor.getLong(2)))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun RunState.isActive(): Boolean = phase == RunPhase.RUNNING || phase == RunPhase.BLOCKED

    private fun File.lengthOrZero(): Long = if (exists()) length() else 0L

    private data class ExistingRun(val revision: Long, val presentedRevision: Long)
    private data class SizedRunKey(val key: RunKey, val bytes: Long)

    companion object {
        private const val DB_NAME = "runs.db"
        private const val VERSION = 2
        private const val COMPLETED_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_STORAGE_BYTES = 100L * 1024 * 1024
        private const val MAX_COMPLETED_RUNS = 50
        private const val PRUNE_BATCH_LIMIT = 64
        private val RUN_ORDER = compareByDescending<StoredRun> { it.active }
            .thenByDescending { it.state.updatedAt }
    }
}
