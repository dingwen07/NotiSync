package net.extrawdw.apps.notisync.data.storage.core

import android.content.Context
import androidx.room3.withWriteTransaction
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

/**
 * Room-only implementation detail behind [CoreFoundationRepository]. Keeping this type separate prevents the
 * repository boundary from accepting a database or exposing DAO/entity types to its consumers.
 */
internal class CoreRoomStore private constructor(
    private val database: CoreDatabase,
) {
    fun observeMaintenance(): Flow<CoreMaintenanceStateEntity?> = database.maintenanceStateDao().observe()
    suspend fun saveMaintenance(entity: CoreMaintenanceStateEntity) = database.maintenanceStateDao().upsert(entity)

    fun observeIdentity(): Flow<IdentityMetadataEntity?> = database.identityMetadataDao().observe()
    suspend fun getIdentity(): IdentityMetadataEntity? = database.identityMetadataDao().get()
    suspend fun saveIdentity(entity: IdentityMetadataEntity): IdentityMetadataSaveResult =
        database.withWriteTransaction {
            val current = database.identityMetadataDao().get()
            if (current != null) {
                return@withWriteTransaction if (current.sameIdentityMetadata(entity)) {
                    IdentityMetadataSaveResult.ALREADY_CURRENT
                } else {
                    IdentityMetadataSaveResult.CONFLICT
                }
            }
            // A raw/tampered dependent row must not let a later save silently rebind its missing authority.
            if (database.trustSnapshotDao().get() != null ||
                database.cryptoEpochDao().getAll().isNotEmpty() ||
                database.transportStateDao().get() != null
            ) {
                return@withWriteTransaction IdentityMetadataSaveResult.CONFLICT
            }
            database.identityMetadataDao().upsert(entity)
            IdentityMetadataSaveResult.SAVED
        }
    suspend fun updateIdentityLifecycle(
        state: IdentityLifecycleState,
        updatedAt: Long,
    ): Int = database.identityMetadataDao().updateLifecycle(state, updatedAt)

    fun observeTrust(): Flow<TrustSnapshotEntity?> = database.trustSnapshotDao().observe()
    suspend fun getTrust(): TrustSnapshotEntity? = database.trustSnapshotDao().get()

    /**
     * Short serialized commit after the repository has completed UTF-8, signature, and digest work. The transaction
     * rechecks both authorities that validation depended on, so a concurrent identity/trust write cannot be lost.
     */
    suspend fun compareAndReplaceTrust(
        observedIdentitySpki: ByteArray,
        observedTrust: TrustSnapshotEntity?,
        expectedSnapshotDigest: ByteArray?,
        candidate: TrustSnapshotEntity,
    ): TrustStoreWriteResult = database.withWriteTransaction {
        val identity = database.identityMetadataDao().get()
            ?: return@withWriteTransaction TrustStoreWriteResult.MissingIdentity
        if (!MessageDigest.isEqual(identity.publicSpki, observedIdentitySpki)) {
            return@withWriteTransaction TrustStoreWriteResult.Conflict
        }

        val current = database.trustSnapshotDao().get()
        if (!current.exactlyMatches(observedTrust, includeUpdatedAt = true)) {
            return@withWriteTransaction TrustStoreWriteResult.Conflict
        }
        if (current.exactlyMatches(candidate, includeUpdatedAt = false)) {
            return@withWriteTransaction TrustStoreWriteResult.AlreadyCurrent(requireNotNull(current))
        }
        if (!current?.snapshotDigest.contentEqualsOrBothNull(expectedSnapshotDigest)) {
            return@withWriteTransaction TrustStoreWriteResult.Conflict
        }

        database.trustSnapshotDao().replace(candidate)
        val persisted = checkNotNull(database.trustSnapshotDao().get()) {
            "Trust snapshot disappeared during its commit"
        }
        TrustStoreWriteResult.Applied(persisted)
    }

    fun observeCryptoEpochs(): Flow<List<CryptoEpochEntity>> = database.cryptoEpochDao().observeAll()
    suspend fun saveCryptoEpoch(entity: CryptoEpochEntity) = database.cryptoEpochDao().upsert(entity)
    suspend fun deleteCryptoEpoch(epoch: Int): Int = database.cryptoEpochDao().deleteByEpoch(epoch)

    fun observeBrokerAuthToken(): Flow<BrokerAuthTokenEntity?> = database.brokerAuthTokenDao().observe()
    suspend fun saveBrokerAuthToken(entity: BrokerAuthTokenEntity): BrokerAuthTokenSaveResult =
        database.withWriteTransaction {
            val transport = database.transportStateDao().get()
                ?: return@withWriteTransaction BrokerAuthTokenSaveResult.MISSING_TRANSPORT
            if (transport.brokerEndpointRevision != entity.brokerEndpointRevision) {
                return@withWriteTransaction BrokerAuthTokenSaveResult.STALE_ENDPOINT
            }
            database.brokerAuthTokenDao().upsert(entity)
            BrokerAuthTokenSaveResult.SAVED
        }

    suspend fun clearBrokerAuthToken(): Int = database.brokerAuthTokenDao().clear()

    fun observeTransport(): Flow<CoreTransportStateEntity?> = database.transportStateDao().observe()
    suspend fun initializeTransport(entity: CoreTransportStateEntity): CoreTransportInitializationResult =
        database.transportStateDao().initialize(entity)

    /** Fresh authority becomes visible only when its active crypto epoch and transport commit together. */
    suspend fun initializeFreshAuthority(
        cryptoEpoch: CryptoEpochEntity,
        transport: CoreTransportStateEntity,
    ): CoreTransportInitializationResult = database.withWriteTransaction {
        if (database.identityMetadataDao().get() == null) {
            return@withWriteTransaction CoreTransportInitializationResult.CONFLICT
        }
        val currentTransport = database.transportStateDao().get()
        if (currentTransport != null) {
            val currentEpoch = database.cryptoEpochDao().find(cryptoEpoch.epoch)
            return@withWriteTransaction if (
                currentTransport.sameInitialTransportForAuthority(transport) &&
                currentEpoch.sameFreshCryptoEpoch(cryptoEpoch)
            ) {
                CoreTransportInitializationResult.ALREADY_INITIALIZED
            } else {
                CoreTransportInitializationResult.CONFLICT
            }
        }
        if (database.cryptoEpochDao().getAll().isNotEmpty()) {
            return@withWriteTransaction CoreTransportInitializationResult.CONFLICT
        }
        check(database.cryptoEpochDao().insertIfAbsent(cryptoEpoch) != -1L) {
            "Fresh crypto epoch raced with another authority initializer"
        }
        check(database.transportStateDao().insertIfAbsent(transport) != -1L) {
            "Fresh transport raced with another authority initializer"
        }
        CoreTransportInitializationResult.INITIALIZED
    }

    suspend fun advanceRoute(update: RouteMutation): RouteAdvanceResult =
        database.transportStateDao().advanceRoute(update)

    suspend fun setGroupId(groupId: String?, updatedAt: Long): GroupIdUpdateResult =
        database.transportStateDao().setGroupId(groupId, updatedAt)

    suspend fun changeBrokerEndpoint(
        canonicalEndpoint: String,
        updatedAt: Long,
    ): BrokerEndpointChangeResult = database.withWriteTransaction {
        val current = database.transportStateDao().get()
            ?: return@withWriteTransaction BrokerEndpointChangeResult.MISSING
        if (current.brokerUrl == canonicalEndpoint) {
            return@withWriteTransaction BrokerEndpointChangeResult.UNCHANGED
        }
        val nextRevision = Math.addExact(current.brokerEndpointRevision, 1L)
        // Delete first so the composite FK also makes the required invalidation executable. A later failure
        // rolls this deletion back with the transport update.
        database.brokerAuthTokenDao().clear()
        check(
            database.transportStateDao().update(
                current.copy(
                    brokerUrl = canonicalEndpoint,
                    brokerEndpointRevision = nextRevision,
                    fcmRouteRef = null,
                    updatedAt = updatedAt,
                ),
            ) == 1,
        ) { "Broker endpoint transition was not persisted" }
        BrokerEndpointChangeResult.CHANGED
    }

    suspend fun advanceOperationalGeneration(
        generation: Long,
        updatedAt: Long,
    ): OperationalGenerationResult =
        database.transportStateDao().advanceOperationalGeneration(generation, updatedAt)

    suspend fun beginReplayFence(generation: Long, updatedAt: Long): ReplayFenceResult =
        database.transportStateDao().beginReplayFence(generation, updatedAt)

    suspend fun establishReplayFence(
        generation: Long,
        fenceId: String,
        fenceEpoch: Int,
        updatedAt: Long,
    ): ReplayFenceResult = database.transportStateDao().establishReplayFence(
        generation,
        fenceId,
        fenceEpoch,
        updatedAt,
    )

    suspend fun blockReplayFence(generation: Long, updatedAt: Long): Int =
        database.transportStateDao().blockReplayFence(generation, updatedAt)

    fun observePendingKeystoreOperations(): Flow<List<KeystoreOperationEntity>> =
        database.keystoreOperationDao().observePending()

    suspend fun getKeystoreOperation(operationId: String): KeystoreOperationEntity? =
        database.keystoreOperationDao().find(operationId)

    suspend fun ensureKeystoreOperation(entity: KeystoreOperationEntity): KeystoreOperationEnsureResult =
        database.keystoreOperationDao().ensure(entity)

    suspend fun transitionKeystoreOperation(
        operationId: String,
        expectedState: KeystoreOperationState,
        expectedAttempts: Int,
        targetState: KeystoreOperationState,
        completedAt: Long?,
        errorCode: String?,
        updatedAt: Long,
    ): Int = database.keystoreOperationDao().transitionState(
        operationId,
        expectedState,
        expectedAttempts,
        targetState,
        completedAt,
        errorCode,
        updatedAt,
    )

    fun observeCoreCommand(commandId: String): Flow<CoreCommandAppliedEntity?> =
        database.commandAppliedDao().observe(commandId)

    suspend fun getCoreCommand(commandId: String): CoreCommandAppliedEntity? =
        database.commandAppliedDao().find(commandId)

    suspend fun getCommandActivity(commandId: String): CoreActivityOutboxEntity? =
        database.activityOutboxDao().findForCommand(commandId)

    fun observeActivity(eventId: String): Flow<CoreActivityOutboxEntity?> =
        database.activityOutboxDao().observe(eventId)

    suspend fun getActivity(eventId: String): CoreActivityOutboxEntity? =
        database.activityOutboxDao().find(eventId)

    /**
     * The only inbound Core-command write boundary. All CPU-heavy canonical hashing, reducer work, signature
     * verification, and Activity encoding have already completed. This transaction rechecks every authority the
     * reducer observed, applies the signed trust aggregate, and creates the marker/outbox obligation atomically.
     *
     * A deterministic outbox collision with no matching applied marker is an integrity conflict and performs no
     * writes. A real SQLite failure (including an insert trigger/constraint or full disk) escapes this method and
     * Room rolls back the trust mutation, marker, and outbox together; the staged Operational command remains
     * retryable. Once committed, later Activity drain failures cannot affect the authoritative Core mutation.
     */
    suspend fun applyCoreTrustCommand(
        identity: PreparedCoreCommandIdentity,
        activityEventId: String,
        expectedOperationalGeneration: Long,
        expectedOperationalIncarnationId: String,
        observedIdentity: IdentityMetadataEntity,
        observedTrust: TrustSnapshotEntity?,
        expectedSnapshotDigest: ByteArray?,
        candidate: TrustSnapshotEntity,
        activity: PreparedCoreCommandActivity?,
        appliedAt: Long,
    ): CoreCommandStoreResult {
        require(identity.commandDigest.size == CORE_COMMAND_DIGEST_BYTES) {
            "Core command digest must be SHA-256"
        }
        require(activity == null || activity.eventId == activityEventId) {
            "Core Activity event identity diverged"
        }
        validateOperationalStorageIncarnationId(expectedOperationalIncarnationId)
        return database.withWriteTransaction {
            val existingMarker = database.commandAppliedDao().find(identity.commandId)
            if (existingMarker != null) {
                return@withWriteTransaction if (existingMarker.matchesIdentity(identity)) {
                    CoreCommandStoreResult.Duplicate(
                        marker = existingMarker,
                        pendingActivity = database.activityOutboxDao().findForCommand(identity.commandId),
                    )
                } else {
                    CoreCommandStoreResult.Conflict
                }
            }

            val transport = database.transportStateDao().get()
                ?: return@withWriteTransaction CoreCommandStoreResult.CoreNotReady
            if (!transport.replayFenceState.isRuntimeReady) {
                return@withWriteTransaction CoreCommandStoreResult.CoreNotReady
            }
            if (
                transport.operationalGeneration != expectedOperationalGeneration ||
                transport.operationalIncarnationId != expectedOperationalIncarnationId
            ) {
                return@withWriteTransaction CoreCommandStoreResult.StaleCoreState
            }

            val currentIdentity = database.identityMetadataDao().get()
                ?: return@withWriteTransaction CoreCommandStoreResult.MissingIdentity
            if (currentIdentity.lifecycleState != IdentityLifecycleState.ACTIVE) {
                return@withWriteTransaction CoreCommandStoreResult.CoreNotReady
            }
            if (!currentIdentity.sameIdentityMetadata(observedIdentity) ||
                currentIdentity.updatedAt != observedIdentity.updatedAt
            ) {
                return@withWriteTransaction CoreCommandStoreResult.StaleCoreState
            }

            val currentTrust = database.trustSnapshotDao().get()
            if (!currentTrust.exactlyMatches(observedTrust, includeUpdatedAt = true) ||
                !currentTrust?.snapshotDigest.contentEqualsOrBothNull(expectedSnapshotDigest)
            ) {
                return@withWriteTransaction CoreCommandStoreResult.StaleCoreState
            }

            val outcome = if (currentTrust.exactlyMatches(candidate, includeUpdatedAt = false)) {
                CoreCommandOutcome.SUPERSEDED
            } else {
                CoreCommandOutcome.APPLIED
            }
            val pendingActivity = if (outcome == CoreCommandOutcome.APPLIED && activity != null) {
                if (database.activityOutboxDao().find(activity.eventId) != null) {
                    return@withWriteTransaction CoreCommandStoreResult.Conflict
                }
                CoreActivityOutboxEntity(
                    commandId = identity.commandId,
                    eventId = activity.eventId,
                    operationalGeneration = transport.operationalGeneration,
                    feature = activity.feature,
                    semanticAction = activity.semanticAction,
                    direction = activity.direction,
                    outcome = activity.outcome,
                    peerClientId = activity.peerClientId,
                    correlationId = activity.correlationId,
                    deliveryMode = activity.deliveryMode,
                    argsVersion = activity.argsVersion,
                    renderArgs = activity.renderArgs,
                    occurredAt = activity.occurredAt,
                    createdAt = appliedAt,
                )
            } else {
                null
            }

            if (outcome == CoreCommandOutcome.APPLIED) {
                database.trustSnapshotDao().replace(candidate)
                val persistedTrust = checkNotNull(database.trustSnapshotDao().get()) {
                    "Trust snapshot disappeared during Core-command commit"
                }
                check(persistedTrust.exactlyMatches(candidate, includeUpdatedAt = true)) {
                    "Trust snapshot readback diverged during Core-command commit"
                }
            }

            val coreRevision = when (outcome) {
                CoreCommandOutcome.APPLIED -> candidate.updatedAt
                CoreCommandOutcome.SUPERSEDED -> requireNotNull(currentTrust).updatedAt
                CoreCommandOutcome.TERMINAL_REJECTED ->
                    error("A reducer-produced trust command cannot be rejected here")
            }
            val marker = CoreCommandAppliedEntity(
                commandId = identity.commandId,
                authenticatedRequestId = identity.authenticatedRequestId,
                commandDigest = identity.commandDigest,
                commandType = identity.commandType,
                outcome = outcome,
                // Trust has no independent numeric revision column; its authoritative updated_at is the stable
                // revision reported to Operational finalization, while the signed snapshot digest remains authority.
                coreRevision = coreRevision,
                appliedAt = appliedAt,
            )
            database.commandAppliedDao().insertRequired(marker)
            pendingActivity?.let { database.activityOutboxDao().insertRequired(it) }

            val persistedMarker = checkNotNull(database.commandAppliedDao().find(identity.commandId)) {
                "Core command marker disappeared during its commit"
            }
            check(persistedMarker.matchesIdentity(identity) && persistedMarker.outcome == outcome) {
                "Core command marker readback diverged"
            }
            val persistedActivity = pendingActivity?.let { expected ->
                checkNotNull(database.activityOutboxDao().find(expected.eventId)) {
                    "Core Activity obligation disappeared during its commit"
                }
            }
            CoreCommandStoreResult.Committed(
                marker = persistedMarker,
                pendingActivity = persistedActivity,
            )
        }
    }

    suspend fun acknowledgeCopiedActivity(eventId: String, generation: Long): Int =
        database.activityOutboxDao().acknowledgeCopied(eventId, generation)

    /**
     * Deletes at most [limit] expired markers, while retaining every marker whose deterministic Core Activity
     * obligation is still pending.
     */
    suspend fun pruneRetainedCoreCommandMarkers(cutoff: Long, limit: Int): Int {
        require(cutoff > 0) { "Core command marker cutoff must be positive" }
        require(limit in 1..MAX_CORE_COMMAND_MARKER_PRUNE_BATCH) {
            "Core command marker prune limit is invalid"
        }
        return database.withWriteTransaction {
            database.commandAppliedDao().findPrunableBefore(cutoff, limit).sumOf { marker ->
                database.commandAppliedDao().deleteExactIfActivityAbsent(
                    commandId = marker.commandId,
                    authenticatedRequestId = marker.authenticatedRequestId,
                    commandDigest = marker.commandDigest.copyOf(),
                    commandType = marker.commandType,
                    outcome = marker.outcome,
                    coreRevision = marker.coreRevision,
                    appliedAt = marker.appliedAt,
                )
            }
        }
    }

    companion object {
        fun processSingleton(context: Context): CoreRoomStore =
            CoreRoomStore(CoreDatabaseFactory.get(context.applicationContext))

        /** Storage-test seam; production callers never receive or pass a Room database. */
        internal fun forDatabase(database: CoreDatabase): CoreRoomStore = CoreRoomStore(database)
    }
}

