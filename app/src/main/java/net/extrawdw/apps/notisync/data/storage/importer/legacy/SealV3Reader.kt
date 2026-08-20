package net.extrawdw.apps.notisync.data.storage.importer.legacy

import android.database.sqlite.SQLiteDatabase
import java.io.File
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpSignLimits
import net.extrawdw.notisync.protocol.ProtocolCodec

/** Read-only importer contract for the shipped v51 openpgp_signing.db v3 source. */
internal class LegacySealV3Reader {
    fun read(file: File): LegacySealSnapshot = readLegacySqliteSnapshot(
        file = file,
        source = LegacySourceId.OPENPGP_SIGNING,
        expectedTables = SEAL_TABLES,
    ) { source, database ->
        val digest = LegacyDigestAccumulator().apply {
            text("NotiSync/openpgp_signing/v3")
        }
        val stateCounts = database.readStateCounts(digest)
        stateCounts.requireWithinShippedBounds(source)
        val terminal = database.readTerminalRows(digest)
        if (terminal.rows.size.toLong() != stateCounts.terminalCount) {
            throw LegacyImportException.schema(source.id, "terminal row count changed inside the read snapshot")
        }

        val contentDigest = digest.digest()
        LegacySealSnapshot(
            source = source,
            terminalRows = terminal.rows,
            skippedActivePendingCount = stateCounts.activePendingCount,
            skippedResponsePendingCount = stateCounts.responsePendingCount,
            malformedDisplayCount = terminal.malformedDisplayCount,
            digests = LegacySourceDigests(
                contentDigest = contentDigest,
                logicalFingerprint = legacyLogicalFingerprint(source, contentDigest),
            ),
        )
    }

    private fun SQLiteDatabase.readStateCounts(digest: LegacyDigestAccumulator): StateCounts = rawQuery(
        "SELECT state, COUNT(*) FROM sign_requests GROUP BY state ORDER BY state COLLATE BINARY",
        emptyArray(),
    ).use { cursor ->
        var activePending = 0L
        var responsePending = 0L
        var terminal = 0L
        var ordinal = 0L
        digest.text("state_counts")
        while (cursor.moveToNext()) {
            if (cursor.isNull(0)) {
                throw LegacyImportException.malformed(
                    LegacySourceId.OPENPGP_SIGNING,
                    "sign_requests",
                    ordinal,
                    "state must not be NULL",
                )
            }
            val state = cursor.getString(0)
            val count = cursor.getLong(1)
            if (count < 0) {
                throw LegacyImportException.malformed(
                    LegacySourceId.OPENPGP_SIGNING,
                    "sign_requests",
                    ordinal,
                    "state count must be non-negative",
                )
            }
            when {
                state in ACTIVE_PENDING_STATES -> activePending += count
                state in RESPONSE_PENDING_STATES -> responsePending += count
                state in TERMINAL_STATES -> terminal += count
                else -> throw LegacyImportException.malformed(
                    LegacySourceId.OPENPGP_SIGNING,
                    "sign_requests",
                    ordinal,
                    "unknown state token",
                )
            }
            digest.text(state)
            digest.long(count)
            ordinal++
        }
        StateCounts(activePending, responsePending, terminal)
    }

    private fun StateCounts.requireWithinShippedBounds(source: LegacySqliteSource) {
        val pending = try {
            Math.addExact(activePendingCount, responsePendingCount)
        } catch (_: ArithmeticException) {
            throw LegacyImportException.schema(source.id, "Seal state counts overflowed")
        }
        if (pending > MAX_PENDING_GLOBAL) {
            throw LegacyImportException.schema(source.id, "pending Seal rows exceed the shipped bound")
        }
        if (terminalCount > MAX_TERMINAL_HISTORY_ROWS) {
            // Enforce the v51 retention shape before selecting or decoding any terminal display BLOB.
            throw LegacyImportException.schema(source.id, "terminal Seal history exceeds the shipped bound")
        }
    }

