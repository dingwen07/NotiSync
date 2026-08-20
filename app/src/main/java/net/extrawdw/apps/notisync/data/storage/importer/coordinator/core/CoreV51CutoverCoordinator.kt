package net.extrawdw.apps.notisync.data.storage.importer.coordinator.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ActivationGate
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51FailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51ImportTarget
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51OperationalRebuildStep
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51PlanSource
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.CoreV51PrepareResult
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.V51LegacySourceInventorySource
import net.extrawdw.apps.notisync.data.storage.importer.target.core.model.V51LegacySourceInventory

/**
 * One process-local owner of the single disposable v51 cutover.
 *
 * Ordering is deliberate: completed Core authority short-circuits without touching legacy; otherwise composition
 * captures the complete app-wide inventory, Core atomically purges disposable projections, Operational is rebuilt
 * and verified, and only then are Core candidates staged, self-tested, and atomically promoted with transport as
 * the completion flag. Legacy inputs remain read-only and retained throughout, so no durable import attempt exists.
 */
internal class CoreV51CutoverCoordinator(
    private val source: CoreV51PlanSource,
    private val target: CoreV51ImportTarget,
    private val activationGate: CoreV51ActivationGate,
) {
    private val mutex = Mutex()

    suspend fun run(
        inventorySource: V51LegacySourceInventorySource,
        operationalRebuild: CoreV51OperationalRebuildStep,
    ): CoreV51CutoverResult = mutex.withLock {
        if (importerCall { target.hasCompletedTransport() }) {
            importerCall { target.validateCompletedTransport() }
            return@withLock CoreV51CutoverResult.ALREADY_COMPLETE
        }

        // Inventory capture is read-only. No target write or destructive rebuild may precede this complete snapshot.
        val sourceInventory = importerCall { inventorySource.capture() }
        when (sourceInventory) {
            V51LegacySourceInventory.ALL_ABSENT -> return@withLock CoreV51CutoverResult.SOURCE_ABSENT
            V51LegacySourceInventory.RECOVERY_REQUIRED -> throw CoreV51ImportFailure(
                CoreV51FailureDisposition.BLOCKED,
                "cutover_inventory_recovery_required",
            )
            V51LegacySourceInventory.CORE_FOUNDATION_PRESENT -> Unit
        }

        try {
            when (target.prepareForRebuild()) {
                CoreV51PrepareResult.ALREADY_COMPLETE -> {
                    target.validateCompletedTransport()
                    return@withLock CoreV51CutoverResult.ALREADY_COMPLETE
                }
                CoreV51PrepareResult.KEYSTORE_RECOVERY_REQUIRED -> throw CoreV51ImportFailure(
                    CoreV51FailureDisposition.BLOCKED,
                    "keystore_operation_present",
                )
                CoreV51PrepareResult.READY -> Unit
            }

            // This callback is the only integration seam for Operational. Core was purged before it can write.
            val operationalStorage = operationalRebuild.rebuildAndVerify()
            val plan = source.readPlan()
            if (plan.isAbsent) {
                throw CoreV51ImportFailure(
                    CoreV51FailureDisposition.BLOCKED,
                    "core_source_inventory_mismatch",
                )
            }

            target.stage(plan)
            val persisted = target.readActivationSnapshot(plan)
            val activation = activationGate.validate(persisted)
            if (activation.planDigest != plan.targetContentDigest ||
                activation.candidateDigest != persisted.candidateDigest
            ) {
                throw CoreV51ImportFailure(CoreV51FailureDisposition.BLOCKED, "activation_evidence_mismatch")
            }
            target.finalize(plan, activation, operationalStorage)
            target.validateCompletedTransport()
            CoreV51CutoverResult.IMPORTED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CoreV51ImportFailure) {
            throw failure
        } catch (failure: Exception) {
            throw CoreV51ImportFailure(CoreV51FailureDisposition.BLOCKED, "unexpected_failure", failure)
        }
    }

    private suspend fun <T> importerCall(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CoreV51ImportFailure) {
        throw failure
    } catch (failure: Exception) {
        throw CoreV51ImportFailure(CoreV51FailureDisposition.BLOCKED, "unexpected_failure", failure)
    }
}
