package net.extrawdw.apps.notisync.data.storage.importer.legacy

import android.database.sqlite.SQLiteDatabase
import java.io.File
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState

/** Read-only contract for the shipped runs.db v2 schema. */
internal class LegacyRunsV2Reader {
    /**
     * Streams one WAL-consistent read transaction. Payload length is checked in a metadata cursor
     * before a separate point query can materialize that BLOB into the process heap.
     */
    fun stream(
        file: File,
        maxRowPayloadBytes: Long,
        checkCancelled: () -> Unit = {},
        consume: (LegacyRunRow) -> Unit,
    ): LegacyRunsStreamEvidence = readLegacySqliteSnapshot(
        file = file,
        source = LegacySourceId.RUNS,
        expectedTables = RUNS_TABLES,
    ) { source, database ->
        require(maxRowPayloadBytes in 1..Int.MAX_VALUE.toLong()) { "Run row bound is invalid" }
        val digest = LegacyRunsV2DigestAccumulator()
        val counts = database.streamRuns(digest, maxRowPayloadBytes, checkCancelled, consume)
        val contentDigest = digest.digest()
        LegacyRunsStreamEvidence(
            source = source,
            rowCount = counts.first,
            totalPayloadBytes = counts.second,
            digests = LegacySourceDigests(
                contentDigest = contentDigest,
                logicalFingerprint = legacyLogicalFingerprint(source, contentDigest),
            ),
        )
    }

    private fun SQLiteDatabase.streamRuns(
        digest: LegacyRunsV2DigestAccumulator,
        maxRowPayloadBytes: Long,
        checkCancelled: () -> Unit,
        consume: (LegacyRunRow) -> Unit,
    ): Pair<Long, Long> = rawQuery(
        "SELECT host_client, run_id, revision, presented_revision, active, updated_at, ended_at, " +
            "received_at, length(payload) FROM runs " +
            "ORDER BY host_client COLLATE BINARY, run_id COLLATE BINARY",
        emptyArray(),
    ).use { cursor ->
        var ordinal = 0L
        var totalPayloadBytes = 0L
        while (cursor.moveToNext()) {
            checkCancelled()
            val hostClientId = cursor.requireText(LegacySourceId.RUNS, "runs", ordinal, 0)
            val runId = cursor.requireText(LegacySourceId.RUNS, "runs", ordinal, 1)
            val revision = cursor.getLong(2)
            val presentedRevision = cursor.getLong(3)
            val activeValue = cursor.getInt(4)
            val updatedAt = cursor.getLong(5)
            val endedAt = cursor.optionalLong(6)
            val receivedAt = cursor.getLong(7)
            if (activeValue !in 0..1) {
                throw LegacyImportException.malformed(
                    LegacySourceId.RUNS,
                    "runs",
                    ordinal,
                    "active must be 0 or 1",
                )
            }
            if (revision <= 0 || presentedRevision != -1L && presentedRevision !in 0..revision) {
                throw LegacyImportException.malformed(
                    LegacySourceId.RUNS,
                    "runs",
                    ordinal,
                    "revision projection is outside its source bounds",
                )
            }
            if (updatedAt < 0 || receivedAt < 0) {
                throw LegacyImportException.malformed(
                    LegacySourceId.RUNS,
                    "runs",
                    ordinal,
                    "Run timestamps must be non-negative",
                )
            }
            val payloadLength = if (cursor.isNull(8)) {
                throw LegacyImportException.malformed(
                    LegacySourceId.RUNS,
                    "runs",
                    ordinal,
                    "payload must not be NULL",
                )
            } else {
                cursor.getLong(8)
            }
            if (payloadLength !in 1..maxRowPayloadBytes) {
                throw LegacyImportException.malformed(
                    LegacySourceId.RUNS,
                    "runs",
                    ordinal,
                    "payload length is outside the import bound",
                )
            }
            totalPayloadBytes = try {
                Math.addExact(totalPayloadBytes, payloadLength)
            } catch (_: ArithmeticException) {
                throw LegacyImportException.malformed(
                    LegacySourceId.RUNS,
                    "runs",
                    ordinal,
                    "total payload length overflowed",
                )
            }
            checkCancelled()
            val payload = readPayload(hostClientId, runId, payloadLength, ordinal)

            val state = try {
                ProtocolCodec.decodeFromCbor<RunState>(payload)
            } catch (_: Exception) {
                throw LegacyImportException.malformed(
                    LegacySourceId.RUNS,
                    "runs",
                    ordinal,
                    "payload is not a valid RunState",
                )
            }
            validateProjection(
                ordinal = ordinal,
                hostClientId = hostClientId,
                runId = runId,
                revision = revision,
                active = activeValue != 0,
                updatedAt = updatedAt,
                endedAt = endedAt,
                state = state,
            )

            digest.append(
                hostClientId = hostClientId,
                runId = runId,
                revision = revision,
                presentedRevision = presentedRevision,
                activeValue = activeValue,
                updatedAt = updatedAt,
                endedAt = endedAt,
                receivedAt = receivedAt,
                payload = payload,
            )
            consume(
                LegacyRunRow(
                    hostClientId = hostClientId,
                    runId = runId,
                    revision = revision,
                    presentedRevision = presentedRevision,
                    active = activeValue != 0,
                    updatedAt = updatedAt,
                    endedAt = endedAt,
                    receivedAt = receivedAt,
                    payload = payload,
                    state = state,
                ),
            )
            ordinal++
        }
        ordinal to totalPayloadBytes
    }