    /**
     * The query intentionally omits `payload` and `encoded_response`.  Pending rows are not in this
     * query at all, and terminal rows retain only the v51 display snapshot, never a commit/signature
     * payload or provider response.
     */
    private fun SQLiteDatabase.readTerminalRows(digest: LegacyDigestAccumulator): TerminalReadResult = rawQuery(
        "SELECT request_id, requester_client_id, sender_client_id, primary_key_id, issued_at, expires_at, " +
            "payload_sha256, object_kind, state, updated_at, " +
            "CASE WHEN commit_details IS NULL OR length(commit_details) > $MAX_DISPLAY_BLOB_BYTES " +
            "THEN NULL ELSE commit_details END, " +
            "CASE WHEN commit_details IS NULL THEN -1 ELSE length(commit_details) END, " +
            "result, working_directory FROM sign_requests " +
            "WHERE state IN ('SENT','CANCELLED','EXPIRED','FAILED') " +
            "ORDER BY request_id COLLATE BINARY",
        emptyArray(),
    ).use { cursor ->
        val rows = mutableListOf<LegacySealHistoryRow>()
        var malformedDisplayCount = 0L
        var ordinal = 0L
        digest.text("terminal_rows")
        while (cursor.moveToNext()) {
            val requestId = cursor.requireText(ordinal, 0)
            val requester = cursor.requireText(ordinal, 1)
            val sender = cursor.requireText(ordinal, 2)
            val primaryKeyId = cursor.requireText(ordinal, 3)
            val issuedAt = cursor.getLong(4)
            val expiresAt = cursor.getLong(5)
            val payloadSha256 = if (cursor.isNull(6)) {
                throw malformed(ordinal, "payload digest must not be NULL")
            } else {
                cursor.copyBlob(6)
            }
            val objectKind = cursor.requireText(ordinal, 7)
            val stateToken = cursor.requireText(ordinal, 8)
            val updatedAt = cursor.getLong(9)
            val displayPayload = if (cursor.isNull(10)) null else cursor.copyBlob(10)
            val displayLength = cursor.getLong(11)
            val resultToken = if (cursor.isNull(12)) null else cursor.getString(12)
            val workingDirectory = if (cursor.isNull(13)) null else cursor.getString(13)

            val state = parseTerminalState(stateToken, ordinal)
            validateRequestIdentity(
                ordinal = ordinal,
                requestId = requestId,
                requester = requester,
                sender = sender,
                primaryKeyId = primaryKeyId,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                payloadSha256 = payloadSha256,
                objectKind = objectKind,
                updatedAt = updatedAt,
                workingDirectory = workingDirectory,
            )
            val outcome = parseOutcome(resultToken, state, ordinal)
            // A shipped v51 store can retain CANCELED after the row reached SENT. The result is
            // the user decision, so normalize that observed terminal pair to the canonical state.
            val canonicalState = if (
                state == LegacySealRequestState.SENT &&
                outcome == LegacySealHistoryOutcome.CANCELED
            ) {
                LegacySealRequestState.CANCELLED
            } else {
                state
            }

            if (displayLength < -1) throw malformed(ordinal, "display length is invalid")
            val displayResult = if (displayPayload == null) {
                DisplayDecodeResult(
                    display = null,
                    malformed = displayLength > MAX_DISPLAY_BLOB_BYTES,
                )
            } else {
                decodeAndBoundDisplay(displayPayload)
            }
            if (displayResult.malformed) malformedDisplayCount++

            digest.text(requestId)
            digest.text(requester)
            digest.text(sender)
            digest.text(primaryKeyId)
            digest.long(issuedAt)
            digest.long(expiresAt)
            digest.bytes(payloadSha256)
            digest.text(objectKind)
            digest.text(stateToken)
            digest.long(updatedAt)
            digest.long(displayLength)
            digest.bytes(displayPayload)
            digest.text(resultToken)
            digest.text(workingDirectory)

            rows += LegacySealHistoryRow(
                requestId = requestId,
                requesterClientId = requester,
                senderClientId = sender,
                primaryKeyId = primaryKeyId,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                payloadSha256 = payloadSha256,
                objectKind = objectKind,
                state = canonicalState,
                updatedAt = updatedAt,
                commit = displayResult.display,
                outcome = outcome,
                workingDirectory = workingDirectory,
            )
            ordinal++
        }
        TerminalReadResult(rows, malformedDisplayCount)
    }

