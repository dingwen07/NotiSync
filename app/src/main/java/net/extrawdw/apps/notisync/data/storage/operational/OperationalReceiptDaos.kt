package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/** ACK-safe semantic outcome produced only after an owning Operational transaction commits. */
internal enum class OperationalReceiptDisposition {
    APPLIED,
    DUPLICATE,
    SUPERSEDED,
}

/** Closed result shared by the exact feature-owned receipt commands. */
internal sealed interface OperationalFeatureCommitResult {
    data class AcknowledgementReady(
        val disposition: OperationalReceiptDisposition,
    ) : OperationalFeatureCommitResult

    data class RetryRequired(val errorCode: String) : OperationalFeatureCommitResult {
        init {
            requireCode(errorCode, "feature receipt retry code")
        }
    }

    data class SecurityBlocked(val errorCode: String) : OperationalFeatureCommitResult {
        init {
            requireCode(errorCode, "feature receipt security code")
        }
    }

    data object LegacyRetainedNoAck : OperationalFeatureCommitResult
    data object ConflictNoAck : OperationalFeatureCommitResult
    data object StorageContinuityMismatch : OperationalFeatureCommitResult
}

/**
 * Defensively owned receipt evidence prepared before a Room write transaction starts.
 *
 * [MessageDedupEntity.handledAt] is receipt chronology, not replay identity. Exact replay is the
 * message ID plus authenticated fingerprint and, when present, the deterministic Activity row.
 * Owner commands persist that Activity only for a meaningful applied transition; semantic
 * duplicate/superseded traffic records handled evidence without adding timeline noise.
 */
internal class PreparedOperationalReceipt private constructor(
    internal val handled: MessageDedupEntity,
    internal val expectedOperationalGeneration: Long,
    internal val expectedStorageIncarnationId: String,
    internal val activity: ActivityEventEntity?,
) {
    companion object {
        fun prepare(
            handled: MessageDedupEntity,
            expectedOperationalGeneration: Long,
            expectedStorageIncarnationId: String,
            activity: ActivityEventEntity?,
        ): PreparedOperationalReceipt {
            val ownedHandled = handled.copy(
                authenticatedFingerprint = handled.authenticatedFingerprint?.copyOf(),
            )
            val ownedActivity = activity?.copy(
                renderArgs = activity.renderArgs.copyOf(),
                coalescingKeyToken = activity.coalescingKeyToken?.copyOf(),
            )
            ownedHandled.requireValid(allowLegacyMessageIdOnly = false)
            require(ownedHandled.evidenceKind == MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT) {
                "feature receipt evidence must be authenticated"
            }
            require(expectedOperationalGeneration > 0) {
                "expected Operational generation must be positive"
            }
            requireStorageIncarnationId(expectedStorageIncarnationId)
            ownedActivity?.requireValid()
            return PreparedOperationalReceipt(
                handled = ownedHandled,
                expectedOperationalGeneration = expectedOperationalGeneration,
                expectedStorageIncarnationId = expectedStorageIncarnationId,
                activity = ownedActivity,
            )
        }
    }
}

private enum class OperationalReceiptPreflight {
    MISSING,
    ALREADY_FINALIZED,
    LEGACY_RETAINED,
    CONFLICT,
    STORAGE_CONTINUITY_MISMATCH,
}

private class OperationalReceiptActivityConflict : RuntimeException()

/**
 * Shared SQL primitive inherited by exact feature DAOs.
 *
 * A subclass exposes only named owner commands. This base deliberately has no public generic
 * transaction entry point, so callers cannot separate a feature mutation from its receipt.
 */
internal abstract class OperationalReceiptOwningDao {
    @Query("SELECT * FROM maintenance_state WHERE singleton_id = 1")
    protected abstract suspend fun maintenanceForOwnedReceipt(): MaintenanceStateEntity?

    @Query("SELECT * FROM message_dedup WHERE message_id = :messageId")
    protected abstract suspend fun handledForOwnedReceipt(messageId: String): MessageDedupEntity?

