package net.extrawdw.apps.notisync.data.storage.core

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface CoreMaintenanceStateDao {
    @Query("SELECT * FROM core_maintenance_state WHERE singleton = 1 LIMIT 1")
    fun observe(): Flow<CoreMaintenanceStateEntity?>

    @Query("SELECT * FROM core_maintenance_state WHERE singleton = 1 LIMIT 1")
    suspend fun get(): CoreMaintenanceStateEntity?

    @Upsert
    suspend fun upsert(entity: CoreMaintenanceStateEntity)

    @Query("DELETE FROM core_maintenance_state WHERE singleton = 1")
    suspend fun clear(): Int
}

@Dao
internal interface IdentityMetadataDao {
    @Query("SELECT * FROM identity_metadata WHERE singleton = 1 LIMIT 1")
    fun observe(): Flow<IdentityMetadataEntity?>

    @Query("SELECT * FROM identity_metadata WHERE singleton = 1 LIMIT 1")
    suspend fun get(): IdentityMetadataEntity?

    @Upsert
    suspend fun upsert(entity: IdentityMetadataEntity)

    @Query("DELETE FROM identity_metadata WHERE singleton = 1")
    suspend fun clear(): Int

    @Query(
        "UPDATE identity_metadata SET lifecycle_state = :state, updated_at = :updatedAt WHERE singleton = 1",
    )
    suspend fun updateLifecycle(
        state: IdentityLifecycleState,
        updatedAt: Long,
    ): Int
}

@Dao
internal interface TrustSnapshotDao {
    @Query("SELECT * FROM trust_snapshot WHERE singleton = 1 LIMIT 1")
    fun observe(): Flow<TrustSnapshotEntity?>

    @Query("SELECT * FROM trust_snapshot WHERE singleton = 1 LIMIT 1")
    suspend fun get(): TrustSnapshotEntity?

    @Upsert
    suspend fun replace(entity: TrustSnapshotEntity)

    @Query("DELETE FROM trust_snapshot WHERE singleton = 1")
    suspend fun clear(): Int
}

@Dao
internal interface CryptoEpochDao {
    @Query("SELECT * FROM crypto_epoch ORDER BY epoch ASC")
    fun observeAll(): Flow<List<CryptoEpochEntity>>

    @Query("SELECT * FROM crypto_epoch ORDER BY epoch ASC")
    suspend fun getAll(): List<CryptoEpochEntity>

    @Query("SELECT * FROM crypto_epoch WHERE epoch = :epoch LIMIT 1")
    fun observe(epoch: Int): Flow<CryptoEpochEntity?>

    @Query("SELECT * FROM crypto_epoch WHERE epoch = :epoch LIMIT 1")
    suspend fun find(epoch: Int): CryptoEpochEntity?

    @Query("SELECT MAX(epoch) FROM crypto_epoch")
    suspend fun maxEpoch(): Int?

    @Upsert
    suspend fun upsert(entity: CryptoEpochEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: CryptoEpochEntity): Long

    @Update
    suspend fun update(entity: CryptoEpochEntity): Int

    @Query("DELETE FROM crypto_epoch WHERE epoch = :epoch")
    suspend fun deleteByEpoch(epoch: Int): Int

    @Query("DELETE FROM crypto_epoch")
    suspend fun clearAll(): Int
}

@Dao
internal interface BrokerAuthTokenDao {
    @Query("SELECT * FROM broker_auth_token WHERE singleton = 1 LIMIT 1")
    fun observe(): Flow<BrokerAuthTokenEntity?>

    @Query("SELECT * FROM broker_auth_token WHERE singleton = 1 LIMIT 1")
    suspend fun get(): BrokerAuthTokenEntity?

    @Upsert
    suspend fun upsert(entity: BrokerAuthTokenEntity)

    @Query("DELETE FROM broker_auth_token WHERE singleton = 1")
    suspend fun clear(): Int
}

@Dao
internal interface CoreTransportStateDao {
    @Query("SELECT * FROM core_transport_state WHERE singleton = 1 LIMIT 1")
    fun observe(): Flow<CoreTransportStateEntity?>

