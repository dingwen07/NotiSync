package net.extrawdw.apps.notisync.data.storage.importer.coordinator.core

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ActivationEvidence
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ActivationGate
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ActivationSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51EpochActivationEvidence
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51EpochCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51EpochLifecycle
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51FailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51IdentityBacking
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51IdentityCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportPlan
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportTarget
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51MaintenanceCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51OperationalBacking
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51OperationalRebuildStep
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51OperationalStorageBinding
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51PlanSource
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51PrepareResult
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51TransportCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51WrappingKeyCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.V51LegacySourceInventorySource
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.V51LegacySourceInventory
import net.extrawdw.notisync.protocol.crypto.ClientIds
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoreV51CutoverCoordinatorTest {
    @Test
    fun completedTransportSkipsInventoryOperationalLegacyAndActivation() = runBlocking {
        val target = FakeTarget(readyPlan()).apply { completed = true }
        var inventoryCalls = 0
        var operationalCalls = 0
        var sourceCalls = 0
        var gateCalls = 0
        val coordinator = CoreV51CutoverCoordinator(
            source = CoreV51PlanSource { sourceCalls += 1; error("legacy Core source must remain closed") },
            target = target,
            activationGate = CoreV51ActivationGate { gateCalls += 1; error("activation must remain closed") },
        )

        val result = coordinator.run(
            V51LegacySourceInventorySource { inventoryCalls += 1; error("inventory must remain closed") },
            CoreV51OperationalRebuildStep { operationalCalls += 1; error("Operational must remain closed") },
        )

        assertEquals(CoreV51CutoverResult.ALREADY_COMPLETE, result)
        assertEquals(listOf("hasTransport", "validate"), target.events)
        assertEquals(0, inventoryCalls)
        assertEquals(0, operationalCalls)
        assertEquals(0, sourceCalls)
        assertEquals(0, gateCalls)
    }

    @Test
    fun successfulCutoverBindsInventoryBeforeOperationalAndPublishesTransportLast() = runBlocking {
        val plan = readyPlan()
        val target = FakeTarget(plan)
        val coordinator = coordinator(plan, target)

        assertEquals(CoreV51CutoverResult.IMPORTED, coordinator.run(inventorySource(target), operationalStep(target)))
        assertEquals(
            listOf(
                "hasTransport",
                "inventory",
                "prepare",
                "operational",
                "source",
                "stage",
                "read",
                "activate",
                "finalize",
                "validate",
            ),
            target.events,
        )
        assertEquals(OPERATIONAL_IDENTITY, target.finalizedOperationalIdentity)

        target.events.clear()
        assertEquals(
            CoreV51CutoverResult.ALREADY_COMPLETE,
            coordinator.run(
                V51LegacySourceInventorySource { error("completed cutover must not inspect legacy") },
                CoreV51OperationalRebuildStep { error("completed cutover must not rebuild Operational") },
            ),
        )
        assertEquals(listOf("hasTransport", "validate"), target.events)
    }

    @Test
    fun cancellationAfterPurgeRethrowsWithoutPublishingTransport() = runBlocking {
        val cancellation = CancellationException("cancelled by test")
        val target = FakeTarget(readyPlan()).apply { operationalFailure = cancellation }
        val coordinator = coordinator(readyPlan(), target)

        try {
            coordinator.run(inventorySource(target), operationalStep(target))
            fail("CancellationException expected")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertTrue(target.events.indexOf("prepare") < target.events.indexOf("operational"))
        assertTrue(!target.completed)
    }

    @Test
    fun restartRebindsSameInventoryAndRebuildsFromSource() = runBlocking {
        val plan = readyPlan()
        val target = FakeTarget(plan).apply {
            stageFailure = CoreV51ImportFailure(CoreV51FailureDisposition.RETRYABLE, "target_storage_full")
        }
        val coordinator = coordinator(plan, target)

        val first = runCatching {
            coordinator.run(inventorySource(target), operationalStep(target))
        }.exceptionOrNull() as CoreV51ImportFailure
        assertEquals(CoreV51FailureDisposition.RETRYABLE, first.disposition)
        target.stageFailure = null

        assertEquals(CoreV51CutoverResult.IMPORTED, coordinator.run(inventorySource(target), operationalStep(target)))
        assertEquals(2, target.prepareCalls)
        assertEquals(2, target.sourceCalls)
        assertEquals(1, target.finalizeCalls)
    }

    @Test
    fun allAbsentInventoryReturnsSourceAbsentWithoutTouchingEitherTargetOrLegacy() = runBlocking {
        val absent = CoreV51ImportPlan.absent()
        val target = FakeTarget(absent)
        var sourceCalls = 0
        var operationalCalls = 0
        val coordinator = CoreV51CutoverCoordinator(
            source = CoreV51PlanSource { sourceCalls += 1; error("absent origin must not open legacy Core") },
            target = target,
            activationGate = CoreV51ActivationGate { error("absent Core must not activate") },
        )

        assertEquals(
            CoreV51CutoverResult.SOURCE_ABSENT,
            coordinator.run(
                V51LegacySourceInventorySource {
                    target.events += "inventory"
                    V51LegacySourceInventory.ALL_ABSENT
                },
                CoreV51OperationalRebuildStep {
                    operationalCalls += 1
                    error("absent origin must not rebuild Operational")
                },
            ),
        )
        assertEquals(listOf("hasTransport", "inventory"), target.events)
        assertEquals(0, sourceCalls)
        assertEquals(0, operationalCalls)
        assertEquals(0, target.finalizeCalls)
        assertTrue(!target.completed)
    }

    @Test
    fun corePresenceBitMustAgreeWithMappedCorePlan() = runBlocking {
        val target = FakeTarget(CoreV51ImportPlan.absent())
        val coordinator = CoreV51CutoverCoordinator(
            source = CoreV51PlanSource { target.events += "source"; CoreV51ImportPlan.absent() },
            target = target,
            activationGate = CoreV51ActivationGate { error("must not activate") },
        )

        val failure = runCatching {
            coordinator.run(inventorySource(target), operationalStep(target))
        }.exceptionOrNull() as CoreV51ImportFailure

        assertEquals("core_source_inventory_mismatch", failure.errorCode)
        assertEquals(0, target.finalizeCalls)
    }

    @Test
    fun recoveryRequiredInventoryCannotStartV51OrWriteEitherTarget() = runBlocking {
        val target = FakeTarget(readyPlan())
        val coordinator = coordinator(readyPlan(), target)

        val failure = runCatching {
            coordinator.run(
                V51LegacySourceInventorySource {
                    target.events += "inventory"
                    V51LegacySourceInventory.RECOVERY_REQUIRED
                },
                operationalStep(target),
            )
        }.exceptionOrNull() as CoreV51ImportFailure

        assertEquals("cutover_inventory_recovery_required", failure.errorCode)
        assertEquals(listOf("hasTransport", "inventory"), target.events)
    }

    @Test
    fun persistedCandidateDigestBindsOperationalAliasVersion() {
        val plan = readyPlan()
        val first = activationSnapshot(plan)
        val foundation = requireNotNull(plan.foundation)
        val epoch = foundation.epochs.single()
        val changedVersion = CoreV51EpochCommand(
            epoch = epoch.epoch,
            operationalSignerAlias = epoch.operationalSignerAlias,
            operationalSignerAliasVersion = epoch.operationalSignerAliasVersion + 1,
            operationalSignerPublicSpki = epoch.operationalSignerPublicSpkiCopy(),
            hpkePublicKeyset = epoch.hpkePublicKeysetCopy(),
            hpkePrivateKeysetWrapped = epoch.hpkePrivateKeysetWrappedCopy(),
            backing = epoch.backing,
            lifecycle = epoch.lifecycle,
            antiRollbackFloor = epoch.antiRollbackFloor,
            activationAt = epoch.activationAt,
            retirementAt = epoch.retirementAt,
            createdAt = epoch.createdAt,
        )
        val second = CoreV51ActivationSnapshot(
            planDigest = first.planDigest,
            identity = foundation.identity,
            wrappingKey = foundation.wrappingKey,
            epochs = listOf(changedVersion),
            authToken = foundation.authToken,
        )

        assertTrue(first.candidateDigest != second.candidateDigest)
    }

    private fun coordinator(plan: CoreV51ImportPlan, target: FakeTarget): CoreV51CutoverCoordinator =
        CoreV51CutoverCoordinator(
            source = CoreV51PlanSource {
                target.events += "source"
                target.sourceCalls += 1
                plan
            },
            target = target,
            activationGate = CoreV51ActivationGate { persisted ->
                target.events += "activate"
                activationFor(persisted)
            },
        )

    private fun inventorySource(target: FakeTarget) = V51LegacySourceInventorySource {
        target.events += "inventory"
        READY_INVENTORY
    }

    private fun operationalStep(target: FakeTarget) = CoreV51OperationalRebuildStep {
        target.events += "operational"
        target.operationalFailure?.let { throw it }
        OPERATIONAL_IDENTITY
    }

    private class FakeTarget(private val plan: CoreV51ImportPlan) : CoreV51ImportTarget {
        val events = mutableListOf<String>()
        var completed = false
        var prepareCalls = 0
        var sourceCalls = 0
        var finalizeCalls = 0
        var stageFailure: Throwable? = null
        var operationalFailure: Throwable? = null
        var finalizedOperationalIdentity: CoreV51OperationalStorageBinding? = null

        override suspend fun hasCompletedTransport(): Boolean {
            events += "hasTransport"
            return completed
        }

        override suspend fun validateCompletedTransport() {
            events += "validate"
        }

        override suspend fun prepareForRebuild(): CoreV51PrepareResult {
            events += "prepare"
            prepareCalls += 1
            return if (completed) CoreV51PrepareResult.ALREADY_COMPLETE else CoreV51PrepareResult.READY
        }

        override suspend fun stage(plan: CoreV51ImportPlan) {
            events += "stage"
            stageFailure?.let { throw it }
        }

        override suspend fun readActivationSnapshot(plan: CoreV51ImportPlan): CoreV51ActivationSnapshot {
            events += "read"
            return activationSnapshot(this.plan)
        }

        override suspend fun finalize(
            plan: CoreV51ImportPlan,
            activation: CoreV51ActivationEvidence,
            operationalStorage: CoreV51OperationalStorageBinding,
        ) {
            events += "finalize"
            finalizeCalls += 1
            finalizedOperationalIdentity = operationalStorage
            completed = true
        }
    }

    private companion object {
        val READY_INVENTORY = V51LegacySourceInventory.CORE_FOUNDATION_PRESENT
        val OPERATIONAL_IDENTITY = CoreV51OperationalStorageBinding(1, "v51-rebuild")
    }
}

private fun readyPlan(): CoreV51ImportPlan {
    val signer = SoftwareIdentitySigner.generate()
    return CoreV51ImportPlan.ready(
        CoreV51ImportPlan.Foundation(
            identity = CoreV51IdentityCommand(
                alias = "notisync.identity.v1",
                aliasVersion = 1,
                publicSpki = signer.publicKeySpki,
                backing = CoreV51IdentityBacking.TRUSTED_ENVIRONMENT,
                createdAt = 1,
            ),
            wrappingKey = CoreV51WrappingKeyCommand(
                alias = "notisync.kek.v1",
                aliasVersion = 1,
                backing = CoreV51IdentityBacking.TRUSTED_ENVIRONMENT,
                createdAt = 1,
            ),
            trust = null,
            epochs = listOf(
                CoreV51EpochCommand(
                    epoch = 1,
                    operationalSignerAlias = "notisync.operational.v1.epoch1",
                    operationalSignerAliasVersion = 1,
                    operationalSignerPublicSpki = byteArrayOf(2),
                    hpkePublicKeyset = byteArrayOf(3),
                    hpkePrivateKeysetWrapped = byteArrayOf(4),
                    backing = CoreV51OperationalBacking.TRUSTED_ENVIRONMENT,
                    lifecycle = CoreV51EpochLifecycle.ACTIVE,
                    antiRollbackFloor = 1,
                    activationAt = 0,
                    retirementAt = null,
                    createdAt = 1,
                ),
            ),
            authToken = null,
            transport = CoreV51TransportCommand(
                brokerUrl = "https://broker.example.test",
                groupId = null,
                fcmRouteRef = null,
                routeEpoch = 0,
                selfEpochActivatedAt = null,
            ),
            maintenance = CoreV51MaintenanceCommand(trustCleanupCompleted = false),
            currentEpoch = 1,
            skippedUnversionedHpkeFileCount = 0,
        ),
    )
}

private fun activationSnapshot(plan: CoreV51ImportPlan): CoreV51ActivationSnapshot {
    val foundation = requireNotNull(plan.foundation)
    return CoreV51ActivationSnapshot(
        planDigest = plan.targetContentDigest,
        identity = foundation.identity,
        wrappingKey = foundation.wrappingKey,
        epochs = foundation.epochs,
        authToken = foundation.authToken,
    )
}

private fun activationFor(snapshot: CoreV51ActivationSnapshot): CoreV51ActivationEvidence =
    CoreV51ActivationEvidence(
        planDigest = snapshot.planDigest,
        candidateDigest = snapshot.candidateDigest,
        identityClientId = ClientIds.derive(snapshot.identity.publicSpkiCopy()).value,
        identitySelfTestedAt = 1,
        wrappingKeySelfTestedAt = 2,
        epochEvidence = snapshot.epochs.map { epoch ->
            CoreV51EpochActivationEvidence(
                epoch = epoch.epoch,
                hpkePublicKeysetFingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(epoch.hpkePublicKeysetCopy()),
                operationalSignerSelfTestedAt = 3,
                hpkePairSelfTestedAt = 4,
            )
        },
        authTokenSelfTestedAt = snapshot.authToken?.let { 5 },
        validatedAt = 6,
    )