    private fun validateRequestIdentity(
        ordinal: Long,
        requestId: String,
        requester: String,
        sender: String,
        primaryKeyId: String,
        issuedAt: Long,
        expiresAt: Long,
        payloadSha256: ByteArray,
        objectKind: String,
        updatedAt: Long,
        workingDirectory: String?,
    ) {
        if (!REQUEST_ID_PATTERN.matches(requestId)) throw malformed(ordinal, "request id is not lowercase 128-bit hex")
        if (requester != sender) throw malformed(ordinal, "requester and sender ids do not match")
        if (payloadSha256.size != OpenPgpSignLimits.PAYLOAD_SHA256_BYTES) {
            throw malformed(ordinal, "payload digest is not SHA-256")
        }
        if (!PRIMARY_KEY_PATTERN.matches(primaryKeyId)) {
            throw malformed(ordinal, "primary key id is not uppercase 64-bit hex")
        }
        if (issuedAt <= 0 || expiresAt <= issuedAt ||
            expiresAt - issuedAt > OpenPgpSignLimits.MAX_REQUEST_LIFETIME_MILLIS
        ) {
            throw malformed(ordinal, "request lifetime is outside protocol bounds")
        }
        if (objectKind != OpenPgpObjectKind.GIT_COMMIT.name) {
            throw malformed(ordinal, "object kind is unsupported")
        }
        if (updatedAt <= 0 || updatedAt < issuedAt) {
            throw malformed(ordinal, "updated timestamp is outside source bounds")
        }
        if (workingDirectory != null && (
                workingDirectory.isBlank() ||
                    workingDirectory.encodeToByteArray().size > OpenPgpSignLimits.MAX_WORKING_DIRECTORY_UTF8_BYTES ||
                    workingDirectory.any(Char::isISOControl)
            )
        ) {
            throw malformed(ordinal, "working directory is outside protocol bounds")
        }
    }

    private fun parseTerminalState(value: String, ordinal: Long): LegacySealRequestState = when (value) {
        "SENT" -> LegacySealRequestState.SENT
        "CANCELLED" -> LegacySealRequestState.CANCELLED
        "EXPIRED" -> LegacySealRequestState.EXPIRED
        "FAILED" -> LegacySealRequestState.FAILED
        else -> throw malformed(ordinal, "terminal state token is unsupported")
    }

    private fun parseOutcome(
        value: String?,
        state: LegacySealRequestState,
        ordinal: Long,
    ): LegacySealHistoryOutcome {
        val outcome = when (value) {
            "APPROVED" -> LegacySealHistoryOutcome.APPROVED
            "REJECTED" -> LegacySealHistoryOutcome.REJECTED
            "CANCELED" -> LegacySealHistoryOutcome.CANCELED
            "EXPIRED" -> LegacySealHistoryOutcome.EXPIRED
            "FAILED" -> LegacySealHistoryOutcome.FAILED
            else -> throw malformed(ordinal, "terminal result token is missing or unsupported")
        }
        val valid = when (state) {
            LegacySealRequestState.SENT -> outcome == LegacySealHistoryOutcome.APPROVED ||
                outcome == LegacySealHistoryOutcome.REJECTED ||
                outcome == LegacySealHistoryOutcome.CANCELED
            LegacySealRequestState.CANCELLED -> outcome == LegacySealHistoryOutcome.CANCELED
            LegacySealRequestState.EXPIRED -> outcome == LegacySealHistoryOutcome.EXPIRED
            LegacySealRequestState.FAILED -> outcome == LegacySealHistoryOutcome.FAILED
        }
        if (!valid) throw malformed(ordinal, "terminal result does not match state")
        return outcome
    }