    @Query("SELECT * FROM core_transport_state WHERE singleton = 1 LIMIT 1")
    suspend fun get(): CoreTransportStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: CoreTransportStateEntity): Long

    @Update
    suspend fun update(entity: CoreTransportStateEntity): Int

    /** Restart-safe initialization with an immutable continuity origin; a collision is never treated as success. */
    @Transaction
    suspend fun initialize(entity: CoreTransportStateEntity): CoreTransportInitializationResult {
        entity.toSnapshot()
        require(entity.operationalGeneration == INITIAL_OPERATIONAL_GENERATION) {
            "Initial Core transport generation must be 1"
        }
        require(entity.replayFenceState == ReplayFenceState.CONTINUITY_INTACT) {
            "Initial Core transport must carry intact-continuity evidence"
        }
        if (insertIfAbsent(entity) != -1L) return CoreTransportInitializationResult.INITIALIZED
        val committed = get() ?: error("Core transport initialization was not persisted")
        return if (committed.sameInitialTransport(entity)) {
            CoreTransportInitializationResult.ALREADY_INITIALIZED
        } else {
            CoreTransportInitializationResult.CONFLICT
        }
    }

    @Transaction
    suspend fun advanceRoute(update: RouteMutation): RouteAdvanceResult {
        require(update.brokerUrl.isNotBlank()) { "brokerUrl must not be blank" }
        require(update.routeEpoch >= 0) { "routeEpoch must not be negative" }
        val current = get() ?: return RouteAdvanceResult.MISSING
        if (update.expectedBrokerEndpointRevision != current.brokerEndpointRevision) {
            return RouteAdvanceResult.STALE_ENDPOINT
        }
        // Endpoint changes have a wider broker-scoped invalidation transaction. A route update must never
        // provide a bypass that changes only this row while leaving the broker token or registration state.
        if (update.brokerUrl != current.brokerUrl) return RouteAdvanceResult.CONFLICT
        if (update.routeEpoch < current.routeEpoch) return RouteAdvanceResult.STALE
        if (update.routeEpoch == current.routeEpoch) {
            return if (
                current.brokerUrl == update.brokerUrl &&
                current.fcmRouteRef == update.fcmRouteRef &&
                current.selfEpochActivatedAt == update.selfEpochActivatedAt
            ) {
                RouteAdvanceResult.UNCHANGED
            } else {
                RouteAdvanceResult.CONFLICT
            }
        }
        check(
            update(
                current.copy(
                    fcmRouteRef = update.fcmRouteRef,
                    routeEpoch = update.routeEpoch,
                    selfEpochActivatedAt = update.selfEpochActivatedAt,
                    updatedAt = update.updatedAt,
                ),
            ) == 1,
        ) { "Route transition was not persisted" }
        return RouteAdvanceResult.ADVANCED
    }

    /** Group identity is trusted transport state, not a side effect of an FCM route callback. */
    @Transaction
    suspend fun setGroupId(groupId: String?, updatedAt: Long): GroupIdUpdateResult {
        validateCoreGroupId(groupId)
        val current = get() ?: return GroupIdUpdateResult.MISSING
        if (current.groupId == groupId) return GroupIdUpdateResult.UNCHANGED
        check(
            update(
                current.copy(
                    groupId = groupId,
                    updatedAt = updatedAt,
                ),
            ) == 1,
        ) { "Group identity transition was not persisted" }
        return GroupIdUpdateResult.UPDATED
    }

    @Transaction
    suspend fun advanceOperationalGeneration(
        generation: Long,
        updatedAt: Long,
    ): OperationalGenerationResult {
        require(generation > 0) { "operational generation must be positive" }
        val current = get() ?: return OperationalGenerationResult.MISSING
        if (generation < current.operationalGeneration) return OperationalGenerationResult.STALE
        if (generation == current.operationalGeneration) return OperationalGenerationResult.UNCHANGED
        val expectedNext = Math.addExact(current.operationalGeneration, 1L)
        if (generation != expectedNext) return OperationalGenerationResult.NON_SEQUENTIAL
        check(
            update(
                current.copy(
                    operationalGeneration = generation,
                    replayFenceState = ReplayFenceState.FENCE_REQUIRED,
                    continuityOrigin = null,
                    replayFenceId = null,
                    replayFenceEpoch = null,
                    updatedAt = updatedAt,
                ),
            ) == 1,
        ) { "Operational generation transition was not persisted" }
        return OperationalGenerationResult.ADVANCED
    }

    @Transaction
    suspend fun beginReplayFence(generation: Long, updatedAt: Long): ReplayFenceResult {
        val current = get() ?: return ReplayFenceResult.MISSING
        if (generation != current.operationalGeneration) return ReplayFenceResult.STALE_GENERATION
        return when (current.replayFenceState) {
            ReplayFenceState.CONTINUITY_INTACT -> ReplayFenceResult.CONTINUITY_INTACT
            ReplayFenceState.FENCE_REQUIRED -> {
                check(
                    update(
                        current.copy(
                            replayFenceState = ReplayFenceState.ESTABLISHING,
                            continuityOrigin = null,
                            updatedAt = updatedAt,
                        ),
                    ) == 1,
                ) { "Replay fence transition was not persisted" }
                ReplayFenceResult.ESTABLISHING
            }
            ReplayFenceState.ESTABLISHING -> ReplayFenceResult.ESTABLISHING
            ReplayFenceState.ESTABLISHED -> ReplayFenceResult.ALREADY_ESTABLISHED
            ReplayFenceState.BLOCKED -> ReplayFenceResult.BLOCKED
        }
    }

    @Transaction
    suspend fun establishReplayFence(
        generation: Long,
        fenceId: String,
        fenceEpoch: Int,
        updatedAt: Long,
    ): ReplayFenceResult {
        require(fenceId.isNotBlank()) { "fenceId must not be blank" }
        require(fenceEpoch > 0) { "fenceEpoch must be positive" }
        val current = get() ?: return ReplayFenceResult.MISSING
        if (generation != current.operationalGeneration) return ReplayFenceResult.STALE_GENERATION
        when (current.replayFenceState) {
            ReplayFenceState.CONTINUITY_INTACT -> return ReplayFenceResult.BLOCKED
            ReplayFenceState.FENCE_REQUIRED -> return ReplayFenceResult.BLOCKED
            ReplayFenceState.BLOCKED -> return ReplayFenceResult.BLOCKED
            ReplayFenceState.ESTABLISHED -> {
                return if (current.replayFenceId == fenceId && current.replayFenceEpoch == fenceEpoch) {
                    ReplayFenceResult.ALREADY_ESTABLISHED
                } else {
                    ReplayFenceResult.BLOCKED
                }
            }
            ReplayFenceState.ESTABLISHING -> Unit
        }
        check(
            update(
                current.copy(
                    replayFenceState = ReplayFenceState.ESTABLISHED,
                    continuityOrigin = null,
                    replayFenceId = fenceId,
                    replayFenceEpoch = fenceEpoch,
                    updatedAt = updatedAt,
                ),
            ) == 1,
        ) { "Replay fence transition was not persisted" }
        return ReplayFenceResult.ESTABLISHED
    }

    @Query(
        "UPDATE core_transport_state SET replay_fence_state = 'BLOCKED', continuity_origin = NULL, " +
            "replay_fence_id = NULL, replay_fence_epoch = NULL, updated_at = :updatedAt " +
            "WHERE singleton = 1 AND operational_generation = :generation",
    )
    suspend fun blockReplayFence(generation: Long, updatedAt: Long): Int
}

