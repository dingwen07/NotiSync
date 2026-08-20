package net.extrawdw.apps.notisync.composition.bootstrap

import android.database.sqlite.SQLiteFullException
import androidx.sqlite.SQLiteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.composition.storage.StorageClock
import net.extrawdw.apps.notisync.crypto.KeyBacking
import net.extrawdw.apps.notisync.data.storage.core.CoreTransportInitializationResult
import net.extrawdw.apps.notisync.data.storage.core.CoreTransportSnapshot
import net.extrawdw.apps.notisync.data.storage.core.IdentityLifecycleState
import net.extrawdw.apps.notisync.data.storage.core.IdentityMetadataInput
import net.extrawdw.apps.notisync.data.storage.core.IdentityMetadataSaveResult
import net.extrawdw.apps.notisync.data.storage.core.IdentitySecurityLevel
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationEnsureResult
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationIntent
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationKind
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationSnapshot
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationState
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationTarget
import net.extrawdw.apps.notisync.data.storage.core.KeystoreOperationTransitionResult
import net.extrawdw.apps.notisync.data.storage.core.OperationalStorageBinding
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.OperationalCutoverCoordinator
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.OperationalRebuildResult
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.core.CoreV51CutoverCoordinator
import net.extrawdw.apps.notisync.data.storage.importer.coordinator.core.CoreV51CutoverResult
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalRebuildIdentity
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51FailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportTarget
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51OperationalStorageBinding
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.V51LegacySourceInventory
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.V51LegacySourceInventorySource
import net.extrawdw.apps.notisync.data.storage.runtime.CoreOperationalContinuityValidator
import net.extrawdw.apps.notisync.data.storage.runtime.OperationalContinuityValidation
import net.extrawdw.notisync.protocol.crypto.ClientIds

/**
 * The one user-open initializer. It owns origin inspection, the complete disposable Room rebuild, existing-key
 * activation, final Core authority publication, and the post-commit cross-database continuity check.
 */
