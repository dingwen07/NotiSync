package net.extrawdw.apps.notisync.data.storage.importer.coordinator

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyDedupRow
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyFailureKind
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyImportException
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyMessageLedgerRowSink
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyMessageLedgerStreamEvidence
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyMessageLedgerV2Reader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyMessageLedgerV2RetainedDigestAccumulator
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyMessageMetaRow
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyMirrorLifecycleRow
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyMirrorMessageRow
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySourceId
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySqliteSource
import net.extrawdw.apps.notisync.data.storage.importer.legacy.legacyMessageLedgerLogicalFingerprint
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportDigest
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportFailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSource
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSources
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalRebuildIdentity

/**
 * Streams the unbounded v2 handled ledger into a non-authoritative no-backup stage. Pending relay
 * and ACK payloads are never selected or staged. Only a COMPLETE, checksummed stage can be paged.
 */
internal class LegacyMessageLedgerSourceAdapter(
    private val sourceFile: File,
    noBackupDirectory: File,
    private val reader: LegacyMessageLedgerV2Reader = LegacyMessageLedgerV2Reader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val usableSpace: (File) -> Long = File::getUsableSpace,
) : OperationalImportSourceAdapter {
    override val source: OperationalImportSource = OperationalImportSources.MESSAGE_LEDGER_V2
    private val stagingFile = File(noBackupDirectory, STAGING_FILE_NAME)

    init {
        require(noBackupDirectory.isDirectory) { "message-ledger staging requires an existing no-backup directory" }
        require(stagingFile.parentFile?.canonicalFile == noBackupDirectory.canonicalFile) {
            "message-ledger staging escaped the no-backup directory"
        }
        require(sourceAndStageFilesAreDisjoint()) {
            "message-ledger staging files overlap the read-only legacy source"
        }
    }

    override suspend fun isPresent(): Boolean = withContext(ioDispatcher) { sourceFile.isFile }

    override suspend fun load(identity: OperationalRebuildIdentity): OperationalImportSnapshot = withContext(ioDispatcher) {
        currentCoroutineContext().ensureActive()
        // A stage is memory-bounding scratch, never a restart cursor or source of authority.
        deleteStageFiles()
        buildStage()
    }

    override suspend fun fingerprintStillMatches(snapshot: OperationalImportSnapshot): Boolean =
        withContext(ioDispatcher) {
            ImportDigest.sha256(readSourceEvidence().digests.logicalFingerprint) == snapshot.sourceFingerprint
        }

    override suspend fun cleanupAfterAttempt() = withContext(ioDispatcher) {
        deleteStageFiles()
    }

    private suspend fun buildStage(): OperationalImportSnapshot {
        deleteStageFiles()
        requireStagingSpace()
        val coroutineContext = currentCoroutineContext()
        val database = openStageWritable()
        try {
            createStageSchema(database)
            database.beginTransactionNonExclusive()
            try {
                var sourceOrdinal = 0L
                var commandOrdinal = 0L
                val sink = object : LegacyMessageLedgerRowSink {
                    override fun acceptDedup(row: LegacyDedupRow) {
                        coroutineContext.ensureActive()
                        val command = validateSourceRow("dedup", sourceOrdinal) {
                            OperationalImportCommand.HandledMessageIdOnly(row.messageId, row.handledAt)
                        }
                        database.insertOrThrow(
                            STAGE_DEDUP,
                            null,
                            ContentValues().apply {
                                put("ordinal", sourceOrdinal)
                                put("command_ordinal", commandOrdinal)
                                put("message_id", command.messageId)
                                put("handled_at", command.handledAt)
                            },
                        )
                        sourceOrdinal = Math.addExact(sourceOrdinal, 1)
                        commandOrdinal = Math.addExact(commandOrdinal, 1)
                    }

                    override fun acceptMirrorMessage(row: LegacyMirrorMessageRow) {
                        coroutineContext.ensureActive()
                        database.insertOrThrow(
                            STAGE_MIRROR_MESSAGE,
                            null,
                            ContentValues().apply {
                                put("ordinal", sourceOrdinal)
                                put("source_client_id", row.sourceClient)
                                put("source_key", row.sourceKey)
                                put("message_id", row.messageId)
                                put("recorded_at", row.recordedAt)
                            },
                        )
                        sourceOrdinal = Math.addExact(sourceOrdinal, 1)
                    }

                    override fun acceptMirrorLifecycle(row: LegacyMirrorLifecycleRow) {
                        coroutineContext.ensureActive()
                        val command = validateSourceRow("mirror_lifecycle", sourceOrdinal) {
                            OperationalImportCommand.MirrorLifecycle(
                                row.sourceClient,
                                row.sourceKey,
                                row.postTime,
                                row.dismissedAt,
                                row.updatedAt,
                            )
                        }
                        database.insertOrThrow(
                            STAGE_MIRROR_LIFECYCLE,
                            null,
                            ContentValues().apply {
                                put("ordinal", sourceOrdinal)
                                put("command_ordinal", commandOrdinal)
                                put("source_client_id", command.sourceClientId)
                                put("source_key", command.sourceKey)
                                command.postTime?.let { put("post_time", it) } ?: putNull("post_time")
                                command.dismissedAt?.let { put("dismissed_at", it) } ?: putNull("dismissed_at")
                                put("updated_at", command.updatedAt)
                            },
                        )
                        sourceOrdinal = Math.addExact(sourceOrdinal, 1)
                        commandOrdinal = Math.addExact(commandOrdinal, 1)
                    }

                    override fun acceptMetadata(row: LegacyMessageMetaRow) {
                        coroutineContext.ensureActive()
                        if (row.name != LAST_DEFERRED_AT) {
                            throw LegacyImportException.malformed(
                                LegacySourceId.MESSAGE_LEDGER,
                                "message_meta",
                                sourceOrdinal,
                                "unknown metadata name",
                            )
                        }
                        if (row.longValue < 0) {
                            throw LegacyImportException.malformed(
                                LegacySourceId.MESSAGE_LEDGER,
                                "message_meta",
                                sourceOrdinal,
                                "last_deferred_at must not be negative",
                            )
                        }
                        database.insertOrThrow(
                            STAGE_METADATA,
                            null,
                            ContentValues().apply {
                                put("ordinal", sourceOrdinal)
                                put("name", LAST_DEFERRED_AT)
                                put("long_value", row.longValue)
                            },
                        )
                        sourceOrdinal = Math.addExact(sourceOrdinal, 1)
                    }
                }
                val evidence = readSourceEvidence(
                    checkCancelled = coroutineContext::ensureActive,
                    sink = sink,
                )
                if (evidence.commandCount != sourceOrdinal) messageSourceBlocked("message_stage_count_mismatch")
                val skippedDeprecatedCount = Math.subtractExact(sourceOrdinal, commandOrdinal)
                database.insertOrThrow(
                    STAGE_META,
                    null,
                    ContentValues().apply {
                        put("singleton_id", 1)
                        put("state", STAGE_COMPLETE)
                        put("source_schema_version", source.expectedSchemaVersion)
                        put("source_fingerprint", evidence.digests.logicalFingerprint)
                        put("logical_content_digest", evidence.digests.contentDigest)
                        put("retained_command_digest", evidence.retainedCommandDigest)
                        put("retained_row_count", evidence.commandCount)
                        put("command_count", commandOrdinal)
                        put("skipped_deprecated_count", skippedDeprecatedCount)
                        put("skipped_relay_count", evidence.skippedRelayInboxCount)
                        put("skipped_ack_count", evidence.skippedPendingAckCount)
                    },
                )
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OperationalImportFailure) {
            throw failure
        } catch (failure: SQLiteFullException) {
            importRetryable("message_stage_storage_full", failure)
        } catch (failure: SQLiteDiskIOException) {
            importRetryable("message_stage_io", failure)
        } catch (failure: SQLException) {
            messageSourceBlocked("message_stage_database_failure")
        } finally {
            database.close()
        }
        return readCompleteStageOrNull() ?: messageSourceBlocked("message_stage_completion_missing")
    }

    private fun readSourceEvidence(
        checkCancelled: () -> Unit = {},
        sink: LegacyMessageLedgerRowSink = LegacyMessageLedgerRowSink.DISCARD,
    ): LegacyMessageLedgerStreamEvidence = try {
        reader.stream(sourceFile, checkCancelled, sink)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: LegacyImportException) {
        (failure.cause as? CancellationException)?.let { throw it }
        when (val cause = failure.cause) {
            is SQLiteFullException -> importRetryable("message_stage_storage_full", cause)
            is SQLiteDiskIOException -> importRetryable("message_stage_io", cause)
            is OperationalImportFailure -> throw cause
        }
        val disposition = if (failure.kind == LegacyFailureKind.SOURCE_IO) {
            ImportFailureDisposition.RETRYABLE
        } else {
            ImportFailureDisposition.BLOCKED
        }
        throw OperationalImportFailure(disposition, failure.kind.toStableCode(), failure)
    }

    private fun readCompleteStageOrNull(): MessageLedgerStagingSnapshot? {
        if (!stagingFile.isFile) return null
        val database = try {
            SQLiteDatabase.openDatabase(stagingFile.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (_: SQLiteDatabaseCorruptException) {
            deleteStageFiles()
            return null
        } catch (_: SQLException) {
            deleteStageFiles()
            return null
        }
        return try {
            if (!database.hasCleanQuickCheck()) return null
            val metadata = database.rawQuery(
                "SELECT state, source_schema_version, source_fingerprint, logical_content_digest, " +
                    "retained_command_digest, retained_row_count, command_count, " +
                    "skipped_deprecated_count, skipped_relay_count, skipped_ack_count " +
                    "FROM $STAGE_META WHERE singleton_id = 1",
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != STAGE_COMPLETE) return null
                MessageLedgerStageMetadata(
                    schemaVersion = cursor.getInt(1),
                    fingerprint = cursor.getBlob(2).copyOf(),
                    contentDigest = cursor.getBlob(3).copyOf(),
                    retainedCommandDigest = cursor.getBlob(4).copyOf(),
                    retainedRowCount = cursor.getLong(5),
                    commandCount = cursor.getLong(6),
                    skippedDeprecatedCount = cursor.getLong(7),
                    skippedRelayCount = cursor.getLong(8),
                    skippedAckCount = cursor.getLong(9),
                )
            }
            if (!metadata.valid()) return null
            val actualCount = listOf(
                STAGE_DEDUP,
                STAGE_MIRROR_MESSAGE,
                STAGE_MIRROR_LIFECYCLE,
                STAGE_METADATA,
            ).fold(0L) { total, table -> Math.addExact(total, database.countRows(table)) }
            if (actualCount != metadata.retainedRowCount) return null
            if (!database.hasCanonicalStageContent(metadata)) return null
            MessageLedgerStagingSnapshot(stagingFile, metadata, ioDispatcher)
        } catch (_: RuntimeException) {
            null
        } finally {
            database.close()
        }.also { snapshot ->
            if (snapshot == null) deleteStageFiles()
        }
    }

    private fun MessageLedgerStageMetadata.valid(): Boolean {
        if (
            schemaVersion != source.expectedSchemaVersion || fingerprint.size != ImportDigest.BYTES ||
            contentDigest.size != ImportDigest.BYTES || retainedCommandDigest.size != ImportDigest.BYTES ||
            retainedRowCount < 0 || commandCount < 0 || skippedDeprecatedCount < 0 ||
            skippedRelayCount < 0 || skippedAckCount < 0 ||
            Math.addExact(commandCount, skippedDeprecatedCount) != retainedRowCount
        ) return false
        val descriptor = LegacySqliteSource(
            id = LegacySourceId.MESSAGE_LEDGER,
            fileName = LegacySourceId.MESSAGE_LEDGER.fileName,
            userVersion = schemaVersion,
        )
        return MessageDigest.isEqual(
            legacyMessageLedgerLogicalFingerprint(descriptor, contentDigest, retainedCommandDigest),
            fingerprint,
        )
    }

    private fun SQLiteDatabase.hasCanonicalStageContent(metadata: MessageLedgerStageMetadata): Boolean {
        val digest = LegacyMessageLedgerV2RetainedDigestAccumulator()
        var ordinal = 0L
        var commandOrdinal = 0L

        rawQuery(
            "SELECT ordinal, command_ordinal, message_id, handled_at FROM $STAGE_DEDUP ORDER BY ordinal",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(0) != ordinal || cursor.getLong(1) != commandOrdinal) return false
                val row = LegacyDedupRow(cursor.getString(2) ?: return false, cursor.getLong(3))
                if (!validStageCommand { OperationalImportCommand.HandledMessageIdOnly(row.messageId, row.handledAt) }) {
                    return false
                }
                digest.appendDedup(row)
                ordinal = Math.addExact(ordinal, 1)
                commandOrdinal = Math.addExact(commandOrdinal, 1)
            }
        }
        rawQuery(
            "SELECT ordinal, source_client_id, source_key, message_id, recorded_at " +
                "FROM $STAGE_MIRROR_MESSAGE ORDER BY ordinal",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(0) != ordinal) return false
                val row = LegacyMirrorMessageRow(
                    cursor.getString(1) ?: return false,
                    cursor.getString(2) ?: return false,
                    cursor.getString(3) ?: return false,
                    cursor.getLong(4),
                )
                if (row.recordedAt < 0) return false
                digest.appendMirrorMessage(row)
                ordinal = Math.addExact(ordinal, 1)
            }
        }
        rawQuery(
            "SELECT ordinal, command_ordinal, source_client_id, source_key, post_time, dismissed_at, updated_at " +
                "FROM $STAGE_MIRROR_LIFECYCLE ORDER BY ordinal",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(0) != ordinal || cursor.getLong(1) != commandOrdinal) return false
                val row = LegacyMirrorLifecycleRow(
                    cursor.getString(2) ?: return false,
                    cursor.getString(3) ?: return false,
                    if (cursor.isNull(4)) null else cursor.getLong(4),
                    if (cursor.isNull(5)) null else cursor.getLong(5),
                    cursor.getLong(6),
                )
                if (!validStageCommand {
                        OperationalImportCommand.MirrorLifecycle(
                            row.sourceClient,
                            row.sourceKey,
                            row.postTime,
                            row.dismissedAt,
                            row.updatedAt,
                        )
                    }
                ) return false
                digest.appendMirrorLifecycle(row)
                ordinal = Math.addExact(ordinal, 1)
                commandOrdinal = Math.addExact(commandOrdinal, 1)
            }
        }
        rawQuery(
            "SELECT ordinal, name, long_value FROM $STAGE_METADATA ORDER BY ordinal",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(0) != ordinal) return false
                val row = LegacyMessageMetaRow(cursor.getString(1) ?: return false, cursor.getLong(2))
                if (row.name != LAST_DEFERRED_AT || row.longValue < 0) return false
                digest.appendMetadata(row)
                ordinal = Math.addExact(ordinal, 1)
            }
        }
        return ordinal == metadata.retainedRowCount && commandOrdinal == metadata.commandCount && MessageDigest.isEqual(
            digest.digest(),
            metadata.retainedCommandDigest,
        )
    }

    private fun SQLiteDatabase.hasCleanQuickCheck(): Boolean = rawQuery("PRAGMA quick_check", null).use { cursor ->
        if (!cursor.moveToFirst() || cursor.getString(0) != "ok") return@use false
        !cursor.moveToNext()
    }

    private fun SQLiteDatabase.countRows(table: String): Long = rawQuery(
        "SELECT COUNT(*) FROM $table",
        null,
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun requireStagingSpace() {
        val sourceBytes = listOf(sourceFile, File(sourceFile.path + "-wal"), File(sourceFile.path + "-shm"))
            .fold(0L) { total, file -> checkedMessageAdd(total, file.length()) }
        val required = checkedMessageAdd(checkedMessageMultiply(sourceBytes, 2), MIN_STAGING_HEADROOM_BYTES)
        if (usableSpace(requireNotNull(stagingFile.parentFile)) < required) {
            importRetryable("message_stage_space_insufficient")
        }
    }

    private fun openStageWritable(): SQLiteDatabase = try {
        SQLiteDatabase.openOrCreateDatabase(stagingFile, null).also(SQLiteDatabase::enableWriteAheadLogging)
    } catch (failure: SQLiteFullException) {
        importRetryable("message_stage_storage_full", failure)
    } catch (failure: SQLException) {
        importRetryable("message_stage_io", failure)
    }

    private fun createStageSchema(database: SQLiteDatabase) {
        database.execSQL("PRAGMA user_version = $STAGING_SCHEMA_VERSION")
        database.execSQL(
            "CREATE TABLE $STAGE_META (singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1), " +
                "state TEXT NOT NULL, source_schema_version INTEGER NOT NULL, source_fingerprint BLOB NOT NULL, " +
                "logical_content_digest BLOB NOT NULL, retained_command_digest BLOB NOT NULL, " +
                "retained_row_count INTEGER NOT NULL, command_count INTEGER NOT NULL, " +
                "skipped_deprecated_count INTEGER NOT NULL, skipped_relay_count INTEGER NOT NULL, " +
                "skipped_ack_count INTEGER NOT NULL)",
        )
        database.execSQL(
            "CREATE TABLE $STAGE_DEDUP (ordinal INTEGER NOT NULL PRIMARY KEY, command_ordinal INTEGER NOT NULL UNIQUE, " +
                "message_id TEXT NOT NULL UNIQUE, " +
                "handled_at INTEGER NOT NULL)",
        )
        database.execSQL(
            "CREATE TABLE $STAGE_MIRROR_MESSAGE (ordinal INTEGER NOT NULL PRIMARY KEY, source_client_id TEXT NOT NULL, " +
                "source_key TEXT NOT NULL, message_id TEXT NOT NULL, recorded_at INTEGER NOT NULL, " +
                "UNIQUE(source_client_id, source_key))",
        )
        database.execSQL(
            "CREATE TABLE $STAGE_MIRROR_LIFECYCLE (ordinal INTEGER NOT NULL PRIMARY KEY, " +
                "command_ordinal INTEGER NOT NULL UNIQUE, " +
                "source_client_id TEXT NOT NULL, source_key TEXT NOT NULL, post_time INTEGER, " +
                "dismissed_at INTEGER, updated_at INTEGER NOT NULL, UNIQUE(source_client_id, source_key))",
        )
        database.execSQL(
            "CREATE TABLE $STAGE_METADATA (ordinal INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL UNIQUE, " +
                "long_value INTEGER NOT NULL)",
        )
    }

    private fun deleteStageFiles() {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val file = File(stagingFile.path + suffix)
            if (file.exists() && !file.delete()) importRetryable("message_stage_cleanup_failed")
        }
    }

    private fun sourceAndStageFilesAreDisjoint(): Boolean {
        val suffixes = listOf("", "-wal", "-shm", "-journal")
        val sourceFiles = suffixes.map { suffix -> File(sourceFile.path + suffix).canonicalFile }.toSet()
        val stageFiles = suffixes.map { suffix -> File(stagingFile.path + suffix).canonicalFile }.toSet()
        return sourceFiles.intersect(stageFiles).isEmpty()
    }

    private inline fun <T> validateSourceRow(table: String, ordinal: Long, block: () -> T): T = try {
        block()
    } catch (invalid: IllegalArgumentException) {
        throw LegacyImportException.malformed(
            LegacySourceId.MESSAGE_LEDGER,
            table,
            ordinal,
            "retained row cannot satisfy target invariants",
        )
    }

    companion object {
        internal const val STAGING_FILE_NAME = "notisync-message-ledger-v2-import-stage.db"
        private const val STAGING_SCHEMA_VERSION = 2
        private const val STAGE_META = "stage_meta"
        private const val STAGE_DEDUP = "stage_dedup"
        private const val STAGE_MIRROR_MESSAGE = "stage_mirror_message"
        private const val STAGE_MIRROR_LIFECYCLE = "stage_mirror_lifecycle"
        private const val STAGE_METADATA = "stage_metadata"
        private const val STAGE_COMPLETE = "complete"
        private const val LAST_DEFERRED_AT = "last_deferred_at"
        private const val MIN_STAGING_HEADROOM_BYTES = 16L * 1024 * 1024
    }
}

