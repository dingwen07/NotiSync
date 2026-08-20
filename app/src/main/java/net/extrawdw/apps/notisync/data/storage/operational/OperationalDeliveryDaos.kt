package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

internal enum class RelayFinalizeResult {
    APPLIED,
    ALREADY_FINALIZED,
    LEGACY_RETAINED_NO_ACK,
    CONFLICT,
    STORAGE_CONTINUITY_MISMATCH,
}

internal enum class RelayHandledResolutionResult {
    EXACT_AUTHENTICATED,
    LEGACY_RETAINED_NO_ACK,
    MISSING,
    CONFLICT,
    STORAGE_CONTINUITY_MISMATCH,
}

/**
 * Durable handled evidence only. Live relay ITEM bytes are validated and streamed directly through
 * an owning database transaction; this DAO intentionally owns no inbox, retry, or ACK lifecycle.
 */
@Dao
internal abstract class RelayDao {
    @Query("SELECT * FROM message_dedup WHERE message_id = :messageId")
    abstract suspend fun findHandled(messageId: String): MessageDedupEntity?

    /**
     * Resolves redelivery evidence and its Operational incarnation in one read transaction. This
     * path never inserts evidence: a missing row must be applied again by its owning feature.
     */
    @Transaction
    open suspend fun resolveHandled(
        messageId: String,
        authenticatedFingerprint: ByteArray,
        expectedOperationalGeneration: Long,
        expectedStorageIncarnationId: String,
    ): RelayHandledResolutionResult {
        requireIdentifier(messageId, "handled message id")
        requireAuthenticatedFingerprint(authenticatedFingerprint, "handled message fingerprint")
        require(expectedOperationalGeneration > 0) { "expected operational generation must be positive" }
        requireStorageIncarnationId(expectedStorageIncarnationId)
        val maintenance = maintenanceForFinalize()
        if (
            maintenance?.operationalGeneration != expectedOperationalGeneration ||
            maintenance.storageIncarnationId != expectedStorageIncarnationId
        ) return RelayHandledResolutionResult.STORAGE_CONTINUITY_MISMATCH
        val handled = findHandled(messageId) ?: return RelayHandledResolutionResult.MISSING
        return when (handled.evidenceKind) {
            MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY ->
                RelayHandledResolutionResult.LEGACY_RETAINED_NO_ACK
            MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT -> if (
                handled.authenticatedFingerprint?.contentEquals(authenticatedFingerprint) == true
            ) {
                RelayHandledResolutionResult.EXACT_AUTHENTICATED
            } else {
                RelayHandledResolutionResult.CONFLICT
            }
        }
    }

    @Query("SELECT * FROM maintenance_state WHERE singleton_id = 1")
    protected abstract suspend fun maintenanceForFinalize(): MaintenanceStateEntity?