    private fun decodeAndBoundDisplay(encoded: ByteArray): DisplayDecodeResult {
        val decoded = runCatching {
            ProtocolCodec.decodeFromCbor<LegacyGitCommitDisplaySnapshotV51>(encoded)
        }.getOrNull() ?: return DisplayDecodeResult(display = null, malformed = true)

        if (!decoded.isStructurallyValid()) {
            return DisplayDecodeResult(display = null, malformed = true)
        }
        val parents = decoded.parentIds.take(MAX_HISTORY_PARENTS)
        val author = decoded.author.take(MAX_HISTORY_IDENTITY_CHARS)
        val committer = decoded.committer.take(MAX_HISTORY_IDENTITY_CHARS)
        val message = decoded.message.take(MAX_HISTORY_MESSAGE_CHARS)
        val headers = decoded.extraHeaders.take(MAX_HISTORY_HEADERS).map {
            LegacySealCommitDisplayHeader(
                name = it.name.take(MAX_HISTORY_HEADER_NAME_CHARS),
                value = it.value.take(MAX_HISTORY_HEADER_VALUE_CHARS),
            )
        }
        val truncated = parents != decoded.parentIds || author != decoded.author ||
            committer != decoded.committer || message != decoded.message ||
            headers != decoded.extraHeaders.map { LegacySealCommitDisplayHeader(it.name, it.value) }
        return DisplayDecodeResult(
            display = LegacySealCommitDisplaySnapshot(
                treeId = decoded.treeId,
                parentIds = parents,
                author = author,
                committer = committer,
                message = message,
                extraHeaders = headers,
                payloadBytes = decoded.payloadBytes,
                truncated = truncated,
            ),
            malformed = false,
        )
    }

    private fun LegacyGitCommitDisplaySnapshotV51.isStructurallyValid(): Boolean {
        if (!OBJECT_ID_PATTERN.matches(treeId)) return false
        if (parentIds.size > MAX_DECODED_PARENTS || parentIds.any { !OBJECT_ID_PATTERN.matches(it) }) return false
        if (!safeDecodedText(author, MAX_DECODED_TEXT_CHARS) ||
            !safeDecodedText(committer, MAX_DECODED_TEXT_CHARS) ||
            !safeDecodedText(message, MAX_DECODED_TEXT_CHARS, allowEmpty = true, allowLineBreaks = true)
        ) return false
        if (payloadBytes !in 0..OpenPgpSignLimits.MAX_PAYLOAD_BYTES) return false
        if (extraHeaders.size > MAX_DECODED_HEADERS) return false
        return extraHeaders.all {
            safeDecodedHeader(it.name) &&
                safeDecodedText(it.value, MAX_DECODED_TEXT_CHARS, allowLineBreaks = true)
        }
    }

    private fun safeDecodedText(
        value: String,
        maxChars: Int,
        allowEmpty: Boolean = false,
        allowLineBreaks: Boolean = false,
    ): Boolean = (allowEmpty || value.isNotEmpty()) && value.length <= maxChars && value.none { char ->
        char == '\u0000' || (char.isISOControl() &&
            (!allowLineBreaks || char !in setOf('\n', '\r', '\t')))
    }

    private fun safeDecodedHeader(name: String): Boolean =
        name.isNotBlank() && name.length <= MAX_DECODED_TEXT_CHARS &&
            name.none(Char::isISOControl) && '\u0000' !in name

    private fun android.database.Cursor.requireText(ordinal: Long, index: Int): String {
        if (isNull(index)) throw malformed(ordinal, "required text is NULL")
        val value = getString(index)
        if (value.isBlank() || value.length > MAX_SOURCE_TEXT_CHARS || value.any(Char::isISOControl)) {
            throw malformed(ordinal, "required text is outside source bounds")
        }
        return value
    }

