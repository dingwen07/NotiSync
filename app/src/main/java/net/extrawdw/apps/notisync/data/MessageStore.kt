package net.extrawdw.apps.notisync.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import net.extrawdw.notisync.peer.channel.MessageDedup
import net.extrawdw.apps.notisync.domain.MirrorAckIndex
import net.extrawdw.apps.notisync.domain.MirrorLifecycleStore
import net.extrawdw.apps.notisync.domain.MirrorPostDecision
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RelayAck

/**
 * Durable, restart-surviving message bookkeeping for the receive path, backed by a tiny SQLite db in
 * app-private storage (so it persists across an app update / package replace, the exact case where the
 * in-memory channel dedup was being wiped and the broker's un-acked backlog re-posted). The same ledger
 * also owns the durable deferred inbox and notification lifecycle watermarks.
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
data class RelayInboxItem(
    val rowId: Long,
    val messageId: String,
    val envelope: ByteArray,
    val acceptedAt: Long,
    val deliveryMode: DeliveryMode,
    val receivedAt: Long,
    val earlyAck: Boolean,
)

enum class InboxInsertResult { INSERTED, EXISTS, FAILED }

class MessageStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, VERSION),
    MessageDedup,
    MirrorAckIndex,
    MirrorLifecycleStore {

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
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS relay_inbox (" +
                "message_id TEXT PRIMARY KEY, envelope BLOB NOT NULL, accepted_at INTEGER NOT NULL, " +
                "delivery_mode TEXT NOT NULL, received_at INTEGER NOT NULL, early_ack INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS message_meta (name TEXT PRIMARY KEY, long_value INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mirror_lifecycle (" +
                "source_client TEXT NOT NULL, source_key TEXT NOT NULL, post_time INTEGER, " +
                "dismissed_at INTEGER, updated_at INTEGER NOT NULL, PRIMARY KEY (source_client, source_key))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS relay_inbox_accepted_idx ON relay_inbox (accepted_at, received_at)")
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
        ensureAckQueued(messageId)
    }

    /** Persist an ACK intent and confirm it is queryable before an inbox row may be removed. */
    fun ensureAckQueued(messageId: String): Boolean = runCatching {
            writableDatabase.insertWithOnConflict(
                "pending_ack",
                null,
                ContentValues().apply {
                    put("message_id", messageId)
                    put("queued_at", now())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            readableDatabase.rawQuery(
                "SELECT 1 FROM pending_ack WHERE message_id = ? LIMIT 1",
                arrayOf(messageId),
            ).use { it.moveToFirst() }
        }.getOrDefault(false)

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

    // --- Durable relay inbox ---------------------------------------------------------------------

    /**
     * Persist a stale, authenticated envelope and its early ACK intent in one transaction. Only a new
     * message id advances the quiet-window clock; duplicate FCM/WebSocket copies cannot starve the drain.
     */
    fun stageDeferred(
        messageId: String,
        envelope: ByteArray,
        acceptedAt: Long,
        deliveryMode: DeliveryMode,
    ): InboxInsertResult = runCatching {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val inserted = db.insertWithOnConflict(
                "relay_inbox",
                null,
                inboxValues(messageId, envelope, acceptedAt, deliveryMode, earlyAck = true),
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (inserted == -1L) {
                db.setTransactionSuccessful()
                return@runCatching InboxInsertResult.EXISTS
            }
            db.insertWithOnConflict(
                "pending_ack",
                null,
                ContentValues().apply {
                    put("message_id", messageId)
                    put("queued_at", now())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            db.insertWithOnConflict(
                "message_meta",
                null,
                ContentValues().apply {
                    put("name", META_LAST_DEFERRED_AT)
                    put("long_value", now())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
            InboxInsertResult.INSERTED
        } finally {
            db.endTransaction()
        }
    }.getOrDefault(InboxInsertResult.FAILED)

    /** Stage one broker-batch item. It remains unacked until its handler/reducer commits. */
    fun stageBatchItem(
        messageId: String,
        envelope: ByteArray,
        acceptedAt: Long,
        deliveryMode: DeliveryMode = DeliveryMode.RELAY_DRAIN,
    ): InboxInsertResult = runCatching {
        val inserted = writableDatabase.insertWithOnConflict(
            "relay_inbox",
            null,
            inboxValues(messageId, envelope, acceptedAt, deliveryMode, earlyAck = false),
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (inserted == -1L) InboxInsertResult.EXISTS else InboxInsertResult.INSERTED
    }.getOrDefault(InboxInsertResult.FAILED)

    fun lastDeferredAt(): Long? = runCatching {
        readableDatabase.rawQuery(
            "SELECT long_value FROM message_meta WHERE name = ? LIMIT 1",
            arrayOf(META_LAST_DEFERRED_AT),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
    }.getOrNull()

    fun hasInbox(): Boolean = runCatching {
        readableDatabase.rawQuery("SELECT 1 FROM relay_inbox LIMIT 1", null).use { it.moveToFirst() }
    }.getOrDefault(false)

    fun hasDeferredInbox(): Boolean = runCatching {
        readableDatabase.rawQuery("SELECT 1 FROM relay_inbox WHERE early_ack = 1 LIMIT 1", null)
            .use { it.moveToFirst() }
    }.getOrDefault(false)

    /** Capture the local inbox boundary before reconciliation; later inserts wait for the next drain. */
    fun relayInboxHighWater(): Long = runCatching {
        readableDatabase.rawQuery("SELECT COALESCE(MAX(rowid), 0) FROM relay_inbox", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }.getOrDefault(0L)

    /** A bounded keyset page. Rows may be deleted between pages without disturbing the cursor. */
    fun relayInboxPage(
        afterRowId: Long,
        throughRowId: Long,
        limit: Int = INBOX_PAGE_SIZE,
    ): List<RelayInboxItem> = runCatching {
        readableDatabase.rawQuery(
            "SELECT rowid, message_id, envelope, accepted_at, delivery_mode, received_at, early_ack " +
                "FROM relay_inbox WHERE rowid > ? AND rowid <= ? ORDER BY rowid LIMIT ?",
            arrayOf(afterRowId.toString(), throughRowId.toString(), limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RelayInboxItem(
                            rowId = cursor.getLong(0),
                            messageId = cursor.getString(1),
                            envelope = cursor.getBlob(2),
                            acceptedAt = cursor.getLong(3),
                            deliveryMode = runCatching { DeliveryMode.valueOf(cursor.getString(4)) }
                                .getOrDefault(DeliveryMode.RELAY_DRAIN),
                            receivedAt = cursor.getLong(5),
                            earlyAck = cursor.getInt(6) != 0,
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    fun deleteInbox(messageIds: Collection<String>): Boolean {
        if (messageIds.isEmpty()) return true
        return runCatching {
            messageIds.chunked(MAX_ACK_BATCH).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                writableDatabase.delete(
                    "relay_inbox",
                    "message_id IN ($placeholders)",
                    chunk.toTypedArray(),
                )
            }
            val placeholders = messageIds.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT 1 FROM relay_inbox WHERE message_id IN ($placeholders) LIMIT 1",
                messageIds.toTypedArray(),
            ).use { !it.moveToFirst() }
        }.getOrDefault(false)
    }

    private fun inboxValues(
        messageId: String,
        envelope: ByteArray,
        acceptedAt: Long,
        deliveryMode: DeliveryMode,
        earlyAck: Boolean,
    ) = ContentValues().apply {
        put("message_id", messageId)
        put("envelope", envelope)
        put("accepted_at", acceptedAt)
        put("delivery_mode", deliveryMode.name)
        put("received_at", now())
        put("early_ack", if (earlyAck) 1 else 0)
    }

    // --- Persistent notification lifecycle -------------------------------------------------------

    override fun acceptPost(
        sourceClientId: ClientId,
        sourceKey: String,
        postTime: Long,
    ): MirrorPostDecision = runCatching {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var priorPost: Long? = null
            var dismissedAt: Long? = null
            db.rawQuery(
                "SELECT post_time, dismissed_at FROM mirror_lifecycle " +
                    "WHERE source_client = ? AND source_key = ? LIMIT 1",
                arrayOf(sourceClientId.value, sourceKey),
            ).use { c ->
                if (c.moveToFirst()) {
                    priorPost = if (c.isNull(0)) null else c.getLong(0)
                    dismissedAt = if (c.isNull(1)) null else c.getLong(1)
                }
            }
            if ((dismissedAt ?: Long.MIN_VALUE) >= postTime || (priorPost ?: Long.MIN_VALUE) > postTime) {
                db.setTransactionSuccessful()
                return@runCatching MirrorPostDecision.STALE
            }
            db.insertWithOnConflict(
                "mirror_lifecycle",
                null,
                ContentValues().apply {
                    put("source_client", sourceClientId.value)
                    put("source_key", sourceKey)
                    put("post_time", maxOf(priorPost ?: Long.MIN_VALUE, postTime))
                    if (dismissedAt != null && postTime <= dismissedAt!!) put("dismissed_at", dismissedAt)
                    else putNull("dismissed_at")
                    put("updated_at", now())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
            if (priorPost == null) MirrorPostDecision.FIRST else MirrorPostDecision.NEWER
        } finally {
            db.endTransaction()
        }
    }.getOrDefault(MirrorPostDecision.NEWER)

    override fun recordDismissal(
        sourceClientId: ClientId,
        sourceKey: String,
        dismissedAt: Long,
    ): Boolean = runCatching {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var priorPost: Long? = null
            var priorDismissal: Long? = null
            db.rawQuery(
                "SELECT post_time, dismissed_at FROM mirror_lifecycle " +
                    "WHERE source_client = ? AND source_key = ? LIMIT 1",
                arrayOf(sourceClientId.value, sourceKey),
            ).use { c ->
                if (c.moveToFirst()) {
                    priorPost = if (c.isNull(0)) null else c.getLong(0)
                    priorDismissal = if (c.isNull(1)) null else c.getLong(1)
                }
            }
            if ((priorPost ?: Long.MIN_VALUE) > dismissedAt) {
                db.setTransactionSuccessful()
                return@runCatching false
            }
            db.insertWithOnConflict(
                "mirror_lifecycle",
                null,
                ContentValues().apply {
                    put("source_client", sourceClientId.value)
                    put("source_key", sourceKey)
                    if (priorPost == null) putNull("post_time") else put("post_time", priorPost)
                    put("dismissed_at", maxOf(priorDismissal ?: Long.MIN_VALUE, dismissedAt))
                    put("updated_at", now())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }.getOrDefault(true)

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
                delete("mirror_lifecycle", "updated_at < ?", arrayOf(cutoff.toString()))
            }
        }
    }

    companion object {
        private const val DB_NAME = "message_ledger.db"
        private const val VERSION = 2
        private const val META_LAST_DEFERRED_AT = "last_deferred_at"
        private const val INBOX_PAGE_SIZE = 128

        /** Retain handled-message ids for 72h — comfortably over the broker's 48h relay TTL, so a
         *  redelivery of any still-queued item is always recognised as a duplicate. */
        private const val RETENTION_MS = 72L * 60 * 60 * 1000

        /** Cap a single batch ack at the shared protocol limit — the same value the server chunks by,
         *  keeping its `IN (...)` under SQLite's 999-variable limit. */
        const val MAX_ACK_BATCH = RelayAck.MAX_BATCH
    }
}
