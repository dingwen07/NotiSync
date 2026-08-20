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
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyFailureKind
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyImportException
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyRunRow
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyRunsV2DigestAccumulator
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyRunsStreamEvidence
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyRunsV2Reader
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySourceId
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacySqliteSource
import net.extrawdw.apps.notisync.data.storage.importer.legacy.legacyLogicalFingerprint
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportDigest
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportFailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportRunPhase
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSource
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSources
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalRebuildIdentity
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState

/**
 * Streams Runs v2 into a non-authoritative no-backup SQLite stage, then exposes bounded keyset
 * pages during this rebuild attempt. A BUILDING/corrupt stage is discarded, and every later
 * attempt deletes even a COMPLETE stage before streaming the legacy source again from ordinal zero.
 */
internal class LegacyRunsStagingSourceAdapter(
    private val sourceFile: File,
    noBackupDirectory: File,
    private val reader: LegacyRunsV2Reader = LegacyRunsV2Reader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val usableSpace: (File) -> Long = File::getUsableSpace,
) : OperationalImportSourceAdapter {
    override val source: OperationalImportSource = OperationalImportSources.RUNS_V2
    private val stagingFile = File(noBackupDirectory, STAGING_FILE_NAME)

    init {
        require(noBackupDirectory.isDirectory) { "Runs staging requires an existing no-backup directory" }
        require(stagingFile.parentFile?.canonicalFile == noBackupDirectory.canonicalFile) {
            "Runs staging escaped the no-backup directory"
        }
        require(sourceAndStageFilesAreDisjoint()) {
            "Runs staging files overlap the read-only legacy source"
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
            val evidence = readSourceEvidence(consume = {})
            ImportDigest.sha256(evidence.digests.logicalFingerprint) == snapshot.sourceFingerprint
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
                var ordinal = 0L
                var activePayloadBytes = 0L
                val evidence = readSourceEvidence(
                    checkCancelled = { coroutineContext.ensureActive() },
                ) { row ->
                    coroutineContext.ensureActive()
                    validateTargetShape(row)
                    val payload = row.payload
                    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
                    if (row.active) {
                        activePayloadBytes = checkedAdd(activePayloadBytes, payload.size.toLong())
                        if (activePayloadBytes > MAX_RETAINED_PAYLOAD_BYTES) {
                            sourceBlocked("run_active_capacity_exceeded")
                        }
                    }
                    database.insertOrThrow(
                        STAGE_RUNS,
                        null,
                        ContentValues().apply {
                            put("ordinal", ordinal)
                            put("host_client_id", row.hostClientId)
                            put("run_id", row.runId)
                            put("revision", row.revision)
                            put("phase", row.state.phase.toStageToken())
                            put("presented_revision", row.presentedRevision)
                            put("active", if (row.active) 1 else 0)
                            put("updated_at", row.updatedAt)
                            row.endedAt?.let { put("ended_at", it) } ?: putNull("ended_at")
                            put("received_at", row.receivedAt)
                            put("payload", payload)
                            put("payload_digest", digest)
                        },
                    )
                    ordinal = Math.addExact(ordinal, 1)
                }
                if (evidence.rowCount != ordinal) sourceBlocked("run_stage_count_mismatch")
                if (evidence.totalPayloadBytes > MAX_RETAINED_PAYLOAD_BYTES) {
                    // The shipped store already prunes terminal history to this retained bound. Do
                    // not let target insertion silently choose a different loss boundary.
                    sourceBlocked("run_retained_capacity_exceeded")
                }
                database.insertOrThrow(
                    STAGE_META,
                    null,
                    ContentValues().apply {
                        put("singleton_id", 1)
                        put("state", STAGE_COMPLETE)
                        put("source_schema_version", source.expectedSchemaVersion)
                        put("source_fingerprint", evidence.digests.logicalFingerprint)
                        put("logical_content_digest", evidence.digests.contentDigest)
                        put("row_count", evidence.rowCount)
                        put("total_payload_bytes", evidence.totalPayloadBytes)
                        put("active_payload_bytes", activePayloadBytes)
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
            throw OperationalImportFailure(ImportFailureDisposition.RETRYABLE, "run_stage_storage_full", failure)
        } catch (failure: SQLiteDiskIOException) {
            throw OperationalImportFailure(ImportFailureDisposition.RETRYABLE, "run_stage_io", failure)
        } catch (failure: SQLException) {
            throw OperationalImportFailure(ImportFailureDisposition.BLOCKED, "run_stage_database_failure", failure)
        } finally {
            database.close()
        }
        return readCompleteStageOrNull() ?: sourceBlocked("run_stage_completion_missing")
    }

    private fun readSourceEvidence(
        checkCancelled: () -> Unit = {},
        consume: (LegacyRunRow) -> Unit,
    ): LegacyRunsStreamEvidence = try {
        reader.stream(
            file = sourceFile,
            maxRowPayloadBytes = LegacyRunsV2Reader.MAX_LEGACY_RUN_PAYLOAD_BYTES,
            checkCancelled = checkCancelled,
            consume = consume,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: LegacyImportException) {
        (failure.cause as? CancellationException)?.let { throw it }
        val disposition = if (failure.kind == LegacyFailureKind.SOURCE_IO) {
            ImportFailureDisposition.RETRYABLE
        } else {
            ImportFailureDisposition.BLOCKED
        }
        throw OperationalImportFailure(disposition, failure.kind.toRunsCode(), failure)
    }

    private fun readCompleteStageOrNull(): RunsStagingSnapshot? {
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
            val quickCheck = database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == "ok"
            }
            if (!quickCheck) return null
            val metadata = database.rawQuery(
                "SELECT state, source_schema_version, source_fingerprint, logical_content_digest, " +
                    "row_count, total_payload_bytes, active_payload_bytes FROM $STAGE_META WHERE singleton_id = 1",
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != STAGE_COMPLETE) return null
                RunsStageMetadata(
                    schemaVersion = cursor.getInt(1),
                    fingerprint = cursor.getBlob(2).copyOf(),
                    contentDigest = cursor.getBlob(3).copyOf(),
                    rowCount = cursor.getLong(4),
                    totalPayloadBytes = cursor.getLong(5),
                    activePayloadBytes = cursor.getLong(6),
                )
            }
            if (!metadata.valid()) return null
            val actual = database.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(length(payload)), 0), " +
                    "COALESCE(SUM(CASE WHEN active = 1 THEN length(payload) ELSE 0 END), 0) FROM $STAGE_RUNS",
                null,
            ).use { cursor ->
                check(cursor.moveToFirst())
                Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
            }
            if (actual != Triple(
                    metadata.rowCount,
                    metadata.totalPayloadBytes,
                    metadata.activePayloadBytes,
                )
            ) return null
            if (!database.hasCanonicalStageContent(metadata)) return null
            RunsStagingSnapshot(stagingFile, metadata, ioDispatcher)
        } catch (_: RuntimeException) {
            null
        } finally {
            database.close()
        }.also { snapshot ->
            if (snapshot == null) deleteStageFiles()
        }
    }

    private fun RunsStageMetadata.valid(): Boolean {
        if (
            schemaVersion != source.expectedSchemaVersion ||
            fingerprint.size != ImportDigest.BYTES || contentDigest.size != ImportDigest.BYTES ||
            rowCount < 0 || totalPayloadBytes < 0 || activePayloadBytes < 0 ||
            activePayloadBytes > totalPayloadBytes || totalPayloadBytes > MAX_RETAINED_PAYLOAD_BYTES
        ) return false
        val descriptor = LegacySqliteSource(
            id = LegacySourceId.RUNS,
            fileName = LegacySourceId.RUNS.fileName,
            userVersion = schemaVersion,
        )
        return MessageDigest.isEqual(legacyLogicalFingerprint(descriptor, contentDigest), fingerprint)
    }

    /**
     * `quick_check` proves SQLite structure, not that stage rows are the source rows named by the
     * immutable metadata. Recompute the exact v2 source framing with payload length guards before
     * accepting a COMPLETE stage; otherwise a changed payload plus changed per-row digest could pass.
     */
    private fun SQLiteDatabase.hasCanonicalStageContent(metadata: RunsStageMetadata): Boolean {
        val canonical = LegacyRunsV2DigestAccumulator()
        var expectedOrdinal = 0L
        return rawQuery(
            "SELECT ordinal, host_client_id, run_id, revision, phase, presented_revision, active, " +
                "updated_at, ended_at, received_at, length(payload), payload_digest FROM $STAGE_RUNS " +
                "ORDER BY host_client_id COLLATE BINARY, run_id COLLATE BINARY",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(0) != expectedOrdinal) return@use false
                val host = cursor.getString(1) ?: return@use false
                val run = cursor.getString(2) ?: return@use false
                val revision = cursor.getLong(3)
                val phase = cursor.getString(4) ?: return@use false
                val presentedRevision = cursor.getLong(5)
                val active = cursor.getInt(6)
                val updatedAt = cursor.getLong(7)
                val endedAt = if (cursor.isNull(8)) null else cursor.getLong(8)
                val receivedAt = cursor.getLong(9)
                val payloadLength = cursor.getLong(10)
                val expectedPayloadDigest = cursor.getBlob(11)?.copyOf() ?: return@use false
                if (
                    active !in 0..1 ||
                    payloadLength !in 1..LegacyRunsV2Reader.MAX_LEGACY_RUN_PAYLOAD_BYTES ||
                    expectedPayloadDigest.size != ImportDigest.BYTES
                ) return@use false
                val payload = readStagePayload(host, run, payloadLength) ?: return@use false
                try {
                    if (!MessageDigest.isEqual(
                            MessageDigest.getInstance("SHA-256").digest(payload),
                            expectedPayloadDigest,
                        )
                    ) return@use false
                    val state = try {
                        ProtocolCodec.decodeFromCbor<RunState>(payload)
                    } catch (_: Exception) {
                        return@use false
                    }
                    if (
                        state.hostClientId.value != host || state.runId != run || state.revision != revision ||
                        state.phase.toStageToken() != phase || state.updatedAt != updatedAt ||
                        state.endedAt != endedAt || active == 1 && state.phase !in setOf(
                            RunPhase.RUNNING,
                            RunPhase.BLOCKED,
                        )
                    ) return@use false
                    canonical.append(
                        hostClientId = host,
                        runId = run,
                        revision = revision,
                        presentedRevision = presentedRevision,
                        activeValue = active,
                        updatedAt = updatedAt,
                        endedAt = endedAt,
                        receivedAt = receivedAt,
                        payload = payload,
                    )
                } finally {
                    payload.fill(0)
                    expectedPayloadDigest.fill(0)
                }
                expectedOrdinal = Math.addExact(expectedOrdinal, 1)
            }
            expectedOrdinal == metadata.rowCount && MessageDigest.isEqual(
                canonical.digest(),
                metadata.contentDigest,
            )
        }
    }

    private fun SQLiteDatabase.readStagePayload(
        hostClientId: String,
        runId: String,
        expectedLength: Long,
    ): ByteArray? = rawQuery(
        "SELECT payload FROM $STAGE_RUNS WHERE host_client_id = ? AND run_id = ? LIMIT 1",
        arrayOf(hostClientId, runId),
    ).use { cursor ->
        if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
        cursor.getBlob(0)?.copyOf()?.takeIf { it.size.toLong() == expectedLength }
    }

    private fun validateTargetShape(row: LegacyRunRow) {
        if (
            row.hostClientId.length > MAX_TARGET_ID_CHARS || row.runId.length > MAX_TARGET_ID_CHARS ||
            row.hostClientId.any(Char::isISOControl) || row.runId.any(Char::isISOControl) ||
            row.updatedAt <= 0 || row.receivedAt <= 0 || row.endedAt != null && row.endedAt <= 0
        ) sourceBlocked("source_target_projection_invalid")
    }

    private fun requireStagingSpace() {
        val sourceBytes = listOf(
            sourceFile,
            File(sourceFile.path + "-wal"),
            File(sourceFile.path + "-shm"),
        ).fold(0L) { total, file -> checkedAdd(total, file.length()) }
        val required = checkedAdd(checkedMultiply(sourceBytes, 2), MIN_STAGING_HEADROOM_BYTES)
        if (usableSpace(requireNotNull(stagingFile.parentFile)) < required) {
            throw OperationalImportFailure(ImportFailureDisposition.RETRYABLE, "run_stage_space_insufficient")
        }
    }

    private fun openStageWritable(): SQLiteDatabase = try {
        SQLiteDatabase.openOrCreateDatabase(stagingFile, null).also { database ->
            database.enableWriteAheadLogging()
        }
    } catch (failure: SQLiteFullException) {
        throw OperationalImportFailure(ImportFailureDisposition.RETRYABLE, "run_stage_storage_full", failure)
    } catch (failure: SQLException) {
        throw OperationalImportFailure(ImportFailureDisposition.RETRYABLE, "run_stage_io", failure)
    }

    private fun createStageSchema(database: SQLiteDatabase) {
        database.execSQL("PRAGMA user_version = $STAGING_SCHEMA_VERSION")
        database.execSQL(
            "CREATE TABLE $STAGE_META (singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1), " +
                "state TEXT NOT NULL, source_schema_version INTEGER NOT NULL, source_fingerprint BLOB NOT NULL, " +
                "logical_content_digest BLOB NOT NULL, row_count INTEGER NOT NULL, " +
                "total_payload_bytes INTEGER NOT NULL, active_payload_bytes INTEGER NOT NULL)",
        )
        database.execSQL(
            "CREATE TABLE $STAGE_RUNS (ordinal INTEGER NOT NULL UNIQUE, host_client_id TEXT NOT NULL, " +
                "run_id TEXT NOT NULL, revision INTEGER NOT NULL, phase TEXT NOT NULL, " +
                "presented_revision INTEGER NOT NULL, active INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
                "ended_at INTEGER, received_at INTEGER NOT NULL, payload BLOB NOT NULL, " +
                "payload_digest BLOB NOT NULL, PRIMARY KEY(host_client_id, run_id))",
        )
    }

    private fun deleteStageFiles() {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val file = File(stagingFile.path + suffix)
            if (file.exists() && !file.delete()) {
                throw OperationalImportFailure(
                    ImportFailureDisposition.RETRYABLE,
                    "run_stage_cleanup_failed",
                )
            }
        }
    }

    private fun sourceAndStageFilesAreDisjoint(): Boolean {
        val suffixes = listOf("", "-wal", "-shm", "-journal")
        val sourceFiles = suffixes.map { suffix -> File(sourceFile.path + suffix).canonicalFile }.toSet()
        val stageFiles = suffixes.map { suffix -> File(stagingFile.path + suffix).canonicalFile }.toSet()
        return sourceFiles.intersect(stageFiles).isEmpty()
    }

    companion object {
        internal const val STAGING_FILE_NAME = "notisync-runs-v2-import-stage.db"
        internal const val MAX_PAGE_PAYLOAD_BYTES = 2L * 1024 * 1024
        private const val STAGING_SCHEMA_VERSION = 1
        private const val STAGE_META = "stage_meta"
        private const val STAGE_RUNS = "stage_runs"
        private const val STAGE_COMPLETE = "complete"
        private const val MAX_TARGET_ID_CHARS = 256
        private const val MAX_RETAINED_PAYLOAD_BYTES = 100L * 1024 * 1024
        private const val MIN_STAGING_HEADROOM_BYTES = 16L * 1024 * 1024
    }
}

