package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "seal_requests",
    primaryKeys = ["request_id"],
    indices = [
        Index(value = ["state", "updated_at"], name = "seal_requests_state_idx"),
        Index(value = ["state", "expires_at"], name = "seal_requests_expiry_idx"),
        Index(value = ["sender_client_id", "state"], name = "seal_requests_sender_idx"),
        Index(value = ["updated_at", "request_id"], name = "seal_requests_history_idx"),
    ],
)
internal data class OpenPgpSignRequestEntity(
    @ColumnInfo(name = "request_id") val requestId: String,
    @ColumnInfo(name = "protocol_version") val protocolVersion: Int,
    @ColumnInfo(name = "requester_client_id") val requesterClientId: String,
    @ColumnInfo(name = "sender_client_id") val senderClientId: String,
    @ColumnInfo(name = "primary_key_id") val primaryKeyId: String,
    @ColumnInfo(name = "issued_at") val issuedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "payload_sha256", typeAffinity = ColumnInfo.BLOB) val payloadSha256: ByteArray,
    @ColumnInfo(name = "object_kind") val objectKind: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray?,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "response_action") val responseAction: String?,
    @ColumnInfo(name = "response_signature_armor") val responseSignatureArmor: String?,
    @ColumnInfo(name = "response_reject_reason") val responseRejectReason: String?,
    @ColumnInfo(name = "response_action_at") val responseActionAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "result") val result: String?,
    @ColumnInfo(name = "working_directory") val workingDirectory: String?,
    @ColumnInfo(name = "summary_title") val summaryTitle: String?,
    @ColumnInfo(name = "summary_reference") val summaryReference: String?,
    @ColumnInfo(name = "summary_identity") val summaryIdentity: String?,
    @ColumnInfo(name = "legacy_details_json") val legacyDetailsJson: String?,
)
