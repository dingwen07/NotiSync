package net.extrawdw.apps.notisync.data.storage.core

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverter
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import net.extrawdw.notisync.protocol.crypto.ClientIds

@Entity(tableName = "core_maintenance_state")
internal data class CoreMaintenanceStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton", defaultValue = "1")
    val singleton: Int = 1,
    @ColumnInfo(name = "trust_cleanup_state")
    val trustCleanupState: TrustCleanupState,
    @ColumnInfo(name = "trust_cleanup_completed_at")
    val trustCleanupCompletedAt: Long? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(tableName = "identity_metadata")
internal data class IdentityMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton", defaultValue = "1")
    val singleton: Int = 1,
    @ColumnInfo(name = "key_alias")
    val keyAlias: String,
    @ColumnInfo(name = "key_alias_version")
    val keyAliasVersion: Int,
    @ColumnInfo(name = "public_spki", typeAffinity = ColumnInfo.BLOB)
    val publicSpki: ByteArray,
    @ColumnInfo(name = "client_id")
    val clientId: String,
    @ColumnInfo(name = "security_level")
    val securityLevel: IdentitySecurityLevel,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: IdentityLifecycleState,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(tableName = "trust_snapshot")
internal data class TrustSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton", defaultValue = "1")
    val singleton: Int = 1,
    @ColumnInfo(name = "signature_format")
    val signatureFormat: String,
    @ColumnInfo(name = "entries", typeAffinity = ColumnInfo.BLOB)
    val entriesUtf8: ByteArray,
    @ColumnInfo(name = "cards", typeAffinity = ColumnInfo.BLOB)
    val cardsUtf8: ByteArray,
    @ColumnInfo(name = "overlays", typeAffinity = ColumnInfo.BLOB)
    val overlaysUtf8: ByteArray,
    @ColumnInfo(name = "epochs", typeAffinity = ColumnInfo.BLOB)
    val epochsUtf8: ByteArray?,
    /** Exact persisted unpadded Base64URL text, retained as UTF-8 bytes rather than decoded DER. */
    @ColumnInfo(name = "signature", typeAffinity = ColumnInfo.BLOB)
    val signatureBase64UrlUtf8: ByteArray,
    @ColumnInfo(name = "snapshot_digest", typeAffinity = ColumnInfo.BLOB)
    val snapshotDigest: ByteArray,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "crypto_epoch",
    indices = [
        Index(value = ["lifecycle_state", "activation_at"]),
        Index(value = ["operational_signer_alias"], unique = true),
    ],
)
internal data class CryptoEpochEntity(
    @PrimaryKey
    @ColumnInfo(name = "epoch")
    val epoch: Int,
    @ColumnInfo(name = "operational_signer_alias")
    val operationalSignerAlias: String,
    @ColumnInfo(name = "operational_signer_public_spki", typeAffinity = ColumnInfo.BLOB)
    val operationalSignerPublicSpki: ByteArray,
    @ColumnInfo(name = "hpke_public_keyset", typeAffinity = ColumnInfo.BLOB)
    val hpkePublicKeyset: ByteArray,
    /** Null is allowed only while a journaled provisioning/deletion operation is in progress. */
    @ColumnInfo(name = "hpke_private_keyset_wrapped", typeAffinity = ColumnInfo.BLOB)
    val hpkePrivateKeysetWrapped: ByteArray? = null,
    @ColumnInfo(name = "security_level")
    val securityLevel: CryptoEpochSecurityLevel,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: CryptoEpochState,
    @ColumnInfo(name = "anti_rollback_floor")
    val antiRollbackFloor: Long,
    @ColumnInfo(name = "activation_at")
    val activationAt: Long? = null,
    @ColumnInfo(name = "retirement_at")
    val retirementAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "keystore_operation",
    indices = [
        Index(value = ["target_type", "target_id", "state"]),
        Index(value = ["state", "updated_at"]),
    ],
)
internal data class KeystoreOperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "target_type")
    val targetType: KeystoreOperationTarget,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    @ColumnInfo(name = "operation_kind")
    val operationKind: KeystoreOperationKind,
    @ColumnInfo(name = "state")
    val state: KeystoreOperationState,
    @ColumnInfo(name = "attempts", defaultValue = "0")
    val attempts: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String? = null,
)

