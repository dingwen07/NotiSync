package net.extrawdw.apps.notisync.data.storage.importer.target.core.room

import android.database.SQLException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteFullException
import androidx.room3.withWriteTransaction
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import net.extrawdw.apps.notisync.data.storage.core.BrokerAuthTokenEntity
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabase
import net.extrawdw.apps.notisync.data.storage.core.CoreFoundationRepository
import net.extrawdw.apps.notisync.data.storage.core.CoreMaintenanceStateEntity
import net.extrawdw.apps.notisync.data.storage.core.CoreTransportStateEntity
import net.extrawdw.apps.notisync.data.storage.core.CryptoEpochEntity
import net.extrawdw.apps.notisync.data.storage.core.CryptoEpochSecurityLevel
import net.extrawdw.apps.notisync.data.storage.core.CryptoEpochState
import net.extrawdw.apps.notisync.data.storage.core.IdentityLifecycleState
import net.extrawdw.apps.notisync.data.storage.core.IdentityMetadataEntity
import net.extrawdw.apps.notisync.data.storage.core.IdentityMetadataInput
import net.extrawdw.apps.notisync.data.storage.core.IdentityMetadataSaveResult
import net.extrawdw.apps.notisync.data.storage.core.IdentitySecurityLevel
import net.extrawdw.apps.notisync.data.storage.core.INITIAL_OPERATIONAL_GENERATION
import net.extrawdw.apps.notisync.data.storage.core.OperationalContinuityOrigin
import net.extrawdw.apps.notisync.data.storage.core.ReplayFenceState
import net.extrawdw.apps.notisync.data.storage.core.TrustCleanupState
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshot
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshotInput
import net.extrawdw.apps.notisync.data.storage.core.TrustSnapshotWriteResult
import net.extrawdw.apps.notisync.data.storage.core.canonicalizeBrokerEndpoint
import net.extrawdw.apps.notisync.data.storage.core.toSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ActivationEvidence
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ActivationSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51FailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportTarget
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51EpochCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51EpochLifecycle
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51IdentityBacking
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51IdentityCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportPlan
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51OperationalBacking
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51OperationalStorageBinding
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51PrepareResult
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51TrustCommand
import net.extrawdw.notisync.protocol.crypto.ClientIds

/**
 * The only Room-aware Core v51 target. This package depends on clean target commands and Core storage, never on a
 * legacy reader/DTO. Rebuildable staging rows remain non-authoritative until one final transport/token transaction.
 */
