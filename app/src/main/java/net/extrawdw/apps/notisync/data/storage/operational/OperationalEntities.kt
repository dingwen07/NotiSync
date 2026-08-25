package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(tableName = "mirror_msg", primaryKeys = ["source_client", "source_key"])
internal data class MirrorMessageEntity(
    @ColumnInfo(name = "source_client") val sourceClient: String,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
)

@Entity(tableName = "mirror_lifecycle", primaryKeys = ["source_client", "source_key"])
internal data class MirrorLifecycleEntity(
    @ColumnInfo(name = "source_client") val sourceClient: String,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "post_time") val postTime: Long?,
    @ColumnInfo(name = "dismissed_at") val dismissedAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "runs",
    primaryKeys = ["host_client", "run_id"],
    indices = [
        Index(
            value = ["active", "updated_at"],
            orders = [Index.Order.DESC, Index.Order.DESC],
            name = "runs_order_idx",
        ),
        Index(value = ["active", "received_at"], name = "runs_retention_idx"),
    ],
)
internal data class RunEntity(
    @ColumnInfo(name = "host_client") val hostClient: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "presented_revision") val presentedRevision: Long,
    @ColumnInfo(name = "active") val active: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

@Entity(
    tableName = "controls",
    primaryKeys = ["request_id"],
    indices = [Index(value = ["requested_at", "request_id"], name = "controls_order_idx")],
)
internal data class RunControlEntity(
    @ColumnInfo(name = "request_id") val requestId: String,
    @ColumnInfo(name = "requested_at") val requestedAt: Long,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
)

@Entity(
    tableName = "sign_requests",
    primaryKeys = ["request_id"],
    indices = [
        Index(value = ["state", "updated_at"], name = "sign_requests_state_idx"),
        Index(value = ["sender_client_id", "state"], name = "sign_requests_sender_idx"),
    ],
)
internal data class OpenPgpSignRequestEntity(
    @ColumnInfo(name = "request_id") val requestId: String,
    @ColumnInfo(name = "requester_client_id") val requesterClientId: String,
    @ColumnInfo(name = "sender_client_id") val senderClientId: String,
    @ColumnInfo(name = "primary_key_id") val primaryKeyId: String,
    @ColumnInfo(name = "issued_at") val issuedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "payload_sha256", typeAffinity = ColumnInfo.BLOB) val payloadSha256: ByteArray,
    @ColumnInfo(name = "object_kind") val objectKind: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray?,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "encoded_response", typeAffinity = ColumnInfo.BLOB) val encodedResponse: ByteArray?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "commit_details", typeAffinity = ColumnInfo.BLOB) val commitDetails: ByteArray?,
    @ColumnInfo(name = "result") val result: String?,
    @ColumnInfo(name = "working_directory") val workingDirectory: String?,
)

@Entity(tableName = "provider_state", primaryKeys = ["singleton"])
internal data class SshProviderStateEntity(
    @ColumnInfo(name = "singleton") val singleton: Int,
    @ColumnInfo(name = "inventory_generation") val inventoryGeneration: String,
    @ColumnInfo(name = "revision") val revision: Long,
)

@Entity(
    tableName = "ssh_keys",
    primaryKeys = ["provider_key_id"],
    indices = [Index(value = ["public_hash"], unique = true, name = "ssh_keys_public_hash_unique")],
)
internal data class SshKeyEntity(
    @ColumnInfo(name = "provider_key_id") val providerKeyId: String,
    @ColumnInfo(name = "public_blob", typeAffinity = ColumnInfo.BLOB) val publicBlob: ByteArray,
    @ColumnInfo(name = "public_hash", typeAffinity = ColumnInfo.BLOB) val publicHash: ByteArray,
    @ColumnInfo(name = "algorithm") val algorithm: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "origin") val origin: String,
    @ColumnInfo(name = "approval_policy") val approvalPolicy: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long?,
)

