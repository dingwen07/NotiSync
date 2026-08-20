package net.extrawdw.apps.notisync.data.storage.importer.legacy

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Read-only contract for the shipped message_ledger.db v2 schema. */
internal class LegacyMessageLedgerV2Reader {
    /**
     * Streams one pinned WAL snapshot without retaining a source-sized Java collection. Text byte
     * lengths are checked through metadata cursors before a point read materializes each value.
     */
    fun stream(
        file: File,
        checkCancelled: () -> Unit = {},
        sink: LegacyMessageLedgerRowSink = LegacyMessageLedgerRowSink.DISCARD,
    ): LegacyMessageLedgerStreamEvidence = readLegacySqliteSnapshot(
        file = file,
        source = LegacySourceId.MESSAGE_LEDGER,
        expectedTables = MESSAGE_LEDGER_TABLES,
    ) { source, database ->
        val sourceDigest = LegacyDigestAccumulator().apply {
            text("NotiSync/message_ledger/v2")
        }
        val retainedDigest = LegacyMessageLedgerV2RetainedDigestAccumulator()
        var commandCount = 0L

        database.streamDedup(sourceDigest, checkCancelled) { row ->
            retainedDigest.appendDedup(row)
            sink.acceptDedup(row)
            commandCount = Math.addExact(commandCount, 1)
        }
        database.streamMirrorMessages(sourceDigest, checkCancelled) { row ->
            retainedDigest.appendMirrorMessage(row)
            sink.acceptMirrorMessage(row)
            commandCount = Math.addExact(commandCount, 1)
        }
        database.streamMirrorLifecycles(sourceDigest, checkCancelled) { row ->
            retainedDigest.appendMirrorLifecycle(row)
            sink.acceptMirrorLifecycle(row)
            commandCount = Math.addExact(commandCount, 1)
        }
        database.streamMetadata(sourceDigest, checkCancelled) { row ->
            retainedDigest.appendMetadata(row)
            sink.acceptMetadata(row)
            commandCount = Math.addExact(commandCount, 1)
        }
        val skippedPendingAckCount = database.readPendingAckDigest(sourceDigest, checkCancelled)
        val skippedRelayInboxCount = database.readRelayInboxDigest(sourceDigest, checkCancelled)

        val contentDigest = sourceDigest.digest()
        val retainedCommandDigest = retainedDigest.digest()
        LegacyMessageLedgerStreamEvidence(
            source = source,
            commandCount = commandCount,
            skippedRelayInboxCount = skippedRelayInboxCount,
            skippedPendingAckCount = skippedPendingAckCount,
            digests = LegacySourceDigests(
                contentDigest = contentDigest,
                logicalFingerprint = legacyMessageLedgerLogicalFingerprint(
                    source,
                    contentDigest,
                    retainedCommandDigest,
                ),
            ),
            retainedCommandDigest = retainedCommandDigest,
        )
    }

    private fun SQLiteDatabase.streamDedup(
        digest: LegacyDigestAccumulator,
        checkCancelled: () -> Unit,
        consume: (LegacyDedupRow) -> Unit,
    ) = rawQuery(
        "SELECT rowid, length(CAST(message_id AS BLOB)), handled_at " +
            "FROM dedup ORDER BY message_id COLLATE BINARY",
        emptyArray(),
    ).use { cursor ->
        var ordinal = 0L
        digest.text("dedup")
        while (cursor.moveToNext()) {
            checkCancelled()
            cursor.requireTextLength("dedup", ordinal, 1, MAX_IMPORTED_TEXT_BYTES)
            val messageId = readTextByRowId("dedup", "message_id", cursor.getLong(0), ordinal)
            val handledAt = cursor.getLong(2)
            if (handledAt < 0) {
                malformed("dedup", ordinal, "handled_at must be non-negative")
            }
            val row = LegacyDedupRow(messageId, handledAt)
            digest.text(messageId)
            digest.long(handledAt)
            consume(row)
            ordinal = Math.addExact(ordinal, 1)
        }
    }

