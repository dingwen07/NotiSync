package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "ssh_provider_state")
internal data class SshProviderStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = OperationalSingletons.ID,
    @ColumnInfo(name = "inventory_generation")
    val inventoryGeneration: String,
    @ColumnInfo(name = "revision")
    val revision: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "ssh_reset_journal",
    indices = [
        Index(value = ["reset_id"], unique = true, name = "index_ssh_reset_journal_reset_id"),
    ],
)
internal data class SshResetJournalEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = OperationalSingletons.ID,
    @ColumnInfo(name = "reset_id")
    val resetId: String,
    @ColumnInfo(name = "state")
    val state: SshResetState,
    @ColumnInfo(name = "old_inventory_generation")
    val oldInventoryGeneration: String?,
    @ColumnInfo(name = "new_inventory_generation")
    val newInventoryGeneration: String,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
)

@Entity(
    tableName = "ssh_reset_alias",
    foreignKeys = [
        ForeignKey(
            entity = SshResetJournalEntity::class,
            parentColumns = ["singleton_id"],
            childColumns = ["reset_singleton_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["reset_singleton_id"], name = "index_ssh_reset_alias_reset_singleton_id"),
        Index(value = ["state", "updated_at"], name = "index_ssh_reset_alias_state_updated_at"),
    ],
)
internal data class SshResetAliasEntity(
    @PrimaryKey
    @ColumnInfo(name = "key_alias")
    val keyAlias: String,
    @ColumnInfo(name = "reset_singleton_id")
    val resetSingletonId: Int = OperationalSingletons.ID,
    @ColumnInfo(name = "alias_kind")
    val aliasKind: SshResetAliasKind,
    @ColumnInfo(name = "state")
    val state: SshResetAliasState,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
)

@Entity(
    tableName = "ssh_key",
    indices = [
        Index(value = ["public_hash"], unique = true, name = "index_ssh_key_public_hash"),
        Index(value = ["created_at"], name = "index_ssh_key_created_at"),
        Index(value = ["expires_at"], name = "index_ssh_key_expires_at"),
    ],
)
internal data class SshKeyEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_key_id")
    val providerKeyId: String,
    @ColumnInfo(name = "public_blob", typeAffinity = ColumnInfo.BLOB)
    val publicBlob: ByteArray,
    @ColumnInfo(name = "public_hash", typeAffinity = ColumnInfo.BLOB)
    val publicHash: ByteArray,
    @ColumnInfo(name = "algorithm")
    val algorithm: SshKeyAlgorithmToken,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "origin")
    val origin: SshKeyOriginToken,
    @ColumnInfo(name = "approval_policy")
    val approvalPolicy: SshApprovalPolicyToken,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "ssh_operational_key",
    foreignKeys = [
        ForeignKey(
            entity = SshKeyEntity::class,
            parentColumns = ["provider_key_id"],
            childColumns = ["provider_key_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["key_alias"], unique = true, name = "index_ssh_operational_key_alias"),
    ],
)
internal data class SshOperationalKeyEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_key_id")
    val providerKeyId: String,
    @ColumnInfo(name = "key_alias")
    val keyAlias: String,
    @ColumnInfo(name = "security_level")
    val securityLevel: SshSecurityLevelToken,
    @ColumnInfo(name = "user_verification_policy")
    val userVerificationPolicy: SshUserVerificationToken,
    @ColumnInfo(name = "strongbox_attempted")
    val strongBoxAttempted: Boolean,
    @ColumnInfo(name = "strongbox_fallback")
    val strongBoxFallback: Boolean,
    @ColumnInfo(name = "last_verified_at")
    val lastVerifiedAt: Long,
)

@Entity(
    tableName = "ssh_wrapped_operational_material",
    foreignKeys = [
        ForeignKey(
            entity = SshOperationalKeyEntity::class,
            parentColumns = ["provider_key_id"],
            childColumns = ["provider_key_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SshWrappedOperationalMaterialEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_key_id")
    val providerKeyId: String,
    @ColumnInfo(name = "private_key_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val privateKeyCiphertext: ByteArray,
    @ColumnInfo(name = "private_key_nonce", typeAffinity = ColumnInfo.BLOB)
    val privateKeyNonce: ByteArray,
)

@Entity(
    tableName = "ssh_export_copy",
    foreignKeys = [
        ForeignKey(
            entity = SshKeyEntity::class,
            parentColumns = ["provider_key_id"],
            childColumns = ["provider_key_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["key_alias"], unique = true, name = "index_ssh_export_copy_alias"),
    ],
)
internal data class SshExportCopyEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_key_id")
    val providerKeyId: String,
    @ColumnInfo(name = "key_alias")
    val keyAlias: String,
    @ColumnInfo(name = "private_key_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val privateKeyCiphertext: ByteArray,
    @ColumnInfo(name = "private_key_nonce", typeAffinity = ColumnInfo.BLOB)
    val privateKeyNonce: ByteArray,
    @ColumnInfo(name = "security_level")
    val securityLevel: SshSecurityLevelToken,
    @ColumnInfo(name = "backend_policy")
    val backendPolicy: SshExportBackendToken,
    @ColumnInfo(name = "authentication")
    val authentication: SshExportAuthenticationToken,
    @ColumnInfo(name = "strongbox_attempted")
    val strongBoxAttempted: Boolean,
    @ColumnInfo(name = "strongbox_fallback")
    val strongBoxFallback: Boolean,
    @ColumnInfo(name = "last_verified_at")
    val lastVerifiedAt: Long,
)

@Entity(
    tableName = "ssh_key_lifecycle",
    indices = [
        Index(value = ["operational_alias"], unique = true, name = "index_ssh_key_lifecycle_operational_alias"),
        Index(value = ["state", "updated_at"], name = "index_ssh_key_lifecycle_state_updated_at"),
    ],
)
internal data class SshKeyLifecycleEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_key_id")
    val providerKeyId: String,
    @ColumnInfo(name = "operational_alias")
    val operationalAlias: String,
    @ColumnInfo(name = "storage_kind")
    val storageKind: SshStorageKind,
    @ColumnInfo(name = "state")
    val state: SshKeyLifecycleState,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "ssh_key_lifecycle_candidate",
    primaryKeys = ["provider_key_id", "purpose"],
    foreignKeys = [
        ForeignKey(
            entity = SshKeyLifecycleEntity::class,
            parentColumns = ["provider_key_id"],
            childColumns = ["provider_key_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["key_alias"], unique = true, name = "index_ssh_key_lifecycle_candidate_alias"),
    ],
)
internal data class SshKeyLifecycleCandidateEntity(
    @ColumnInfo(name = "provider_key_id")
    val providerKeyId: String,
    @ColumnInfo(name = "purpose")
    val purpose: SshLifecycleCandidatePurpose,
    @ColumnInfo(name = "key_alias")
    val keyAlias: String,
    @ColumnInfo(name = "private_key_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val privateKeyCiphertext: ByteArray,
    @ColumnInfo(name = "private_key_nonce", typeAffinity = ColumnInfo.BLOB)
    val privateKeyNonce: ByteArray,
    @ColumnInfo(name = "security_level")
    val securityLevel: SshSecurityLevelToken,
)

@Entity(
    tableName = "ssh_authorization_floor",
    primaryKeys = ["requester_client_id", "authorization_generation"],
    indices = [
        Index(value = ["updated_at"], name = "index_ssh_authorization_floor_updated_at"),
    ],
)
internal data class SshAuthorizationFloorEntity(
    @ColumnInfo(name = "requester_client_id")
    val requesterClientId: String,
    @ColumnInfo(name = "authorization_generation")
    val authorizationGeneration: String,
    @ColumnInfo(name = "invalidated_through_epoch")
    val invalidatedThroughEpoch: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "ssh_peer_authorization",
    primaryKeys = ["provider_key_id", "requester_client_id", "authorization_generation", "authorization_epoch"],
    foreignKeys = [
        ForeignKey(
            entity = SshKeyEntity::class,
            parentColumns = ["provider_key_id"],
            childColumns = ["provider_key_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["authorization_id"], unique = true, name = "index_ssh_peer_authorization_id"),
        Index(
            value = ["requester_client_id", "authorization_generation", "authorization_epoch"],
            name = "index_ssh_peer_authorization_namespace",
        ),
    ],
)
internal data class SshPeerAuthorizationEntity(
    @ColumnInfo(name = "provider_key_id")
    val providerKeyId: String,
    @ColumnInfo(name = "requester_client_id")
    val requesterClientId: String,
    @ColumnInfo(name = "authorization_generation")
    val authorizationGeneration: String,
    @ColumnInfo(name = "authorization_epoch")
    val authorizationEpoch: Long,
    @ColumnInfo(name = "authorization_id")
    val authorizationId: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "ssh_known_host",
    indices = [
        Index(value = ["last_approved_at"], name = "index_ssh_known_host_last_approved_at"),
    ],
)
internal data class SshKnownHostEntity(
    @PrimaryKey
    @ColumnInfo(name = "host_key_sha256", typeAffinity = ColumnInfo.BLOB)
    val hostKeySha256: ByteArray,
    @ColumnInfo(name = "first_approved_at")
    val firstApprovedAt: Long,
    @ColumnInfo(name = "last_approved_at")
    val lastApprovedAt: Long,
)

@Entity(
    tableName = "ssh_host_authorization",
    primaryKeys = [
        "provider_key_id",
        "requester_client_id",
        "authorization_generation",
        "authorization_epoch",
        "host_key_sha256",
    ],
    foreignKeys = [
        ForeignKey(
            entity = SshKeyEntity::class,
            parentColumns = ["provider_key_id"],
            childColumns = ["provider_key_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SshKnownHostEntity::class,
            parentColumns = ["host_key_sha256"],
            childColumns = ["host_key_sha256"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["authorization_id"], unique = true, name = "index_ssh_host_authorization_id"),
        Index(
            value = ["requester_client_id", "authorization_generation", "authorization_epoch"],
            name = "index_ssh_host_authorization_namespace",
        ),
        Index(value = ["host_key_sha256"], name = "index_ssh_host_authorization_host_key_sha256"),
    ],
)
internal data class SshHostAuthorizationEntity(
    @ColumnInfo(name = "provider_key_id")
    val providerKeyId: String,
    @ColumnInfo(name = "requester_client_id")
    val requesterClientId: String,
    @ColumnInfo(name = "authorization_generation")
    val authorizationGeneration: String,
    @ColumnInfo(name = "authorization_epoch")
    val authorizationEpoch: Long,
    @ColumnInfo(name = "host_key_sha256", typeAffinity = ColumnInfo.BLOB)
    val hostKeySha256: ByteArray,
    @ColumnInfo(name = "authorization_id")
    val authorizationId: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "ssh_provider_request",
    indices = [
        Index(value = ["state", "updated_at"], name = "index_ssh_provider_request_state_updated_at"),
        Index(
            value = ["requester_client_id", "state"],
            name = "index_ssh_provider_request_requester_state",
        ),
        Index(value = ["expires_at"], name = "index_ssh_provider_request_expires_at"),
    ],
)
internal data class SshProviderRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "kind")
    val kind: SshProviderRequestKind,
    @ColumnInfo(name = "requester_client_id")
    val requesterClientId: String,
    @ColumnInfo(name = "request_fingerprint", typeAffinity = ColumnInfo.BLOB)
    val requestFingerprint: ByteArray,
    @ColumnInfo(name = "history_protection_scheme")
    val historyProtectionScheme: String,
    @ColumnInfo(name = "history_protection_version")
    val historyProtectionVersion: Int,
    @ColumnInfo(name = "history_protection_key_ref")
    val historyProtectionKeyRef: String,
    @ColumnInfo(name = "history_protection_generation")
    val historyProtectionGeneration: Long,
    @ColumnInfo(name = "history_payload_codec_version")
    val historyPayloadCodecVersion: Int,
    @ColumnInfo(name = "history_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val historyCiphertext: ByteArray,
    @ColumnInfo(name = "history_nonce", typeAffinity = ColumnInfo.BLOB)
    val historyNonce: ByteArray,
    @ColumnInfo(name = "state")
    val state: SshProviderRequestState,
    @ColumnInfo(name = "outcome")
    val outcome: SshProviderRequestOutcome?,
    @ColumnInfo(name = "result_at")
    val resultAt: Long?,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "ssh_provider_pending_payload",
    foreignKeys = [
        ForeignKey(
            entity = SshProviderRequestEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SshProviderPendingPayloadEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "protection_scheme")
    val protectionScheme: String,
    @ColumnInfo(name = "protection_version")
    val protectionVersion: Int,
    @ColumnInfo(name = "protection_key_ref")
    val protectionKeyRef: String,
    @ColumnInfo(name = "protection_generation")
    val protectionGeneration: Long,
    @ColumnInfo(name = "payload_codec_version")
    val payloadCodecVersion: Int,
    @ColumnInfo(name = "request_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val requestCiphertext: ByteArray,
    @ColumnInfo(name = "request_nonce", typeAffinity = ColumnInfo.BLOB)
    val requestNonce: ByteArray,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "ssh_provider_response_custody",
    foreignKeys = [
        ForeignKey(
            entity = SshProviderRequestEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SshProviderResponseCustodyEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "payload_format")
    val payloadFormat: SshProviderResponsePayloadFormat,
    @ColumnInfo(name = "protection_scheme")
    val protectionScheme: String,
    @ColumnInfo(name = "protection_version")
    val protectionVersion: Int,
    @ColumnInfo(name = "protection_key_ref")
    val protectionKeyRef: String,
    @ColumnInfo(name = "protection_generation")
    val protectionGeneration: Long,
    @ColumnInfo(name = "payload_codec_version")
    val payloadCodecVersion: Int,
    @ColumnInfo(name = "payload_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val payloadCiphertext: ByteArray,
    @ColumnInfo(name = "payload_nonce", typeAffinity = ColumnInfo.BLOB)
    val payloadNonce: ByteArray,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