private data class MessageLedgerStageMetadata(
    val schemaVersion: Int,
    val fingerprint: ByteArray,
    val contentDigest: ByteArray,
    val retainedCommandDigest: ByteArray,
    val retainedRowCount: Long,
    val commandCount: Long,
    val skippedDeprecatedCount: Long,
    val skippedRelayCount: Long,
    val skippedAckCount: Long,
)

private class MessageLedgerStagingSnapshot(
    private val file: File,
    private val evidence: MessageLedgerStageMetadata,
    private val ioDispatcher: CoroutineDispatcher,
) : OperationalImportSnapshot {
    override val source: OperationalImportSource = OperationalImportSources.MESSAGE_LEDGER_V2
    override val sourceFingerprint: ImportDigest = ImportDigest.sha256(evidence.fingerprint)
    override val logicalContentDigest: ImportDigest = ImportDigest.sha256(evidence.contentDigest)
    override val commandCount: Long = evidence.commandCount
    override val skippedRowCount: Long = Math.addExact(
        evidence.skippedDeprecatedCount,
        Math.addExact(evidence.skippedRelayCount, evidence.skippedAckCount),
    )
    override val quarantinedRowCount: Long = 0

    override suspend fun commands(startOrdinal: Long, limit: Int): List<OperationalImportCommand> =
        withContext(ioDispatcher) {
            require(startOrdinal in 0..commandCount && limit > 0) { "invalid message-ledger staging page" }
            if (startOrdinal == commandCount) return@withContext emptyList()
            val database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val rows = database.readCommands(startOrdinal, limit)
                if (rows.isEmpty()) stageRetryable("message_stage_page_missing")
                rows.forEachIndexed { index, row ->
                    if (row.ordinal != Math.addExact(startOrdinal, index.toLong())) {
                        stageRetryable("message_stage_ordinal_mismatch")
                    }
                }
                rows.map(StagedMessageCommand::command)
            } catch (failure: OperationalImportFailure) {
                throw failure
            } catch (failure: RuntimeException) {
                stageRetryable("message_stage_corrupt", failure)
            } finally {
                database.close()
            }
        }

    private fun SQLiteDatabase.readCommands(start: Long, limit: Int): List<StagedMessageCommand> {
        return rawQuery(
            "SELECT command_ordinal, command_kind, field_1, field_2, long_1, long_2, long_3 FROM (" +
                "SELECT command_ordinal, 0 AS command_kind, message_id AS field_1, NULL AS field_2, " +
                "handled_at AS long_1, NULL AS long_2, NULL AS long_3 FROM stage_dedup UNION ALL " +
                "SELECT command_ordinal, 1 AS command_kind, source_client_id AS field_1, source_key AS field_2, " +
                "post_time AS long_1, dismissed_at AS long_2, updated_at AS long_3 " +
                "FROM stage_mirror_lifecycle) WHERE command_ordinal >= ? ORDER BY command_ordinal LIMIT ?",
            arrayOf(start.toString(), limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val command = when (cursor.getInt(1)) {
                        0 -> OperationalImportCommand.HandledMessageIdOnly(
                            cursor.getString(2),
                            cursor.getLong(4),
                        )
                        1 -> OperationalImportCommand.MirrorLifecycle(
                            cursor.getString(2),
                            cursor.getString(3),
                            if (cursor.isNull(4)) null else cursor.getLong(4),
                            if (cursor.isNull(5)) null else cursor.getLong(5),
                            cursor.getLong(6),
                        )
                        else -> stageRetryable("message_stage_command_kind_unknown")
                    }
                    add(
                        StagedMessageCommand(
                            cursor.getLong(0),
                            command,
                        ),
                    )
                }
            }
        }
    }
}

private data class StagedMessageCommand(
    val ordinal: Long,
    val command: OperationalImportCommand,
)

private inline fun validStageCommand(block: () -> OperationalImportCommand): Boolean = try {
    block()
    true
} catch (_: IllegalArgumentException) {
    false
}

private fun checkedMessageAdd(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    messageSourceBlocked("message_stage_size_overflow")
}

private fun checkedMessageMultiply(left: Long, right: Long): Long = try {
    Math.multiplyExact(left, right)
} catch (_: ArithmeticException) {
    messageSourceBlocked("message_stage_size_overflow")
}

private fun importRetryable(code: String, cause: Throwable? = null): Nothing = throw OperationalImportFailure(
    ImportFailureDisposition.RETRYABLE,
    code,
    cause,
)

private fun stageRetryable(code: String, cause: Throwable? = null): Nothing = importRetryable(code, cause)

private fun messageSourceBlocked(code: String, cause: Throwable? = null): Nothing =
    throw OperationalImportFailure(ImportFailureDisposition.BLOCKED, code, cause)