    private fun SQLiteDatabase.streamMirrorMessages(
        digest: LegacyDigestAccumulator,
        checkCancelled: () -> Unit,
        consume: (LegacyMirrorMessageRow) -> Unit,
    ) = rawQuery(
        "SELECT rowid, length(CAST(source_client AS BLOB)), length(CAST(source_key AS BLOB)), " +
            "length(CAST(message_id AS BLOB)), recorded_at " +
            "FROM mirror_msg ORDER BY source_client COLLATE BINARY, source_key COLLATE BINARY",
        emptyArray(),
    ).use { cursor ->
        var ordinal = 0L
        digest.text("mirror_msg")
        while (cursor.moveToNext()) {
            checkCancelled()
            (1..3).forEach { index ->
                cursor.requireTextLength("mirror_msg", ordinal, index, MAX_IMPORTED_TEXT_BYTES)
            }
            val fields = readTextsByRowId(
                table = "mirror_msg",
                columns = "source_client, source_key, message_id",
                rowId = cursor.getLong(0),
                ordinal = ordinal,
                expectedCount = 3,
            )
            val recordedAt = cursor.getLong(4)
            if (recordedAt < 0) malformed("mirror_msg", ordinal, "recorded_at must be non-negative")
            val row = LegacyMirrorMessageRow(fields[0], fields[1], fields[2], recordedAt)
            digest.text(row.sourceClient)
            digest.text(row.sourceKey)
            digest.text(row.messageId)
            digest.long(row.recordedAt)
            consume(row)
            ordinal = Math.addExact(ordinal, 1)
        }
    }

    private fun SQLiteDatabase.streamMirrorLifecycles(
        digest: LegacyDigestAccumulator,
        checkCancelled: () -> Unit,
        consume: (LegacyMirrorLifecycleRow) -> Unit,
    ) = rawQuery(
        "SELECT rowid, length(CAST(source_client AS BLOB)), length(CAST(source_key AS BLOB)), " +
            "post_time, dismissed_at, updated_at " +
            "FROM mirror_lifecycle ORDER BY source_client COLLATE BINARY, source_key COLLATE BINARY",
        emptyArray(),
    ).use { cursor ->
        var ordinal = 0L
        digest.text("mirror_lifecycle")
        while (cursor.moveToNext()) {
            checkCancelled()
            cursor.requireTextLength("mirror_lifecycle", ordinal, 1, MAX_IMPORTED_TEXT_BYTES)
            cursor.requireTextLength("mirror_lifecycle", ordinal, 2, MAX_IMPORTED_TEXT_BYTES)
            val fields = readTextsByRowId(
                table = "mirror_lifecycle",
                columns = "source_client, source_key",
                rowId = cursor.getLong(0),
                ordinal = ordinal,
                expectedCount = 2,
            )
            val postTime = cursor.optionalLong(3)
            val dismissedAt = cursor.optionalLong(4)
            val updatedAt = cursor.getLong(5)
            if (postTime != null && postTime < 0 || dismissedAt != null && dismissedAt < 0 || updatedAt < 0) {
                malformed("mirror_lifecycle", ordinal, "lifecycle timestamps must be non-negative")
            }
            if (postTime != null && dismissedAt != null && dismissedAt < postTime) {
                malformed("mirror_lifecycle", ordinal, "dismissed_at must not precede post_time")
            }
            val row = LegacyMirrorLifecycleRow(fields[0], fields[1], postTime, dismissedAt, updatedAt)
            digest.text(row.sourceClient)
            digest.text(row.sourceKey)
            digest.nullableLong(row.postTime)
            digest.nullableLong(row.dismissedAt)
            digest.long(row.updatedAt)
            consume(row)
            ordinal = Math.addExact(ordinal, 1)
        }
    }