    @Query("SELECT * FROM activity_event WHERE event_id = :eventId")
    protected abstract suspend fun activityForOwnedReceipt(eventId: String): ActivityEventEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOwnedHandled(entity: MessageDedupEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOwnedActivity(entity: ActivityEventEntity): Long

    /** Returns a terminal result for existing/unsafe evidence, or null when mutation may proceed. */
    protected suspend fun existingReceiptResult(
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult? = when (preflight(receipt)) {
        OperationalReceiptPreflight.MISSING -> null
        OperationalReceiptPreflight.ALREADY_FINALIZED ->
            OperationalFeatureCommitResult.AcknowledgementReady(OperationalReceiptDisposition.DUPLICATE)
        OperationalReceiptPreflight.LEGACY_RETAINED -> OperationalFeatureCommitResult.LegacyRetainedNoAck
        OperationalReceiptPreflight.CONFLICT -> OperationalFeatureCommitResult.ConflictNoAck
        OperationalReceiptPreflight.STORAGE_CONTINUITY_MISMATCH ->
            OperationalFeatureCommitResult.StorageContinuityMismatch
    }

    /** Maps only the internal rollback signal after Room has unwound the owning transaction. */
    protected suspend fun runOwnedReceiptTransaction(
        transaction: suspend () -> OperationalFeatureCommitResult,
    ): OperationalFeatureCommitResult = try {
        transaction()
    } catch (_: OperationalReceiptActivityConflict) {
        OperationalFeatureCommitResult.ConflictNoAck
    }

    /** Must be called only after the owner mutation has reached an ACK-safe durable outcome. */
    protected suspend fun finalizeOwnedReceipt(
        receipt: PreparedOperationalReceipt,
        disposition: OperationalReceiptDisposition,
        persistActivity: Boolean,
    ): OperationalFeatureCommitResult.AcknowledgementReady {
        check(handledForOwnedReceipt(receipt.handled.messageId) == null) {
            "owned receipt changed after transaction preflight"
        }
        val activity = if (persistActivity) {
            requireNotNull(receipt.activity) { "applied feature receipt requires deterministic Activity" }
        } else {
            null
        }
        activity?.let { candidate ->
            val existing = activityForOwnedReceipt(candidate.eventId)
            if (existing != null && !existing.hasSamePersistedProjection(candidate)) {
                throw OperationalReceiptActivityConflict()
            }
        }
        insertOwnedHandled(receipt.handled)
        activity?.let { candidate ->
            if (insertOwnedActivity(candidate) == -1L) {
                if (activityForOwnedReceipt(candidate.eventId)?.hasSamePersistedProjection(candidate) != true) {
                    throw OperationalReceiptActivityConflict()
                }
            }
        }
        return OperationalFeatureCommitResult.AcknowledgementReady(disposition)
    }

    private suspend fun preflight(receipt: PreparedOperationalReceipt): OperationalReceiptPreflight {
        val maintenance = maintenanceForOwnedReceipt()
        if (
            maintenance?.operationalGeneration != receipt.expectedOperationalGeneration ||
            maintenance.storageIncarnationId != receipt.expectedStorageIncarnationId
        ) return OperationalReceiptPreflight.STORAGE_CONTINUITY_MISMATCH

        val existingHandled = handledForOwnedReceipt(receipt.handled.messageId)
        if (existingHandled != null) {
            if (existingHandled.evidenceKind == MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY) {
                return OperationalReceiptPreflight.LEGACY_RETAINED
            }
            if (
                existingHandled.evidenceKind != MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT ||
                existingHandled.authenticatedFingerprint?.contentEquals(
                    requireNotNull(receipt.handled.authenticatedFingerprint),
                ) != true
            ) return OperationalReceiptPreflight.CONFLICT
            val candidateActivity = receipt.activity
            if (candidateActivity != null) {
                val existingActivity = activityForOwnedReceipt(candidateActivity.eventId)
                if (existingActivity != null && !existingActivity.hasSamePersistedProjection(candidateActivity)) {
                    return OperationalReceiptPreflight.CONFLICT
                }
            }
            return OperationalReceiptPreflight.ALREADY_FINALIZED
        }

        return OperationalReceiptPreflight.MISSING
    }
}
