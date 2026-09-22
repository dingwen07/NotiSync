package net.extrawdw.apps.notisync.data.storage.operational

import android.content.ContentValues
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.extrawdw.apps.notisync.sshkeyprovider.*
import net.extrawdw.notisync.protocol.*

/** Scalar request data plus ordered JSON context, shared by live storage and legacy migration. */
internal object SshRequestStorage {
    // List views read no BLOBs; public keys and signing/import/result data load only when opening a record.
    val summaryColumns = listOf(
        "request_id", "kind", "requester_client_id", "state", "outcome", "result_at",
        "updated_at", "requested_at", "expires_at", "key_name", "suggested_name",
        "import_source_type", "encrypted_import", "signature_algorithm", "destination_username",
        "destination_host", "destination_host_key_fingerprint", "payload_size", "approval_kind",
        "remembered_authorization_id", "remembered_scope", "process_lineage_json",
    ).joinToString(", ")

    fun values(stored: StoredSshProviderRequest): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
        "request_id" to stored.requestId,
        "kind" to stored.kind.name,
        "requester_client_id" to stored.requesterClientId.value,
        "request_fingerprint" to stored.requestFingerprint,
        "state" to stored.state.name,
        "outcome" to stored.outcome?.name,
        "result_at" to stored.resultAt,
        "updated_at" to stored.updatedAt,
        "request_complete" to if (stored.signRequest != null ||
            stored.importRequest != null && stored.state == SshProviderRequestState.PENDING_REVIEW) 1 else 0,
    ).apply {
        putAll(historyValues(stored.history))
        stored.signRequest?.let { request ->
            put("sign_data", request.data)
            put("sign_flags", request.flags)
            put("authorization_generation", request.authorizationGeneration)
            put("authorization_epoch", request.authorizationEpoch)
            put("process_source", request.processContext.source.name)
            put("process_boot_id", request.processContext.bootId)
            put("eligible_provider_client_ids_json", ProtocolCodec.encodeToJson(request.eligibleProviderClientIds))
            put("host_aliases_json", ProtocolCodec.encodeToJson(request.destinationContext.hostAliases))
            put("binding_chain_json", ProtocolCodec.encodeToJson(request.destinationContext.bindingChain))
            put("destination_provenance", request.destinationContext.provenance.name)
            put("connection_direction", request.destinationContext.connectionDirection.name)
            put("destination_service", request.destinationContext.service)
            put("destination_authentication_method", request.destinationContext.authenticationMethod)
            put("session_id_sha256", request.destinationContext.sessionIdSha256)
            put("server_host_key_blob", request.destinationContext.serverHostKeyBlob)
            put("server_host_key_blob_sha256", request.destinationContext.serverHostKeyBlobSha256)
            put("connection_id", request.connectionId)
            put("confirmation_required", if (request.confirmationRequired) 1 else 0)
        }
        stored.importRequest?.let { request ->
            // Terminal imports keep review metadata but never a usable private-key source copy.
            if (stored.state == SshProviderRequestState.PENDING_REVIEW) {
                put("import_file_bytes", request.fileBytes)
                put("import_agent_identity", request.agentIdentity)
            }
            put("import_lifetime_seconds", request.constraints?.lifetimeSeconds)
            put("import_confirmation_required", request.constraints?.let { if (it.confirmationRequired) 1 else 0 })
        }
        stored.encodedResponse?.let { bytes ->
            putAll(responseValues(when (stored.kind) {
                SshProviderRequestKind.SIGN -> ProtocolCodec.decodeFromCbor<SshSignResult>(bytes)
                SshProviderRequestKind.IMPORT -> ProtocolCodec.decodeFromCbor<SshImportResult>(bytes)
            }))
        }
    }

    fun historyValues(history: SshRequestHistorySnapshot): Map<String, Any?> = linkedMapOf(
        "requested_at" to history.requestedAt,
        "expires_at" to history.expiresAt,
        "public_key_blob" to history.publicKeyBlob,
        "key_name" to history.keyName,
        "suggested_name" to history.suggestedName,
        "import_source_type" to history.importSourceType?.name,
        "encrypted_import" to if (history.encryptedImport) 1 else 0,
        "signature_algorithm" to history.signatureAlgorithm?.name,
        "destination_username" to history.destinationUsername,
        "destination_host" to history.destinationHost,
        "destination_host_key_fingerprint" to history.destinationHostKeyFingerprint,
        "payload_size" to history.payloadSize,
        "approval_kind" to history.approvalKind?.name,
        "remembered_authorization_id" to history.rememberedAuthorizationId,
        "remembered_scope" to history.rememberedScope?.name,
        "process_lineage_json" to ProtocolCodec.encodeToJson(history.processLineage),
    )

    fun responseValues(response: Any): Map<String, Any?> = when (response) {
        is SshSignResult -> linkedMapOf(
            "response_kind" to response.kind.name,
            "response_provider_client_id" to response.providerClientId.value,
            "response_public_key_sha256" to response.publicKeyBlobSha256,
            "response_signature_blob" to response.signature?.signatureBlob,
            "response_remember_disposition" to response.signature?.rememberDisposition?.name,
            "response_authorization_generation" to response.signature?.authorizationGeneration,
            "response_authorization_epoch" to response.signature?.authorizationEpoch,
            "response_rejection_reason" to response.rejection?.reason?.name,
            "response_failure_code" to response.failure?.code?.name,
            "response_failure_retryable" to response.failure?.let { if (it.retryable) 1 else 0 },
            "response_failure_message" to response.failure?.message,
        )
        is SshImportResult -> linkedMapOf(
            "response_kind" to response.kind.name,
            "response_provider_client_id" to response.providerClientId.value,
            "response_provider_key_id" to response.providerKeyId,
            "response_public_key_blob" to response.publicKeyBlob,
            "response_message" to response.message,
        )
        else -> error("Unsupported SSH response")
    }

    fun insert(database: SQLiteDatabase, stored: StoredSshProviderRequest) {
        database.insertOrThrow("ssh_requests", null, contentValues(values(stored)))
    }

    fun contentValues(values: Map<String, Any?>) = ContentValues().apply {
        values.forEach { (name, value) ->
            when (value) {
                null -> putNull(name)
                is String -> put(name, value)
                is ByteArray -> put(name, value)
                is Long -> put(name, value)
                is Int -> put(name, value)
                else -> error("Unsupported SQLite value for $name")
            }
        }
    }

    private fun Cursor.values(): Map<String, Any?> = columnNames.mapIndexed { index, name ->
        name to when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            Cursor.FIELD_TYPE_BLOB -> getBlob(index)
            Cursor.FIELD_TYPE_STRING -> getString(index)
            else -> error("Unexpected SSH SQLite type for $name")
        }
    }.toMap()

    fun read(cursor: Cursor, includePayload: Boolean = true): StoredSshProviderRequest =
        reconstruct(cursor.values(), includePayload)

    fun readAll(cursor: Cursor, includePayload: Boolean): List<StoredSshProviderRequest> =
        buildList { while (cursor.moveToNext()) add(read(cursor, includePayload)) }

    internal fun reconstruct(
        row: Map<String, Any?>,
        includePayload: Boolean,
    ): StoredSshProviderRequest {
        fun text(column: String) = row[column] as String?
        fun bytes(column: String) = row[column] as ByteArray?
        fun number(column: String) = (row[column] as Number?)?.toLong()
        val requestId = requireNotNull(text("request_id"))
        val requester = ClientId(requireNotNull(text("requester_client_id")))
        val requestedAt = requireNotNull(number("requested_at"))
        val expiresAt = requireNotNull(number("expires_at"))
        val kind = SshProviderRequestKind.valueOf(requireNotNull(text("kind")))
        val lineage = ProtocolCodec.decodeFromJson<List<DesktopProcessIdentity>>(requireNotNull(text("process_lineage_json")))
        val history = SshRequestHistorySnapshot(
            requestedAt = requestedAt, expiresAt = expiresAt,
            publicKeyBlob = bytes("public_key_blob"), keyName = text("key_name"),
            suggestedName = text("suggested_name"), importSourceType = text("import_source_type")?.let(SshImportSourceType::valueOf),
            encryptedImport = number("encrypted_import") == 1L,
            signatureAlgorithm = text("signature_algorithm")?.let(SshSignatureAlgorithm::valueOf),
            processLineage = lineage, destinationUsername = text("destination_username"),
            destinationHost = text("destination_host"), destinationHostKeyFingerprint = text("destination_host_key_fingerprint"),
            payloadSize = requireNotNull(number("payload_size")).toInt(),
            approvalKind = text("approval_kind")?.let(SshRequestApprovalKind::valueOf),
            rememberedAuthorizationId = text("remembered_authorization_id"),
            rememberedScope = text("remembered_scope")?.let(SshRememberScope::valueOf),
        )
        val complete = includePayload && number("request_complete") == 1L
        val sign = if (complete && kind == SshProviderRequestKind.SIGN) SshSignRequest(
            requestId, requester, requestedAt, expiresAt,
            requireNotNull(bytes("public_key_blob")), requireNotNull(bytes("sign_data")),
            requireNotNull(number("sign_flags")), requireNotNull(history.signatureAlgorithm),
            ProtocolCodec.decodeFromJson<List<ClientId>>(requireNotNull(text("eligible_provider_client_ids_json"))),
            requireNotNull(text("authorization_generation")), requireNotNull(number("authorization_epoch")),
            DesktopProcessContext(
                DesktopProcessContextSource.valueOf(requireNotNull(text("process_source"))), lineage, text("process_boot_id"),
            ),
            SshDestinationContext(
                provenance = SshDestinationProvenance.valueOf(requireNotNull(text("destination_provenance"))),
                connectionDirection = SshConnectionDirection.valueOf(requireNotNull(text("connection_direction"))),
                username = text("destination_username"), service = text("destination_service"),
                authenticationMethod = text("destination_authentication_method"), sessionIdSha256 = bytes("session_id_sha256"),
                serverHostKeyBlob = bytes("server_host_key_blob"), serverHostKeyBlobSha256 = bytes("server_host_key_blob_sha256"),
                hostAliases = ProtocolCodec.decodeFromJson<List<SshHostAlias>>(requireNotNull(text("host_aliases_json"))),
                bindingChain = ProtocolCodec.decodeFromJson<List<SshVerifiedBinding>>(requireNotNull(text("binding_chain_json"))),
            ),
            requireNotNull(text("connection_id")), number("confirmation_required") == 1L,
        ) else null
        val import = if (complete && kind == SshProviderRequestKind.IMPORT) SshImportRequest(
            requestId, requester, requestedAt, expiresAt, requireNotNull(history.importSourceType),
            bytes("import_file_bytes"), bytes("import_agent_identity"),
            number("import_confirmation_required")?.let {
                SshImportConstraints(number("import_lifetime_seconds"), it == 1L)
            },
            text("suggested_name"),
        ) else null
        val responseKind = text("response_kind")
        val response = if (!includePayload || responseKind == null) null else when (kind) {
            SshProviderRequestKind.SIGN -> ProtocolCodec.encodeToCbor(SshSignResult(
                requestId, requester, requireNotNull(bytes("response_public_key_sha256")),
                SshSignResultKind.valueOf(responseKind), requireNotNull(number("result_at")),
                ClientId(requireNotNull(text("response_provider_client_id"))),
                signature = bytes("response_signature_blob")?.let {
                    SshSignatureResult(it,
                        SshRememberDisposition.valueOf(requireNotNull(text("response_remember_disposition"))),
                        requireNotNull(text("response_authorization_generation")), requireNotNull(number("response_authorization_epoch")))
                },
                rejection = text("response_rejection_reason")?.let { SshUserRejection(SshUserRejectionReason.valueOf(it)) },
                failure = text("response_failure_code")?.let {
                    SshProviderFailure(SshProviderFailureCode.valueOf(it), number("response_failure_retryable") == 1L,
                        text("response_failure_message"))
                },
            ))
            SshProviderRequestKind.IMPORT -> ProtocolCodec.encodeToCbor(SshImportResult(
                requestId, requester, ClientId(requireNotNull(text("response_provider_client_id"))),
                requireNotNull(number("result_at")), SshImportResultKind.valueOf(responseKind),
                text("response_provider_key_id"), bytes("response_public_key_blob"), text("response_message"),
            ))
        }
        return StoredSshProviderRequest(
            requestId, kind, requester,
            if (includePayload) requireNotNull(bytes("request_fingerprint")) else byteArrayOf(),
            sign, import, history,
            SshProviderRequestState.valueOf(requireNotNull(text("state"))),
            text("outcome")?.let(SshProviderRequestOutcome::valueOf), number("result_at"), response,
            requireNotNull(number("updated_at")),
        )
    }
}