    private fun SQLiteDatabase.streamMetadata(
        digest: LegacyDigestAccumulator,
        checkCancelled: () -> Unit,
        consume: (LegacyMessageMetaRow) -> Unit,
    ) = rawQuery(
        "SELECT rowid, length(CAST(name AS BLOB)), long_value " +
            "FROM message_meta ORDER BY name COLLATE BINARY",
        emptyArray(),
    ).use { cursor ->
        var ordinal = 0L
        digest.text("message_meta")
        while (cursor.moveToNext()) {
            checkCancelled()
            cursor.requireTextLength("message_meta", ordinal, 1, MAX_METADATA_NAME_BYTES)
            val row = LegacyMessageMetaRow(
                name = readTextByRowId("message_meta", "name", cursor.getLong(0), ordinal),
                longValue = cursor.getLong(2),
            )
            digest.text(row.name)
            digest.long(row.longValue)
            consume(row)
            ordinal = Math.addExact(ordinal, 1)
        }
    }

    /** This query selects no ACK payload/body; only bounded metadata contributes to source evidence. */
    private fun SQLiteDatabase.readPendingAckDigest(
        digest: LegacyDigestAccumulator,
        checkCancelled: () -> Unit,
    ): Long = rawQuery(
        "SELECT rowid, length(CAST(message_id AS BLOB)), queued_at " +
            "FROM pending_ack ORDER BY message_id COLLATE BINARY",
        emptyArray(),
    ).use { cursor ->
        var count = 0L
        digest.text("pending_ack_skipped")
        while (cursor.moveToNext()) {
            checkCancelled()
            cursor.requireTextLength("pending_ack", count, 1, MAX_IMPORTED_TEXT_BYTES)
            val messageId = readTextByRowId("pending_ack", "message_id", cursor.getLong(0), count)
            val queuedAt = cursor.getLong(2)
            if (queuedAt < 0) {
                malformed("pending_ack", count, "queued_at must be non-negative")
            }
            digest.text(messageId)
            digest.long(queuedAt)
            count = Math.addExact(count, 1)
        }
        count
    }

    /**
     * Read only non-envelope columns. In particular, neither this metadata cursor nor the bounded
     * point read selects `envelope`, and no relay row reaches the retained-row sink.
     */
    private fun SQLiteDatabase.readRelayInboxDigest(
        digest: LegacyDigestAccumulator,
        checkCancelled: () -> Unit,
    ): Long = rawQuery(
        "SELECT rowid, length(CAST(message_id AS BLOB)), accepted_at, " +
            "length(CAST(delivery_mode AS BLOB)), received_at, early_ack " +
            "FROM relay_inbox ORDER BY message_id COLLATE BINARY",
        emptyArray(),
    ).use { cursor ->
        var count = 0L
        digest.text("relay_inbox_skipped")
        while (cursor.moveToNext()) {
            checkCancelled()
            cursor.requireTextLength("relay_inbox", count, 1, MAX_IMPORTED_TEXT_BYTES)
            cursor.requireTextLength("relay_inbox", count, 3, MAX_DELIVERY_MODE_BYTES)
            val fields = readTextsByRowId(
                table = "relay_inbox",
                columns = "message_id, delivery_mode",
                rowId = cursor.getLong(0),
                ordinal = count,
                expectedCount = 2,
            )
            val acceptedAt = cursor.getLong(2)
            val receivedAt = cursor.getLong(4)
            val earlyAck = cursor.getInt(5)
            if (acceptedAt < 0 || receivedAt < 0 || earlyAck !in 0..1) {
                malformed("relay_inbox", count, "relay metadata is outside its source bounds")
            }
            digest.text(fields[0])
            digest.long(acceptedAt)
            digest.text(fields[1])
            digest.long(receivedAt)
            digest.int(earlyAck)
            count = Math.addExact(count, 1)
        }
        count
    }

