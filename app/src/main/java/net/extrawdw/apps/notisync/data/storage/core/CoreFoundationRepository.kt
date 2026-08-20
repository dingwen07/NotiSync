package net.extrawdw.apps.notisync.data.storage.core

import android.content.Context
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.crypto.ClientIds
import net.extrawdw.notisync.protocol.crypto.TrustStoreSigning

/**
 * Domain boundary for foundation state. Room databases, DAOs, and entities never cross this API. Byte arrays are
 * copied both when accepted and when projected so a caller cannot mutate durable state through a retained buffer.
 */
internal class CoreFoundationRepository(
    private val store: CoreRoomStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val maintenance: Flow<CoreMaintenanceSnapshot?> = store.observeMaintenance().map { it?.toSnapshot() }
    val identity: Flow<IdentityMetadataSnapshot?> = store.observeIdentity().map { it?.toSnapshot() }
    val trust: Flow<TrustSnapshot?> = combine(store.observeIdentity(), store.observeTrust()) { identity, trust ->
        when {
            trust == null -> null
            identity == null -> throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.MISSING_IDENTITY)
            else -> validateStoredTrust(identity, trust)
        }
    }.flowOn(Dispatchers.Default)
    val cryptoEpochs: Flow<List<CryptoEpochSnapshot>> =
        store.observeCryptoEpochs().map { rows -> rows.map(CryptoEpochEntity::toSnapshot) }
    val brokerAuthToken: Flow<BrokerAuthTokenSnapshot?> = store.observeBrokerAuthToken().map { it?.toSnapshot() }
    val transport: Flow<CoreTransportSnapshot?> = store.observeTransport().map { it?.toSnapshot() }
    val pendingKeystoreOperations: Flow<List<KeystoreOperationSnapshot>> =
        store.observePendingKeystoreOperations().map { rows -> rows.map(KeystoreOperationEntity::toSnapshot) }

    fun observeCoreCommand(commandId: String): Flow<CoreCommandSnapshot?> {
        require(commandId.isNotBlank()) { "commandId must not be blank" }
        return store.observeCoreCommand(commandId).map { it?.toSnapshot() }
    }

    fun observeActivity(eventId: String): Flow<CoreActivitySnapshot?> {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        return store.observeActivity(eventId).map { it?.toSnapshot() }
    }

    suspend fun saveMaintenanceState(update: CoreMaintenanceUpdate) {
        require(
            (update.trustCleanupState == TrustCleanupState.COMPLETE) ==
                (update.trustCleanupCompletedAt != null),
        ) { "Only completed trust cleanup has a completion time" }
        store.saveMaintenance(
            CoreMaintenanceStateEntity(
                trustCleanupState = update.trustCleanupState,
                trustCleanupCompletedAt = update.trustCleanupCompletedAt,
                updatedAt = now(),
            ),
        )
    }

    suspend fun saveIdentityMetadata(metadata: IdentityMetadataInput): IdentityMetadataSaveResult {
        require(metadata.keyAlias.isNotBlank()) { "keyAlias must not be blank" }
        require(metadata.keyAliasVersion > 0) { "keyAliasVersion must be positive" }
        require(metadata.publicSpki.isNotEmpty()) { "publicSpki must not be empty" }
        require(metadata.createdAt >= 0) { "createdAt must not be negative" }
        return store.saveIdentity(
            IdentityMetadataEntity(
                keyAlias = metadata.keyAlias,
                keyAliasVersion = metadata.keyAliasVersion,
                publicSpki = metadata.publicSpki.copyOf(),
                // client_id is a materialized lookup projection of the authoritative SPKI. Derive it here so
                // the functional dependency cannot diverge through an independently supplied value.
                clientId = ClientIds.derive(metadata.publicSpki).value,
                securityLevel = metadata.securityLevel,
                lifecycleState = metadata.lifecycleState,
                createdAt = metadata.createdAt,
                updatedAt = now(),
            ),
        )
    }

    suspend fun updateIdentityLifecycle(state: IdentityLifecycleState): Boolean =
        store.updateIdentityLifecycle(state, now()) == 1

    /**
     * Verifies exact signed bytes and computes their identity outside Room, then performs a short CAS transaction.
     * A null [expectedSnapshotDigest] means "no prior snapshot"; an exact replay is nevertheless timestamp-stable.
     */
    suspend fun replaceTrustSnapshot(
        snapshot: TrustSnapshotInput,
        expectedSnapshotDigest: ByteArray? = null,
    ): TrustSnapshotWriteResult {
        require(expectedSnapshotDigest == null || expectedSnapshotDigest.size == TRUST_SNAPSHOT_DIGEST_BYTES) {
            "expected trust snapshot digest must be SHA-256"
        }
        val identity = store.getIdentity() ?: return TrustSnapshotWriteResult.MISSING_IDENTITY
        validateIdentityProjection(identity)
        val observedTrust = store.getTrust()
        observedTrust?.let { validateStoredTrust(identity, it) }

        val exact = snapshot.exactBytes()
        val validated = validateExactTrust(identity, exact)
        val candidate = TrustSnapshotEntity(
            signatureFormat = exact.signatureFormat.token,
            entriesUtf8 = exact.entriesUtf8.copyOf(),
            cardsUtf8 = exact.cardsUtf8.copyOf(),
            overlaysUtf8 = exact.overlaysUtf8.copyOf(),
            epochsUtf8 = exact.epochsUtf8?.copyOf(),
            signatureBase64UrlUtf8 = exact.signatureBase64UrlUtf8.copyOf(),
            snapshotDigest = validated.digest.copyOf(),
            updatedAt = now(),
        )
        return when (
            val result = store.compareAndReplaceTrust(
                observedIdentitySpki = identity.publicSpki.copyOf(),
                observedTrust = observedTrust?.defensiveCopy(),
                expectedSnapshotDigest = expectedSnapshotDigest?.copyOf(),
                candidate = candidate,
            )
        ) {
            is TrustStoreWriteResult.Applied -> {
                requireExactReadback(candidate, result.persisted, includeUpdatedAt = true)
                validateStoredTrust(identity, result.persisted)
                TrustSnapshotWriteResult.APPLIED
            }
            is TrustStoreWriteResult.AlreadyCurrent -> {
                requireExactReadback(candidate, result.persisted, includeUpdatedAt = false)
                validateStoredTrust(identity, result.persisted)
                TrustSnapshotWriteResult.ALREADY_CURRENT
            }
            TrustStoreWriteResult.Conflict -> TrustSnapshotWriteResult.CONFLICT
            TrustStoreWriteResult.MissingIdentity -> TrustSnapshotWriteResult.MISSING_IDENTITY
        }
    }

    /** One-shot semantic readiness check used by bootstrap verification and recovery diagnostics. */
    suspend fun loadValidatedTrustSnapshot(): TrustSnapshot? {
        val trust = store.getTrust() ?: return null
        val identity = store.getIdentity()
            ?: throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.MISSING_IDENTITY)
        return validateStoredTrust(identity, trust)
    }

    suspend fun saveCryptoEpoch(epoch: CryptoEpochInput) {
        require(epoch.epoch > 0) { "epoch must be positive" }
        require(epoch.operationalSignerAlias.isNotBlank()) { "operationalSignerAlias must not be blank" }
        require(epoch.operationalSignerPublicSpki.isNotEmpty()) { "operational signer SPKI must not be empty" }
        require(epoch.hpkePublicKeyset.isNotEmpty()) { "HPKE public keyset must not be empty" }
        require(epoch.antiRollbackFloor >= 0) { "antiRollbackFloor must not be negative" }
        require(epoch.createdAt >= 0) { "createdAt must not be negative" }
        if (epoch.lifecycleState == CryptoEpochState.ACTIVE) {
            require(epoch.hpkePrivateKeysetWrapped?.isNotEmpty() == true) {
                "An active crypto epoch requires wrapped HPKE private material"
            }
            require(epoch.activationAt != null) { "An active crypto epoch requires activationAt" }
        }
        store.saveCryptoEpoch(
            CryptoEpochEntity(
                epoch = epoch.epoch,
                operationalSignerAlias = epoch.operationalSignerAlias,
                operationalSignerPublicSpki = epoch.operationalSignerPublicSpki.copyOf(),
                hpkePublicKeyset = epoch.hpkePublicKeyset.copyOf(),
                hpkePrivateKeysetWrapped = epoch.hpkePrivateKeysetWrapped?.copyOf(),
                securityLevel = epoch.securityLevel,
                lifecycleState = epoch.lifecycleState,
                antiRollbackFloor = epoch.antiRollbackFloor,
                activationAt = epoch.activationAt,
                retirementAt = epoch.retirementAt,
                createdAt = epoch.createdAt,
                updatedAt = now(),
            ),
        )
    }

    suspend fun deleteCryptoEpoch(epoch: Int): Boolean {
        require(epoch > 0) { "epoch must be positive" }
        return store.deleteCryptoEpoch(epoch) == 1
    }

    suspend fun saveBrokerAuthToken(token: BrokerAuthTokenInput): BrokerAuthTokenSaveResult {
        require(token.wrappedToken.isNotEmpty()) { "wrappedToken must not be empty" }
        require(token.encodingVersion > 0) { "encodingVersion must be positive" }
        require(token.issuedAt == null || token.issuedAt >= 0) { "issuedAt must not be negative" }
        require(token.expiresAt == null || token.expiresAt >= 0) { "expiresAt must not be negative" }
        require(token.issuedAt == null || token.expiresAt == null || token.expiresAt >= token.issuedAt) {
            "expiresAt must not precede issuedAt"
        }
        require(token.expectedBrokerEndpointRevision >= 0) {
            "expectedBrokerEndpointRevision must not be negative"
        }
        return store.saveBrokerAuthToken(
            BrokerAuthTokenEntity(
                wrappedToken = token.wrappedToken.copyOf(),
                encodingVersion = token.encodingVersion,
                issuedAt = token.issuedAt,
                expiresAt = token.expiresAt,
                brokerEndpointRevision = token.expectedBrokerEndpointRevision,
                updatedAt = now(),
            ),
        )
    }

    suspend fun clearBrokerAuthToken(): Boolean = store.clearBrokerAuthToken() == 1

    /** A brand-new identity starts at generation 1 with an empty, therefore intact, operational ledger. */
    suspend fun initializeFreshAuthority(
        initialization: FreshIdentityTransportInitialization,
        operationalStorage: OperationalStorageBinding,
        cryptoEpoch: CryptoEpochInput,
    ): CoreTransportInitializationResult {
        require(operationalStorage.operationalGeneration == INITIAL_OPERATIONAL_GENERATION) {
            "Fresh identity authority must start at Operational generation 1"
        }
        require(cryptoEpoch.epoch == 1 && cryptoEpoch.lifecycleState == CryptoEpochState.ACTIVE) {
            "Fresh identity authority requires active crypto epoch 1"
        }
        require(cryptoEpoch.hpkePrivateKeysetWrapped?.isNotEmpty() == true) {
            "Fresh identity authority requires wrapped HPKE private material"
        }
        val transport = newContinuityTransport(
            brokerUrl = initialization.brokerUrl,
            groupId = null,
            fcmRouteRef = null,
            routeEpoch = 0,
            selfEpochActivatedAt = null,
            operationalStorage = operationalStorage,
            continuityOrigin = OperationalContinuityOrigin.FRESH_IDENTITY,
        )
        val now = now()
        return store.initializeFreshAuthority(
            cryptoEpoch = cryptoEpoch.toEntity(now),
            transport = transport.copy(updatedAt = now),
        )
    }

    private suspend fun initializeContinuityTransport(
        brokerUrl: String,
        groupId: String?,
        fcmRouteRef: String?,
        routeEpoch: Long,
        selfEpochActivatedAt: Long?,
        operationalStorage: OperationalStorageBinding,
        continuityOrigin: OperationalContinuityOrigin,
    ): CoreTransportInitializationResult {
        canonicalizeBrokerEndpoint(brokerUrl)
        validateCoreGroupId(groupId)
        require(routeEpoch >= 0) { "routeEpoch must not be negative" }
        return store.initializeTransport(
            newContinuityTransport(
                brokerUrl = brokerUrl,
                groupId = groupId,
                fcmRouteRef = fcmRouteRef,
                routeEpoch = routeEpoch,
                selfEpochActivatedAt = selfEpochActivatedAt,
                operationalStorage = operationalStorage,
                continuityOrigin = continuityOrigin,
                updatedAt = now(),
            ),
        )
    }

    private fun newContinuityTransport(
        brokerUrl: String,
        groupId: String?,
        fcmRouteRef: String?,
        routeEpoch: Long,
        selfEpochActivatedAt: Long?,
        operationalStorage: OperationalStorageBinding,
        continuityOrigin: OperationalContinuityOrigin,
        updatedAt: Long = 0,
    ): CoreTransportStateEntity = CoreTransportStateEntity(
        brokerUrl = canonicalizeBrokerEndpoint(brokerUrl),
        groupId = groupId,
        fcmRouteRef = fcmRouteRef,
        routeEpoch = routeEpoch,
        brokerEndpointRevision = 0,
        selfEpochActivatedAt = selfEpochActivatedAt,
        operationalGeneration = operationalStorage.operationalGeneration,
        operationalIncarnationId = operationalStorage.storageIncarnationId,
        replayFenceState = ReplayFenceState.CONTINUITY_INTACT,
        continuityOrigin = continuityOrigin,
        replayFenceId = null,
        replayFenceEpoch = null,
        updatedAt = updatedAt,
    )

    private fun CryptoEpochInput.toEntity(updatedAt: Long): CryptoEpochEntity = CryptoEpochEntity(
        epoch = epoch,
        operationalSignerAlias = operationalSignerAlias,
        operationalSignerPublicSpki = operationalSignerPublicSpki.copyOf(),
        hpkePublicKeyset = hpkePublicKeyset.copyOf(),
        hpkePrivateKeysetWrapped = hpkePrivateKeysetWrapped?.copyOf(),
        securityLevel = securityLevel,
        lifecycleState = lifecycleState,
        antiRollbackFloor = antiRollbackFloor,
        activationAt = activationAt,
        retirementAt = retirementAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    suspend fun advanceRoute(update: RouteUpdate): RouteAdvanceResult = store.advanceRoute(
        RouteMutation(
            brokerUrl = canonicalizeBrokerEndpoint(update.brokerUrl),
            fcmRouteRef = update.fcmRouteRef,
            routeEpoch = update.routeEpoch,
            expectedBrokerEndpointRevision = update.expectedBrokerEndpointRevision,
            selfEpochActivatedAt = update.selfEpochActivatedAt,
            updatedAt = now(),
        ),
    )

    /** Group identity changes are explicit and cannot be smuggled through a route-registration callback. */
    suspend fun setGroupId(groupId: String?): GroupIdUpdateResult {
        validateCoreGroupId(groupId)
        return store.setGroupId(groupId, now())
    }

    suspend fun changeBrokerEndpoint(endpoint: String): BrokerEndpointChangeResult {
        val canonicalEndpoint = canonicalizeBrokerEndpoint(endpoint)
        return store.changeBrokerEndpoint(canonicalEndpoint, now())
    }

    suspend fun advanceOperationalGeneration(generation: Long): OperationalGenerationResult {
        require(generation > 0) { "operational generation must be positive" }
        return store.advanceOperationalGeneration(generation, now())
    }

    suspend fun beginReplayFence(generation: Long): ReplayFenceResult = store.beginReplayFence(generation, now())

    suspend fun establishReplayFence(
        generation: Long,
        fenceId: String,
        fenceEpoch: Int,
    ): ReplayFenceResult = store.establishReplayFence(generation, fenceId, fenceEpoch, now())

    suspend fun blockReplayFence(generation: Long): Boolean = store.blockReplayFence(generation, now()) == 1

    suspend fun getKeystoreOperation(operationId: String): KeystoreOperationSnapshot? {
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        return store.getKeystoreOperation(operationId)?.toSnapshot()
    }

    suspend fun ensureKeystoreOperation(intent: KeystoreOperationIntent): KeystoreOperationEnsureResult {
        require(intent.operationId.isNotBlank()) { "operationId must not be blank" }
        require(intent.targetId.isNotBlank()) { "targetId must not be blank" }
        require(intent.createdAt >= 0) { "createdAt must not be negative" }
        return store.ensureKeystoreOperation(
            KeystoreOperationEntity(
                operationId = intent.operationId,
                targetType = intent.targetType,
                targetId = intent.targetId,
                operationKind = intent.operationKind,
                state = KeystoreOperationState.PENDING,
                attempts = 0,
                createdAt = intent.createdAt,
                updatedAt = now(),
            ),
        )
    }

    suspend fun transitionKeystoreOperation(
        operationId: String,
        expectedState: KeystoreOperationState,
        expectedAttempts: Int,
        targetState: KeystoreOperationState,
        completedAt: Long?,
        errorCode: String?,
    ): KeystoreOperationTransitionResult {
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        require(expectedState == KeystoreOperationState.PENDING || expectedState == KeystoreOperationState.RETRYABLE) {
            "Only an incomplete Keystore operation may transition"
        }
        require(expectedAttempts >= 0) { "expectedAttempts must not be negative" }
        require(targetState != KeystoreOperationState.PENDING) { "A persisted intent is already pending" }
        if (targetState == KeystoreOperationState.APPLIED) {
            require(completedAt != null) { "An applied operation requires completedAt" }
            require(errorCode == null) { "An applied operation must not retain an error" }
        } else {
            require(completedAt == null) { "An incomplete operation must not have completedAt" }
            requireCoreDiagnosticCode(errorCode)
        }
        val updated = store.transitionKeystoreOperation(
            operationId = operationId,
            expectedState = expectedState,
            expectedAttempts = expectedAttempts,
            targetState = targetState,
            completedAt = completedAt,
            errorCode = errorCode,
            updatedAt = now(),
        )
        return if (updated == 1) {
            KeystoreOperationTransitionResult.UPDATED
        } else {
            KeystoreOperationTransitionResult.STALE
        }
    }

    /**
     * Applies the Core half of an authenticated cross-database handoff.
     *
     * The caller supplies the already reduced/signed aggregate candidate, never a callback or persistence type.
     * Canonical hashing, signature/digest validation, and Activity encoding happen before the short Room write.
     * Exact applied-marker replay returns before re-running those state-dependent checks, so a later valid Core
     * transition cannot turn an already committed command into a false conflict after process death.
     */
    suspend fun applyCoreTrustCommand(command: CoreTrustCommand): CoreCommandApplyResult {
        val preparedIdentity = command.prepareIdentity()
        val activityEventId = coreCommandActivityEventId(command.commandType, command.commandId)

        store.getCoreCommand(command.commandId)?.let { marker ->
            return if (marker.matchesIdentity(preparedIdentity)) {
                CoreCommandApplyResult.Duplicate(
                    marker.toReceipt(store.getCommandActivity(command.commandId)),
                )
            } else {
                CoreCommandApplyResult.Conflict
            }
        }

        val identity = store.getIdentity() ?: return CoreCommandApplyResult.MissingIdentity
        validateIdentityProjection(identity)
        val observedTrust = store.getTrust()
        observedTrust?.let { validateStoredTrust(identity, it) }
        val expectedSnapshotDigest = command.expectedSnapshotDigestCopy()
        if (!observedTrust?.snapshotDigest.contentEqualsOrBothNull(expectedSnapshotDigest)) {
            return CoreCommandApplyResult.StaleCoreState
        }

        // The Foundation reducer/signing port produced these exact bytes before entering this repository. Verify
        // both signature and repository digest outside SQL, then let the transaction CAS the observed authority.
        val exact = command.candidateSnapshot.exactBytes()
        val validated = validateExactTrust(identity, exact)
        val appliedAt = now()
        require(appliedAt > 0) { "Core command application time must be positive" }
        val candidate = TrustSnapshotEntity(
            signatureFormat = exact.signatureFormat.token,
            entriesUtf8 = exact.entriesUtf8.copyOf(),
            cardsUtf8 = exact.cardsUtf8.copyOf(),
            overlaysUtf8 = exact.overlaysUtf8.copyOf(),
            epochsUtf8 = exact.epochsUtf8?.copyOf(),
            signatureBase64UrlUtf8 = exact.signatureBase64UrlUtf8.copyOf(),
            snapshotDigest = validated.digest.copyOf(),
            updatedAt = appliedAt,
        )
        val preparedActivity = command.prepareActivity()
        check(preparedActivity == null || preparedActivity.eventId == activityEventId) {
            "Prepared Core Activity identity diverged"
        }

        return when (
            val result = store.applyCoreTrustCommand(
                identity = preparedIdentity,
                activityEventId = activityEventId,
                expectedOperationalGeneration = command.expectedOperationalGeneration,
                expectedOperationalIncarnationId = command.expectedOperationalIncarnationId,
                observedIdentity = identity.copy(publicSpki = identity.publicSpki.copyOf()),
                observedTrust = observedTrust?.defensiveCopy(),
                expectedSnapshotDigest = expectedSnapshotDigest?.copyOf(),
                candidate = candidate,
                activity = preparedActivity,
                appliedAt = appliedAt,
            )
        ) {
            is CoreCommandStoreResult.Committed -> {
                val receipt = result.marker.toReceipt(result.pendingActivity)
                when (result.marker.outcome) {
                    CoreCommandOutcome.APPLIED -> CoreCommandApplyResult.Applied(receipt)
                    CoreCommandOutcome.SUPERSEDED -> CoreCommandApplyResult.Superseded(receipt)
                    CoreCommandOutcome.TERMINAL_REJECTED ->
                        error("A reducer-produced trust command was persisted as rejected")
                }
            }
            is CoreCommandStoreResult.Duplicate -> CoreCommandApplyResult.Duplicate(
                result.marker.toReceipt(result.pendingActivity),
            )
            CoreCommandStoreResult.Conflict -> CoreCommandApplyResult.Conflict
            CoreCommandStoreResult.StaleCoreState -> CoreCommandApplyResult.StaleCoreState
            CoreCommandStoreResult.MissingIdentity -> CoreCommandApplyResult.MissingIdentity
            CoreCommandStoreResult.CoreNotReady -> CoreCommandApplyResult.CoreNotReady
        }
    }

    /** Resolves retained Core authority without replaying reducer/signing work. */
    suspend fun resolveCoreCommandReceipt(reference: CoreCommandReceiptReference): CoreCommandReceiptResolution {
        val marker = store.getCoreCommand(reference.commandId)
            ?: return CoreCommandReceiptResolution.Missing
        if (!marker.matchesIdentity(
                PreparedCoreCommandIdentity(
                    commandId = reference.commandId,
                    authenticatedRequestId = reference.authenticatedRequestId,
                    commandDigest = reference.commandDigest,
                    commandType = reference.commandType.token,
                ),
            )
        ) {
            return CoreCommandReceiptResolution.Conflict
        }
        return CoreCommandReceiptResolution.Found(marker.toReceipt(store.getCommandActivity(reference.commandId)))
    }

    suspend fun acknowledgeCopiedCoreActivity(eventId: String, generation: Long): Boolean {
        requireCompactCoreIdentifier(eventId, "Core Activity event id")
        require(generation > 0) { "operational generation must be positive" }
        return store.acknowledgeCopiedActivity(eventId, generation) == 1
    }

    /**
     * Retains exact command markers for 72 hours: longer than the broker's 48-hour relay TTL and equal to the
     * shipped handled-ledger horizon. Cleanup is bounded and a pending Core Activity obligation protects its marker.
     */
    suspend fun pruneRetainedCoreCommandMarkers(limit: Int): Int {
        require(limit in 1..MAX_CORE_COMMAND_MARKER_PRUNE_BATCH) {
            "Core command marker prune limit is invalid"
        }
        val pruneAt = now()
        require(pruneAt > 0) { "Core command marker prune time must be positive" }
        if (pruneAt <= CORE_COMMAND_MARKER_RETENTION_MILLIS) return 0
        return store.pruneRetainedCoreCommandMarkers(
            cutoff = pruneAt - CORE_COMMAND_MARKER_RETENTION_MILLIS,
            limit = limit,
        )
    }

    companion object {
        fun create(context: Context, now: () -> Long = System::currentTimeMillis): CoreFoundationRepository =
            CoreFoundationRepository(CoreRoomStore.processSingleton(context), now)
    }
}

