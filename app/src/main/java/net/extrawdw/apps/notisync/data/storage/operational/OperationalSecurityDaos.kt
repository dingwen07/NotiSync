package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

internal enum class SealAcceptResult {
    STORED,
    DUPLICATE,
    CONFLICT,
    RATE_LIMITED,
}

internal data class SealOutcomeTransition(
    val requestId: String,
    val outcome: SealRequestOutcome,
    val decidedAt: Long,
    /** Present only for an approved result whose exact signed bytes cannot be reconstructed. */
    val responseCustody: SealResponseCustodyEntity?,
    val activity: ActivityEventEntity?,
)

internal enum class SealResponsePrepareResult { UPDATED, ALREADY_PREPARED, NOT_FOUND, STALE, CONFLICT }

internal enum class SealResponseCompleteResult { SENT, ALREADY_SENT, NOT_READY, CONFLICT }

@Dao
internal abstract class SealDao : OperationalReceiptOwningDao() {
    @Query("SELECT * FROM seal_enrollment WHERE singleton_id = 1")
    abstract fun observeEnrollment(): Flow<SealEnrollmentEntity?>

    @Query("SELECT * FROM seal_enrollment WHERE singleton_id = 1")
    abstract suspend fun readEnrollment(): SealEnrollmentEntity?

    @Query("SELECT * FROM seal_enrollment_protected WHERE singleton_id = 1")
    abstract suspend fun readEnrollmentProtected(): SealEnrollmentProtectedEntity?

    @Query("SELECT * FROM seal_request ORDER BY updated_at DESC, request_id ASC LIMIT :limit")
    abstract fun observeHistory(limit: Int = OperationalRetention.SEAL_MAX_HISTORY_ROWS): Flow<List<SealRequestEntity>>

    @Query("SELECT * FROM seal_request WHERE request_id = :requestId")
    abstract suspend fun findRequest(requestId: String): SealRequestEntity?

    @Query("SELECT * FROM seal_pending_payload WHERE request_id = :requestId")
    abstract suspend fun findPendingPayload(requestId: String): SealPendingPayloadEntity?

    @Query("SELECT * FROM seal_response_custody WHERE request_id = :requestId")
    abstract suspend fun findResponseCustody(requestId: String): SealResponseCustodyEntity?

    @Upsert
    protected abstract suspend fun upsertEnrollmentInternal(entity: SealEnrollmentEntity)

    @Upsert
    protected abstract suspend fun upsertEnrollmentProtectedInternal(entity: SealEnrollmentProtectedEntity)

    @Query("DELETE FROM seal_enrollment WHERE singleton_id = 1")
    protected abstract suspend fun deleteEnrollmentInternal(): Int