    @Query("SELECT * FROM activity_event WHERE event_id = :eventId")
    protected abstract suspend fun findActivityForFinalize(eventId: String): ActivityEventEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertHandledInternal(entity: MessageDedupEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertActivityInternal(entity: ActivityEventEntity): Long

    /**
     * Database-local finalization primitive used either directly or inside an owning feature's outer
     * write transaction. It never persists transient routing outcomes or an ACK retry lifecycle.
     */
    @Transaction
    open suspend fun finalizeHandled(
        handled: MessageDedupEntity,
        expectedOperationalGeneration: Long,
        expectedStorageIncarnationId: String,
        activity: ActivityEventEntity?,
    ): RelayFinalizeResult {
        handled.requireValid(allowLegacyMessageIdOnly = false)
        require(handled.evidenceKind == MessageDedupEvidenceKind.AUTHENTICATED_FINGERPRINT) {
            "new handled evidence must be authenticated"
        }
        require(expectedOperationalGeneration > 0) { "expected operational generation must be positive" }
        requireStorageIncarnationId(expectedStorageIncarnationId)
        activity?.requireValid()

        val maintenance = maintenanceForFinalize()
        if (
            maintenance?.operationalGeneration != expectedOperationalGeneration ||
            maintenance.storageIncarnationId != expectedStorageIncarnationId
        ) return RelayFinalizeResult.STORAGE_CONTINUITY_MISMATCH

        findHandled(handled.messageId)?.let { existing ->
            if (existing.evidenceKind == MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY) {
                return RelayFinalizeResult.LEGACY_RETAINED_NO_ACK
            }
            if (!existing.hasSameReplayIdentity(handled)) {
                return RelayFinalizeResult.CONFLICT
            }
            if (!activityMatchesExisting(activity)) return RelayFinalizeResult.CONFLICT
            return RelayFinalizeResult.ALREADY_FINALIZED
        }

        if (!activityCanBeInserted(activity)) return RelayFinalizeResult.CONFLICT
        insertHandledInternal(handled)
        activity?.let { candidate ->
            if (insertActivityInternal(candidate) == -1L) {
                check(findActivityForFinalize(candidate.eventId)?.hasSamePersistedProjection(candidate) == true) {
                    "activity projection changed after collision validation"
                }
            }
        }
        return RelayFinalizeResult.APPLIED
    }

    @Query("DELETE FROM message_dedup WHERE handled_at < :cutoff")
    abstract suspend fun pruneHandledBefore(cutoff: Long): Int

    @Transaction
    open suspend fun insertImportedHandled(entity: MessageDedupEntity): Boolean {
        entity.requireValid(allowLegacyMessageIdOnly = true)
        require(entity.evidenceKind == MessageDedupEvidenceKind.LEGACY_MESSAGE_ID_ONLY) {
            "cutover handled-message import must use legacy message-id-only evidence"
        }
        require(entity.authenticatedFingerprint == null) {
            "cutover handled-message import cannot synthesize a fingerprint"
        }
        findHandled(entity.messageId)?.let { current ->
            require(
                current.evidenceKind == entity.evidenceKind &&
                    current.authenticatedFingerprint == null &&
                    current.handledAt == entity.handledAt,
            ) { "imported handled-message row conflicts with existing target state" }
            return false
        }
        insertHandledInternal(entity)
        return true
    }

    private suspend fun activityMatchesExisting(activity: ActivityEventEntity?): Boolean =
        activity == null || findActivityForFinalize(activity.eventId)?.hasSamePersistedProjection(activity) == true

    private suspend fun activityCanBeInserted(activity: ActivityEventEntity?): Boolean =
        activity == null || findActivityForFinalize(activity.eventId)?.hasSamePersistedProjection(activity) != false

    private fun MessageDedupEntity.hasSameReplayIdentity(other: MessageDedupEntity): Boolean =
        messageId == other.messageId && evidenceKind == other.evidenceKind &&
            authenticatedFingerprint.contentEqualsNullable(other.authenticatedFingerprint)
}

internal enum class RelayBatchRecordResult { INSERTED, EXACT, CONFLICT }

/** Metadata-only scratch for detecting same-id/different-envelope conflicts within one live drain. */
@Dao
internal abstract class RelayBatchStageDao {
    @Query("SELECT * FROM relay_batch_stage WHERE message_id = :messageId")
    protected abstract suspend fun findInternal(messageId: String): RelayBatchStageEntity?