private data class RunsStageMetadata(
    val schemaVersion: Int,
    val fingerprint: ByteArray,
    val contentDigest: ByteArray,
    val rowCount: Long,
    val totalPayloadBytes: Long,
    val activePayloadBytes: Long,
)

private class RunsStagingSnapshot(
    private val file: File,
    private val evidence: RunsStageMetadata,
    private val ioDispatcher: CoroutineDispatcher,
) : OperationalImportSnapshot {
    override val source: OperationalImportSource = OperationalImportSources.RUNS_V2
    override val sourceFingerprint: ImportDigest = ImportDigest.sha256(evidence.fingerprint)
    override val logicalContentDigest: ImportDigest = ImportDigest.sha256(evidence.contentDigest)
    override val commandCount: Long = evidence.rowCount
    override val skippedRowCount: Long = 0
    override val quarantinedRowCount: Long = 0

    override suspend fun commands(startOrdinal: Long, limit: Int): List<OperationalImportCommand> =
        withContext(ioDispatcher) {
            require(startOrdinal in 0..commandCount && limit > 0) { "invalid Runs staging page" }
            if (startOrdinal == commandCount) return@withContext emptyList()
            val database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val previousKey = if (startOrdinal == 0L) null else database.rawQuery(
                    "SELECT host_client_id, run_id FROM stage_runs WHERE ordinal = ?",
                    arrayOf((startOrdinal - 1).toString()),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) stageBlocked("run_stage_page_missing")
                    cursor.getString(0) to cursor.getString(1)
                }
                val rows = if (previousKey == null) {
                    database.readPage(null, null, limit)
                } else {
                    database.readPage(previousKey.first, previousKey.second, limit)
                }
                var pageBytes = 0L
                buildList {
                    for (row in rows) {
                        currentCoroutineContext().ensureActive()
                        val nextBytes = checkedAdd(pageBytes, row.payloadLength)
                        if (isNotEmpty() && nextBytes > LegacyRunsStagingSourceAdapter.MAX_PAGE_PAYLOAD_BYTES) break
                        if (nextBytes > LegacyRunsStagingSourceAdapter.MAX_PAGE_PAYLOAD_BYTES) {
                            stageBlocked("run_stage_row_bound_exceeded")
                        }
                        add(database.readCommand(row))
                        pageBytes = nextBytes
                    }
                }
            } finally {
                database.close()
            }
        }

    private fun SQLiteDatabase.readPage(
        previousHost: String?,
        previousRun: String?,
        limit: Int,
    ): List<StagedRunMetadata> {
        val (sql, args) = if (previousHost == null) {
            "SELECT ordinal, host_client_id, run_id, revision, phase, presented_revision, active, " +
                "updated_at, ended_at, received_at, length(payload), payload_digest FROM stage_runs " +
                "ORDER BY host_client_id COLLATE BINARY, run_id COLLATE BINARY LIMIT ?" to
                arrayOf(limit.toString())
        } else {
            "SELECT ordinal, host_client_id, run_id, revision, phase, presented_revision, active, " +
                "updated_at, ended_at, received_at, length(payload), payload_digest FROM stage_runs " +
                "WHERE host_client_id > ? COLLATE BINARY OR " +
                "(host_client_id = ? AND run_id > ? COLLATE BINARY) " +
                "ORDER BY host_client_id COLLATE BINARY, run_id COLLATE BINARY LIMIT ?" to
                arrayOf(previousHost, previousHost, requireNotNull(previousRun), limit.toString())
        }
        return rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val active = cursor.getInt(6)
                    if (active !in 0..1) stageBlocked("run_stage_corrupt")
                    val payloadLength = cursor.getLong(10)
                    if (payloadLength !in 1..LegacyRunsV2Reader.MAX_LEGACY_RUN_PAYLOAD_BYTES) {
                        stageBlocked("run_stage_corrupt")
                    }
                    add(
                        StagedRunMetadata(
                            ordinal = cursor.getLong(0),
                            hostClientId = cursor.getString(1),
                            runId = cursor.getString(2),
                            revision = cursor.getLong(3),
                            phase = cursor.getString(4).toImportPhase(),
                            presentedRevision = cursor.getLong(5),
                            active = active != 0,
                            updatedAt = cursor.getLong(7),
                            endedAt = if (cursor.isNull(8)) null else cursor.getLong(8),
                            receivedAt = cursor.getLong(9),
                            payloadLength = payloadLength,
                            payloadDigest = cursor.getBlob(11).copyOf(),
                        ),
                    )
                }
            }
        }
    }

    private fun SQLiteDatabase.readCommand(row: StagedRunMetadata): OperationalImportCommand.RunState = rawQuery(
        "SELECT payload FROM stage_runs WHERE host_client_id = ? AND run_id = ? LIMIT 1",
        arrayOf(row.hostClientId, row.runId),
    ).use { cursor ->
        if (!cursor.moveToFirst() || cursor.isNull(0)) stageBlocked("run_stage_corrupt")
        val payload = cursor.getBlob(0).copyOf()
        if (payload.size.toLong() != row.payloadLength ||
            !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), row.payloadDigest)
        ) stageBlocked("run_stage_corrupt")
        val state = try {
            ProtocolCodec.decodeFromCbor<RunState>(payload)
        } catch (_: Exception) {
            stageBlocked("run_stage_corrupt")
        }
        if (
            state.hostClientId.value != row.hostClientId || state.runId != row.runId ||
            state.revision != row.revision || state.phase.toImportPhase() != row.phase ||
            state.updatedAt != row.updatedAt || state.endedAt != row.endedAt
        ) stageBlocked("run_stage_projection_mismatch")
        OperationalImportCommand.RunState(
            hostClientId = row.hostClientId,
            runId = row.runId,
            revision = row.revision,
            phase = row.phase,
            presentedRevision = row.presentedRevision,
            active = row.active,
            updatedAt = row.updatedAt,
            endedAt = row.endedAt,
            receivedAt = row.receivedAt,
            payload = payload,
            payloadDigest = ImportDigest.sha256(row.payloadDigest),
        )
    }

    private data class StagedRunMetadata(
        val ordinal: Long,
        val hostClientId: String,
        val runId: String,
        val revision: Long,
        val phase: ImportRunPhase,
        val presentedRevision: Long,
        val active: Boolean,
        val updatedAt: Long,
        val endedAt: Long?,
        val receivedAt: Long,
        val payloadLength: Long,
        val payloadDigest: ByteArray,
    )

}

