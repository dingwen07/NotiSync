package net.extrawdw.apps.notisync.data.storage.runtime

import net.extrawdw.apps.notisync.data.storage.core.CoreTransportSnapshot
import net.extrawdw.apps.notisync.data.storage.core.ReplayFenceState
import net.extrawdw.apps.notisync.data.storage.core.validateOperationalStorageIncarnationId

/** Current Operational identity read directly from Room during application initialization. */
internal data class ObservedOperationalContinuityMarker(
    val operationalGeneration: Long,
    val storageIncarnationId: String,
    val postCutoverWriteAt: Long?,
) {
    init {
        require(operationalGeneration > 0) { "Observed Operational generation must be positive" }
        validateOperationalStorageIncarnationId(storageIncarnationId)
        require(postCutoverWriteAt == null || postCutoverWriteAt >= 0) {
            "Observed post-cutover write time must not be negative"
        }
    }
}

internal fun interface OperationalContinuityMarkerSource {
    suspend fun readMarker(): ObservedOperationalContinuityMarker?
}

/** One closed result for the only cross-database startup check that survives the readiness simplification. */
internal enum class OperationalContinuityValidation {
    VALID,
    CORE_TRANSPORT_MISSING,
    REPLAY_FENCE_REQUIRED,
    REPLAY_FENCE_ESTABLISHING,
    REPLAY_FENCE_BLOCKED,
    MARKER_MISSING,
    GENERATION_MISMATCH,
    INCARNATION_MISMATCH,
}

/**
 * Validates the production continuity pair directly. Import-origin evidence does not survive successful migration:
 * a valid Core transport is authoritative, and the Operational marker only has to match its generation/incarnation.
 * `postCutoverWriteAt` intentionally does not invalidate normal runtime; it only forbids destructive rebuild.
 */
internal class CoreOperationalContinuityValidator(
    private val transportSource: suspend () -> CoreTransportSnapshot?,
    private val markerSource: OperationalContinuityMarkerSource,
) {
    suspend fun validate(): OperationalContinuityValidation {
        val transport = transportSource()
            ?: return OperationalContinuityValidation.CORE_TRANSPORT_MISSING
        when (transport.replayFenceState) {
            ReplayFenceState.FENCE_REQUIRED -> return OperationalContinuityValidation.REPLAY_FENCE_REQUIRED
            ReplayFenceState.ESTABLISHING -> return OperationalContinuityValidation.REPLAY_FENCE_ESTABLISHING
            ReplayFenceState.BLOCKED -> return OperationalContinuityValidation.REPLAY_FENCE_BLOCKED
            ReplayFenceState.CONTINUITY_INTACT,
            ReplayFenceState.ESTABLISHED,
            -> Unit
        }
        val marker = markerSource.readMarker() ?: return OperationalContinuityValidation.MARKER_MISSING
        if (marker.operationalGeneration != transport.operationalGeneration) {
            return OperationalContinuityValidation.GENERATION_MISMATCH
        }
        if (marker.storageIncarnationId != transport.operationalIncarnationId) {
            return OperationalContinuityValidation.INCARNATION_MISMATCH
        }
        return OperationalContinuityValidation.VALID
    }
}