    private fun android.database.Cursor.requireTextLength(
        table: String,
        ordinal: Long,
        index: Int,
        maximum: Long,
    ) {
        if (isNull(index) || getLong(index) !in 1..maximum) {
            malformed(table, ordinal, "text byte length is outside the import bound")
        }
    }

    private fun SQLiteDatabase.readTextByRowId(
        table: String,
        column: String,
        rowId: Long,
        ordinal: Long,
    ): String = readTextsByRowId(table, column, rowId, ordinal, expectedCount = 1).single()

    private fun SQLiteDatabase.readTextsByRowId(
        table: String,
        columns: String,
        rowId: Long,
        ordinal: Long,
        expectedCount: Int,
    ): List<String> = rawQuery(
        "SELECT $columns FROM $table WHERE rowid = ? LIMIT 1",
        arrayOf(rowId.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) malformed(table, ordinal, "row disappeared inside pinned snapshot")
        List(expectedCount) { index ->
            if (cursor.isNull(index)) malformed(table, ordinal, "text column is NULL")
            cursor.getString(index).takeIf(String::isNotBlank)
                ?: malformed(table, ordinal, "text column is blank")
        }
    }

    private fun malformed(table: String, ordinal: Long, reason: String): Nothing =
        throw LegacyImportException.malformed(LegacySourceId.MESSAGE_LEDGER, table, ordinal, reason)

    companion object {
        /** Per-row heap guard; target-specific identifiers are checked more tightly by the adapter. */
        const val MAX_LEGACY_TEXT_BYTES: Long = 4L * 1024
        private const val MAX_IMPORTED_TEXT_BYTES = MAX_LEGACY_TEXT_BYTES
        private const val MAX_METADATA_NAME_BYTES = 256L
        private const val MAX_DELIVERY_MODE_BYTES = 128L
        private val MESSAGE_LEDGER_TABLES = listOf(
            LegacyTableContract(
                name = "dedup",
                columns = listOf(
                    LegacyColumnContract("message_id", "TEXT", notNull = false, primaryKeyOrdinal = 1),
                    LegacyColumnContract("handled_at", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                ),
            ),
            LegacyTableContract(
                name = "pending_ack",
                columns = listOf(
                    LegacyColumnContract("message_id", "TEXT", notNull = false, primaryKeyOrdinal = 1),
                    LegacyColumnContract("queued_at", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                ),
            ),
            LegacyTableContract(
                name = "mirror_msg",
                columns = listOf(
                    LegacyColumnContract("source_client", "TEXT", notNull = true, primaryKeyOrdinal = 1),
                    LegacyColumnContract("source_key", "TEXT", notNull = true, primaryKeyOrdinal = 2),
                    LegacyColumnContract("message_id", "TEXT", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("recorded_at", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                ),
            ),
            LegacyTableContract(
                name = "relay_inbox",
                columns = listOf(
                    LegacyColumnContract("message_id", "TEXT", notNull = false, primaryKeyOrdinal = 1),
                    LegacyColumnContract("envelope", "BLOB", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("accepted_at", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("delivery_mode", "TEXT", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("received_at", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("early_ack", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                ),
            ),
            LegacyTableContract(
                name = "message_meta",
                columns = listOf(
                    LegacyColumnContract("name", "TEXT", notNull = false, primaryKeyOrdinal = 1),
                    LegacyColumnContract("long_value", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                ),
            ),
            LegacyTableContract(
                name = "mirror_lifecycle",
                columns = listOf(
                    LegacyColumnContract("source_client", "TEXT", notNull = true, primaryKeyOrdinal = 1),
                    LegacyColumnContract("source_key", "TEXT", notNull = true, primaryKeyOrdinal = 2),
                    LegacyColumnContract("post_time", "INTEGER", notNull = false, primaryKeyOrdinal = 0),
                    LegacyColumnContract("dismissed_at", "INTEGER", notNull = false, primaryKeyOrdinal = 0),
                    LegacyColumnContract("updated_at", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                ),
            ),
        )
    }
}
