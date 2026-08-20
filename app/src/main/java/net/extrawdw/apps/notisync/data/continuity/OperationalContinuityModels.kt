package net.extrawdw.apps.notisync.data.continuity

import kotlinx.coroutines.flow.Flow

/**
 * Immutable domain projection of the Operational database's continuity marker and maintenance
 * evidence. The generation and storage incarnation are immutable after initialization; only the
 * nullable evidence timestamps may advance.
 */
data class OperationalMaintenanceState(
    val operationalGeneration: Long,
    val storageIncarnationId: String,
    val postCutoverWriteAt: Long?,
    val lastIntegrityCheckAt: Long?,
    val updatedAt: Long,
) {
    init {
        require(operationalGeneration > 0) { "operational generation must be positive" }
        requireStorageIncarnationId(storageIncarnationId)
        require(postCutoverWriteAt == null || postCutoverWriteAt > 0) {
            "post-cutover write timestamp must be positive"
        }
        require(lastIntegrityCheckAt == null || lastIntegrityCheckAt > 0) {
            "integrity-check timestamp must be positive"
        }
        require(updatedAt > 0) { "maintenance update timestamp must be positive" }
    }
}

/** Immutable input for the one-time pristine Operational marker command. */
data class OperationalContinuityInitialization(
    val operationalGeneration: Long,
    val storageIncarnationId: String,
    val initializedAt: Long,
) {
    init {
        require(operationalGeneration > 0) { "operational generation must be positive" }
        requireStorageIncarnationId(storageIncarnationId)
        require(initializedAt > 0) { "continuity initialization timestamp must be positive" }
    }
}

enum class OperationalContinuityInitializationResult {
    INSERTED,
    ALREADY_INITIALIZED,
    CONFLICT,
}

/** Sole domain owner of Operational continuity and maintenance evidence. */
interface OperationalContinuityRepository {
    fun observeMaintenance(): Flow<OperationalMaintenanceState?>

    suspend fun readMaintenance(): OperationalMaintenanceState?

    suspend fun initializeIfPristine(
        initialization: OperationalContinuityInitialization,
    ): OperationalContinuityInitializationResult

    suspend fun replaceMaintenance(state: OperationalMaintenanceState)
}

private fun requireStorageIncarnationId(value: String) {
    require(value.isNotBlank()) { "storage incarnation id must not be blank" }
    require(value.length <= MAX_STORAGE_INCARNATION_ID_CHARS) {
        "storage incarnation id is too long"
    }
    require(value.all { character ->
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character == '_' || character == '-' || character == '.'
    }) { "storage incarnation id contains unsupported characters" }
}

private const val MAX_STORAGE_INCARNATION_ID_CHARS = 128
