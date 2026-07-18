package net.extrawdw.apps.notisync.run

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunControl

internal interface RunControlQueue {
    fun enqueue(control: RunControl)
    fun pending(): List<RunControl>
    fun remove(requestId: String)
}

internal enum class QueuedRunControlDisposition { SEND, RETAIN, DROP }

/**
 * Process-death-safe outbound controls originating from notification actions. Rows are removed only after the
 * broker accepts the exact [RunControl]; retries preserve its request id so host-side dedup prevents repeated input
 * or signals if Android dies after send acceptance but before local removal.
 */
internal class RunControlOutbox(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, VERSION),
    RunControlQueue {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE controls (" +
                "request_id TEXT PRIMARY KEY, requested_at INTEGER NOT NULL, payload BLOB NOT NULL)"
        )
        db.execSQL("CREATE INDEX controls_order_idx ON controls(requested_at, request_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This outbox has not shipped; replacing its private schema is safe until the first release.
        db.execSQL("DROP TABLE IF EXISTS controls")
        onCreate(db)
    }

    @Synchronized
    override fun enqueue(control: RunControl) {
        val inserted = writableDatabase.insertWithOnConflict(
            "controls",
            null,
            ContentValues().apply {
                put("request_id", control.requestId)
                put("requested_at", control.requestedAt)
                put("payload", ProtocolCodec.encodeToCbor(control))
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (inserted == -1L && !contains(control.requestId)) {
            error("could not persist Run control")
        }
    }

    @Synchronized
    override fun pending(): List<RunControl> = readableDatabase.rawQuery(
        "SELECT payload FROM controls ORDER BY requested_at, request_id",
        emptyArray(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val control = runCatching {
                    ProtocolCodec.decodeFromCbor<RunControl>(cursor.getBlob(0))
                }.getOrElse { error("corrupt Run control outbox row") }
                add(control)
            }
        }
    }

    @Synchronized
    override fun remove(requestId: String) {
        val removed = writableDatabase.delete("controls", "request_id = ?", arrayOf(requestId))
        if (removed == 0 && contains(requestId)) error("could not remove sent Run control")
    }

    private fun contains(requestId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM controls WHERE request_id = ? LIMIT 1",
        arrayOf(requestId),
    ).use { it.moveToFirst() }

    companion object {
        private const val DB_NAME = "run_control_outbox.db"
        private const val VERSION = 1
    }
}

/** Single-process serialization between the receiver's fast path and WorkManager's durable drain. */
internal object RunControlOutboxDrainer {
    private val mutex = Mutex()

    suspend fun drain(
        queue: RunControlQueue,
        nowMillis: Long = System.currentTimeMillis(),
        classify: (RunControl) -> QueuedRunControlDisposition = {
            QueuedRunControlDisposition.SEND
        },
        send: suspend (RunControl) -> Boolean,
    ): Boolean = mutex.withLock {
        val pending = runCatching { queue.pending() }.getOrElse { return@withLock false }
        var retryNeeded = false
        val byRun = pending.groupBy { control ->
            RunKey(control.hostClientId.value, control.runId)
        }
        for (controls in byRun.values) {
            for (control in controls) {
                // Notification actions are immediate commands, not an eventual-command queue. In particular, a
                // SIGNAL accepted after a long connectivity/key failure could terminate a Run the user no longer
                // intended to touch. Five minutes covers ordinary transient delivery while remaining conservative.
                val ageMillis = nowMillis - control.requestedAt
                if (ageMillis < 0 || ageMillis >= CONTROL_TTL_MS) {
                    if (runCatching { queue.remove(control.requestId) }.isFailure) {
                        retryNeeded = true
                        break
                    }
                    continue
                }
                val disposition = runCatching { classify(control) }.getOrNull()
                if (disposition == null) {
                    retryNeeded = true
                    break
                }
                when (disposition) {
                    QueuedRunControlDisposition.DROP -> {
                        if (runCatching { queue.remove(control.requestId) }.isFailure) {
                            retryNeeded = true
                            break
                        }
                    }
                    QueuedRunControlDisposition.RETAIN -> {
                        retryNeeded = true
                        break // Preserve order for this Run; unrelated Runs continue below.
                    }
                    QueuedRunControlDisposition.SEND -> {
                        if (!runCatching { send(control) }.getOrDefault(false)) {
                            retryNeeded = true
                            break // Preserve order for this Run; unrelated Runs continue below.
                        }
                        if (runCatching { queue.remove(control.requestId) }.isFailure) {
                            retryNeeded = true
                            break
                        }
                    }
                }
            }
        }
        !retryNeeded
    }

    internal const val CONTROL_TTL_MS = 5 * 60 * 1_000L
}