internal const val CORE_COMMAND_MARKER_RETENTION_MILLIS = 72L * 60 * 60 * 1_000
private const val MAX_CORE_COMMAND_MARKER_PRUNE_BATCH = 64

private data class ValidatedTrust(
    val digest: ByteArray,
    val entries: String,
    val cards: String,
    val overlays: String,
    val epochs: String?,
    val signatureBase64Url: String,
)

private fun CoreCommandAppliedEntity.toReceipt(
    pendingActivity: CoreActivityOutboxEntity?,
): CoreCommandReceipt = CoreCommandReceipt(
    command = toSnapshot(),
    pendingActivity = pendingActivity?.toSnapshot(),
)

private fun TrustSnapshotEntity.defensiveCopy(): TrustSnapshotEntity = copy(
    entriesUtf8 = entriesUtf8.copyOf(),
    cardsUtf8 = cardsUtf8.copyOf(),
    overlaysUtf8 = overlaysUtf8.copyOf(),
    epochsUtf8 = epochsUtf8?.copyOf(),
    signatureBase64UrlUtf8 = signatureBase64UrlUtf8.copyOf(),
    snapshotDigest = snapshotDigest.copyOf(),
)

private fun validateStoredTrust(
    identity: IdentityMetadataEntity,
    trust: TrustSnapshotEntity,
): TrustSnapshot {
    validateIdentityProjection(identity)
    val format = trust.signatureFormat.toTrustSignatureFormat()
    if ((format == TrustSignatureFormat.TRUSTSTORE_V1_FOUR_SECTION) != (trust.epochsUtf8 != null)) {
        throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.INVALID_SECTION_SHAPE)
    }
    val validated = validateExactTrust(
        identity = identity,
        exact = TrustSnapshotExactBytes(
            signatureFormat = format,
            entriesUtf8 = trust.entriesUtf8.copyOf(),
            cardsUtf8 = trust.cardsUtf8.copyOf(),
            overlaysUtf8 = trust.overlaysUtf8.copyOf(),
            epochsUtf8 = trust.epochsUtf8?.copyOf(),
            signatureBase64UrlUtf8 = trust.signatureBase64UrlUtf8.copyOf(),
        ),
    )
    if (!MessageDigest.isEqual(validated.digest, trust.snapshotDigest)) {
        throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.DIGEST_MISMATCH)
    }
    return trust.toSnapshot(format)
}

