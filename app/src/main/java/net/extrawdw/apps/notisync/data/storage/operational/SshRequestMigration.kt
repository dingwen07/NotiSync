package net.extrawdw.apps.notisync.data.storage.operational

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.extrawdw.apps.notisync.sshkeyprovider.*
import net.extrawdw.notisync.protocol.*

/**
 * Decrypts legacy rows exactly once. Invalid or unreadable records, including records whose legacy
 * Keystore key is unavailable, are dropped independently under the migration's best-effort policy.
 */
internal fun migrateSshRequests4To5(
    connection: SQLiteConnection,
    decrypt: (String, String, ByteArray, ByteArray) -> ByteArray = ::decryptLegacySshRecord,
) {
    // Old trigger definitions reference removed CBOR columns and terminal payload erasure rules.
    val triggers = connection.prepare(
        "SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name='provider_requests'",
    ).use { statement -> buildList { while (statement.step()) add(statement.getText(0)) } }
    triggers.forEach { connection.execSQL("DROP TRIGGER \"" + it.replace("\"", "\"\"") + "\"") }
    connection.execSQL("DROP INDEX IF EXISTS provider_requests_state_idx")
    connection.execSQL("ALTER TABLE provider_requests RENAME TO provider_requests_legacy")
    connection.execSQL(
        """
        CREATE TABLE ssh_requests (
            `request_id` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `requester_client_id` TEXT NOT NULL,
            `request_fingerprint` BLOB NOT NULL,
            `state` TEXT NOT NULL,
            `outcome` TEXT,
            `result_at` INTEGER,
            `updated_at` INTEGER NOT NULL,
            `request_complete` INTEGER NOT NULL,
            `requested_at` INTEGER NOT NULL,
            `expires_at` INTEGER NOT NULL,
            `public_key_blob` BLOB,
            `key_name` TEXT,
            `suggested_name` TEXT,
            `import_source_type` TEXT,
            `encrypted_import` INTEGER NOT NULL,
            `signature_algorithm` TEXT,
            `destination_username` TEXT,
            `destination_host` TEXT,
            `destination_host_key_fingerprint` TEXT,
            `payload_size` INTEGER NOT NULL,
            `approval_kind` TEXT,
            `remembered_authorization_id` TEXT,
            `remembered_scope` TEXT,
            `sign_data` BLOB,
            `sign_flags` INTEGER,
            `authorization_generation` TEXT,
            `authorization_epoch` INTEGER,
            `process_lineage_json` TEXT NOT NULL,
            `eligible_provider_client_ids_json` TEXT,
            `host_aliases_json` TEXT,
            `binding_chain_json` TEXT,
            `process_source` TEXT,
            `process_boot_id` TEXT,
            `destination_provenance` TEXT,
            `connection_direction` TEXT,
            `destination_service` TEXT,
            `destination_authentication_method` TEXT,
            `session_id_sha256` BLOB,
            `server_host_key_blob` BLOB,
            `server_host_key_blob_sha256` BLOB,
            `connection_id` TEXT,
            `confirmation_required` INTEGER,
            `import_file_bytes` BLOB,
            `import_agent_identity` BLOB,
            `import_lifetime_seconds` INTEGER,
            `import_confirmation_required` INTEGER,
            `response_kind` TEXT,
            `response_provider_client_id` TEXT,
            `response_public_key_sha256` BLOB,
            `response_signature_blob` BLOB,
            `response_remember_disposition` TEXT,
            `response_authorization_generation` TEXT,
            `response_authorization_epoch` INTEGER,
            `response_rejection_reason` TEXT,
            `response_failure_code` TEXT,
            `response_failure_retryable` INTEGER,
            `response_failure_message` TEXT,
            `response_provider_key_id` TEXT,
            `response_public_key_blob` BLOB,
            `response_message` TEXT,
            PRIMARY KEY(request_id)
        )
        """.trimIndent(),
    )
    connection.execSQL("CREATE INDEX ssh_requests_state_idx ON ssh_requests(state, updated_at)")
    connection.execSQL("CREATE INDEX ssh_requests_expiry_idx ON ssh_requests(state, expires_at)")
    connection.execSQL("CREATE INDEX ssh_requests_history_idx ON ssh_requests(updated_at, request_id)")
    var skippedRows = 0
    connection.prepare(
        "SELECT request_id, kind, requester_client_id, request_fingerprint, request_cbor, request_nonce, " +
            "history_cbor, history_nonce, state, outcome, result_at, response_cbor, response_nonce, updated_at " +
            "FROM provider_requests_legacy",
    ).use { source ->
        while (source.step()) {
            val requestId = source.getText(0)
            var request: ByteArray? = null
            var history: ByteArray? = null
            var response: ByteArray? = null
            connection.execSQL("SAVEPOINT ssh_request_row")
            try {
                val kind = SshProviderRequestKind.valueOf(source.getText(1))
                request = if (source.isNull(4)) null else decrypt(requestId, "request", source.getBlob(4), source.getBlob(5))
                history = decrypt(requestId, "history", source.getBlob(6), source.getBlob(7))
                response = if (source.isNull(11)) null else decrypt(requestId, "response", source.getBlob(11), source.getBlob(12))
                val stored = StoredSshProviderRequest(
                    requestId = requestId,
                    kind = kind,
                    requesterClientId = ClientId(source.getText(2)),
                    requestFingerprint = source.getBlob(3),
                    signRequest = if (kind == SshProviderRequestKind.SIGN && request != null)
                        ProtocolCodec.decodeFromCbor<SshSignRequest>(request) else null,
                    importRequest = if (kind == SshProviderRequestKind.IMPORT && request != null)
                        ProtocolCodec.decodeFromCbor<SshImportRequest>(request) else null,
                    history = ProtocolCodec.decodeFromCbor<SshRequestHistorySnapshot>(requireNotNull(history)),
                    state = SshProviderRequestState.valueOf(source.getText(8)),
                    outcome = if (source.isNull(9)) null else SshProviderRequestOutcome.valueOf(source.getText(9)),
                    resultAt = if (source.isNull(10)) null else source.getLong(10),
                    encodedResponse = response,
                    updatedAt = source.getLong(13),
                )
                validateLegacySshRecord(stored)
                connection.insertSshRow("ssh_requests", SshRequestStorage.values(stored))
            } catch (failure: Exception) {
                connection.execSQL("ROLLBACK TO SAVEPOINT ssh_request_row")
                // Legacy decryption is deliberately best-effort, including unavailable Keystore
                // keys/providers. Database I/O/schema failures still abort rather than erase rows
                // merely because their INSERT could not complete.
                if (failure is android.database.sqlite.SQLiteException &&
                    failure !is android.database.sqlite.SQLiteConstraintException
                ) throw failure
                skippedRows++
            } finally {
                request?.fill(0)
                history?.fill(0)
                response?.fill(0)
                connection.execSQL("RELEASE SAVEPOINT ssh_request_row")
            }
        }
    }
    connection.execSQL("DROP TABLE provider_requests_legacy")
    if (skippedRows > 0) Log.w("SshRequestMigration", "Skipped $skippedRows invalid or unreadable legacy SSH records")
}