    @Query("DELETE FROM seal_enrollment_protected WHERE singleton_id = 1")
    protected abstract suspend fun deleteEnrollmentProtectedInternal(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRequestInternal(entity: SealRequestEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPendingPayloadInternal(entity: SealPendingPayloadEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertResponseCustodyInternal(entity: SealResponseCustodyEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertActivityInternal(entity: ActivityEventEntity): Long

    @Query("DELETE FROM seal_pending_payload WHERE request_id = :requestId")
    protected abstract suspend fun deletePendingPayloadInternal(requestId: String): Int

    @Query("DELETE FROM seal_response_custody WHERE request_id = :requestId")
    protected abstract suspend fun deleteResponseCustodyInternal(requestId: String): Int

    @Query(
        "SELECT COUNT(*) FROM seal_request WHERE state IN (:pending, :approved, :provider) " +
            "AND expires_at >= :now",
    )
    protected abstract suspend fun activeCount(
        now: Long,
        pending: SealRequestState = SealRequestState.PENDING_REVIEW,
        approved: SealRequestState = SealRequestState.USER_APPROVED,
        provider: SealRequestState = SealRequestState.PROVIDER_INTERACTION,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM seal_request WHERE sender_client_id = :senderClientId " +
            "AND state IN (:pending, :approved, :provider) AND expires_at >= :now",
    )
    protected abstract suspend fun activeCountForSender(
        senderClientId: String,
        now: Long,
        pending: SealRequestState = SealRequestState.PENDING_REVIEW,
        approved: SealRequestState = SealRequestState.USER_APPROVED,
        provider: SealRequestState = SealRequestState.PROVIDER_INTERACTION,
    ): Int

    @Transaction
    open suspend fun replaceEnrollment(
        entity: SealEnrollmentEntity,
        protected: SealEnrollmentProtectedEntity?,
    ) {
        require(entity.singletonId == OperationalSingletons.ID) { "invalid Seal enrollment singleton id" }
        require(entity.updatedAt > 0) { "Seal enrollment update time must be positive" }
        requireCode(entity.recoveryReasonCode, "Seal enrollment recovery reason")
        when (entity.state) {
            SealEnrollmentState.ENROLLED -> {
                require(entity.recoveryReasonCode == null) {
                    "enrolled Seal header is invalid"
                }
                requireNotNull(protected).requireValidEnrollmentProtected()
            }
            SealEnrollmentState.DISABLED -> require(
                entity.recoveryReasonCode == null && protected == null,
            ) { "disabled Seal enrollment cannot retain protected material or recovery state" }
            SealEnrollmentState.RECOVERY_REQUIRED -> require(
                entity.recoveryReasonCode != null && protected == null,
            ) { "recovery-required Seal enrollment must contain only a safe reason" }
        }

        upsertEnrollmentInternal(entity)
        if (protected == null) {
            deleteEnrollmentProtectedInternal()
        } else {
            upsertEnrollmentProtectedInternal(protected)
        }
        check(readEnrollment() == entity) { "Seal enrollment header reread did not match the committed command" }
        check(readEnrollmentProtected().contentEqualsNullable(protected)) {
            "Seal protected enrollment reread did not match the committed command"
        }
    }

    private fun SealEnrollmentProtectedEntity.requireValidEnrollmentProtected() {
        require(singletonId == OperationalSingletons.ID) { "invalid protected Seal enrollment singleton id" }
        require(protectionGeneration > 0) { "Seal enrollment protection generation must be positive" }
        requireProtectedBlob(
            protectionScheme,
            protectionVersion,
            protectionKeyRef,
            protectionGeneration,
            payloadCodecVersion,
            payloadCiphertext,
            payloadNonce,
        )
        require(payloadCiphertext.size <= OperationalStorageLimits.SEAL_ENROLLMENT_MAX_CIPHERTEXT_BYTES) {
            "protected Seal enrollment exceeds its reviewed role bound"
        }
    }

    private fun SealEnrollmentProtectedEntity?.contentEqualsNullable(
        other: SealEnrollmentProtectedEntity?,
    ): Boolean = when {
        this == null || other == null -> this == null && other == null
        else -> singletonId == other.singletonId && protectionScheme == other.protectionScheme &&
            protectionVersion == other.protectionVersion && protectionKeyRef == other.protectionKeyRef &&
            protectionGeneration == other.protectionGeneration && payloadCodecVersion == other.payloadCodecVersion &&
            payloadCiphertext.contentEquals(other.payloadCiphertext) && payloadNonce.contentEquals(other.payloadNonce)
    }

    @Transaction
    open suspend fun insertImportedTerminalHistory(request: SealRequestEntity): Boolean {
        request.requireValid()
        require(request.state in setOf(
            SealRequestState.SENT,
            SealRequestState.CANCELLED,
            SealRequestState.EXPIRED,
            SealRequestState.FAILED,
        )) { "imported Seal history must already be terminal" }
        findRequest(request.requestId)?.let { current ->
            require(current.requestFingerprint.contentEquals(request.requestFingerprint)) {
                "imported Seal history conflicts with an existing request"
            }
            return false
        }
        insertRequestInternal(request)
        return true
    }

    @Transaction
    open suspend fun accept(
        request: SealRequestEntity,
        pendingPayload: SealPendingPayloadEntity,
        activity: ActivityEventEntity?,
        now: Long,
    ): SealAcceptResult {
        request.requireValid()
        pendingPayload.requireValid()
        activity?.requireValid()
        require(request.requestId == pendingPayload.requestId) { "Seal pending payload has a different request id" }
        require(request.state == SealRequestState.PENDING_REVIEW && request.outcome == null) {
            "new Seal request must be pending review"
        }
        findRequest(request.requestId)?.let { current ->
            return if (current.requestFingerprint.contentEquals(request.requestFingerprint)) {
                SealAcceptResult.DUPLICATE
            } else {
                SealAcceptResult.CONFLICT
            }
        }
        if (
            activeCount(now) >= OperationalRetention.SEAL_MAX_PENDING_GLOBAL ||
            activeCountForSender(request.senderClientId, now) >=
            OperationalRetention.SEAL_MAX_PENDING_PER_SENDER
        ) return SealAcceptResult.RATE_LIMITED
        insertRequestInternal(request)
        insertPendingPayloadInternal(pendingPayload)
        activity?.let { insertActivityInternal(it) }
        return SealAcceptResult.STORED
    }

    /** Atomically accepts a protected Seal request and commits its broker receipt evidence. */
    suspend fun acceptWithReceipt(
        request: SealRequestEntity,
        pendingPayload: SealPendingPayloadEntity,
        receipt: PreparedOperationalReceipt,
        now: Long,
    ): OperationalFeatureCommitResult = runOwnedReceiptTransaction {
        acceptWithReceiptInternal(request, pendingPayload, receipt, now)
    }

    @Transaction
    protected open suspend fun acceptWithReceiptInternal(
        request: SealRequestEntity,
        pendingPayload: SealPendingPayloadEntity,
        receipt: PreparedOperationalReceipt,
        now: Long,
    ): OperationalFeatureCommitResult {
        existingReceiptResult(receipt)?.let { return it }
        return when (accept(request, pendingPayload, activity = null, now = now)) {
            SealAcceptResult.STORED ->
                finalizeOwnedReceipt(
                    receipt,
                    OperationalReceiptDisposition.APPLIED,
                    persistActivity = receipt.activity != null,
                )
            SealAcceptResult.DUPLICATE ->
                finalizeOwnedReceipt(
                    receipt,
                    OperationalReceiptDisposition.DUPLICATE,
                    persistActivity = false,
                )
            SealAcceptResult.CONFLICT -> OperationalFeatureCommitResult.ConflictNoAck
            SealAcceptResult.RATE_LIMITED ->
                OperationalFeatureCommitResult.RetryRequired("seal_pending_capacity")
        }
    }

    @Query(
        "UPDATE seal_request SET state = :toState, updated_at = :updatedAt " +
            "WHERE request_id = :requestId AND state = :fromState AND expires_at >= :updatedAt",
    )
    protected abstract suspend fun transitionActiveInternal(
        requestId: String,
        fromState: SealRequestState,
        toState: SealRequestState,
        updatedAt: Long,
    ): Int

    suspend fun markUserApproved(requestId: String, updatedAt: Long): Boolean =
        transitionActiveInternal(
            requestId,
            SealRequestState.PENDING_REVIEW,
            SealRequestState.USER_APPROVED,
            updatedAt,
        ) == 1

    suspend fun markProviderInteraction(requestId: String, updatedAt: Long): Boolean =
        transitionActiveInternal(
            requestId,
            SealRequestState.USER_APPROVED,
            SealRequestState.PROVIDER_INTERACTION,
            updatedAt,
        ) == 1

    @Query(
        "UPDATE seal_request SET state = :state, outcome = :outcome, decision_at = :decidedAt, " +
            "updated_at = :decidedAt WHERE request_id = :requestId " +
            "AND state IN (:pending, :approved, :provider)",
    )
    protected abstract suspend fun recordOutcomeInternal(
        requestId: String,
        state: SealRequestState,
        outcome: SealRequestOutcome,
        decidedAt: Long,
        pending: SealRequestState = SealRequestState.PENDING_REVIEW,
        approved: SealRequestState = SealRequestState.USER_APPROVED,
        provider: SealRequestState = SealRequestState.PROVIDER_INTERACTION,
    ): Int

    @Transaction
    open suspend fun recordOutcomeAndQueueResponse(transition: SealOutcomeTransition): Boolean {
        transition.requireValid()
        val responseState = if (transition.responseCustody != null) {
            SealRequestState.RESPONSE_QUEUED
        } else {
            transition.outcome.terminalStateWithoutResponse()
        }
        if (transition.responseCustody == null) {
            check(findResponseCustody(transition.requestId) == null) {
                "Seal non-custody outcome encountered existing protected response custody"
            }
        }
        if (
            recordOutcomeInternal(
                requestId = transition.requestId,
                state = responseState,
                outcome = transition.outcome,
                decidedAt = transition.decidedAt,
            ) != 1
        ) return false
        check(deletePendingPayloadInternal(transition.requestId) == 1) {
            "Seal outcome did not erase exactly one pending payload"
        }
        transition.responseCustody?.let { insertResponseCustodyInternal(it) }
        transition.activity?.let { insertActivityInternal(it) }
        return true
    }

    @Query(
        "UPDATE seal_response_custody SET payload_format = :preparedFormat, " +
            "protection_scheme = :nextScheme, protection_version = :nextVersion, " +
            "protection_key_ref = :nextKeyRef, protection_generation = :nextGeneration, " +
            "payload_codec_version = :nextCodecVersion, payload_ciphertext = :nextCiphertext, " +
            "payload_nonce = :nextNonce, updated_at = :nextUpdatedAt " +
            "WHERE request_id = :requestId AND payload_format = :bodyFormat " +
            "AND protection_scheme = :expectedScheme AND protection_version = :expectedVersion " +
            "AND protection_key_ref = :expectedKeyRef AND protection_generation = :expectedGeneration " +
            "AND payload_codec_version = :expectedCodecVersion AND payload_ciphertext = :expectedCiphertext " +
            "AND payload_nonce = :expectedNonce AND created_at = :expectedCreatedAt " +
            "AND updated_at = :expectedUpdatedAt",
    )
    protected abstract suspend fun prepareResponseInternal(
        requestId: String,
        expectedScheme: String,
        expectedVersion: Int,
        expectedKeyRef: String,
        expectedGeneration: Long,
        expectedCodecVersion: Int,
        expectedCiphertext: ByteArray,
        expectedNonce: ByteArray,
        expectedCreatedAt: Long,
        expectedUpdatedAt: Long,
        nextScheme: String,
        nextVersion: Int,
        nextKeyRef: String,
        nextGeneration: Long,
        nextCodecVersion: Int,
        nextCiphertext: ByteArray,
        nextNonce: ByteArray,
        nextUpdatedAt: Long,
        bodyFormat: SealResponsePayloadFormat = SealResponsePayloadFormat.BODY,
        preparedFormat: SealResponsePayloadFormat = SealResponsePayloadFormat.PREPARED_ENVELOPE,
    ): Int

    @Transaction
    open suspend fun prepareResponse(
        expected: SealResponseCustodyEntity,
        prepared: SealResponseCustodyEntity,
    ): SealResponsePrepareResult {
        expected.requireValidResponseCustody(SealResponsePayloadFormat.BODY)
        prepared.requireValidResponseCustody(SealResponsePayloadFormat.PREPARED_ENVELOPE)
        require(expected.requestId == prepared.requestId) { "Seal response custody request id changed" }
        require(expected.createdAt == prepared.createdAt && prepared.updatedAt > expected.updatedAt) {
            "Seal response preparation timestamps are invalid"
        }
        val current = findResponseCustody(expected.requestId) ?: return SealResponsePrepareResult.NOT_FOUND
        if (current.hasSamePersistedProjection(prepared)) return SealResponsePrepareResult.ALREADY_PREPARED
        if (!current.hasSamePersistedProjection(expected)) return SealResponsePrepareResult.STALE
        val changed = prepareResponseInternal(
            requestId = expected.requestId,
            expectedScheme = expected.protectionScheme,
            expectedVersion = expected.protectionVersion,
            expectedKeyRef = expected.protectionKeyRef,
            expectedGeneration = expected.protectionGeneration,
            expectedCodecVersion = expected.payloadCodecVersion,
            expectedCiphertext = expected.payloadCiphertext,
            expectedNonce = expected.payloadNonce,
            expectedCreatedAt = expected.createdAt,
            expectedUpdatedAt = expected.updatedAt,
            nextScheme = prepared.protectionScheme,
            nextVersion = prepared.protectionVersion,
            nextKeyRef = prepared.protectionKeyRef,
            nextGeneration = prepared.protectionGeneration,
            nextCodecVersion = prepared.payloadCodecVersion,
            nextCiphertext = prepared.payloadCiphertext,
            nextNonce = prepared.payloadNonce,
            nextUpdatedAt = prepared.updatedAt,
        )
        if (changed != 1) return SealResponsePrepareResult.STALE
        return if (findResponseCustody(expected.requestId)?.hasSamePersistedProjection(prepared) == true) {
            SealResponsePrepareResult.UPDATED
        } else {
            SealResponsePrepareResult.CONFLICT
        }
    }

    @Query(
        "UPDATE seal_request SET state = :sentState, updated_at = :sentAt " +
            "WHERE request_id = :requestId AND state = :responseQueuedState " +
            "AND updated_at = :expectedUpdatedAt AND :sentAt > updated_at",
    )
    protected abstract suspend fun markSentInternal(
        requestId: String,
        expectedUpdatedAt: Long,
        sentAt: Long,
        sentState: SealRequestState = SealRequestState.SENT,
        responseQueuedState: SealRequestState = SealRequestState.RESPONSE_QUEUED,
    ): Int

    @Transaction
    open suspend fun completeResponse(
        expectedCustody: SealResponseCustodyEntity,
        sentAt: Long,
    ): SealResponseCompleteResult {
        val requestId = expectedCustody.requestId
        requireIdentifier(requestId, "Seal request id")
        require(sentAt > 0) { "Seal sent time must be positive" }
        expectedCustody.requireValidResponseCustody(SealResponsePayloadFormat.PREPARED_ENVELOPE)
        val request = findRequest(requestId) ?: return SealResponseCompleteResult.NOT_READY
        val custody = findResponseCustody(requestId)
        if (request.state == SealRequestState.SENT && custody == null) {
            return SealResponseCompleteResult.ALREADY_SENT
        }
        if (request.state != SealRequestState.RESPONSE_QUEUED ||
            custody?.hasSamePersistedProjection(expectedCustody) != true
        ) return SealResponseCompleteResult.CONFLICT
        if (markSentInternal(requestId, request.updatedAt, sentAt) != 1) {
            return SealResponseCompleteResult.CONFLICT
        }
        check(deleteResponseCustodyInternal(requestId) == 1) {
            "Seal response custody disappeared during accepted-send completion"
        }
        return SealResponseCompleteResult.SENT
    }

    @Transaction
    open suspend fun terminalWithoutResponse(
        requestId: String,
        outcome: SealRequestOutcome,
        decidedAt: Long,
        activity: ActivityEventEntity?,
    ): Boolean {
        require(outcome in setOf(
            SealRequestOutcome.CANCELLED,
            SealRequestOutcome.EXPIRED,
            SealRequestOutcome.FAILED,
        )) { "Seal terminal transition requires a non-response outcome" }
        requireIdentifier(requestId, "Seal outcome request id")
        require(decidedAt > 0) { "Seal outcome time must be positive" }
        activity?.requireValid()
        val state = when (outcome) {
            SealRequestOutcome.CANCELLED -> SealRequestState.CANCELLED
            SealRequestOutcome.EXPIRED -> SealRequestState.EXPIRED
            SealRequestOutcome.FAILED -> SealRequestState.FAILED
            else -> error("unreachable Seal outcome")
        }
        check(findResponseCustody(requestId) == null) {
            "Seal terminal outcome encountered existing protected response custody"
        }
        if (recordOutcomeInternal(requestId, state, outcome, decidedAt) != 1) return false
        check(deletePendingPayloadInternal(requestId) == 1) {
            "Seal terminal outcome did not erase exactly one pending payload"
        }
        activity?.let { insertActivityInternal(it) }
        return true
    }

    @Query(
        "SELECT request_id FROM seal_request WHERE state IN (:pending, :approved, :provider) " +
            "ORDER BY created_at ASC, request_id ASC",
    )
    protected abstract suspend fun activeRequestIds(
        pending: SealRequestState = SealRequestState.PENDING_REVIEW,
        approved: SealRequestState = SealRequestState.USER_APPROVED,
        provider: SealRequestState = SealRequestState.PROVIDER_INTERACTION,
    ): List<String>

    @Transaction
    open suspend fun clearEnrollment(responses: List<SealOutcomeTransition>) {
        val activeIds = activeRequestIds()
        require(responses.map { it.requestId }.toSet().size == responses.size) {
            "Seal enrollment clear contains duplicate transitions"
        }
        require(responses.map { it.requestId }.toSet() == activeIds.toSet()) {
            "Seal enrollment clear must account for every active request"
        }
        responses.forEach { transition ->
            transition.requireValid()
            check(recordOutcomeAndQueueResponse(transition)) {
                "Seal enrollment clear lost an active request transition"
            }
        }
        deleteEnrollmentInternal()
    }

    @Query(
        "DELETE FROM seal_request WHERE request_id IN (SELECT request_id FROM seal_request " +
            "WHERE state NOT IN (:pending, :approved, :provider, :responseQueued) AND updated_at < :cutoff " +
            "ORDER BY updated_at ASC, request_id ASC LIMIT :limit)",
    )
    abstract suspend fun pruneTerminalBefore(
        cutoff: Long,
        limit: Int,
        pending: SealRequestState = SealRequestState.PENDING_REVIEW,
        approved: SealRequestState = SealRequestState.USER_APPROVED,
        provider: SealRequestState = SealRequestState.PROVIDER_INTERACTION,
        responseQueued: SealRequestState = SealRequestState.RESPONSE_QUEUED,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM seal_request " +
            "WHERE state NOT IN (:pending, :approved, :provider, :responseQueued)",
    )
    protected abstract suspend fun terminalHistoryCount(
        pending: SealRequestState = SealRequestState.PENDING_REVIEW,
        approved: SealRequestState = SealRequestState.USER_APPROVED,
        provider: SealRequestState = SealRequestState.PROVIDER_INTERACTION,
        responseQueued: SealRequestState = SealRequestState.RESPONSE_QUEUED,
    ): Int

    @Query(
        "DELETE FROM seal_request WHERE request_id IN (SELECT request_id FROM seal_request " +
            "WHERE state NOT IN (:pending, :approved, :provider, :responseQueued) " +
            "ORDER BY updated_at ASC, request_id ASC LIMIT :limit)",
    )
    protected abstract suspend fun pruneOldestTerminal(
        limit: Int,
        pending: SealRequestState = SealRequestState.PENDING_REVIEW,
        approved: SealRequestState = SealRequestState.USER_APPROVED,
        provider: SealRequestState = SealRequestState.PROVIDER_INTERACTION,
        responseQueued: SealRequestState = SealRequestState.RESPONSE_QUEUED,
    ): Int

    @Transaction
    open suspend fun pruneHistoryBatch(now: Long): Int {
        require(now > 0) { "Seal history prune time must be positive" }
        var removed = pruneTerminalBefore(
            cutoff = now - OperationalRetention.SEAL_HISTORY_RETENTION_MILLIS,
            limit = OperationalRetention.SEAL_PRUNE_BATCH_SIZE,
        )
        val overflow = (terminalHistoryCount() - OperationalRetention.SEAL_MAX_HISTORY_ROWS).coerceAtLeast(0)
        val remainingBatch = OperationalRetention.SEAL_PRUNE_BATCH_SIZE - removed
        if (overflow > 0 && remainingBatch > 0) {
            removed += pruneOldestTerminal(minOf(overflow, remainingBatch))
        }
        return removed
    }

    private fun SealOutcomeTransition.requireValid() {
        requireIdentifier(requestId, "Seal outcome request id")
        require(decidedAt > 0) { "Seal outcome time must be positive" }
        when (outcome) {
            SealRequestOutcome.APPROVED -> {
                val custody = requireNotNull(responseCustody) {
                    "approved Seal outcome requires protected response custody"
                }
                custody.requireValidResponseCustody(SealResponsePayloadFormat.BODY)
                require(custody.requestId == requestId) {
                    "Seal response custody request id must match outcome"
                }
                require(custody.createdAt == decidedAt && custody.updatedAt == decidedAt) {
                    "new Seal response custody timestamps must equal the decision time"
                }
            }
            SealRequestOutcome.REJECTED,
            SealRequestOutcome.CANCELLED,
            SealRequestOutcome.EXPIRED,
            SealRequestOutcome.FAILED,
            -> require(responseCustody == null) {
                "non-successful Seal outcome must not carry response custody"
            }
        }
        activity?.requireValid()
    }

    /** There is no dedicated rejected state in the v1 schema; retain the exact outcome in FAILED. */
    private fun SealRequestOutcome.terminalStateWithoutResponse(): SealRequestState = when (this) {
        SealRequestOutcome.REJECTED,
        SealRequestOutcome.FAILED,
        -> SealRequestState.FAILED
        SealRequestOutcome.CANCELLED -> SealRequestState.CANCELLED
        SealRequestOutcome.EXPIRED -> SealRequestState.EXPIRED
        SealRequestOutcome.APPROVED -> error("approved Seal outcome requires response custody")
    }

    private fun SealResponseCustodyEntity.requireValidResponseCustody(
        expectedFormat: SealResponsePayloadFormat,
    ) {
        requireIdentifier(requestId, "Seal response custody request id")
        require(payloadFormat == expectedFormat) { "Seal response custody format is invalid for this transition" }
        require(protectionGeneration > 0) { "Seal response protection generation must be positive" }
        requireProtectedBlob(
            protectionScheme,
            protectionVersion,
            protectionKeyRef,
            protectionGeneration,
            payloadCodecVersion,
            payloadCiphertext,
            payloadNonce,
        )
        require(createdAt > 0 && updatedAt >= createdAt) { "Seal response custody timestamps are invalid" }
    }

    private fun SealResponseCustodyEntity.hasSamePersistedProjection(
        other: SealResponseCustodyEntity,
    ): Boolean = requestId == other.requestId && payloadFormat == other.payloadFormat &&
        protectionScheme == other.protectionScheme && protectionVersion == other.protectionVersion &&
        protectionKeyRef == other.protectionKeyRef && protectionGeneration == other.protectionGeneration &&
        payloadCodecVersion == other.payloadCodecVersion &&
        payloadCiphertext.contentEquals(other.payloadCiphertext) && payloadNonce.contentEquals(other.payloadNonce) &&
        createdAt == other.createdAt && updatedAt == other.updatedAt
}

internal enum class ScreenReplayConsumeResult {
    CONSUMED,
    DUPLICATE,
    CAPACITY_EXCEEDED,
    DISABLED,
    QUARANTINED,
}

internal data class ScreenAuthorizationAggregate(
    val securityState: ScreenSecurityStateEntity?,
    val peers: List<ScreenAuthorizedPeerEntity>,
)

@Dao
internal abstract class ScreenDao : OperationalReceiptOwningDao() {
    @Query("SELECT * FROM screen_security_state WHERE singleton_id = 1")
    abstract fun observeSecurityState(): Flow<ScreenSecurityStateEntity?>

    @Query("SELECT * FROM screen_security_state WHERE singleton_id = 1")
    protected abstract suspend fun securityState(): ScreenSecurityStateEntity?

    @Query("SELECT * FROM screen_authorized_peer ORDER BY peer_id")
    protected abstract suspend fun authorizedPeers(): List<ScreenAuthorizedPeerEntity>

    @Query("SELECT * FROM screen_codec_preference ORDER BY peer_id")
    abstract fun observeCodecPreferences(): Flow<List<ScreenCodecPreferenceEntity>>

    @Upsert
    protected abstract suspend fun upsertSecurityStateInternal(entity: ScreenSecurityStateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAuthorizedPeers(entities: List<ScreenAuthorizedPeerEntity>)

    @Query("DELETE FROM screen_authorized_peer")
    protected abstract suspend fun deleteAuthorizedPeers(): Int

    @Upsert
    protected abstract suspend fun upsertCodecInternal(entity: ScreenCodecPreferenceEntity)

    @Query("DELETE FROM screen_codec_preference WHERE peer_id = :peerId")
    abstract suspend fun removeCodecPreference(peerId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM screen_replay_token WHERE digest = :digest)")
    protected abstract suspend fun replayExists(digest: ByteArray): Boolean

    @Query("SELECT COUNT(*) FROM screen_replay_token")
    protected abstract suspend fun replayCount(): Int

    @Query("DELETE FROM screen_replay_token WHERE expires_at <= :now")
    protected abstract suspend fun pruneExpiredReplay(now: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertReplayTokens(entities: List<ScreenReplayTokenEntity>)

    @Query("DELETE FROM screen_replay_token")
    protected abstract suspend fun clearReplayTokens(): Int

    suspend fun replaceSecurityState(entity: ScreenSecurityStateEntity) {
        entity.requireValid()
        upsertSecurityStateInternal(entity)
    }

    /** Reads the authorization revision header and its peer set from one SQLite snapshot. */
    @Transaction
    open suspend fun readAuthorizations(): ScreenAuthorizationAggregate = ScreenAuthorizationAggregate(
        securityState = securityState(),
        peers = authorizedPeers(),
    )

    @Transaction
    open suspend fun replaceAuthorizations(
        peers: List<ScreenAuthorizedPeerEntity>,
        securityState: ScreenSecurityStateEntity,
    ) {
        securityState.requireValid()
        require(peers.map { it.peerId }.toSet().size == peers.size) { "duplicate screen authorized peer" }
        peers.forEach { peer ->
            requireIdentifier(peer.peerId, "screen authorized peer id")
            require(peer.grantedAt > 0 && peer.updatedAt >= peer.grantedAt) {
                "screen authorization timestamps are invalid"
            }
        }
        deleteAuthorizedPeers()
        if (peers.isNotEmpty()) insertAuthorizedPeers(peers)
        upsertSecurityStateInternal(securityState)
    }

    @Transaction
    open suspend fun consumeReplay(
        sessionDigest: ByteArray,
        routingTokenDigest: ByteArray,
        expiresAt: Long,
        consumedAt: Long,
    ): ScreenReplayConsumeResult {
        require(sessionDigest.size == OperationalStorageLimits.SHA256_BYTES) {
            "screen session replay digest must be SHA-256"
        }
        require(routingTokenDigest.size == OperationalStorageLimits.SHA256_BYTES) {
            "screen token replay digest must be SHA-256"
        }
        require(!sessionDigest.contentEquals(routingTokenDigest)) { "screen replay digests must be domain-separated" }
        require(consumedAt > 0 && expiresAt > consumedAt) { "screen replay timestamps are invalid" }
        val state = securityState() ?: return ScreenReplayConsumeResult.DISABLED
        if (state.replayHealth != ScreenReplayHealth.HEALTHY) return ScreenReplayConsumeResult.QUARANTINED
        if (!state.enabled) return ScreenReplayConsumeResult.DISABLED
        pruneExpiredReplay(consumedAt)
        if (replayExists(sessionDigest) || replayExists(routingTokenDigest)) {
            return ScreenReplayConsumeResult.DUPLICATE
        }
        if (replayCount() > OperationalRetention.SCREEN_MAX_REPLAY_ROWS - 2) {
            return ScreenReplayConsumeResult.CAPACITY_EXCEEDED
        }
        insertReplayTokens(
            listOf(
                ScreenReplayTokenEntity(sessionDigest, ScreenReplayKind.SESSION, expiresAt, consumedAt),
                ScreenReplayTokenEntity(routingTokenDigest, ScreenReplayKind.ROUTING_TOKEN, expiresAt, consumedAt),
            ),
        )
        return ScreenReplayConsumeResult.CONSUMED
    }

    /**
     * Atomically consumes both REQUEST replay tokens and commits the matching broker receipt.
     * Token reuse under a different message identity is security-blocked and never ACK-ready.
     */
    suspend fun consumeReplayWithReceipt(
        sessionDigest: ByteArray,
        routingTokenDigest: ByteArray,
        expiresAt: Long,
        consumedAt: Long,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult = runOwnedReceiptTransaction {
        consumeReplayWithReceiptInternal(sessionDigest, routingTokenDigest, expiresAt, consumedAt, receipt)
    }

    @Transaction
    protected open suspend fun consumeReplayWithReceiptInternal(
        sessionDigest: ByteArray,
        routingTokenDigest: ByteArray,
        expiresAt: Long,
        consumedAt: Long,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult {
        existingReceiptResult(receipt)?.let { return it }
        return when (consumeReplay(sessionDigest, routingTokenDigest, expiresAt, consumedAt)) {
            ScreenReplayConsumeResult.CONSUMED ->
                finalizeOwnedReceipt(
                    receipt,
                    OperationalReceiptDisposition.APPLIED,
                    persistActivity = receipt.activity != null,
                )
            ScreenReplayConsumeResult.DUPLICATE ->
                OperationalFeatureCommitResult.SecurityBlocked("screen_replay_token_reused")
            ScreenReplayConsumeResult.CAPACITY_EXCEEDED ->
                OperationalFeatureCommitResult.RetryRequired("screen_replay_capacity")
            ScreenReplayConsumeResult.DISABLED ->
                OperationalFeatureCommitResult.SecurityBlocked("screen_replay_disabled")
            ScreenReplayConsumeResult.QUARANTINED ->
                OperationalFeatureCommitResult.SecurityBlocked("screen_replay_quarantined")
        }
    }

    @Transaction
    open suspend fun quarantineReplay(
        quarantineDigest: ByteArray,
        quarantinedAt: Long,
    ) {
        require(quarantineDigest.size == OperationalStorageLimits.SHA256_BYTES) {
            "screen quarantine digest must be SHA-256"
        }
        require(quarantinedAt > 0) { "screen quarantine time must be positive" }
        val current = securityState()
        clearReplayTokens()
        upsertSecurityStateInternal(
            ScreenSecurityStateEntity(
                enabled = false,
                replayHealth = ScreenReplayHealth.QUARANTINED,
                quarantineDigest = quarantineDigest,
                quarantinedAt = quarantinedAt,
                authorizationRevision = current?.authorizationRevision ?: 0,
                updatedAt = quarantinedAt,
            ),
        )
    }

    @Transaction
    open suspend fun repairReplay(repairedAt: Long) {
        require(repairedAt > 0) { "screen repair time must be positive" }
        val current = securityState()
        clearReplayTokens()
        upsertSecurityStateInternal(
            ScreenSecurityStateEntity(
                enabled = false,
                replayHealth = ScreenReplayHealth.HEALTHY,
                quarantineDigest = null,
                quarantinedAt = null,
                authorizationRevision = current?.authorizationRevision ?: 0,
                updatedAt = repairedAt,
            ),
        )
    }

    suspend fun putCodecPreference(entity: ScreenCodecPreferenceEntity) {
        requireIdentifier(entity.peerId, "screen codec peer id")
        require(entity.updatedAt > 0) { "screen codec update time must be positive" }
        upsertCodecInternal(entity)
    }

    private fun ScreenSecurityStateEntity.requireValid() {
        require(singletonId == OperationalSingletons.ID) { "invalid screen-security singleton id" }
        require(authorizationRevision >= 0 && updatedAt > 0) {
            "screen security metadata is invalid"
        }
        require(
            (replayHealth == ScreenReplayHealth.HEALTHY && quarantineDigest == null && quarantinedAt == null) ||
                (replayHealth == ScreenReplayHealth.QUARANTINED && !enabled &&
                    quarantineDigest?.size == OperationalStorageLimits.SHA256_BYTES && quarantinedAt != null),
        ) { "screen replay health/quarantine fields are inconsistent" }
    }
}
