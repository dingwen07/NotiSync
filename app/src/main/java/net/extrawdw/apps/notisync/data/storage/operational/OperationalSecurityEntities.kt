package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "seal_enrollment")
internal data class SealEnrollmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = OperationalSingletons.ID,
    @ColumnInfo(name = "state")
    val state: SealEnrollmentState,
    /** Privacy-safe recovery category; never a provider response, identifier, or source value. */
    @ColumnInfo(name = "recovery_reason_code")
    val recoveryReasonCode: String?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "seal_enrollment_protected",
    foreignKeys = [
        ForeignKey(
            entity = SealEnrollmentEntity::class,
            parentColumns = ["singleton_id"],
            childColumns = ["singleton_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SealEnrollmentProtectedEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = OperationalSingletons.ID,
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
    /** Complete encoded enrollment tuple; plaintext values never enter Room. */
    @ColumnInfo(name = "payload_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val payloadCiphertext: ByteArray,
    @ColumnInfo(name = "payload_nonce", typeAffinity = ColumnInfo.BLOB)
    val payloadNonce: ByteArray,
)

@Entity(
    tableName = "seal_request",
    indices = [
        Index(value = ["state", "updated_at"], name = "index_seal_request_state_updated_at"),
        Index(value = ["sender_client_id", "state"], name = "index_seal_request_sender_state"),
        Index(value = ["expires_at"], name = "index_seal_request_expires_at"),
    ],
)
internal data class SealRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "requester_client_id")
    val requesterClientId: String,
    @ColumnInfo(name = "sender_client_id")
    val senderClientId: String,
    /** Equality/conflict input prepared from authenticated request metadata outside the transaction. */
    @ColumnInfo(name = "request_fingerprint", typeAffinity = ColumnInfo.BLOB)
    val requestFingerprint: ByteArray,
    @ColumnInfo(name = "issued_at")
    val issuedAt: Long,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
    @ColumnInfo(name = "payload_sha256", typeAffinity = ColumnInfo.BLOB)
    val payloadSha256: ByteArray,
    @ColumnInfo(name = "object_kind")
    val objectKind: SealObjectKind,
    @ColumnInfo(name = "display_protection_scheme")
    val displayProtectionScheme: String,
    @ColumnInfo(name = "display_protection_version")
    val displayProtectionVersion: Int,
    @ColumnInfo(name = "display_protection_key_ref")
    val displayProtectionKeyRef: String,
    @ColumnInfo(name = "display_protection_generation")
    val displayProtectionGeneration: Long,
    @ColumnInfo(name = "display_payload_codec_version")
    val displayPayloadCodecVersion: Int,
    /** Bounded history/review projection protected before it reaches Room. */
    @ColumnInfo(name = "display_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val displayCiphertext: ByteArray,
    @ColumnInfo(name = "display_nonce", typeAffinity = ColumnInfo.BLOB)
    val displayNonce: ByteArray,
    @ColumnInfo(name = "display_truncated")
    val displayTruncated: Boolean,
    @ColumnInfo(name = "state")
    val state: SealRequestState,
    @ColumnInfo(name = "outcome")
    val outcome: SealRequestOutcome?,
    @ColumnInfo(name = "decision_at")
    val decisionAt: Long?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "seal_pending_payload",
    foreignKeys = [
        ForeignKey(
            entity = SealRequestEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SealPendingPayloadEntity(
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
    /** Exact pending request/provider bytes, already protected by the repository/vault boundary. */
    @ColumnInfo(name = "payload_ciphertext", typeAffinity = ColumnInfo.BLOB)
    val payloadCiphertext: ByteArray,
    @ColumnInfo(name = "payload_nonce", typeAffinity = ColumnInfo.BLOB)
    val payloadNonce: ByteArray,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * The only durable outgoing Seal custody: a response that already belongs to [SealRequestEntity].
 * Retry scheduling remains a process/WorkManager concern; request state is the lifecycle authority.
 */
@Entity(
    tableName = "seal_response_custody",
    foreignKeys = [
        ForeignKey(
            entity = SealRequestEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SealResponseCustodyEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "payload_format")
    val payloadFormat: SealResponsePayloadFormat,
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

@Entity(tableName = "screen_authorized_peer")
internal data class ScreenAuthorizedPeerEntity(
    @PrimaryKey
    @ColumnInfo(name = "peer_id")
    val peerId: String,
    @ColumnInfo(name = "granted_at")
    val grantedAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "screen_replay_token",
    indices = [
        Index(value = ["expires_at"], name = "index_screen_replay_token_expires_at"),
    ],
)
internal data class ScreenReplayTokenEntity(
    @PrimaryKey
    @ColumnInfo(name = "digest", typeAffinity = ColumnInfo.BLOB)
    val digest: ByteArray,
    @ColumnInfo(name = "kind")
    val kind: ScreenReplayKind,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,
    @ColumnInfo(name = "consumed_at")
    val consumedAt: Long,
)

@Entity(tableName = "screen_security_state")
internal data class ScreenSecurityStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = OperationalSingletons.ID,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "replay_health")
    val replayHealth: ScreenReplayHealth,
    @ColumnInfo(name = "quarantine_digest", typeAffinity = ColumnInfo.BLOB)
    val quarantineDigest: ByteArray?,
    @ColumnInfo(name = "quarantined_at")
    val quarantinedAt: Long?,
    @ColumnInfo(name = "authorization_revision")
    val authorizationRevision: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(tableName = "screen_codec_preference")
internal data class ScreenCodecPreferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "peer_id")
    val peerId: String,
    @ColumnInfo(name = "codec")
    val codec: ScreenCodecToken,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