/** Migration must not silently rewrite the identity or meaning of a legacy request. */
private fun validateLegacySshRecord(stored: StoredSshProviderRequest) {
    fun requireMatch(matches: Boolean) {
        check(matches) { "Inconsistent legacy SSH request" }
    }
    if (stored.state == SshProviderRequestState.PENDING_REVIEW) {
        requireMatch(stored.signRequest != null || stored.importRequest != null)
    }
    if (stored.state == SshProviderRequestState.RESPONSE_PENDING_SEND) {
        requireMatch(stored.encodedResponse != null)
    }
    stored.signRequest?.let { request ->
        requireMatch(request.requestId == stored.requestId && request.requesterClientId == stored.requesterClientId)
        requireMatch(request.requestedAt == stored.history.requestedAt && request.expiresAt == stored.history.expiresAt)
        requireMatch(stored.history.publicKeyBlob?.contentEquals(request.publicKeyBlob) == true)
        requireMatch(stored.history.signatureAlgorithm == request.requestedSignatureAlgorithm)
        requireMatch(stored.history.payloadSize == request.data.size)
        requireMatch(stored.history.processLineage == request.processContext.processLineage)
        requireMatch(stored.history.destinationUsername == request.destinationContext.username)
    }
    stored.importRequest?.let { request ->
        requireMatch(request.requestId == stored.requestId && request.requesterClientId == stored.requesterClientId)
        requireMatch(request.requestedAt == stored.history.requestedAt && request.expiresAt == stored.history.expiresAt)
        requireMatch(request.sourceType == stored.history.importSourceType && request.suggestedName == stored.history.suggestedName)
        requireMatch(stored.history.payloadSize == (request.fileBytes?.size ?: request.agentIdentity?.size ?: 0))
    }
    stored.encodedResponse?.let { bytes ->
        when (stored.kind) {
            SshProviderRequestKind.SIGN -> {
                val response = ProtocolCodec.decodeFromCbor<SshSignResult>(bytes)
                requireMatch(response.requestId == stored.requestId && response.requesterClientId == stored.requesterClientId)
                requireMatch(response.resultAt == stored.resultAt)
                stored.history.publicKeyBlob?.let { publicKey ->
                    requireMatch(response.publicKeyBlobSha256.contentEquals(
                        java.security.MessageDigest.getInstance("SHA-256").digest(publicKey),
                    ))
                }
            }
            SshProviderRequestKind.IMPORT -> {
                val response = ProtocolCodec.decodeFromCbor<SshImportResult>(bytes)
                requireMatch(response.requestId == stored.requestId && response.requesterClientId == stored.requesterClientId)
                requireMatch(response.resultAt == stored.resultAt)
                response.publicKeyBlob?.let { requireMatch(stored.history.publicKeyBlob?.contentEquals(it) == true) }
            }
        }
    }
}

private fun SQLiteConnection.insertSshRow(table: String, values: Map<String, Any?>) {
    prepare("INSERT INTO $table (" + values.keys.joinToString(",") + ") VALUES (" +
        values.keys.joinToString(",") { "?" } + ")").use { statement ->
        values.values.forEachIndexed { index, value ->
            when (value) {
                null -> statement.bindNull(index + 1)
                is String -> statement.bindText(index + 1, value)
                is ByteArray -> statement.bindBlob(index + 1, value)
                is Number -> statement.bindLong(index + 1, value.toLong())
                else -> error("Unsupported SSH migration value")
            }
        }
        statement.step()
    }
}

private fun decryptLegacySshRecord(requestId: String, purpose: String, ciphertext: ByteArray, nonce: ByteArray): ByteArray {
    val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .getKey("notisync_ssh_audit_wrapping_v1", null) as? SecretKey
        ?: error("Legacy SSH review encryption key is unavailable")
    return Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        updateAAD("notisync:ssh-provider-audit:v1:$purpose:$requestId".encodeToByteArray())
        doFinal(ciphertext)
    }
}