private fun String.toTrustSignatureFormat(): TrustSignatureFormat = when (this) {
    TrustSignatureFormat.TRUSTSTORE_V1_THREE_SECTION.token ->
        TrustSignatureFormat.TRUSTSTORE_V1_THREE_SECTION
    TrustSignatureFormat.TRUSTSTORE_V1_FOUR_SECTION.token ->
        TrustSignatureFormat.TRUSTSTORE_V1_FOUR_SECTION
    else -> throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.UNKNOWN_SIGNATURE_FORMAT)
}

private fun validateIdentityProjection(identity: IdentityMetadataEntity) {
    val derived = runCatching { ClientIds.derive(identity.publicSpki).value }.getOrNull()
    if (derived == null || derived != identity.clientId) {
        throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.IDENTITY_PROJECTION_MISMATCH)
    }
}

private fun validateExactTrust(
    identity: IdentityMetadataEntity,
    exact: TrustSnapshotExactBytes,
): ValidatedTrust {
    val entries = exact.entriesUtf8.decodeBoundedTrustUtf8()
    val cards = exact.cardsUtf8.decodeBoundedTrustUtf8()
    val overlays = exact.overlaysUtf8.decodeBoundedTrustUtf8()
    val epochs = exact.epochsUtf8?.decodeBoundedTrustUtf8()
    val signature = exact.signatureBase64UrlUtf8.decodeCanonicalSignature()
    val clientId = ClientId(ClientIds.derive(identity.publicSpki).value)
    val verifies = when (exact.signatureFormat) {
        TrustSignatureFormat.TRUSTSTORE_V1_THREE_SECTION ->
            TrustStoreSigning.verifyLegacyThreeSection(
                publicKeySpki = identity.publicSpki,
                selfId = clientId,
                entriesJson = entries,
                cardsJson = cards,
                overlaysJson = overlays,
                signatureB64 = signature,
            )
        TrustSignatureFormat.TRUSTSTORE_V1_FOUR_SECTION ->
            TrustStoreSigning.verify(
                publicKeySpki = identity.publicSpki,
                selfId = clientId,
                entriesJson = entries,
                cardsJson = cards,
                overlaysJson = overlays,
                epochsJson = requireNotNull(epochs),
                signatureB64 = signature,
            )
    }
    if (!verifies) throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.SIGNATURE_MISMATCH)
    return ValidatedTrust(
        digest = exact.computeTrustSnapshotDigest(clientId.value),
        entries = entries,
        cards = cards,
        overlays = overlays,
        epochs = epochs,
        signatureBase64Url = signature,
    )
}