@Entity(
    tableName = "ssh_operational_keys",
    primaryKeys = ["provider_key_id"],
    foreignKeys = [
        ForeignKey(
            entity = SshKeyEntity::class,
            parentColumns = ["provider_key_id"],
            childColumns = ["provider_key_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["key_alias"], unique = true, name = "ssh_operational_keys_alias_unique")],
)
internal data class SshOperationalKeyEntity(
    @ColumnInfo(name = "provider_key_id") val providerKeyId: String,
    @ColumnInfo(name = "provider_kind") val providerKind: String,
    @ColumnInfo(name = "key_alias") val keyAlias: String,
    @ColumnInfo(name = "ciphertext", typeAffinity = ColumnInfo.BLOB) val ciphertext: ByteArray?,
    @ColumnInfo(name = "nonce", typeAffinity = ColumnInfo.BLOB) val nonce: ByteArray?,
    @ColumnInfo(name = "security_level") val securityLevel: String,
    @ColumnInfo(name = "user_verification_policy") val userVerificationPolicy: String,
    @ColumnInfo(name = "strongbox_attempted") val strongBoxAttempted: Int,
    @ColumnInfo(name = "strongbox_fallback") val strongBoxFallback: Int,
)

@Entity(
    tableName = "ssh_webauthn_credentials",
    primaryKeys = ["provider_key_id"],
    foreignKeys = [
        ForeignKey(
            entity = SshKeyEntity::class,
            parentColumns = ["provider_key_id"],
            childColumns = ["provider_key_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["credential_id"], unique = true, name = "ssh_webauthn_credential_id_unique")],
)
internal data class SshWebAuthnCredentialEntity(
    @ColumnInfo(name = "provider_key_id") val providerKeyId: String,
    @ColumnInfo(name = "credential_id", typeAffinity = ColumnInfo.BLOB) val credentialId: ByteArray,
    @ColumnInfo(name = "user_handle", typeAffinity = ColumnInfo.BLOB) val userHandle: ByteArray,
    @ColumnInfo(name = "rp_id") val rpId: String,
    @ColumnInfo(name = "cose_public_key", typeAffinity = ColumnInfo.BLOB) val cosePublicKey: ByteArray,
    @ColumnInfo(name = "backup_eligible") val backupEligible: Int,
    @ColumnInfo(name = "backup_state") val backupState: Int,
)

@Entity(
    tableName = "ssh_export_copies",
    primaryKeys = ["provider_key_id"],
    foreignKeys = [
        ForeignKey(
            entity = SshKeyEntity::class,
            parentColumns = ["provider_key_id"],
            childColumns = ["provider_key_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["key_alias"], unique = true, name = "ssh_export_copies_alias_unique")],
)
internal data class SshExportCopyEntity(
    @ColumnInfo(name = "provider_key_id") val providerKeyId: String,
    @ColumnInfo(name = "key_alias") val keyAlias: String,
    @ColumnInfo(name = "ciphertext", typeAffinity = ColumnInfo.BLOB) val ciphertext: ByteArray,
    @ColumnInfo(name = "nonce", typeAffinity = ColumnInfo.BLOB) val nonce: ByteArray,
    @ColumnInfo(name = "security_level") val securityLevel: String,
    @ColumnInfo(name = "backend_policy") val backendPolicy: String,
    @ColumnInfo(name = "authentication") val authentication: String,
    @ColumnInfo(name = "strongbox_attempted") val strongBoxAttempted: Int,
    @ColumnInfo(name = "strongbox_fallback") val strongBoxFallback: Int,
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: Long,
)

@Entity(
    tableName = "ssh_key_lifecycle",
    primaryKeys = ["provider_key_id"],
    indices = [Index(value = ["operational_alias"], unique = true, name = "ssh_key_lifecycle_alias_unique")],
)
internal data class SshKeyLifecycleEntity(
    @ColumnInfo(name = "provider_key_id") val providerKeyId: String,
    @ColumnInfo(name = "operational_alias") val operationalAlias: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "operational_candidate_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val operationalCandidateCiphertext: ByteArray?,
    @ColumnInfo(name = "operational_candidate_nonce", typeAffinity = ColumnInfo.BLOB)
    val operationalCandidateNonce: ByteArray?,
    @ColumnInfo(name = "operational_candidate_security_level") val operationalCandidateSecurityLevel: String?,
    @ColumnInfo(name = "export_candidate_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val exportCandidateCiphertext: ByteArray?,
    @ColumnInfo(name = "export_candidate_nonce", typeAffinity = ColumnInfo.BLOB)
    val exportCandidateNonce: ByteArray?,
    @ColumnInfo(name = "export_candidate_security_level") val exportCandidateSecurityLevel: String?,
)

@Entity(
    tableName = "authorization_floors",
    primaryKeys = ["requester_client_id", "authorization_generation"],
)
internal data class SshAuthorizationFloorEntity(
    @ColumnInfo(name = "requester_client_id") val requesterClientId: String,
    @ColumnInfo(name = "authorization_generation") val authorizationGeneration: String,
    @ColumnInfo(name = "invalidated_through_epoch") val invalidatedThroughEpoch: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "ssh_remembered_authorizations",
    primaryKeys = ["authorization_id"],
    foreignKeys = [
        ForeignKey(
            entity = SshKeyEntity::class,
            parentColumns = ["provider_key_id"],
            childColumns = ["provider_key_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = [
                "provider_key_id",
                "requester_client_id",
                "authorization_generation",
                "authorization_epoch",
            ],
            name = "ssh_remembered_authorizations_match_idx",
        ),
        Index(
            value = [
                "provider_key_id",
                "requester_client_id",
                "authorization_generation",
                "authorization_epoch",
                "scope",
                "host_key_sha256",
            ],
            unique = true,
            name = "ssh_remembered_authorizations_scope_unique",
        ),
    ],
)
internal data class SshRememberedAuthorizationEntity(
    @ColumnInfo(name = "authorization_id") val authorizationId: String,
    @ColumnInfo(name = "provider_key_id") val providerKeyId: String,
    @ColumnInfo(name = "requester_client_id") val requesterClientId: String,
    @ColumnInfo(name = "authorization_generation") val authorizationGeneration: String,
    @ColumnInfo(name = "authorization_epoch") val authorizationEpoch: Long,
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "host_key_sha256", typeAffinity = ColumnInfo.BLOB) val hostKeySha256: ByteArray?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "ssh_known_hosts", primaryKeys = ["host_key_sha256"])
internal data class SshKnownHostEntity(
    @ColumnInfo(name = "host_key_sha256", typeAffinity = ColumnInfo.BLOB) val hostKeySha256: ByteArray,
    @ColumnInfo(name = "hostname") val hostname: String?,
    @ColumnInfo(name = "first_approved_at") val firstApprovedAt: Long,
    @ColumnInfo(name = "last_approved_at") val lastApprovedAt: Long,
)

@Entity(
    tableName = "provider_requests",
    primaryKeys = ["request_id"],
    indices = [Index(value = ["state", "updated_at"], name = "provider_requests_state_idx")],
)
internal data class SshProviderRequestEntity(
    @ColumnInfo(name = "request_id") val requestId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "requester_client_id") val requesterClientId: String,
    @ColumnInfo(name = "request_fingerprint", typeAffinity = ColumnInfo.BLOB) val requestFingerprint: ByteArray,
    @ColumnInfo(name = "request_cbor", typeAffinity = ColumnInfo.BLOB) val requestCbor: ByteArray?,
    @ColumnInfo(name = "request_nonce", typeAffinity = ColumnInfo.BLOB) val requestNonce: ByteArray?,
    @ColumnInfo(name = "history_cbor", typeAffinity = ColumnInfo.BLOB) val historyCbor: ByteArray,
    @ColumnInfo(name = "history_nonce", typeAffinity = ColumnInfo.BLOB) val historyNonce: ByteArray,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "outcome") val outcome: String?,
    @ColumnInfo(name = "result_at") val resultAt: Long?,
    @ColumnInfo(name = "response_cbor", typeAffinity = ColumnInfo.BLOB) val responseCbor: ByteArray?,
    @ColumnInfo(name = "response_nonce", typeAffinity = ColumnInfo.BLOB) val responseNonce: ByteArray?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