    suspend fun find(messageId: String): RelayBatchStageEntity? {
        requireIdentifier(messageId, "relay batch message id")
        return findInternal(messageId)?.also(RelayBatchStageEntity::requireValid)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertInternal(entity: RelayBatchStageEntity)

    @Query("UPDATE relay_batch_stage SET conflict = 1 WHERE message_id = :messageId AND conflict = 0")
    protected abstract suspend fun markConflictInternal(messageId: String): Int

    @Query("DELETE FROM relay_batch_stage")
    abstract suspend fun clearAtDrainBoundary(): Int

    @Query(
        "SELECT * FROM relay_batch_stage WHERE presentation_kind != :none " +
            "AND (:afterMessageId IS NULL OR message_id > :afterMessageId) " +
            "ORDER BY message_id LIMIT :limit",
    )
    protected abstract suspend fun presentationPageInternal(
        afterMessageId: String?,
        limit: Int,
        none: RelayBatchPresentationKind = RelayBatchPresentationKind.NONE,
    ): List<RelayBatchStageEntity>

    @Query(
        "SELECT * FROM relay_batch_stage WHERE presentation_kind = :none " +
            "AND (:afterMessageId IS NULL OR message_id > :afterMessageId) " +
            "ORDER BY message_id LIMIT :limit",
    )
    protected abstract suspend fun nonPresentationPageInternal(
        afterMessageId: String?,
        limit: Int,
        none: RelayBatchPresentationKind = RelayBatchPresentationKind.NONE,
    ): List<RelayBatchStageEntity>

    @Query(
        "DELETE FROM relay_batch_stage WHERE message_id = :messageId " +
            "AND authenticated_fingerprint = :authenticatedFingerprint AND conflict = :conflict " +
            "AND presentation_kind = :presentationKind",
    )
    protected abstract suspend fun deleteExactInternal(
        messageId: String,
        authenticatedFingerprint: ByteArray,
        conflict: Boolean,
        presentationKind: RelayBatchPresentationKind,
    ): Int

    @Transaction
    open suspend fun recordItem(
        messageId: String,
        authenticatedFingerprint: ByteArray,
        presentationKind: RelayBatchPresentationKind,
    ): RelayBatchRecordResult {
        val candidate = RelayBatchStageEntity(
            messageId = messageId,
            authenticatedFingerprint = authenticatedFingerprint.copyOf(),
            conflict = false,
            presentationKind = presentationKind,
        )
        candidate.requireValid()
        val current = find(messageId)
        if (current == null) {
            insertInternal(candidate)
            return RelayBatchRecordResult.INSERTED
        }
        current.requireValid()
        if (current.conflict) return RelayBatchRecordResult.CONFLICT
        // The authenticated envelope fingerprint is the drain-local identity. Preserve the first
        // presentation classification: the same bytes cannot legitimately acquire a new meaning,
        // and a caller recomputation must not mutate scratch evidence.
        if (current.authenticatedFingerprint.contentEquals(authenticatedFingerprint)) {
            return RelayBatchRecordResult.EXACT
        }
        check(markConflictInternal(messageId) == 1) { "relay batch conflict transition was lost" }
        return RelayBatchRecordResult.CONFLICT
    }

    /** Includes conflict-latched rows; callers must never present or ACK those rows. */
    suspend fun presentationPage(afterMessageId: String?, limit: Int): List<RelayBatchStageEntity> =
        readBoundedPage(afterMessageId, limit, ::presentationPageInternal)

    /** Includes conflict-latched rows; callers must continuity-resolve exact handled evidence before ACK. */
    suspend fun nonPresentationPage(afterMessageId: String?, limit: Int): List<RelayBatchStageEntity> =
        readBoundedPage(afterMessageId, limit, ::nonPresentationPageInternal)

    suspend fun deleteExact(expected: RelayBatchStageEntity): Boolean {
        expected.requireValid()
        return deleteExactInternal(
            expected.messageId,
            expected.authenticatedFingerprint,
            expected.conflict,
            expected.presentationKind,
        ) == 1
    }

    private suspend fun readBoundedPage(
        afterMessageId: String?,
        limit: Int,
        read: suspend (String?, Int, RelayBatchPresentationKind) -> List<RelayBatchStageEntity>,
    ): List<RelayBatchStageEntity> {
        afterMessageId?.let { requireIdentifier(it, "relay batch page cursor") }
        require(limit in 1..OperationalStorageLimits.RELAY_BATCH_PAGE_MAX_ROWS) {
            "relay batch page limit is outside its bound"
        }
        return read(afterMessageId, limit, RelayBatchPresentationKind.NONE).also { rows ->
            require(rows.size <= limit) { "relay batch page exceeded its bound" }
            rows.forEach(RelayBatchStageEntity::requireValid)
        }
    }
}

internal enum class MirrorPostResult { FIRST, NEWER, STALE }

@Dao
internal abstract class MirrorLifecycleDao : OperationalReceiptOwningDao() {
    @Query(
        "SELECT * FROM mirror_lifecycle WHERE source_client_id = :sourceClientId AND source_key = :sourceKey",
    )
    abstract suspend fun findLifecycle(sourceClientId: String, sourceKey: String): MirrorLifecycleEntity?