private fun ByteArray.decodeBoundedTrustUtf8(): String {
    if (isEmpty() || size > MAX_TRUST_SECTION_UTF8_BYTES) {
        throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.INVALID_SECTION_ENCODING)
    }
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (_: Exception) {
        throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.INVALID_SECTION_ENCODING)
    }
}

private fun ByteArray.decodeCanonicalSignature(): String {
    if (isEmpty() || size > MAX_TRUST_SIGNATURE_BASE64URL_BYTES || any { byte ->
            val char = byte.toInt().toChar()
            !(char.isLetterOrDigit() || char == '_' || char == '-') || byte.toInt() !in 0..127
        }
    ) {
        throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.INVALID_SIGNATURE_ENCODING)
    }
    val text = toString(StandardCharsets.UTF_8)
    val decoded = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
        ?.takeIf { it.isNotEmpty() && it.size <= MAX_TRUST_SIGNATURE_DER_BYTES }
        ?: throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.INVALID_SIGNATURE_ENCODING)
    if (Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != text) {
        throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.INVALID_SIGNATURE_ENCODING)
    }
    return text
}

internal fun TrustSnapshotExactBytes.computeTrustSnapshotDigest(clientId: String): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateFramed(TRUST_SNAPSHOT_DIGEST_DOMAIN)
    digest.updateFramed(clientId.encodeToByteArray())
    digest.updateFramed(signatureFormat.token.encodeToByteArray())
    digest.updateFramed(entriesUtf8)
    digest.updateFramed(cardsUtf8)
    digest.updateFramed(overlaysUtf8)
    digest.update(if (epochsUtf8 == null) 0.toByte() else 1.toByte())
    epochsUtf8?.let(digest::updateFramed)
    digest.updateFramed(signatureBase64UrlUtf8)
    return digest.digest()
}

