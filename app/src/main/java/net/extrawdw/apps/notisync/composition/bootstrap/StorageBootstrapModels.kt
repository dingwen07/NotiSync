package net.extrawdw.apps.notisync.composition.bootstrap

import net.extrawdw.apps.notisync.crypto.AndroidIdentitySigner
import net.extrawdw.apps.notisync.data.storage.core.CoreTransportSnapshot
import net.extrawdw.apps.notisync.data.storage.core.IdentityLifecycleState
import net.extrawdw.apps.notisync.data.storage.core.IdentityMetadataSnapshot
import net.extrawdw.apps.notisync.data.storage.core.IdentitySecurityLevel
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationKind
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationSnapshot
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationState
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationTarget
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalRebuildIdentity
import net.extrawdw.notisync.protocol.crypto.ClientIds

internal object StorageBootstrapContract {
    const val FRESH_IDENTITY_OPERATION_ID = "bootstrap.identity.create.v1"
    const val FRESH_IDENTITY_ALIAS_VERSION = 1
    val FRESH_IDENTITY_ALIAS: String = AndroidIdentitySigner.KEY_ALIAS
}

internal enum class StorageBootstrapFailureDisposition {
    RETRYABLE,
    SECURITY_BLOCKING,
    USER_RECOVERABLE,
}

/** Value-free failure returned by the one user-open initializer. */
internal class StorageBootstrapFailure(
    val disposition: StorageBootstrapFailureDisposition,
    val errorCode: String,
    cause: Throwable? = null,
) : IllegalStateException("Storage bootstrap failed: $errorCode", cause) {
    init {
        require(errorCode.length in 1..128 &&
            errorCode.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        ) { "Bootstrap error code is invalid" }
    }
}

/** One transactionally read Core target view. */
internal class CoreBootstrapTargetSnapshot(
    val totalApplicationRowCount: Long,
    val keystoreOperationRowCount: Long,
    val transport: CoreTransportSnapshot?,
    val identity: IdentityMetadataSnapshot?,
    val freshIdentityOperation: KeystoreOperationSnapshot?,
    val validatedAt: Long,
) {
    init {
        require(totalApplicationRowCount >= 0 && keystoreOperationRowCount >= 0 && validatedAt > 0) {
            "Core bootstrap snapshot is invalid"
        }
    }
}

internal fun interface CoreBootstrapTargetSnapshotSource {
    suspend fun read(): CoreBootstrapTargetSnapshot
}

internal enum class OperationalRebuildPurpose { FRESH, VERIFIED_V51 }

/** Resolves or creates only the attempt identity; it stores no migration progress. */
internal fun interface OperationalRebuildIdentitySource {
    suspend fun resolve(purpose: OperationalRebuildPurpose): OperationalRebuildIdentity
}

internal sealed interface CoreBootstrapTargetDecision {
    data object InspectLegacy : CoreBootstrapTargetDecision
    data object ResumeFreshIdentity : CoreBootstrapTargetDecision
    data object ExistingAuthority : CoreBootstrapTargetDecision
    data class Blocked(val errorCode: String) : CoreBootstrapTargetDecision
}

internal object StorageBootstrapOriginResolver {
    fun classifyTarget(target: CoreBootstrapTargetSnapshot): CoreBootstrapTargetDecision {
        if (target.transport != null) return CoreBootstrapTargetDecision.ExistingAuthority
        val operation = target.freshIdentityOperation ?: return CoreBootstrapTargetDecision.InspectLegacy
        if (!operation.isCanonicalFreshOperation() || target.keystoreOperationRowCount != 1L) {
            return blocked("fresh_scaffolding_ambiguous")
        }
        val expectedRows = 1L + if (target.identity == null) 0L else 1L
        if (target.totalApplicationRowCount != expectedRows) return blocked("fresh_scaffolding_ambiguous")
        if (target.identity != null && !target.identity.matchesFreshIdentity(operation)) {
            return blocked("fresh_identity_metadata_invalid")
        }
        return when (operation.state) {
            KeystoreOperationState.PENDING,
            KeystoreOperationState.RETRYABLE,
            -> CoreBootstrapTargetDecision.ResumeFreshIdentity
            KeystoreOperationState.APPLIED -> if (target.identity != null) {
                CoreBootstrapTargetDecision.ResumeFreshIdentity
            } else {
                blocked("fresh_applied_identity_missing")
            }
            KeystoreOperationState.BLOCKED -> blocked("fresh_operation_blocked")
        }
    }

    private fun blocked(code: String) = CoreBootstrapTargetDecision.Blocked(code)
}

internal fun KeystoreOperationSnapshot.isCanonicalFreshOperation(): Boolean {
    if (operationId != StorageBootstrapContract.FRESH_IDENTITY_OPERATION_ID ||
        targetType != KeystoreOperationTarget.IDENTITY ||
        targetId != StorageBootstrapContract.FRESH_IDENTITY_ALIAS ||
        operationKind != KeystoreOperationKind.CREATE || attempts < 0 || createdAt <= 0 || updatedAt < createdAt
    ) return false
    return when (state) {
        KeystoreOperationState.PENDING -> attempts == 0 && completedAt == null && lastErrorCode == null
        KeystoreOperationState.RETRYABLE -> attempts > 0 && completedAt == null && lastErrorCode.isSafeCode()
        KeystoreOperationState.APPLIED -> attempts > 0 && completedAt != null && completedAt >= createdAt &&
            lastErrorCode == null
        KeystoreOperationState.BLOCKED -> attempts > 0 && completedAt == null && lastErrorCode.isSafeCode()
    }
}

internal fun IdentityMetadataSnapshot.matchesFreshIdentity(operation: KeystoreOperationSnapshot): Boolean =
    keyAlias == StorageBootstrapContract.FRESH_IDENTITY_ALIAS &&
        keyAliasVersion == StorageBootstrapContract.FRESH_IDENTITY_ALIAS_VERSION &&
        publicSpki.isNotEmpty() && ClientIds.derive(publicSpki).value == clientId &&
        securityLevel in setOf(IdentitySecurityLevel.TRUSTED_ENVIRONMENT, IdentitySecurityLevel.STRONGBOX) &&
        lifecycleState == IdentityLifecycleState.ACTIVE && createdAt == operation.createdAt && updatedAt >= createdAt

private fun String?.isSafeCode(): Boolean = this != null && length in 1..128 &&
    all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
