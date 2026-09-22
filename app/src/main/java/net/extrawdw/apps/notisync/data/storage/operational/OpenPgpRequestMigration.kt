package net.extrawdw.apps.notisync.data.storage.operational

import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import kotlinx.serialization.Serializable
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import net.extrawdw.apps.notisync.seal.GitCommitDetails
import net.extrawdw.apps.notisync.seal.GitCommitHeader
import net.extrawdw.apps.notisync.seal.GitTagDetails
import net.extrawdw.apps.notisync.seal.OpenPgpRequestState
import net.extrawdw.apps.notisync.seal.OpenPgpRequestResult
import net.extrawdw.apps.notisync.seal.toCommitDetails
import net.extrawdw.apps.notisync.seal.toTagDetails
import net.extrawdw.apps.notisync.seal.toSummary
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignLimits
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import net.extrawdw.notisync.protocol.ProtocolCodec

/** Migrates healthy records; invalid legacy rows are dropped without blocking application startup. */
internal fun migrateOpenPgpRequests4To5(connection: SQLiteConnection) {
    connection.execSQL("ALTER TABLE sign_requests RENAME TO sign_requests_v4")
    connection.execSQL("DROP INDEX sign_requests_state_idx")
    connection.execSQL("DROP INDEX sign_requests_sender_idx")
    connection.execSQL(
        """
        CREATE TABLE seal_requests (
            request_id TEXT NOT NULL PRIMARY KEY,
            protocol_version INTEGER NOT NULL,
            requester_client_id TEXT NOT NULL,
            sender_client_id TEXT NOT NULL,
            primary_key_id TEXT NOT NULL,
            issued_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL,
            payload_sha256 BLOB NOT NULL,
            object_kind TEXT NOT NULL,
            payload BLOB,
            state TEXT NOT NULL,
            response_action TEXT,
            response_signature_armor TEXT,
            response_reject_reason TEXT,
            response_action_at INTEGER,
            updated_at INTEGER NOT NULL,
            result TEXT,
            working_directory TEXT,
            summary_title TEXT,
            summary_reference TEXT,
            summary_identity TEXT,
            legacy_details_json TEXT
        )
        """.trimIndent(),
    )
    connection.execSQL("CREATE INDEX seal_requests_state_idx ON seal_requests(state, updated_at)")
    connection.execSQL("CREATE INDEX seal_requests_expiry_idx ON seal_requests(state, expires_at)")
    connection.execSQL("CREATE INDEX seal_requests_sender_idx ON seal_requests(sender_client_id, state)")
    connection.execSQL("CREATE INDEX seal_requests_history_idx ON seal_requests(updated_at, request_id)")
    var droppedRows = 0
    connection.prepare(
        "SELECT request_id,requester_client_id,sender_client_id,primary_key_id,issued_at,expires_at," +
            "payload_sha256,object_kind,payload,state,encoded_response,updated_at,commit_details,result," +
            "working_directory FROM sign_requests_v4",
    ).use { row ->
        while (row.step()) {
            val requestId = row.getText(0)
            connection.execSQL("SAVEPOINT openpgp_row")
            try {
                migrateOpenPgpRow(connection, row, requestId)
                connection.execSQL("RELEASE SAVEPOINT openpgp_row")
            } catch (failure: Exception) {
                connection.execSQL("ROLLBACK TO SAVEPOINT openpgp_row")
                connection.execSQL("RELEASE SAVEPOINT openpgp_row")
                // Parsing and consistency checks indicate a bad record. Database I/O, full-disk,
                // or schema failures must still propagate rather than being mistaken for bad data.
                if (failure !is IllegalArgumentException && failure !is IllegalStateException &&
                    failure !is CharacterCodingException
                ) throw failure
                droppedRows++
            }
        }
    }
    connection.execSQL("DROP TABLE sign_requests_v4")
    if (droppedRows > 0) {
        Log.w("OpenPgpRequestMigration", "Dropped $droppedRows invalid legacy OpenPGP signing records")
    }
}