private fun RunPhase.toStageToken(): String = when (this) {
    RunPhase.RUNNING -> "running"
    RunPhase.BLOCKED -> "blocked"
    RunPhase.COMPLETED -> "completed"
    RunPhase.FAILED_TO_START -> "failed_to_start"
}

private fun RunPhase.toImportPhase(): ImportRunPhase = when (this) {
    RunPhase.RUNNING -> ImportRunPhase.RUNNING
    RunPhase.BLOCKED -> ImportRunPhase.BLOCKED
    RunPhase.COMPLETED -> ImportRunPhase.COMPLETED
    RunPhase.FAILED_TO_START -> ImportRunPhase.FAILED_TO_START
}

private fun String.toImportPhase(): ImportRunPhase = when (this) {
    "running" -> ImportRunPhase.RUNNING
    "blocked" -> ImportRunPhase.BLOCKED
    "completed" -> ImportRunPhase.COMPLETED
    "failed_to_start" -> ImportRunPhase.FAILED_TO_START
    else -> stageBlocked("run_stage_phase_unknown")
}

private fun LegacyFailureKind.toRunsCode(): String = when (this) {
    LegacyFailureKind.SOURCE_MISSING -> "source_missing"
    LegacyFailureKind.FILENAME_MISMATCH -> "source_filename_mismatch"
    LegacyFailureKind.UNSUPPORTED_VERSION -> "source_version_unsupported"
    LegacyFailureKind.SCHEMA_MISMATCH -> "source_schema_mismatch"
    LegacyFailureKind.QUICK_CHECK_FAILED -> "source_integrity_failed"
    LegacyFailureKind.MALFORMED_ROW -> "source_row_malformed"
    LegacyFailureKind.SOURCE_IO -> "source_io"
}

private fun checkedAdd(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    sourceBlocked("run_stage_size_overflow")
}

private fun checkedMultiply(left: Long, right: Long): Long = try {
    Math.multiplyExact(left, right)
} catch (_: ArithmeticException) {
    sourceBlocked("run_stage_size_overflow")
}

private fun sourceBlocked(code: String): Nothing = throw OperationalImportFailure(
    ImportFailureDisposition.BLOCKED,
    code,
)

private fun stageBlocked(code: String): Nothing = throw OperationalImportFailure(
    ImportFailureDisposition.RETRYABLE,
    code,
)