internal class ProductionStorageBootstrapCoordinator(
    private val targetSource: CoreBootstrapTargetSnapshotSource,
    private val inventorySource: V51LegacySourceInventorySource,
    private val coreCutover: CoreV51CutoverCoordinator,
    private val coreTarget: CoreV51ImportTarget,
    private val operationalCutover: OperationalCutoverCoordinator,
    private val operationalIdentitySource: OperationalRebuildIdentitySource,
    private val freshPersistence: FreshIdentityPersistencePort,
    private val freshCrypto: FreshIdentityCryptoPort,
    private val continuityValidator: CoreOperationalContinuityValidator,
    private val clock: StorageClock,
    private val defaultBrokerUrl: String,
) {
    init {
        require(defaultBrokerUrl.isNotBlank()) { "Default broker URL must not be blank" }
    }

    suspend fun initialize(): CoreTransportSnapshot = try {
        when (val decision = StorageBootstrapOriginResolver.classifyTarget(targetSource.read())) {
            CoreBootstrapTargetDecision.ExistingAuthority -> Unit
            CoreBootstrapTargetDecision.ResumeFreshIdentity -> {
                requireInventory(V51LegacySourceInventory.ALL_ABSENT)
                runFreshIdentity(rebuildOperational(OperationalRebuildPurpose.FRESH))
            }
            CoreBootstrapTargetDecision.InspectLegacy -> initializeWithoutAuthority()
            is CoreBootstrapTargetDecision.Blocked -> blocked(decision.errorCode)
        }
        validateCompletedAuthority()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: StorageBootstrapFailure) {
        throw failure
    } catch (failure: CoreV51ImportFailure) {
        throw StorageBootstrapFailure(
            disposition = when (failure.disposition) {
                CoreV51FailureDisposition.RETRYABLE -> StorageBootstrapFailureDisposition.RETRYABLE
                CoreV51FailureDisposition.BLOCKED -> StorageBootstrapFailureDisposition.SECURITY_BLOCKING
            },
            errorCode = "core_v51_${failure.errorCode}",
            cause = failure,
        )
    } catch (failure: SQLiteFullException) {
        throw StorageBootstrapFailure(
            StorageBootstrapFailureDisposition.USER_RECOVERABLE,
            "bootstrap_storage_full",
            failure,
        )
    } catch (failure: SQLiteException) {
        throw StorageBootstrapFailure(
            StorageBootstrapFailureDisposition.RETRYABLE,
            "bootstrap_storage_temporarily_unavailable",
            failure,
        )
    } catch (failure: Exception) {
        throw StorageBootstrapFailure(
            StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
            "bootstrap_unexpected_failure",
            failure,
        )
    }

    /**
     * Background process entry points may resume an already-completed Room authority, but they never inspect or
     * migrate legacy sources. Null means MainActivity must remain the sole caller allowed to run [initialize].
     */
    suspend fun loadExistingAuthorityOrNull(): CoreTransportSnapshot? =
        if (StorageBootstrapOriginResolver.classifyTarget(targetSource.read()) ==
            CoreBootstrapTargetDecision.ExistingAuthority
        ) {
            validateCompletedAuthority()
        } else {
            null
        }

    private suspend fun initializeWithoutAuthority() {
        val before = targetSource.read()
        if (StorageBootstrapOriginResolver.classifyTarget(before) != CoreBootstrapTargetDecision.InspectLegacy) {
            blocked("bootstrap_target_changed_during_resolution")
        }
        when (val inventory = inventorySource.capture()) {
            V51LegacySourceInventory.RECOVERY_REQUIRED -> blocked("cutover_inventory_recovery_required")
            V51LegacySourceInventory.CORE_FOUNDATION_PRESENT -> {
                when (
                    coreCutover.run(
                        inventorySource = V51LegacySourceInventorySource { inventory },
                        operationalRebuild = {
                            rebuildOperational(OperationalRebuildPurpose.VERIFIED_V51).toStorageBinding()
                        },
                    )
                ) {
                    CoreV51CutoverResult.IMPORTED,
                    CoreV51CutoverResult.ALREADY_COMPLETE,
                    -> Unit
                    CoreV51CutoverResult.SOURCE_ABSENT -> blocked("core_source_disappeared")
                }
            }
            V51LegacySourceInventory.ALL_ABSENT -> {
                // Without a real Keystore operation, arbitrary Core rows are not a resumable fresh bootstrap.
                if (before.totalApplicationRowCount != 0L) blocked("fresh_core_target_not_pristine")
                runFreshIdentity(rebuildOperational(OperationalRebuildPurpose.FRESH))
            }
        }
    }

    private suspend fun rebuildOperational(purpose: OperationalRebuildPurpose): OperationalRebuildIdentity {
        val identity = operationalIdentitySource.resolve(purpose)
        return when (
            val result = operationalCutover.rebuild(
                operationalGeneration = identity.operationalGeneration,
                storageIncarnationId = identity.storageIncarnationId,
            )
        ) {
            is OperationalRebuildResult.Complete -> identity
            is OperationalRebuildResult.Retryable -> throw StorageBootstrapFailure(
                StorageBootstrapFailureDisposition.RETRYABLE,
                "operational_${result.errorCode}",
            )
            is OperationalRebuildResult.Blocked -> blocked("operational_${result.errorCode}")
        }
    }

    private suspend fun runFreshIdentity(operationalIdentity: OperationalRebuildIdentity) {
        var target = targetSource.read()
        var operation = target.freshIdentityOperation
        if (operation == null) {
            val intent = KeystoreOperationIntent(
                operationId = StorageBootstrapContract.FRESH_IDENTITY_OPERATION_ID,
                targetType = KeystoreOperationTarget.IDENTITY,
                targetId = StorageBootstrapContract.FRESH_IDENTITY_ALIAS,
                operationKind = KeystoreOperationKind.CREATE,
                createdAt = orderedTime(operationalIdentity.startedAt),
            )
            when (freshPersistence.ensureIdentityCreation(intent)) {
                KeystoreOperationEnsureResult.INSERTED,
                KeystoreOperationEnsureResult.EXISTING_PENDING,
                KeystoreOperationEnsureResult.EXISTING_RETRYABLE,
                KeystoreOperationEnsureResult.EXISTING_APPLIED,
                -> Unit
                KeystoreOperationEnsureResult.EXISTING_BLOCKED -> blocked("fresh_operation_blocked")
                KeystoreOperationEnsureResult.CONFLICT -> blocked("fresh_operation_conflict")
            }
            target = targetSource.read()
            operation = target.freshIdentityOperation
        }
        operation = requireNotNull(operation) { "Fresh identity operation is missing after insert" }
        if (StorageBootstrapOriginResolver.classifyTarget(target) != CoreBootstrapTargetDecision.ResumeFreshIdentity) {
            blocked("fresh_intent_readback_invalid")
        }

        try {
            val material = if (target.identity == null) {
                freshCrypto.loadExisting(operation.targetId) ?: when (operation.state) {
                    KeystoreOperationState.PENDING,
                    KeystoreOperationState.RETRYABLE,
                    -> freshCrypto.loadOrCreateAfterIntent(operation.targetId)
                    KeystoreOperationState.APPLIED -> blocked("fresh_applied_identity_missing")
                    KeystoreOperationState.BLOCKED -> blocked("fresh_operation_blocked")
                }
            } else {
                freshCrypto.loadExisting(operation.targetId) ?: blocked("fresh_identity_alias_missing")
            }
            material.requireCanonical(operation)
            material.selfTest()

            when (
                freshPersistence.saveIdentity(
                    IdentityMetadataInput(
                        keyAlias = material.alias,
                        keyAliasVersion = StorageBootstrapContract.FRESH_IDENTITY_ALIAS_VERSION,
                        publicSpki = material.publicSpki,
                        securityLevel = material.backing.toIdentitySecurityLevel(),
                        lifecycleState = IdentityLifecycleState.ACTIVE,
                        createdAt = operation.createdAt,
                    ),
                )
            ) {
                IdentityMetadataSaveResult.SAVED,
                IdentityMetadataSaveResult.ALREADY_CURRENT,
                -> Unit
                IdentityMetadataSaveResult.CONFLICT -> blocked("fresh_identity_target_conflict")
            }

            target = targetSource.read()
            operation = requireNotNull(target.freshIdentityOperation)
            if (target.identity?.matchesFreshIdentity(operation) != true) {
                blocked("fresh_identity_readback_invalid")
            }
            if (operation.state == KeystoreOperationState.PENDING ||
                operation.state == KeystoreOperationState.RETRYABLE
            ) {
                val transitioned = freshPersistence.markIdentityCreationApplied(
                    expectedState = operation.state,
                    expectedAttempts = operation.attempts,
                    completedAt = orderedTime(operation.createdAt),
                )
                if (transitioned == KeystoreOperationTransitionResult.STALE) {
                    val winner = targetSource.read().freshIdentityOperation
                    if (winner?.state != KeystoreOperationState.APPLIED || !winner.isCanonicalFreshOperation()) {
                        blocked("fresh_operation_transition_stale")
                    }
                }
            }

            val cryptoEpoch = freshCrypto.provisionFoundationAfterIntent(
                clientId = material.clientId,
                createdAt = operation.createdAt,
            )
            when (
                freshPersistence.initializeAuthority(
                    defaultBrokerUrl = defaultBrokerUrl,
                    operationalStorage = OperationalStorageBinding(
                        operationalGeneration = operationalIdentity.operationalGeneration,
                        storageIncarnationId = operationalIdentity.storageIncarnationId,
                    ),
                    cryptoEpoch = cryptoEpoch,
                )
            ) {
                CoreTransportInitializationResult.INITIALIZED,
                CoreTransportInitializationResult.ALREADY_INITIALIZED,
                -> Unit
                CoreTransportInitializationResult.CONFLICT -> blocked("fresh_transport_target_conflict")
            }
        } catch (cancelled: CancellationException) {
            recordFreshFailure(operation, KeystoreOperationState.RETRYABLE, "cancelled")
            throw cancelled
        } catch (failure: StorageBootstrapFailure) {
            val state = if (failure.disposition == StorageBootstrapFailureDisposition.SECURITY_BLOCKING) {
                KeystoreOperationState.BLOCKED
            } else {
                KeystoreOperationState.RETRYABLE
            }
            recordFreshFailure(operation, state, failure.errorCode)
            throw failure
        }
    }

    private suspend fun validateCompletedAuthority(): CoreTransportSnapshot {
        coreTarget.validateCompletedTransport()
        val validation = continuityValidator.validate()
        if (validation != OperationalContinuityValidation.VALID) {
            blocked("continuity_${validation.name.lowercase()}")
        }
        return targetSource.read().transport ?: blocked("completed_transport_missing")
    }

    private suspend fun requireInventory(expected: V51LegacySourceInventory) {
        if (inventorySource.capture() != expected) blocked("fresh_legacy_inventory_changed")
    }

    private suspend fun recordFreshFailure(
        observed: KeystoreOperationSnapshot,
        targetState: KeystoreOperationState,
        errorCode: String,
    ) = withContext(NonCancellable) {
        if (observed.state != KeystoreOperationState.PENDING &&
            observed.state != KeystoreOperationState.RETRYABLE
        ) return@withContext
        runCatching {
            freshPersistence.markIdentityCreationFailure(
                expectedState = observed.state,
                expectedAttempts = observed.attempts,
                targetState = targetState,
                errorCode = errorCode,
            )
        }
    }

    private fun FreshIdentityKeyMaterial.requireCanonical(operation: KeystoreOperationSnapshot) {
        if (alias != StorageBootstrapContract.FRESH_IDENTITY_ALIAS || alias != operation.targetId ||
            publicSpki.isEmpty() || ClientIds.derive(publicSpki).value != clientId ||
            backing !in setOf(KeyBacking.TEE, KeyBacking.STRONGBOX)
        ) blocked("fresh_identity_material_invalid")
    }

    private fun KeyBacking.toIdentitySecurityLevel(): IdentitySecurityLevel = when (this) {
        KeyBacking.TEE -> IdentitySecurityLevel.TRUSTED_ENVIRONMENT
        KeyBacking.STRONGBOX -> IdentitySecurityLevel.STRONGBOX
        KeyBacking.UNKNOWN,
        KeyBacking.UNKNOWN_SECURE,
        KeyBacking.SOFTWARE,
        -> blocked("fresh_identity_hardware_backing_required")
    }

    private fun orderedTime(minimum: Long): Long = clock.nowMillis().also {
        if (it <= 0 || it < minimum) blocked("bootstrap_clock_invalid")
    }

    private fun OperationalRebuildIdentity.toStorageBinding(): CoreV51OperationalStorageBinding =
        CoreV51OperationalStorageBinding(operationalGeneration, storageIncarnationId)

    private fun blocked(code: String): Nothing = throw StorageBootstrapFailure(
        StorageBootstrapFailureDisposition.SECURITY_BLOCKING,
        code,
    )
}