private fun migrateOpenPgpRow(connection: SQLiteConnection, row: SQLiteStatement, requestId: String) {
    val payload = row.blobOrNull(8)
    val details = row.blobOrNull(12)
    val response = row.blobOrNull(10)?.let {
        ProtocolCodec.decodeFromCbor<OpenPgpSignSync>(it)
    }
    val state = row.getText(9)
    OpenPgpRequestState.valueOf(state)
    row.textOrNull(13)?.let(OpenPgpRequestResult::valueOf)
    check(payload != null || state !in setOf("PENDING_REVIEW", "USER_APPROVED", "PROVIDER_INTERACTION")) {
        "OpenPGP active request is missing its signing payload"
    }
    if (payload != null) {
        check(MessageDigest.isEqual(sha256(payload), row.getBlob(6))) {
            "OpenPGP legacy payload digest mismatch"
        }
    }
    check(state != "SIGNED_PENDING_SEND" || response?.action == OpenPgpSignAction.RESULT) {
        "OpenPGP pending result is missing its response"
    }
    check(state != "REJECTED_PENDING_SEND" || response?.action == OpenPgpSignAction.REJECT) {
        "OpenPGP pending rejection is missing its response"
    }
    if (response != null) {
        check(response.action in setOf(OpenPgpSignAction.RESULT, OpenPgpSignAction.REJECT) &&
            response.validationError(::sha256) == null
        ) { "OpenPGP legacy response is invalid" }
        check(response.requestId == requestId && response.requesterClientId.value == row.getText(1) &&
            response.primaryKeyId == row.getText(3) && response.issuedAt == row.getLong(4) &&
            response.expiresAt == row.getLong(5) && response.payloadSha256.contentEquals(row.getBlob(6)) &&
            response.objectKind.name == row.getText(7)
        ) { "OpenPGP legacy response does not match its request" }
    }
    val objectKind = OpenPgpObjectKind.valueOf(row.getText(7))
    // The exact payload is the sole source of full details for current records. Only old records
    // whose bytes were erased need a readable JSON fallback preserving the already-retained facts.
    val commit = if (objectKind == OpenPgpObjectKind.GIT_COMMIT) {
        payload?.toCommitDetails()
            ?: details?.let { ProtocolCodec.decodeFromCbor<LegacyCommitSnapshot>(it).details() }
    } else null
    val tag = if (objectKind == OpenPgpObjectKind.GIT_TAG) {
        payload?.toTagDetails()
            ?: details?.let { ProtocolCodec.decodeFromCbor<LegacyTagSnapshot>(it).details() }
    } else null
    val summary = commit?.toSummary() ?: tag?.toSummary()
    val legacyDetailsJson = if (payload != null) null else when {
        commit != null -> ProtocolCodec.encodeToJson(commit)
        tag != null -> ProtocolCodec.encodeToJson(tag)
        else -> null
    }
    connection.insertValues(
        "seal_requests",
        listOf(
            requestId, response?.protocolVersion ?: OpenPgpSignLimits.PROTOCOL_VERSION,
            row.getText(1), row.getText(2), row.getText(3), row.getLong(4), row.getLong(5),
            row.getBlob(6), row.getText(7), payload, row.getText(9), response?.action?.name,
            response?.signatureArmor, response?.rejectReason?.name, response?.actionAt,
            row.getLong(11), row.textOrNull(13), row.textOrNull(14),
            summary?.title, summary?.reference, summary?.identity, legacyDetailsJson,
        ),
    )
}

private fun SQLiteConnection.insertValues(table: String, values: List<Any?>) {
    prepare("INSERT INTO $table VALUES (${values.joinToString(",") { "?" }})").use { insert ->
        values.forEachIndexed { index, value ->
            when (value) {
                null -> insert.bindNull(index + 1)
                is String -> insert.bindText(index + 1, value)
                is ByteArray -> insert.bindBlob(index + 1, value)
                is Int -> insert.bindLong(index + 1, value.toLong())
                is Long -> insert.bindLong(index + 1, value)
                is Boolean -> insert.bindLong(index + 1, if (value) 1 else 0)
                else -> error("Unsupported migration binding")
            }
        }
        insert.step()
    }
}

private fun SQLiteStatement.blobOrNull(index: Int): ByteArray? = if (isNull(index)) null else getBlob(index)
private fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)
private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

// Frozen v4 encodings are read only during migration. Runtime storage does not serialize these models.
@Serializable
private data class LegacyCommitSnapshot(
    val treeId: String,
    val parentIds: List<String>,
    val author: String,
    val committer: String,
    val message: String,
    val extraHeaders: List<LegacyCommitHeader>,
    val payloadBytes: Int,
    val truncated: Boolean = false,
) {
    fun details() = GitCommitDetails(
        treeId, parentIds, author, committer, message,
        extraHeaders.map { GitCommitHeader(it.name, it.value) }, payloadBytes, truncated,
    )
}

@Serializable
private data class LegacyCommitHeader(val name: String, val value: String)

@Serializable
private data class LegacyTagSnapshot(
    val objectId: String,
    val objectType: String,
    val tagName: String,
    val tagger: String,
    val message: String,
    val payloadBytes: Int,
    val truncated: Boolean = false,
) {
    fun details() = GitTagDetails(
        objectId, objectType, tagName, tagger, message, payloadBytes, truncated,
    )
}