    private fun malformed(ordinal: Long, reason: String): LegacyImportException =
        LegacyImportException.malformed(LegacySourceId.OPENPGP_SIGNING, "sign_requests", ordinal, reason)

    private data class StateCounts(
        val activePendingCount: Long,
        val responsePendingCount: Long,
        val terminalCount: Long,
    )

    private class TerminalReadResult(
        val rows: List<LegacySealHistoryRow>,
        val malformedDisplayCount: Long,
    )

    private data class DisplayDecodeResult(
        val display: LegacySealCommitDisplaySnapshot?,
        val malformed: Boolean,
    )

    companion object {
        private const val MAX_TERMINAL_HISTORY_ROWS = 500L
        private const val MAX_PENDING_GLOBAL = 10L
        private const val MAX_DISPLAY_BLOB_BYTES = 2 * 1024 * 1024
        private const val MAX_SOURCE_TEXT_CHARS = 4_096
        // These are decoder guards, not target storage limits.  Valid old displays are clipped to
        // the current history limits below after decoding; the guards only cap hostile/corrupt
        // structures before they can create an unexpectedly large in-memory object graph.
        private const val MAX_DECODED_PARENTS = 4_096
        private const val MAX_DECODED_HEADERS = 4_096
        private const val MAX_DECODED_TEXT_CHARS = 128 * 1_024
        private const val MAX_HISTORY_PARENTS = 64
        private const val MAX_HISTORY_IDENTITY_CHARS = 1_024
        private const val MAX_HISTORY_MESSAGE_CHARS = 16 * 1_024
        private const val MAX_HISTORY_HEADERS = 64
        private const val MAX_HISTORY_HEADER_NAME_CHARS = 128
        private const val MAX_HISTORY_HEADER_VALUE_CHARS = 2 * 1_024
        private val REQUEST_ID_PATTERN = Regex("[0-9a-f]{32}")
        private val PRIMARY_KEY_PATTERN = Regex("[0-9A-F]{16}")
        private val OBJECT_ID_PATTERN = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
        private val ACTIVE_PENDING_STATES = setOf(
            "PENDING_REVIEW",
            "USER_APPROVED",
            "PROVIDER_INTERACTION",
        )
        private val RESPONSE_PENDING_STATES = setOf(
            "SIGNED_PENDING_SEND",
            "REJECTED_PENDING_SEND",
        )
        private val TERMINAL_STATES = setOf("SENT", "CANCELLED", "EXPIRED", "FAILED")
        private val SEAL_TABLES = listOf(
            LegacyTableContract(
                name = "sign_requests",
                columns = listOf(
                    LegacyColumnContract("request_id", "TEXT", notNull = false, primaryKeyOrdinal = 1),
                    LegacyColumnContract("requester_client_id", "TEXT", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("sender_client_id", "TEXT", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("primary_key_id", "TEXT", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("issued_at", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("expires_at", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("payload_sha256", "BLOB", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("object_kind", "TEXT", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("payload", "BLOB", notNull = false, primaryKeyOrdinal = 0),
                    LegacyColumnContract("state", "TEXT", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("encoded_response", "BLOB", notNull = false, primaryKeyOrdinal = 0),
                    LegacyColumnContract("updated_at", "INTEGER", notNull = true, primaryKeyOrdinal = 0),
                    LegacyColumnContract("commit_details", "BLOB", notNull = false, primaryKeyOrdinal = 0),
                    LegacyColumnContract("result", "TEXT", notNull = false, primaryKeyOrdinal = 0),
                    LegacyColumnContract("working_directory", "TEXT", notNull = false, primaryKeyOrdinal = 0),
                ),
                indexes = listOf(
                    LegacyIndexContract(
                        name = "sign_requests_state_idx",
                        unique = false,
                        columns = listOf("state", "updated_at"),
                    ),
                    LegacyIndexContract(
                        name = "sign_requests_sender_idx",
                        unique = false,
                        columns = listOf("sender_client_id", "state"),
                    ),
                ),
            ),
        )
    }
}