@Dao
internal interface KeystoreOperationDao {
    @Query("SELECT COUNT(*) FROM keystore_operation")
    suspend fun countAll(): Int

    @Query("SELECT * FROM keystore_operation WHERE operation_id = :operationId LIMIT 1")
    fun observe(operationId: String): Flow<KeystoreOperationEntity?>

    @Query("SELECT * FROM keystore_operation WHERE operation_id = :operationId LIMIT 1")
    suspend fun find(operationId: String): KeystoreOperationEntity?

    @Query(
        "SELECT * FROM keystore_operation WHERE state IN ('PENDING', 'RETRYABLE') " +
            "ORDER BY updated_at ASC, operation_id ASC",
    )
    fun observePending(): Flow<List<KeystoreOperationEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: KeystoreOperationEntity): Long

    /**
     * Establishes one immutable operation identity. A deterministic operation ID may be replayed after process
     * death, but it may never be rebound to a different target or operation kind.
     */
    @Transaction
    suspend fun ensure(entity: KeystoreOperationEntity): KeystoreOperationEnsureResult {
        val existing = find(entity.operationId)
        if (existing == null && insertIfAbsent(entity) != -1L) {
            return KeystoreOperationEnsureResult.INSERTED
        }
        val committed = find(entity.operationId) ?: error("Keystore operation intent was not persisted")
        if (!committed.sameOperationIdentity(entity)) return KeystoreOperationEnsureResult.CONFLICT
        return when (committed.state) {
            KeystoreOperationState.PENDING -> KeystoreOperationEnsureResult.EXISTING_PENDING
            KeystoreOperationState.RETRYABLE -> KeystoreOperationEnsureResult.EXISTING_RETRYABLE
            KeystoreOperationState.APPLIED -> KeystoreOperationEnsureResult.EXISTING_APPLIED
            KeystoreOperationState.BLOCKED -> KeystoreOperationEnsureResult.EXISTING_BLOCKED
        }
    }

    @Query(
        "UPDATE keystore_operation SET state = :targetState, attempts = attempts + 1, " +
            "completed_at = :completedAt, last_error_code = :errorCode, updated_at = :updatedAt " +
            "WHERE operation_id = :operationId AND state = :expectedState AND attempts = :expectedAttempts " +
            "AND updated_at <= :updatedAt",
    )
    suspend fun transitionState(
        operationId: String,
        expectedState: KeystoreOperationState,
        expectedAttempts: Int,
        targetState: KeystoreOperationState,
        completedAt: Long?,
        errorCode: String?,
        updatedAt: Long,
    ): Int
}