private fun MessageDigest.updateFramed(value: ByteArray) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
    update(value)
}

private fun requireExactReadback(
    expected: TrustSnapshotEntity,
    actual: TrustSnapshotEntity,
    includeUpdatedAt: Boolean,
) {
    val exact = expected.singleton == actual.singleton &&
        expected.signatureFormat == actual.signatureFormat &&
        MessageDigest.isEqual(expected.entriesUtf8, actual.entriesUtf8) &&
        MessageDigest.isEqual(expected.cardsUtf8, actual.cardsUtf8) &&
        MessageDigest.isEqual(expected.overlaysUtf8, actual.overlaysUtf8) &&
        expected.epochsUtf8.contentEqualsOrBothNull(actual.epochsUtf8) &&
        MessageDigest.isEqual(expected.signatureBase64UrlUtf8, actual.signatureBase64UrlUtf8) &&
        MessageDigest.isEqual(expected.snapshotDigest, actual.snapshotDigest) &&
        (!includeUpdatedAt || expected.updatedAt == actual.updatedAt)
    if (!exact) throw CoreTrustIntegrityException(CoreTrustIntegrityIssue.PERSISTED_READBACK_MISMATCH)
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean = when {
    this == null || other == null -> this == null && other == null
    else -> MessageDigest.isEqual(this, other)
}

private const val MAX_TRUST_SECTION_UTF8_BYTES = 4 * 1024 * 1024
private const val MAX_TRUST_SIGNATURE_BASE64URL_BYTES = 192
private const val MAX_TRUST_SIGNATURE_DER_BYTES = 144
private val TRUST_SNAPSHOT_DIGEST_DOMAIN = "notisync-core-trust-snapshot-v1".encodeToByteArray()

private fun requireCoreDiagnosticCode(value: String?) {
    require(!value.isNullOrBlank()) { "An incomplete Keystore outcome requires an error code" }
    require(value.length <= 128) { "Keystore error code is too long" }
    require(value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
        "Keystore error code contains unsupported characters"
    }
}