internal fun CoreMaintenanceStateEntity.toSnapshot(): CoreMaintenanceSnapshot = CoreMaintenanceSnapshot(
    trustCleanupState = trustCleanupState,
    trustCleanupCompletedAt = trustCleanupCompletedAt,
    updatedAt = updatedAt,
)

internal fun IdentityMetadataEntity.toSnapshot(): IdentityMetadataSnapshot {
    check(ClientIds.derive(publicSpki).value == clientId) {
        "Stored identity client ID does not match its public SPKI"
    }
    return IdentityMetadataSnapshot(
        keyAlias = keyAlias,
        keyAliasVersion = keyAliasVersion,
        publicSpki = publicSpki.copyOf(),
        clientId = clientId,
        securityLevel = securityLevel,
        lifecycleState = lifecycleState,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

internal fun TrustSnapshotEntity.toSnapshot(format: TrustSignatureFormat): TrustSnapshot = TrustSnapshot(
    signatureFormat = format,
    entriesUtf8 = entriesUtf8.copyOf(),
    cardsUtf8 = cardsUtf8.copyOf(),
    overlaysUtf8 = overlaysUtf8.copyOf(),
    epochsUtf8 = epochsUtf8?.copyOf(),
    signatureBase64UrlUtf8 = signatureBase64UrlUtf8.copyOf(),
    snapshotDigest = snapshotDigest.copyOf(),
    updatedAt = updatedAt,
)

internal fun CryptoEpochEntity.toSnapshot(): CryptoEpochSnapshot = CryptoEpochSnapshot(
    epoch = epoch,
    operationalSignerAlias = operationalSignerAlias,
    operationalSignerPublicSpki = operationalSignerPublicSpki.copyOf(),
    hpkePublicKeyset = hpkePublicKeyset.copyOf(),
    hpkePrivateKeysetWrapped = hpkePrivateKeysetWrapped?.copyOf(),
    securityLevel = securityLevel,
    lifecycleState = lifecycleState,
    antiRollbackFloor = antiRollbackFloor,
    activationAt = activationAt,
    retirementAt = retirementAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun KeystoreOperationEntity.toSnapshot(): KeystoreOperationSnapshot = KeystoreOperationSnapshot(
    operationId = operationId,
    targetType = targetType,
    targetId = targetId,
    operationKind = operationKind,
    state = state,
    attempts = attempts,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    lastErrorCode = lastErrorCode,
)

internal object TrustCleanupStateConverter {
    @ColumnTypeConverter fun encode(value: TrustCleanupState): String = value.token
    @ColumnTypeConverter fun decode(value: String): TrustCleanupState = TrustCleanupState.fromToken(value)
}

internal object IdentitySecurityLevelConverter {
    @ColumnTypeConverter fun encode(value: IdentitySecurityLevel): String = value.token
    @ColumnTypeConverter fun decode(value: String): IdentitySecurityLevel = IdentitySecurityLevel.fromToken(value)
}

internal object IdentityLifecycleStateConverter {
    @ColumnTypeConverter fun encode(value: IdentityLifecycleState): String = value.token
    @ColumnTypeConverter fun decode(value: String): IdentityLifecycleState = IdentityLifecycleState.fromToken(value)
}

internal object CryptoEpochSecurityLevelConverter {
    @ColumnTypeConverter fun encode(value: CryptoEpochSecurityLevel): String = value.token
    @ColumnTypeConverter fun decode(value: String): CryptoEpochSecurityLevel = CryptoEpochSecurityLevel.fromToken(value)
}

internal object CryptoEpochStateConverter {
    @ColumnTypeConverter fun encode(value: CryptoEpochState): String = value.token
    @ColumnTypeConverter fun decode(value: String): CryptoEpochState = CryptoEpochState.fromToken(value)
}

internal object KeystoreOperationKindConverter {
    @ColumnTypeConverter fun encode(value: KeystoreOperationKind): String = value.token
    @ColumnTypeConverter fun decode(value: String): KeystoreOperationKind = KeystoreOperationKind.fromToken(value)
}

internal object KeystoreOperationTargetConverter {
    @ColumnTypeConverter fun encode(value: KeystoreOperationTarget): String = value.token
    @ColumnTypeConverter fun decode(value: String): KeystoreOperationTarget = KeystoreOperationTarget.fromToken(value)
}

internal object KeystoreOperationStateConverter {
    @ColumnTypeConverter fun encode(value: KeystoreOperationState): String = value.token
    @ColumnTypeConverter fun decode(value: String): KeystoreOperationState = KeystoreOperationState.fromToken(value)
}