internal class RoomCoreV51ImportTarget(
    private val database: CoreDatabase,
    private val repository: CoreFoundationRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : CoreV51ImportTarget {
    override suspend fun hasCompletedTransport(): Boolean = storageAccess {
        val transport = database.transportStateDao().get() ?: return@storageAccess false
        transport.toSnapshot()
        true
    }

    override suspend fun validateCompletedTransport() = storageAccess {
        val identity = database.identityMetadataDao().get() ?: targetConflict("terminal_identity_missing")
        if (identity.clientId != ClientIds.derive(identity.publicSpki).value ||
            identity.lifecycleState != IdentityLifecycleState.ACTIVE || identity.createdAt < 0
        ) {
            targetConflict("terminal_identity_invalid")
        }
        repository.loadValidatedTrustSnapshot()
        database.maintenanceStateDao().get()?.let { maintenance ->
            if ((maintenance.trustCleanupState == TrustCleanupState.COMPLETE) !=
                (maintenance.trustCleanupCompletedAt != null)
            ) {
                targetConflict("terminal_maintenance_invalid")
            }
        }
        val transport = database.transportStateDao().get() ?: targetConflict("terminal_transport_missing")
        transport.toSnapshot()
        val epochs = database.cryptoEpochDao().getAll()
        if ((epochs.isNotEmpty() && epochs.count { it.lifecycleState == CryptoEpochState.ACTIVE } != 1) ||
            epochs.any { epoch ->
                epoch.epoch <= 0 || epoch.operationalSignerAlias.isBlank() ||
                    epoch.operationalSignerPublicSpki.isEmpty() || epoch.hpkePublicKeyset.isEmpty() ||
                    epoch.createdAt < 0 ||
                    (epoch.lifecycleState == CryptoEpochState.ACTIVE &&
                        (epoch.hpkePrivateKeysetWrapped == null ||
                            epoch.hpkePrivateKeysetWrapped.isEmpty() || epoch.activationAt == null))
            }
        ) {
            targetConflict("terminal_epoch_invalid")
        }
        database.brokerAuthTokenDao().get()?.let { token ->
            if (token.wrappedToken.isEmpty() || token.encodingVersion <= 0 ||
                token.brokerEndpointRevision != transport.brokerEndpointRevision
            ) {
                targetConflict("terminal_auth_token_invalid")
            }
        }
        Unit
    }

    override suspend fun prepareForRebuild(): CoreV51PrepareResult = storageAccess {
        database.withWriteTransaction {
            if (database.transportStateDao().get() != null) {
                return@withWriteTransaction CoreV51PrepareResult.ALREADY_COMPLETE
            }
            val keystoreRecoveryRequired = database.keystoreOperationDao().countAll() != 0

            // Purge every pre-authority Core projection in this transaction. The external-effect Keystore journal is
            // never deleted by an importer; if nonempty it blocked above for its owning recovery flow to reconcile.
            database.brokerAuthTokenDao().clear()
            database.activityOutboxDao().clearAll()
            database.commandAppliedDao().clearAll()
            database.trustSnapshotDao().clear()
            database.cryptoEpochDao().clearAll()
            database.maintenanceStateDao().clear()
            database.identityMetadataDao().clear()
            if (database.hasPreAuthorityProjections()) targetConflict("pre_authority_purge_incomplete")
            if (keystoreRecoveryRequired) {
                CoreV51PrepareResult.KEYSTORE_RECOVERY_REQUIRED
            } else {
                CoreV51PrepareResult.READY
            }
        }
    }

    override suspend fun stage(plan: CoreV51ImportPlan) = storageAccess {
        val foundation = plan.requireFoundation()
        requirePreAuthorityWindow()
        when (
            repository.saveIdentityMetadata(
                IdentityMetadataInput(
                    keyAlias = foundation.identity.alias,
                    keyAliasVersion = foundation.identity.aliasVersion,
                    publicSpki = foundation.identity.publicSpkiCopy(),
                    securityLevel = foundation.identity.backing.toCoreIdentityBacking(),
                    lifecycleState = IdentityLifecycleState.ACTIVE,
                    createdAt = foundation.identity.createdAt,
                ),
            )
        ) {
            IdentityMetadataSaveResult.SAVED,
            IdentityMetadataSaveResult.ALREADY_CURRENT,
            -> Unit
            IdentityMetadataSaveResult.CONFLICT -> targetConflict("identity_target_conflict")
        }

        val trust = foundation.trust
        if (trust == null) {
            if (repository.loadValidatedTrustSnapshot() != null) targetConflict("trust_target_conflict")
        } else {
            when (repository.replaceTrustSnapshot(trust.toCoreInput(), expectedSnapshotDigest = null)) {
                TrustSnapshotWriteResult.APPLIED,
                TrustSnapshotWriteResult.ALREADY_CURRENT,
                -> Unit
                TrustSnapshotWriteResult.CONFLICT -> targetConflict("trust_target_conflict")
                TrustSnapshotWriteResult.MISSING_IDENTITY -> targetConflict("trust_identity_missing")
            }
        }

        val stagedAt = now()
        require(stagedAt >= 0) { "Core v51 staging time must not be negative" }
        database.withWriteTransaction {
            requirePreAuthorityWindow()
            if (database.transportStateDao().get() != null || database.brokerAuthTokenDao().get() != null) {
                targetConflict("authority_present_during_staging")
            }
            val maintenance = CoreMaintenanceStateEntity(
                trustCleanupState = if (foundation.maintenance.trustCleanupCompleted) {
                    TrustCleanupState.COMPLETE
                } else {
                    TrustCleanupState.NOT_STARTED
                },
                trustCleanupCompletedAt = stagedAt.takeIf { foundation.maintenance.trustCleanupCompleted },
                updatedAt = stagedAt,
            )
            database.maintenanceStateDao().get()?.let { current ->
                if (!current.sameMaintenance(maintenance)) targetConflict("maintenance_target_conflict")
            } ?: database.maintenanceStateDao().upsert(maintenance)

            val existingEpochs = database.cryptoEpochDao().getAll()
            if (existingEpochs.map { it.epoch } != foundation.epochs.map { it.epoch }) {
                if (existingEpochs.isNotEmpty()) targetConflict("epoch_inventory_target_conflict")
            }
            foundation.epochs.forEach { command ->
                val staged = command.toStagedEntity(stagedAt)
                database.cryptoEpochDao().find(command.epoch)?.let { current ->
                    if (!current.sameEpochSource(staged)) targetConflict("epoch_target_conflict")
                } ?: database.cryptoEpochDao().upsert(staged)
            }
        }
    }

    override suspend fun readActivationSnapshot(plan: CoreV51ImportPlan): CoreV51ActivationSnapshot = storageAccess {
        val foundation = plan.requireFoundation()
        requirePreAuthorityWindow()
        if (database.transportStateDao().get() != null || database.brokerAuthTokenDao().get() != null) {
            targetConflict("authority_present_before_activation")
        }
        val identity = requireNotNull(database.identityMetadataDao().get()) { "Core v51 identity target is missing" }
        identity.requireMatches(foundation.identity)
        val rows = database.cryptoEpochDao().getAll()
        rows.requireMatchSource(foundation.epochs)
        CoreV51ActivationSnapshot(
            planDigest = plan.targetContentDigest,
            identity = CoreV51IdentityCommand(
                alias = identity.keyAlias,
                aliasVersion = identity.keyAliasVersion,
                publicSpki = identity.publicSpki.copyOf(),
                backing = identity.securityLevel.toTargetIdentityBacking(),
                createdAt = identity.createdAt,
            ),
            wrappingKey = foundation.wrappingKey,
            epochs = rows.map { row -> row.toActivationCommand(foundation.epochs.single { it.epoch == row.epoch }) },
            authToken = foundation.authToken,
        )
    }

    override suspend fun finalize(
        plan: CoreV51ImportPlan,
        activation: CoreV51ActivationEvidence,
        operationalStorage: CoreV51OperationalStorageBinding,
    ) = storageAccess {
        val foundation = plan.requireFoundation()
        val validatedTrust = repository.loadValidatedTrustSnapshot()
        foundation.trust.requireMatches(validatedTrust)
        if (operationalStorage.operationalGeneration != INITIAL_OPERATIONAL_GENERATION) {
            targetConflict("operational_generation_invalid")
        }
        val finalizedAt = now()
        require(finalizedAt >= 0) { "Core v51 finalization time must not be negative" }
        val expectedTransport = CoreTransportStateEntity(
            brokerUrl = canonicalizeBrokerEndpoint(foundation.transport.brokerUrl),
            groupId = foundation.transport.groupId,
            fcmRouteRef = foundation.transport.fcmRouteRef,
            routeEpoch = foundation.transport.routeEpoch,
            brokerEndpointRevision = INITIAL_BROKER_ENDPOINT_REVISION,
            selfEpochActivatedAt = foundation.transport.selfEpochActivatedAt,
            operationalGeneration = operationalStorage.operationalGeneration,
            operationalIncarnationId = operationalStorage.storageIncarnationId,
            replayFenceState = ReplayFenceState.CONTINUITY_INTACT,
            continuityOrigin = OperationalContinuityOrigin.VERIFIED_V51_CUTOVER,
            replayFenceId = null,
            replayFenceEpoch = null,
            updatedAt = finalizedAt,
        ).also { it.toSnapshot() }
        val expectedToken = foundation.authToken?.let { command ->
            BrokerAuthTokenEntity(
                wrappedToken = command.wrappedTokenCopy(),
                encodingVersion = AUTH_TOKEN_ENCODING_VERSION,
                issuedAt = null,
                expiresAt = null,
                brokerEndpointRevision = INITIAL_BROKER_ENDPOINT_REVISION,
                updatedAt = finalizedAt,
            )
        }
        database.withWriteTransaction {
            requirePreAuthorityWindow()
            if (database.transportStateDao().get() != null || database.brokerAuthTokenDao().get() != null) {
                targetConflict("authority_present_before_final_commit")
            }
            val identity = requireNotNull(database.identityMetadataDao().get())
            identity.requireMatches(foundation.identity)
            foundation.trust.requireMatchesRaw(database.trustSnapshotDao().get(), validatedTrust)
            database.maintenanceStateDao().get().requireMatches(foundation)

            val rows = database.cryptoEpochDao().getAll()
            rows.requireMatchSource(foundation.epochs)
            val persisted = CoreV51ActivationSnapshot(
                planDigest = plan.targetContentDigest,
                identity = foundation.identity,
                wrappingKey = foundation.wrappingKey,
                epochs = rows.map { row ->
                    row.toActivationCommand(foundation.epochs.single { it.epoch == row.epoch })
                },
                authToken = foundation.authToken,
            )
            activation.requireMatches(plan, foundation, persisted)

            foundation.epochs.forEach { command ->
                val staged = requireNotNull(database.cryptoEpochDao().find(command.epoch))
                database.cryptoEpochDao().upsert(
                    staged.copy(
                        lifecycleState = command.lifecycle.toCoreLifecycle(),
                        activationAt = command.activationAt,
                        retirementAt = command.retirementAt,
                        updatedAt = finalizedAt,
                    ),
                )
            }
            database.cryptoEpochDao().getAll().requireFinalMatch(foundation.epochs, finalizedAt)

            check(database.transportStateDao().insertIfAbsent(expectedTransport) != -1L) {
                "Core v51 transport finalization collided"
            }
            database.transportStateDao().get().requireExactTransport(expectedTransport)
            if (expectedToken != null) database.brokerAuthTokenDao().upsert(expectedToken)
            database.brokerAuthTokenDao().get().requireExactToken(expectedToken)
        }
    }

    private suspend fun requirePreAuthorityWindow() {
        if (database.transportStateDao().get() != null) targetConflict("transport_became_authoritative")
        if (database.keystoreOperationDao().countAll() != 0) targetConflict("keystore_operation_present")
        if (database.commandAppliedDao().countAll() != 0 || database.activityOutboxDao().countAll() != 0) {
            targetConflict("pre_authority_delivery_projection_present")
        }
    }

    private suspend fun <T> storageAccess(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expected: CoreV51ImportFailure) {
        throw expected
    } catch (_: SQLiteFullException) {
        throw CoreV51ImportFailure(CoreV51FailureDisposition.RETRYABLE, "target_storage_full")
    } catch (_: SQLiteDatabaseCorruptException) {
        targetConflict("target_database_corrupt")
    } catch (_: SQLException) {
        throw CoreV51ImportFailure(CoreV51FailureDisposition.RETRYABLE, "target_temporarily_unavailable")
    } catch (_: IllegalArgumentException) {
        targetConflict("target_invariant_rejected")
    } catch (_: IllegalStateException) {
        targetConflict("target_consistency_failure")
    }
}

private fun CoreV51ImportPlan.requireFoundation(): CoreV51ImportPlan.Foundation =
    foundation ?: targetConflict("foundation_plan_missing")

private suspend fun CoreDatabase.hasPreAuthorityProjections(): Boolean =
    commandAppliedDao().countAll() != 0 ||
        activityOutboxDao().countAll() != 0 ||
        brokerAuthTokenDao().get() != null ||
        trustSnapshotDao().get() != null ||
        cryptoEpochDao().getAll().isNotEmpty() ||
        maintenanceStateDao().get() != null ||
        identityMetadataDao().get() != null

private fun IdentityMetadataEntity.requireMatches(command: CoreV51IdentityCommand) {
    if (keyAlias != command.alias || keyAliasVersion != command.aliasVersion ||
        !MessageDigest.isEqual(publicSpki, command.publicSpkiCopy()) ||
        clientId != ClientIds.derive(command.publicSpkiCopy()).value ||
        securityLevel != command.backing.toCoreIdentityBacking() ||
        lifecycleState != IdentityLifecycleState.ACTIVE || createdAt != command.createdAt
    ) targetConflict("identity_target_conflict")
}

private fun CoreMaintenanceStateEntity?.requireMatches(foundation: CoreV51ImportPlan.Foundation) {
    val current = this ?: targetConflict("maintenance_target_missing")
    val expectedState = if (foundation.maintenance.trustCleanupCompleted) {
        TrustCleanupState.COMPLETE
    } else {
        TrustCleanupState.NOT_STARTED
    }
    if (current.trustCleanupState != expectedState ||
        (current.trustCleanupCompletedAt != null) != foundation.maintenance.trustCleanupCompleted
    ) targetConflict("maintenance_target_conflict")
}

private fun CoreMaintenanceStateEntity.sameMaintenance(other: CoreMaintenanceStateEntity): Boolean =
    singleton == other.singleton && trustCleanupState == other.trustCleanupState &&
        trustCleanupCompletedAt == other.trustCleanupCompletedAt

private fun CoreV51EpochCommand.toStagedEntity(updatedAt: Long): CryptoEpochEntity = CryptoEpochEntity(
    epoch = epoch,
    operationalSignerAlias = operationalSignerAlias,
    operationalSignerPublicSpki = operationalSignerPublicSpkiCopy(),
    hpkePublicKeyset = hpkePublicKeysetCopy(),
    hpkePrivateKeysetWrapped = hpkePrivateKeysetWrappedCopy(),
    securityLevel = backing.toCoreOperationalBacking(),
    lifecycleState = CryptoEpochState.PROVISIONING,
    antiRollbackFloor = antiRollbackFloor,
    activationAt = activationAt,
    retirementAt = retirementAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun CryptoEpochEntity.sameEpochSource(other: CryptoEpochEntity): Boolean =
    epoch == other.epoch && operationalSignerAlias == other.operationalSignerAlias &&
        MessageDigest.isEqual(operationalSignerPublicSpki, other.operationalSignerPublicSpki) &&
        MessageDigest.isEqual(hpkePublicKeyset, other.hpkePublicKeyset) &&
        hpkePrivateKeysetWrapped.contentEqualsOrBothNull(other.hpkePrivateKeysetWrapped) &&
        securityLevel == other.securityLevel && antiRollbackFloor == other.antiRollbackFloor &&
        activationAt == other.activationAt && retirementAt == other.retirementAt &&
        createdAt == other.createdAt && lifecycleState in setOf(CryptoEpochState.PROVISIONING, other.lifecycleState)

private fun List<CryptoEpochEntity>.requireMatchSource(commands: List<CoreV51EpochCommand>) {
    if (map { it.epoch } != commands.map { it.epoch }) targetConflict("epoch_inventory_target_conflict")
    zip(commands).forEach { (row, command) ->
        if (!row.sameEpochSource(command.toStagedEntity(row.updatedAt))) targetConflict("epoch_target_conflict")
    }
}

private fun List<CryptoEpochEntity>.requireFinalMatch(
    commands: List<CoreV51EpochCommand>,
    finalizedAt: Long,
) {
    if (map { it.epoch } != commands.map { it.epoch }) targetConflict("epoch_inventory_target_conflict")
    zip(commands).forEach { (row, command) ->
        val expected = command.toStagedEntity(finalizedAt).copy(
            lifecycleState = command.lifecycle.toCoreLifecycle(),
            activationAt = command.activationAt,
            retirementAt = command.retirementAt,
        )
        if (!row.sameExactEpoch(expected)) targetConflict("epoch_final_readback_mismatch")
    }
}

private fun CryptoEpochEntity.sameExactEpoch(other: CryptoEpochEntity): Boolean =
    sameEpochSource(other) && lifecycleState == other.lifecycleState && updatedAt == other.updatedAt

private fun CryptoEpochEntity.toActivationCommand(planned: CoreV51EpochCommand): CoreV51EpochCommand =
    CoreV51EpochCommand(
        epoch = epoch,
        operationalSignerAlias = operationalSignerAlias,
        operationalSignerAliasVersion = planned.operationalSignerAliasVersion,
        operationalSignerPublicSpki = operationalSignerPublicSpki.copyOf(),
        hpkePublicKeyset = hpkePublicKeyset.copyOf(),
        hpkePrivateKeysetWrapped = requireNotNull(hpkePrivateKeysetWrapped).copyOf(),
        backing = securityLevel.toTargetOperationalBacking(),
        lifecycle = planned.lifecycle,
        antiRollbackFloor = antiRollbackFloor,
        activationAt = planned.activationAt,
        retirementAt = planned.retirementAt,
        createdAt = createdAt,
    )

private fun CoreV51TrustCommand.toCoreInput(): TrustSnapshotInput = when (this) {
    is CoreV51TrustCommand.ThreeSection -> TrustSnapshotInput.ThreeSection(
        entriesUtf8Copy(), cardsUtf8Copy(), overlaysUtf8Copy(), signatureBase64UrlUtf8Copy(),
    )
    is CoreV51TrustCommand.FourSection -> TrustSnapshotInput.FourSection(
        entriesUtf8Copy(), cardsUtf8Copy(), overlaysUtf8Copy(), requireNotNull(epochsUtf8OrNull()),
        signatureBase64UrlUtf8Copy(),
    )
}

private fun CoreV51TrustCommand?.requireMatches(snapshot: TrustSnapshot?) {
    if (this == null) {
        if (snapshot != null) targetConflict("trust_target_conflict")
        return
    }
    if (snapshot == null || !MessageDigest.isEqual(entriesUtf8Copy(), snapshot.entriesUtf8) ||
        !MessageDigest.isEqual(cardsUtf8Copy(), snapshot.cardsUtf8) ||
        !MessageDigest.isEqual(overlaysUtf8Copy(), snapshot.overlaysUtf8) ||
        !epochsUtf8OrNull().contentEqualsOrBothNull(snapshot.epochsUtf8) ||
        !MessageDigest.isEqual(signatureBase64UrlUtf8Copy(), snapshot.signatureBase64UrlUtf8) ||
        (this is CoreV51TrustCommand.ThreeSection) !=
        (snapshot.signatureFormat == net.extrawdw.apps.notisync.data.storage.core.TrustSignatureFormat.TRUSTSTORE_V1_THREE_SECTION)
    ) targetConflict("trust_target_conflict")
}

private fun CoreV51TrustCommand?.requireMatchesRaw(
    raw: net.extrawdw.apps.notisync.data.storage.core.TrustSnapshotEntity?,
    validated: TrustSnapshot?,
) {
    requireMatches(validated)
    if (this == null) {
        if (raw != null) targetConflict("trust_target_conflict")
    } else if (raw == null || validated == null ||
        raw.signatureFormat != validated.signatureFormat.token ||
        !MessageDigest.isEqual(raw.entriesUtf8, entriesUtf8Copy()) ||
        !MessageDigest.isEqual(raw.cardsUtf8, cardsUtf8Copy()) ||
        !MessageDigest.isEqual(raw.overlaysUtf8, overlaysUtf8Copy()) ||
        !raw.epochsUtf8.contentEqualsOrBothNull(epochsUtf8OrNull()) ||
        !MessageDigest.isEqual(raw.signatureBase64UrlUtf8, signatureBase64UrlUtf8Copy()) ||
        !MessageDigest.isEqual(raw.snapshotDigest, validated.snapshotDigest)
    ) targetConflict("trust_target_conflict")
}

private fun CoreTransportStateEntity?.requireExactTransport(expected: CoreTransportStateEntity) {
    val actual = this ?: targetConflict("transport_final_readback_missing")
    if (actual != expected) targetConflict("transport_final_readback_mismatch")
}

private fun BrokerAuthTokenEntity?.requireExactToken(expected: BrokerAuthTokenEntity?) {
    if (this == null || expected == null) {
        if (this != null || expected != null) targetConflict("auth_token_final_readback_mismatch")
        return
    }
    if (!MessageDigest.isEqual(wrappedToken, expected.wrappedToken) ||
        singleton != expected.singleton || encodingVersion != expected.encodingVersion ||
        issuedAt != expected.issuedAt || expiresAt != expected.expiresAt ||
        brokerEndpointRevision != expected.brokerEndpointRevision || updatedAt != expected.updatedAt
    ) targetConflict("auth_token_final_readback_mismatch")
}

private fun CoreV51ActivationEvidence.requireMatches(
    plan: CoreV51ImportPlan,
    foundation: CoreV51ImportPlan.Foundation,
    persisted: CoreV51ActivationSnapshot,
) {
    if (planDigest != plan.targetContentDigest || candidateDigest != persisted.candidateDigest ||
        identityClientId != ClientIds.derive(foundation.identity.publicSpkiCopy()).value ||
        (authTokenSelfTestedAt == null) != (foundation.authToken == null)
    ) targetConflict("activation_evidence_mismatch")
    if (epochEvidence.map { it.epoch } != foundation.epochs.map { it.epoch }) {
        targetConflict("activation_epoch_evidence_mismatch")
    }
    epochEvidence.zip(foundation.epochs).forEach { (evidence, command) ->
        val expectedFingerprint = MessageDigest.getInstance("SHA-256").digest(command.hpkePublicKeysetCopy())
        if (!MessageDigest.isEqual(evidence.hpkePublicKeysetFingerprint, expectedFingerprint)) {
            targetConflict("activation_epoch_evidence_mismatch")
        }
    }
}

private fun CoreV51IdentityBacking.toCoreIdentityBacking(): IdentitySecurityLevel = when (this) {
    CoreV51IdentityBacking.HARDWARE_SECURE_UNKNOWN -> IdentitySecurityLevel.UNKNOWN
    CoreV51IdentityBacking.TRUSTED_ENVIRONMENT -> IdentitySecurityLevel.TRUSTED_ENVIRONMENT
    CoreV51IdentityBacking.STRONGBOX -> IdentitySecurityLevel.STRONGBOX
}

private fun IdentitySecurityLevel.toTargetIdentityBacking(): CoreV51IdentityBacking = when (this) {
    IdentitySecurityLevel.UNKNOWN -> CoreV51IdentityBacking.HARDWARE_SECURE_UNKNOWN
    IdentitySecurityLevel.TRUSTED_ENVIRONMENT -> CoreV51IdentityBacking.TRUSTED_ENVIRONMENT
    IdentitySecurityLevel.STRONGBOX -> CoreV51IdentityBacking.STRONGBOX
}

private fun CoreV51OperationalBacking.toCoreOperationalBacking(): CryptoEpochSecurityLevel = when (this) {
    CoreV51OperationalBacking.TRUSTED_ENVIRONMENT -> CryptoEpochSecurityLevel.TRUSTED_ENVIRONMENT
    CoreV51OperationalBacking.STRONGBOX -> CryptoEpochSecurityLevel.STRONGBOX
}

private fun CryptoEpochSecurityLevel.toTargetOperationalBacking(): CoreV51OperationalBacking = when (this) {
    CryptoEpochSecurityLevel.TRUSTED_ENVIRONMENT -> CoreV51OperationalBacking.TRUSTED_ENVIRONMENT
    CryptoEpochSecurityLevel.STRONGBOX -> CoreV51OperationalBacking.STRONGBOX
}

private fun CoreV51EpochLifecycle.toCoreLifecycle(): CryptoEpochState = when (this) {
    CoreV51EpochLifecycle.PROVISIONING -> CryptoEpochState.PROVISIONING
    CoreV51EpochLifecycle.ACTIVE -> CryptoEpochState.ACTIVE
    CoreV51EpochLifecycle.RETIRED -> CryptoEpochState.RETIRED
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean = when {
    this == null || other == null -> this == null && other == null
    else -> MessageDigest.isEqual(this, other)
}

private fun targetConflict(code: String): Nothing =
    throw CoreV51ImportFailure(CoreV51FailureDisposition.BLOCKED, code)

private const val AUTH_TOKEN_ENCODING_VERSION = 1
private const val INITIAL_BROKER_ENDPOINT_REVISION = 0L
