package net.extrawdw.apps.notisync.data.continuity

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import net.extrawdw.apps.notisync.data.storage.operational.MaintenanceStateEntity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalProfileDao

/** Sole Room adapter for [OperationalContinuityRepository]. */
internal class RoomOperationalContinuityRepository(
    private val dao: OperationalProfileDao,
) : OperationalContinuityRepository {
    override fun observeMaintenance(): Flow<OperationalMaintenanceState?> =
        dao.observeMaintenance()
            .map { entity -> entity?.toDomain() }
            .catch { error ->
                if (error is CancellationException) throw error
                throw IllegalStateException("Persisted Operational maintenance state is invalid", error)
            }

    override suspend fun readMaintenance(): OperationalMaintenanceState? = try {
        dao.readMaintenance()?.toDomain()
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException("Persisted Operational maintenance state is invalid", error)
    }

    override suspend fun initializeIfPristine(
        initialization: OperationalContinuityInitialization,
    ): OperationalContinuityInitializationResult = when (
        dao.initializeIfPristine(
            operationalGeneration = initialization.operationalGeneration,
            storageIncarnationId = initialization.storageIncarnationId,
            updatedAt = initialization.initializedAt,
        )
    ) {
        OperationalProfileDao.MaintenancePristineInitializeResult.INSERTED ->
            OperationalContinuityInitializationResult.INSERTED
        OperationalProfileDao.MaintenancePristineInitializeResult.ALREADY_INITIALIZED ->
            OperationalContinuityInitializationResult.ALREADY_INITIALIZED
        OperationalProfileDao.MaintenancePristineInitializeResult.CONFLICT ->
            OperationalContinuityInitializationResult.CONFLICT
    }

    override suspend fun replaceMaintenance(state: OperationalMaintenanceState) {
        dao.replaceMaintenance(state.toEntity())
    }
}

private fun MaintenanceStateEntity.toDomain(): OperationalMaintenanceState = try {
    OperationalMaintenanceState(
        operationalGeneration = operationalGeneration,
        storageIncarnationId = storageIncarnationId,
        postCutoverWriteAt = postCutoverWriteAt,
        lastIntegrityCheckAt = lastIntegrityCheckAt,
        updatedAt = updatedAt,
    )
} catch (error: IllegalArgumentException) {
    throw IllegalStateException("Persisted Operational maintenance state is invalid", error)
}

private fun OperationalMaintenanceState.toEntity(): MaintenanceStateEntity = MaintenanceStateEntity(
    operationalGeneration = operationalGeneration,
    storageIncarnationId = storageIncarnationId,
    postCutoverWriteAt = postCutoverWriteAt,
    lastIntegrityCheckAt = lastIntegrityCheckAt,
    updatedAt = updatedAt,
)