    @Upsert
    protected abstract suspend fun upsertLifecycle(entity: MirrorLifecycleEntity)

    @Transaction
    open suspend fun acceptPost(
        sourceClientId: String,
        sourceKey: String,
        postTime: Long,
        updatedAt: Long,
    ): MirrorPostResult {
        requireMirrorIdentity(sourceClientId, sourceKey)
        require(postTime > 0 && updatedAt > 0) { "mirror post timestamps are invalid" }
        val current = findLifecycle(sourceClientId, sourceKey)
        if (
            (current?.dismissedAt ?: Long.MIN_VALUE) >= postTime ||
            (current?.postTime ?: Long.MIN_VALUE) > postTime
        ) return MirrorPostResult.STALE
        upsertLifecycle(
            MirrorLifecycleEntity(
                sourceClientId = sourceClientId,
                sourceKey = sourceKey,
                postTime = maxOf(current?.postTime ?: Long.MIN_VALUE, postTime),
                dismissedAt = current?.dismissedAt?.takeIf { postTime <= it },
                updatedAt = updatedAt,
            ),
        )
        return if (current == null) MirrorPostResult.FIRST else MirrorPostResult.NEWER
    }

    /** Atomically records a mirror post outcome and its broker receipt evidence. */
    suspend fun acceptPostWithReceipt(
        sourceClientId: String,
        sourceKey: String,
        postTime: Long,
        updatedAt: Long,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult = runOwnedReceiptTransaction {
        acceptPostWithReceiptInternal(sourceClientId, sourceKey, postTime, updatedAt, receipt)
    }

    @Transaction
    protected open suspend fun acceptPostWithReceiptInternal(
        sourceClientId: String,
        sourceKey: String,
        postTime: Long,
        updatedAt: Long,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult {
        existingReceiptResult(receipt)?.let { return it }
        val disposition = when (acceptPost(sourceClientId, sourceKey, postTime, updatedAt)) {
            MirrorPostResult.FIRST,
            MirrorPostResult.NEWER -> OperationalReceiptDisposition.APPLIED
            MirrorPostResult.STALE -> OperationalReceiptDisposition.SUPERSEDED
        }
        return finalizeOwnedReceipt(
            receipt,
            disposition,
            persistActivity = disposition == OperationalReceiptDisposition.APPLIED && receipt.activity != null,
        )
    }

    @Transaction
    open suspend fun recordDismissal(
        sourceClientId: String,
        sourceKey: String,
        dismissedAt: Long,
        updatedAt: Long,
    ): Boolean {
        requireMirrorIdentity(sourceClientId, sourceKey)
        require(dismissedAt > 0 && updatedAt > 0) { "mirror dismissal timestamps are invalid" }
        val current = findLifecycle(sourceClientId, sourceKey)
        if ((current?.postTime ?: Long.MIN_VALUE) > dismissedAt) return false
        upsertLifecycle(
            MirrorLifecycleEntity(
                sourceClientId = sourceClientId,
                sourceKey = sourceKey,
                postTime = current?.postTime,
                dismissedAt = maxOf(current?.dismissedAt ?: Long.MIN_VALUE, dismissedAt),
                updatedAt = updatedAt,
            ),
        )
        return true
    }

    /** Atomically records a mirror dismissal outcome and its broker receipt evidence. */
    suspend fun recordDismissalWithReceipt(
        sourceClientId: String,
        sourceKey: String,
        dismissedAt: Long,
        updatedAt: Long,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult = runOwnedReceiptTransaction {
        recordDismissalWithReceiptInternal(sourceClientId, sourceKey, dismissedAt, updatedAt, receipt)
    }

    @Transaction
    protected open suspend fun recordDismissalWithReceiptInternal(
        sourceClientId: String,
        sourceKey: String,
        dismissedAt: Long,
        updatedAt: Long,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult {
        existingReceiptResult(receipt)?.let { return it }
        val disposition = if (recordDismissal(sourceClientId, sourceKey, dismissedAt, updatedAt)) {
            OperationalReceiptDisposition.APPLIED
        } else {
            OperationalReceiptDisposition.SUPERSEDED
        }
        return finalizeOwnedReceipt(
            receipt,
            disposition,
            persistActivity = disposition == OperationalReceiptDisposition.APPLIED && receipt.activity != null,
        )
    }

    /**
     * Commits receipt evidence only after the notification action performer has reported success.
     *
     * The external Android action cannot be made part of SQLite's transaction. Callers therefore
     * retain at-least-once effect semantics across a crash between the effect and this commit.
     */
    suspend fun finalizeNotificationActionReceipt(
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult {
        requireNotNull(receipt.activity) { "successful notification action requires deterministic Activity" }
        return runOwnedReceiptTransaction { finalizeNotificationActionReceiptInternal(receipt) }
    }

    @Transaction
    protected open suspend fun finalizeNotificationActionReceiptInternal(
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult {
        existingReceiptResult(receipt)?.let { return it }
        return finalizeOwnedReceipt(
            receipt,
            OperationalReceiptDisposition.APPLIED,
            persistActivity = true,
        )
    }

    @Query("DELETE FROM mirror_lifecycle WHERE updated_at < :cutoff")
    abstract suspend fun pruneLifecycleBefore(cutoff: Long): Int

    private fun requireMirrorIdentity(sourceClientId: String, sourceKey: String) {
        requireIdentifier(sourceClientId, "mirror source client id")
        requireIdentifier(sourceKey, "mirror source key")
    }
}

internal fun ActivityEventEntity.hasSamePersistedProjection(other: ActivityEventEntity): Boolean =
    eventId == other.eventId && occurredAt == other.occurredAt && recordedAt == other.recordedAt &&
        feature == other.feature && semanticAction == other.semanticAction && direction == other.direction &&
        outcome == other.outcome && peerClientId == other.peerClientId && correlationId == other.correlationId &&
        deliveryMode == other.deliveryMode && renderArgsVersion == other.renderArgsVersion &&
        renderArgs.contentEquals(other.renderArgs) &&
        coalescingKeyToken.contentEqualsNullable(other.coalescingKeyToken) &&
        coalescedCount == other.coalescedCount

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)

internal enum class RunCompareUpsertResult {
    INSERTED,
    UPDATED,
    EQUAL,
    OLDER,
    CONFLICT,
    CAPACITY_EXCEEDED,
}

internal data class RunStorageCandidate(
    @androidx.room3.ColumnInfo(name = "host_client_id")
    val hostClientId: String,
    @androidx.room3.ColumnInfo(name = "run_id")
    val runId: String,
    @androidx.room3.ColumnInfo(name = "payload_bytes")
    val payloadBytes: Long,
)

/**
 * An owned, validated command prepared before Room opens the Run write transaction.
 *
 * Snapshotting is synchronous. Callers must not mutate input arrays concurrently while [prepare]
 * copies them; mutations after [prepare] returns cannot affect the persisted bytes.
 */
internal class PreparedRunUpsert private constructor(
    val candidate: RunStateEntity,
    val activity: ActivityEventEntity?,
) {
    companion object {
        fun prepare(candidate: RunStateEntity, activity: ActivityEventEntity?): PreparedRunUpsert {
            val ownedCandidate = candidate.copy(
                payload = candidate.payload.copyOf(),
                payloadDigest = candidate.payloadDigest.copyOf(),
            )
            val ownedActivity = activity?.copy(
                renderArgs = activity.renderArgs.copyOf(),
                coalescingKeyToken = activity.coalescingKeyToken?.copyOf(),
            )
            ownedCandidate.requireValidRunState()
            ownedActivity?.requireValid()
            return PreparedRunUpsert(ownedCandidate, ownedActivity)
        }
    }
}

@Dao
internal abstract class RunDao : OperationalReceiptOwningDao() {
    @Query("SELECT * FROM run_state WHERE host_client_id = :hostClientId AND run_id = :runId")
    abstract suspend fun find(hostClientId: String, runId: String): RunStateEntity?

    @Query("SELECT * FROM run_state ORDER BY active DESC, updated_at DESC, host_client_id ASC, run_id ASC")
    abstract fun observeAll(): Flow<List<RunStateEntity>>

    @Upsert
    protected abstract suspend fun upsertInternal(entity: RunStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertActivity(entity: ActivityEventEntity): Long

    @Query("SELECT COALESCE(SUM(length(payload)), 0) FROM run_state")
    protected abstract suspend fun totalStorageBytes(): Long

    @Query(
        "SELECT host_client_id, run_id, length(payload) AS payload_bytes FROM run_state " +
            "WHERE active = 0 AND NOT (host_client_id = :hostClientId AND run_id = :runId) " +
            "ORDER BY received_at ASC, updated_at ASC, host_client_id ASC, run_id ASC LIMIT :limit",
    )
    protected abstract suspend fun oldestCompletedExcluding(
        hostClientId: String,
        runId: String,
        limit: Int,
    ): List<RunStorageCandidate>

    @Query(
        "SELECT host_client_id, run_id, length(payload) AS payload_bytes FROM run_state WHERE active = 0 " +
            "ORDER BY received_at ASC, updated_at ASC, host_client_id ASC, run_id ASC LIMIT :limit",
    )
    protected abstract suspend fun oldestCompletedForBudget(limit: Int): List<RunStorageCandidate>

    @Query("DELETE FROM run_state WHERE host_client_id = :hostClientId AND run_id = :runId AND active = 0")
    protected abstract suspend fun deleteCompleted(hostClientId: String, runId: String): Int

    suspend fun compareAndUpsert(
        candidate: RunStateEntity,
        activity: ActivityEventEntity?,
    ): RunCompareUpsertResult = compareAndUpsertPrepared(PreparedRunUpsert.prepare(candidate, activity))

    /**
     * Validates/copies the potentially large Run projection before opening the Room transaction,
     * then atomically commits the Run outcome, deterministic Activity, and handled evidence.
     */
    suspend fun compareAndUpsertWithReceipt(
        candidate: RunStateEntity,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult {
        val command = PreparedRunUpsert.prepare(candidate, activity = null)
        return runOwnedReceiptTransaction { compareAndUpsertPreparedWithReceipt(command, receipt) }
    }

    @Transaction
    protected open suspend fun compareAndUpsertPreparedWithReceipt(
        command: PreparedRunUpsert,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult {
        existingReceiptResult(receipt)?.let { return it }
        return when (compareAndUpsertPrepared(command)) {
            RunCompareUpsertResult.INSERTED,
            RunCompareUpsertResult.UPDATED -> finalizeOwnedReceipt(
                receipt,
                OperationalReceiptDisposition.APPLIED,
                persistActivity = receipt.activity != null,
            )
            RunCompareUpsertResult.EQUAL ->
                finalizeOwnedReceipt(
                    receipt,
                    OperationalReceiptDisposition.DUPLICATE,
                    persistActivity = false,
                )
            RunCompareUpsertResult.OLDER ->
                finalizeOwnedReceipt(
                    receipt,
                    OperationalReceiptDisposition.SUPERSEDED,
                    persistActivity = false,
                )
            RunCompareUpsertResult.CONFLICT -> OperationalFeatureCommitResult.ConflictNoAck
            RunCompareUpsertResult.CAPACITY_EXCEEDED ->
                OperationalFeatureCommitResult.RetryRequired("run_storage_capacity")
        }
    }

    /** Database-local compare/prune/write work only; large projection validation is already complete. */
    @Transaction
    protected open suspend fun compareAndUpsertPrepared(command: PreparedRunUpsert): RunCompareUpsertResult {
        val candidate = command.candidate
        val activity = command.activity
        val current = find(candidate.hostClientId, candidate.runId)
        if (current != null) {
            if (candidate.revision < current.revision) return RunCompareUpsertResult.OLDER
            if (candidate.revision == current.revision) {
                return if (
                    candidate.payloadDigest.contentEquals(current.payloadDigest) &&
                    candidate.payload.contentEquals(current.payload)
                ) {
                    RunCompareUpsertResult.EQUAL
                } else {
                    RunCompareUpsertResult.CONFLICT
                }
            }
        }
        val projectedBytes = totalStorageBytes() - (current?.payload?.size?.toLong() ?: 0L) +
            candidate.payload.size.toLong()
        val projectedCompletedCount = completedCount() -
            (if (current?.active == false) 1 else 0) +
            (if (candidate.active) 0 else 1)
        val retentionVictims = selectRetentionVictims(
            projectedBytes = projectedBytes,
            projectedCompletedCount = projectedCompletedCount,
            excludedHostClientId = candidate.hostClientId,
            excludedRunId = candidate.runId,
        ) ?: return RunCompareUpsertResult.CAPACITY_EXCEEDED
        retentionVictims.forEach { victim ->
            check(deleteCompleted(victim.hostClientId, victim.runId) == 1) {
                "Run retention pruning lost a selected completed row"
            }
        }
        upsertInternal(
            candidate.copy(
                presentedRevision = current?.presentedRevision ?: candidate.presentedRevision,
            ),
        )
        activity?.let { insertActivity(it) }
        return if (current == null) RunCompareUpsertResult.INSERTED else RunCompareUpsertResult.UPDATED
    }

    @Query(
        "UPDATE run_state SET presented_revision = :revision " +
            "WHERE host_client_id = :hostClientId AND run_id = :runId " +
            "AND revision >= :revision AND presented_revision < :revision",
    )
    abstract suspend fun markPresented(hostClientId: String, runId: String, revision: Long): Int

    @Query(
        "UPDATE run_state SET active = 0, presented_revision = revision " +
            "WHERE host_client_id = :hostClientId AND run_id = :runId AND active = 1",
    )
    abstract suspend fun markInactive(hostClientId: String, runId: String): Int

    @Query("DELETE FROM run_state WHERE active = 0")
    abstract suspend fun clearHistory(): Int

    @Query(
        "UPDATE run_state SET active = 0, presented_revision = revision " +
            "WHERE active = 1 AND received_at < :cutoff",
    )
    abstract suspend fun markStaleActiveInactive(cutoff: Long): Int

    @Query(
        "DELETE FROM run_state WHERE host_client_id || char(0) || run_id IN (" +
            "SELECT host_client_id || char(0) || run_id FROM run_state " +
            "WHERE active = 0 AND received_at < :cutoff " +
            "ORDER BY received_at ASC, updated_at ASC LIMIT :limit)",
    )
    protected abstract suspend fun pruneCompletedBefore(cutoff: Long, limit: Int): Int

    @Query("SELECT COUNT(*) FROM run_state WHERE active = 0")
    protected abstract suspend fun completedCount(): Int

    @Query(
        "DELETE FROM run_state WHERE host_client_id || char(0) || run_id IN (" +
            "SELECT host_client_id || char(0) || run_id FROM run_state WHERE active = 0 " +
            "ORDER BY received_at ASC, updated_at ASC, host_client_id ASC, run_id ASC LIMIT :limit)",
    )
    protected abstract suspend fun pruneOldestCompleted(limit: Int): Int

    @Transaction
    open suspend fun pruneBatch(now: Long): Int {
        require(now > 0) { "Run prune time must be positive" }
        markStaleActiveInactive(now - OperationalRetention.RUN_ACTIVE_STALE_AFTER_MILLIS)
        var removed = pruneCompletedBefore(
            cutoff = now - OperationalRetention.RUN_COMPLETED_RETENTION_MILLIS,
            limit = OperationalRetention.RUN_PRUNE_BATCH_SIZE,
        )
        val overflow = (completedCount() - OperationalRetention.RUN_MAX_COMPLETED_ROWS).coerceAtLeast(0)
        val remainingBatch = OperationalRetention.RUN_PRUNE_BATCH_SIZE - removed
        if (overflow > 0 && remainingBatch > 0) {
            removed += pruneOldestCompleted(minOf(overflow, remainingBatch))
        }
        var storageBytes = totalStorageBytes()
        val storageVictims = oldestCompletedForBudget(
            OperationalRetention.RUN_PRUNE_BATCH_SIZE - removed,
        )
        for (victim in storageVictims) {
            if (storageBytes <= OperationalRetention.RUN_MAX_STORAGE_BYTES) break
            if (deleteCompleted(victim.hostClientId, victim.runId) == 1) {
                removed++
                storageBytes -= victim.payloadBytes
            }
        }
        return removed
    }

    /** Protects the just-applied key while atomically satisfying both retained-history bounds. */
    private suspend fun selectRetentionVictims(
        projectedBytes: Long,
        projectedCompletedCount: Int,
        excludedHostClientId: String,
        excludedRunId: String,
    ): List<RunStorageCandidate>? {
        if (
            projectedBytes <= OperationalRetention.RUN_MAX_STORAGE_BYTES &&
            projectedCompletedCount <= OperationalRetention.RUN_MAX_COMPLETED_ROWS
        ) return emptyList()
        var retainedBytes = projectedBytes
        var retainedCompletedCount = projectedCompletedCount
        val selected = mutableListOf<RunStorageCandidate>()
        for (
            candidate in oldestCompletedExcluding(
                excludedHostClientId,
                excludedRunId,
                OperationalRetention.RUN_PRUNE_BATCH_SIZE,
            )
        ) {
            selected += candidate
            retainedBytes -= candidate.payloadBytes
            retainedCompletedCount--
            if (
                retainedBytes <= OperationalRetention.RUN_MAX_STORAGE_BYTES &&
                retainedCompletedCount <= OperationalRetention.RUN_MAX_COMPLETED_ROWS
            ) return selected
        }
        return null
    }
}

private fun RunStateEntity.requireValidRunState() {
    requireIdentifier(hostClientId, "Run host client id")
    requireIdentifier(runId, "Run id")
    require(revision > 0 && (presentedRevision == -1L || presentedRevision in 0..revision)) {
        "Run revisions are invalid"
    }
    require(!active || phase == RunPhaseToken.RUNNING || phase == RunPhaseToken.BLOCKED) {
        "Run active projection contradicts its phase"
    }
    require(updatedAt > 0 && receivedAt > 0) { "Run timestamps must be positive" }
    require(endedAt == null || endedAt > 0) { "Run end time is invalid" }
    require(payload.isNotEmpty()) { "Run payload must not be empty" }
    require(payload.size.toLong() <= OperationalRetention.RUN_MAX_STORAGE_BYTES) {
        "Run payload exceeds the total storage budget"
    }
    requireSha256Projection(payload, payloadDigest, "Run payload digest")
}