private fun CoreTransportStateEntity.sameInitialTransportForAuthority(other: CoreTransportStateEntity): Boolean =
    brokerUrl == other.brokerUrl && groupId == other.groupId && fcmRouteRef == other.fcmRouteRef &&
        routeEpoch == other.routeEpoch && brokerEndpointRevision == other.brokerEndpointRevision &&
        selfEpochActivatedAt == other.selfEpochActivatedAt &&
        operationalGeneration == other.operationalGeneration &&
        operationalIncarnationId == other.operationalIncarnationId &&
        replayFenceState == other.replayFenceState && continuityOrigin == other.continuityOrigin &&
        replayFenceId == other.replayFenceId && replayFenceEpoch == other.replayFenceEpoch

private fun CryptoEpochEntity?.sameFreshCryptoEpoch(other: CryptoEpochEntity): Boolean = this != null &&
    epoch == other.epoch && operationalSignerAlias == other.operationalSignerAlias &&
    operationalSignerPublicSpki.contentEquals(other.operationalSignerPublicSpki) &&
    hpkePublicKeyset.contentEquals(other.hpkePublicKeyset) &&
    hpkePrivateKeysetWrapped.contentEqualsOrBothNull(other.hpkePrivateKeysetWrapped) &&
    securityLevel == other.securityLevel && lifecycleState == other.lifecycleState &&
    antiRollbackFloor == other.antiRollbackFloor && activationAt == other.activationAt &&
    retirementAt == other.retirementAt && createdAt == other.createdAt

