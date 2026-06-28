package net.extrawdw.apps.notisync.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import net.extrawdw.apps.notisync.channel.MessageDedup
import net.extrawdw.apps.notisync.domain.MirrorAckIndex
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RelayAck

/**
 * Durable, restart-surviving message bookkeeping for the receive path, backed by a tiny SQLite db in
 * app-private storage (so it persists across an app update / package replace, the exact case where the
 * in-memory channel dedup was being wiped and the broker's un-acked backlog re-posted). Three concerns,
 * one file:
 *
 *  * **dedup** — every relay message id we've durably handled, kept [RETENTION_MS] (≥ the broker's
 *    relay TTL, so any redelivery of a still-queued item is always recognised). Implements
 *    [MessageDedup] for [net.extrawdw.apps.notisync.channel.SecureChannel].
 *  * **pending_ack** — ids we handled but haven't yet told the broker to drop (chiefly FCM-inline
 *    deliveries, which never ack inline). Drained in one batch request by the relay worker.
 *  * **mirror_msg** — which relay message delivered the mirror for a (sourceClient, sourceKey), so a
 *    local dismissal can queue that exact id for ack. Implements [MirrorAckIndex] for the mirror engine.
 *
 * All methods are best-effort: any storage error degrades toward "not seen / not recorded", i.e.
 * toward a possible duplicate, never toward suppressing a notification that was never shown.
 * Thread-safe — Android's [SQLiteDatabase] serialises writes; only opened off the main thread.
 */
class MessageStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, VERSION),
    MessageDedup,
    MirrorAckIndex {

    private fun now(): Long = System.currentTimeMillis()

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        // IF NOT EXISTS + an additive onUpgrade means a future schema-version bump never drops these
        // tables (which would re-wipe the dedup and reintroduce the very bug this fixes).
        db.execSQL("CREATE TABLE IF NOT EXISTS dedup (message_id TEXT PRIMARY KEY, handled_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS pending_ack (message_id TEXT PRIMARY KEY, queued_at INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mirror_msg (" +
                    "source_client TEXT NOT NULL, source_key TEXT NOT NULL, message_id TEXT NOT NULL, " +
                    "recorded_at INTEGER NOT NULL, PRIMARY KEY (source_client, source_key))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db) // additive only — preserve handled-message history across upgrades
    }

    // --- MessageDedup (channel) -------------------------------------------------------------------

    override fun seen(messageId: String): Boolean = runCatching {
        readableDatabase.rawQuery(
            "SELECT 1 FROM dedup WHERE message_id = ? LIMIT 1",
            arrayOf(messageId)
        ).use {
            it.moveToFirst()
        }
    }.getOrDefault(false)

    override fun record(messageId: String) {
        runCatching {
            writableDatabase.insertWithOnConflict(
                "dedup",
                null,
                ContentValues().apply {
                    put("message_id", messageId)
                    put("handled_at", now())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    // --- Pending relay acks -----------------------------------------------------------------------

    /** Queue [messageId] for a later batch relay-ack. Idempotent (PK conflict ignored). */
    fun enqueueAck(messageId: String) {
        runCatching {
            writableDatabase.insertWithOnConflict(
                "pending_ack",
                null,
                ContentValues().apply {
                    put("message_id", messageId)
                    put("queued_at", now())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    /** Up to [limit] queued ack ids, oldest first. The worker acks these, then [clearAcks] them. */
    fun pendingAcks(limit: Int = MAX_ACK_BATCH): List<String> = runCatching {
        readableDatabase.rawQuery(
            "SELECT message_id FROM pending_ack ORDER BY queued_at LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }.getOrDefault(emptyList())

    /** Remove [messageIds] from the ack queue after the broker confirmed the batch ack. */
    fun clearAcks(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        runCatching {
            val placeholders = messageIds.joinToString(",") { "?" }
            writableDatabase.delete(
                "pending_ack",
                "message_id IN ($placeholders)",
                messageIds.toTypedArray()
            )
        }
    }

    // --- MirrorAckIndex (dismissal -> ack) --------------------------------------------------------

    override fun recordMirror(sourceClientId: ClientId, sourceKey: String, messageId: String) {
        if (messageId.isEmpty()) return
        runCatching {
            writableDatabase.insertWithOnConflict(
                "mirror_msg",
                null,
                ContentValues().apply {
                    put("source_client", sourceClientId.value)
                    put("source_key", sourceKey)
                    put("message_id", messageId)
                    put("recorded_at", now())
                },
                SQLiteDatabase.CONFLICT_REPLACE, // a re-post (same key, new id) supersedes the old mapping
            )
        }
    }

    override fun onDismissed(sourceClientId: ClientId, sourceKey: String) {
        val messageId = runCatching {
            readableDatabase.rawQuery(
                "SELECT message_id FROM mirror_msg WHERE source_client = ? AND source_key = ? LIMIT 1",
                arrayOf(sourceClientId.value, sourceKey),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: return
        enqueueAck(messageId)
    }

    /** Drop dedup / mapping / ack rows older than the retention window (relay TTL has passed by then). */
    fun prune() {
        val cutoff = now() - RETENTION_MS
        runCatching {
            writableDatabase.run {
                delete("dedup", "handled_at < ?", arrayOf(cutoff.toString()))
                delete("mirror_msg", "recorded_at < ?", arrayOf(cutoff.toString()))
                delete("pending_ack", "queued_at < ?", arrayOf(cutoff.toString()))
            }
        }
    }

    companion object {
        private const val DB_NAME = "message_ledger.db"
        private const val VERSION = 1

        /** Retain handled-message ids for 72h — comfortably over the broker's 48h relay TTL, so a
         *  redelivery of any still-queued item is always recognised as a duplicate. */
        private const val RETENTION_MS = 72L * 60 * 60 * 1000

        /** Cap a single batch ack at the shared protocol limit — the same value the server chunks by,
         *  keeping its `IN (...)` under SQLite's 999-variable limit. */
        const val MAX_ACK_BATCH = RelayAck.MAX_BATCH
    }
}