@Dao
internal interface CoreCommandAppliedDao {
    @Query("SELECT COUNT(*) FROM core_command_applied")
    suspend fun countAll(): Int

    @Query("DELETE FROM core_command_applied")
    suspend fun clearAll(): Int

    @Query("SELECT * FROM core_command_applied WHERE command_id = :commandId LIMIT 1")
    fun observe(commandId: String): Flow<CoreCommandAppliedEntity?>

    @Query("SELECT * FROM core_command_applied WHERE command_id = :commandId LIMIT 1")
    suspend fun find(commandId: String): CoreCommandAppliedEntity?

    /** Only [CoreRoomStore.applyCoreTrustCommand] may call this inside the owning aggregate transaction. */
    @Insert
    suspend fun insertRequired(entity: CoreCommandAppliedEntity)

    @Query(
        "SELECT * FROM core_command_applied AS marker WHERE applied_at < :cutoff " +
            "AND NOT EXISTS (SELECT 1 FROM core_activity_outbox AS activity " +
            "WHERE activity.command_id = marker.command_id) " +
            "ORDER BY applied_at ASC, command_id ASC LIMIT :limit",
    )
    suspend fun findPrunableBefore(cutoff: Long, limit: Int): List<CoreCommandAppliedEntity>

    /** Exact, bounded retention cleanup. A pending deterministic Activity obligation always protects its marker. */
    @Query(
        "DELETE FROM core_command_applied WHERE command_id = :commandId " +
            "AND authenticated_request_id = :authenticatedRequestId AND command_digest = :commandDigest " +
            "AND command_type = :commandType AND outcome = :outcome AND core_revision = :coreRevision " +
            "AND applied_at = :appliedAt " +
            "AND NOT EXISTS (SELECT 1 FROM core_activity_outbox WHERE command_id = :commandId)",
    )
    suspend fun deleteExactIfActivityAbsent(
        commandId: String,
        authenticatedRequestId: String,
        commandDigest: ByteArray,
        commandType: String,
        outcome: CoreCommandOutcome,
        coreRevision: Long,
        appliedAt: Long,
    ): Int
}

@Dao
internal interface CoreActivityOutboxDao {
    @Query("SELECT COUNT(*) FROM core_activity_outbox")
    suspend fun countAll(): Int

    @Query("DELETE FROM core_activity_outbox")
    suspend fun clearAll(): Int

    @Query("SELECT * FROM core_activity_outbox WHERE command_id = :commandId LIMIT 1")
    suspend fun findForCommand(commandId: String): CoreActivityOutboxEntity?

    @Query("SELECT * FROM core_activity_outbox WHERE event_id = :eventId LIMIT 1")
    fun observe(eventId: String): Flow<CoreActivityOutboxEntity?>

    @Query("SELECT * FROM core_activity_outbox WHERE event_id = :eventId LIMIT 1")
    suspend fun find(eventId: String): CoreActivityOutboxEntity?

    /** Only an owning Core aggregate transaction may create a new outbox obligation. */
    @Insert
    suspend fun insertRequired(entity: CoreActivityOutboxEntity)

    /** Acknowledge only the exact generation-tagged row copied (or invalidated by a later generation). */
    @Query(
        "DELETE FROM core_activity_outbox WHERE event_id = :eventId AND operational_generation = :generation",
    )
    suspend fun acknowledgeCopied(eventId: String, generation: Long): Int
}

private fun KeystoreOperationEntity.sameOperationIdentity(other: KeystoreOperationEntity): Boolean =
    targetType == other.targetType &&
        targetId == other.targetId &&
        operationKind == other.operationKind

private fun CoreTransportStateEntity.sameInitialTransport(other: CoreTransportStateEntity): Boolean =
    brokerUrl == other.brokerUrl &&
        groupId == other.groupId &&
        fcmRouteRef == other.fcmRouteRef &&
        routeEpoch == other.routeEpoch &&
        brokerEndpointRevision == other.brokerEndpointRevision &&
        selfEpochActivatedAt == other.selfEpochActivatedAt &&
        operationalGeneration == other.operationalGeneration &&
        operationalIncarnationId == other.operationalIncarnationId &&
        replayFenceState == other.replayFenceState &&
        continuityOrigin == other.continuityOrigin &&
        replayFenceId == other.replayFenceId &&
        replayFenceEpoch == other.replayFenceEpoch