private const val MAX_CORE_COMMAND_MARKER_PRUNE_BATCH = 64

internal sealed interface TrustStoreWriteResult {
    data class Applied(val persisted: TrustSnapshotEntity) : TrustStoreWriteResult
    data class AlreadyCurrent(val persisted: TrustSnapshotEntity) : TrustStoreWriteResult
    data object Conflict : TrustStoreWriteResult
    data object MissingIdentity : TrustStoreWriteResult
}

internal sealed interface CoreCommandStoreResult {
    data class Committed(
        val marker: CoreCommandAppliedEntity,
        val pendingActivity: CoreActivityOutboxEntity?,
    ) : CoreCommandStoreResult

    data class Duplicate(
        val marker: CoreCommandAppliedEntity,
        val pendingActivity: CoreActivityOutboxEntity?,
    ) : CoreCommandStoreResult

    data object Conflict : CoreCommandStoreResult
    data object StaleCoreState : CoreCommandStoreResult
    data object MissingIdentity : CoreCommandStoreResult
    data object CoreNotReady : CoreCommandStoreResult
}

private fun TrustSnapshotEntity?.exactlyMatches(
    other: TrustSnapshotEntity?,
    includeUpdatedAt: Boolean,
): Boolean {
    if (this == null || other == null) return this == null && other == null
    return singleton == other.singleton &&
        signatureFormat == other.signatureFormat &&
        MessageDigest.isEqual(entriesUtf8, other.entriesUtf8) &&
        MessageDigest.isEqual(cardsUtf8, other.cardsUtf8) &&
        MessageDigest.isEqual(overlaysUtf8, other.overlaysUtf8) &&
        epochsUtf8.contentEqualsOrBothNull(other.epochsUtf8) &&
        MessageDigest.isEqual(signatureBase64UrlUtf8, other.signatureBase64UrlUtf8) &&
        MessageDigest.isEqual(snapshotDigest, other.snapshotDigest) &&
        (!includeUpdatedAt || updatedAt == other.updatedAt)
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean = when {
    this == null || other == null -> this == null && other == null
    else -> MessageDigest.isEqual(this, other)
}

private fun IdentityMetadataEntity.sameIdentityMetadata(other: IdentityMetadataEntity): Boolean =
    singleton == other.singleton && keyAlias == other.keyAlias && keyAliasVersion == other.keyAliasVersion &&
        MessageDigest.isEqual(publicSpki, other.publicSpki) && clientId == other.clientId &&
        securityLevel == other.securityLevel && lifecycleState == other.lifecycleState &&
        createdAt == other.createdAt