    private fun SQLiteDatabase.readPayload(
        hostClientId: String,
        runId: String,
        expectedLength: Long,
        ordinal: Long,
    ): ByteArray = rawQuery(
        "SELECT payload FROM runs WHERE host_client = ? AND run_id = ? LIMIT 1",
        arrayOf(hostClientId, runId),
    ).use { payloadCursor ->
        if (!payloadCursor.moveToFirst() || payloadCursor.isNull(0)) {
            throw LegacyImportException.malformed(
                LegacySourceId.RUNS,
                "runs",
                ordinal,
                "payload disappeared inside the pinned snapshot",
            )
        }
        payloadCursor.copyBlob(0).also { payload ->
            if (payload.size.toLong() != expectedLength) {
                throw LegacyImportException.malformed(
                    LegacySourceId.RUNS,
                    "runs",
                    ordinal,
                    "payload length changed inside the pinned snapshot",
                )
            }
        }
    }

    private fun validateProjection(
        ordinal: Long,
        hostClientId: String,
        runId: String,
        revision: Long,
        active: Boolean,
        updatedAt: Long,
        endedAt: Long?,
        state: RunState,
    ) {
        fun mismatch(reason: String): Nothing = throw LegacyImportException.malformed(
            LegacySourceId.RUNS,
            "runs",
            ordinal,
            reason,
        )

        if (state.hostClientId.value != hostClientId) mismatch("host_client does not match RunState")
        if (state.runId != runId) mismatch("run_id does not match RunState")
        if (state.revision != revision) mismatch("revision does not match RunState")
        if (state.updatedAt != updatedAt) mismatch("updated_at does not match RunState")
        if (state.endedAt != endedAt) mismatch("ended_at does not match RunState")

        // The legacy `active` column is a local presentation projection: markInactive may clear it while
        // retaining an active remote snapshot.  The safe one-way invariant is that a row marked
        // active must contain an active RunState; false may represent either active history or a
        // terminal state.
        if (active && state.phase !in ACTIVE_PHASES) mismatch("active projection contradicts RunState phase")
    }

    private fun android.database.Cursor.requireText(
        source: LegacySourceId,
        table: String,
        rowNumber: Long,
        index: Int,
    ): String {
        if (isNull(index)) throw LegacyImportException.malformed(source, table, rowNumber, "text column is NULL")
        return getString(index).takeIf { it.isNotBlank() }
            ?: throw LegacyImportException.malformed(source, table, rowNumber, "text column is blank")
    }

    companion object {
        /**
         * Protocol v1 max-shape CBOR is below 160 KiB (64 KiB terminal + 64 KiB argv +
         * 16 KiB cwd and bounded projections). 256 KiB leaves framing headroom without turning the
         * shipped store's 100 MiB aggregate retention budget into a per-row heap allowance.
         */
        const val MAX_LEGACY_RUN_PAYLOAD_BYTES: Long = 256L * 1024
        private val ACTIVE_PHASES = setOf(RunPhase.RUNNING, RunPhase.BLOCKED)
        private val RUNS_TABLES = listOf(
            LegacyTableContract(
                name = "runs",
                columns = listOf(
                    LegacyColumnContract("host_client", "TEXT", notNull = true, primaryKeyOrdinal = 1),
                    LegacyColumnContract("run_id", "TEXT", notNull = true, primaryKeyOrdinal = 2),
                    LegacyColumnContract("revision", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("presented_revision", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("active", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("updated_at", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("ended_at", "INTEGER", notNull = false, primaryKeyOrdinal = 0),
                    LegacyColumnContract("received_at", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("payload", "BLOB", notNull = true, primaryKeyOrdinal = 0),
                ),
            ),
        )
    }
}

/** Canonical v2 logical row framing reused to authenticate one disposable Runs staging copy. */
internal class LegacyRunsV2DigestAccumulator {
    private val digest = LegacyDigestAccumulator().apply {
        text("NotiSync/runs/v2")
        text("runs")
    }

    fun append(
        hostClientId: String,
        runId: String,
        revision: Long,
        presentedRevision: Long,
        activeValue: Int,
        updatedAt: Long,
        endedAt: Long?,
        receivedAt: Long,
        payload: ByteArray,
    ) {
        require(activeValue in 0..1) { "Runs active projection must be 0 or 1" }
        digest.text(hostClientId)
        digest.text(runId)
        digest.long(revision)
        digest.long(presentedRevision)
        digest.int(activeValue)
        digest.long(updatedAt)
        digest.nullableLong(endedAt)
        digest.long(receivedAt)
        digest.bytes(payload)
    }

    fun digest(): ByteArray = digest.digest()
}
