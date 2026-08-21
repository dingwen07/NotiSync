package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class SshKeyDao {
    @Query("SELECT * FROM ssh_provider_state WHERE singleton_id = 1")
    abstract fun observeProviderState(): Flow<SshProviderStateEntity?>

    @Query("SELECT * FROM ssh_provider_state WHERE singleton_id = 1")
    protected abstract suspend fun providerState(): SshProviderStateEntity?

    /** Read-only provider revision used by Room feature adapters when constructing snapshots. */
    @Query("SELECT * FROM ssh_provider_state WHERE singleton_id = 1")
    abstract suspend fun readProviderState(): SshProviderStateEntity?

    @Query("SELECT * FROM ssh_key ORDER BY created_at ASC, provider_key_id ASC")
    abstract fun observeKeys(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_key WHERE provider_key_id = :providerKeyId")
    abstract suspend fun findKey(providerKeyId: String): SshKeyEntity?

    @Query("SELECT * FROM ssh_key WHERE public_hash = :publicHash LIMIT 1")
    abstract suspend fun findKeyByPublicHash(publicHash: ByteArray): SshKeyEntity?

    @Query(
        "UPDATE ssh_key SET display_name = :displayName, approval_policy = :approvalPolicy, " +
            "expires_at = :expiresAt, updated_at = :updatedAt WHERE provider_key_id = :providerKeyId",
    )
    abstract suspend fun updateKeyMetadata(
        providerKeyId: String,
        displayName: String,
        approvalPolicy: SshApprovalPolicyToken,
        expiresAt: Long?,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM ssh_operational_key WHERE provider_key_id = :providerKeyId")
    abstract suspend fun findOperationalKey(providerKeyId: String): SshOperationalKeyEntity?

    @Query("SELECT * FROM ssh_wrapped_operational_material WHERE provider_key_id = :providerKeyId")
    abstract suspend fun findWrappedOperationalMaterial(providerKeyId: String): SshWrappedOperationalMaterialEntity?

    @Query("SELECT * FROM ssh_export_copy WHERE provider_key_id = :providerKeyId")
    abstract suspend fun findExportCopy(providerKeyId: String): SshExportCopyEntity?

    @Query("SELECT * FROM ssh_key_lifecycle WHERE provider_key_id = :providerKeyId")
    abstract suspend fun findLifecycle(providerKeyId: String): SshKeyLifecycleEntity?

    @Query("SELECT * FROM ssh_key_lifecycle ORDER BY created_at ASC, provider_key_id ASC")
    abstract suspend fun pendingLifecycles(): List<SshKeyLifecycleEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM ssh_reset_journal LIMIT 1)")
    protected abstract suspend fun resetInProgress(): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM (" +
            "SELECT key_alias FROM ssh_operational_key UNION ALL " +
            "SELECT key_alias FROM ssh_export_copy UNION ALL " +
            "SELECT operational_alias AS key_alias FROM ssh_key_lifecycle UNION ALL " +
            "SELECT key_alias FROM ssh_key_lifecycle_candidate" +
            ") AS aliases WHERE key_alias = :keyAlias)",
    )
    protected abstract suspend fun aliasInUse(keyAlias: String): Boolean

    @Query(
        "SELECT * FROM ssh_key_lifecycle_candidate WHERE provider_key_id = :providerKeyId " +
            "AND purpose = :purpose",
    )
    abstract suspend fun findCandidate(
        providerKeyId: String,
        purpose: SshLifecycleCandidatePurpose,
    ): SshKeyLifecycleCandidateEntity?

    @Query(
        "SELECT * FROM ssh_key_lifecycle_candidate WHERE provider_key_id = :providerKeyId ORDER BY purpose",
    )
    abstract suspend fun findCandidates(providerKeyId: String): List<SshKeyLifecycleCandidateEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertLifecycleInternal(entity: SshKeyLifecycleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCandidatesInternal(entities: List<SshKeyLifecycleCandidateEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun addLifecycleCandidate(entity: SshKeyLifecycleCandidateEntity)

    @Query(
        "DELETE FROM ssh_key_lifecycle_candidate WHERE provider_key_id = :providerKeyId " +
            "AND purpose = :purpose",
    )
    abstract suspend fun deleteLifecycleCandidate(
        providerKeyId: String,
        purpose: SshLifecycleCandidatePurpose,
    ): Int

    @Query(
        "UPDATE ssh_key_lifecycle SET storage_kind = :replacement, updated_at = :updatedAt " +
            "WHERE provider_key_id = :providerKeyId AND state = :provisioningState " +
            "AND storage_kind = :expected AND updated_at <= :updatedAt",
    )
    protected abstract suspend fun transitionProvisioningStorageKindInternal(
        providerKeyId: String,
        expected: SshStorageKind,
        replacement: SshStorageKind,
        updatedAt: Long,
        provisioningState: SshKeyLifecycleState = SshKeyLifecycleState.PROVISIONING,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertKeyInternal(entity: SshKeyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOperationalInternal(entity: SshOperationalKeyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertWrappedMaterialInternal(entity: SshWrappedOperationalMaterialEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertExportCopyInternal(entity: SshExportCopyEntity)

    @Upsert
    protected abstract suspend fun upsertProviderStateInternal(entity: SshProviderStateEntity)

    @Transaction
    open suspend fun ensureProviderState(
        inventoryGeneration: String,
        updatedAt: Long,
    ): SshProviderStateEntity {
        requireIdentifier(inventoryGeneration, "SSH inventory generation")
        require(updatedAt > 0) { "SSH provider-state update time must be positive" }
        val current = providerState()
        if (current != null) return current
        val created = SshProviderStateEntity(
            inventoryGeneration = inventoryGeneration,
            revision = 1,
            updatedAt = updatedAt,
        )
        upsertProviderStateInternal(created)
        return requireNotNull(providerState())
    }

    @Transaction
    open suspend fun beginProvisioningHeader(lifecycle: SshKeyLifecycleEntity) {
        lifecycle.requireValid()
        require(lifecycle.state == SshKeyLifecycleState.PROVISIONING) {
            "SSH provisioning header must use the provisioning state"
        }
        require(!resetInProgress()) { "SSH provisioning is fenced during reset" }
        require(findLifecycle(lifecycle.providerKeyId) == null) { "SSH key lifecycle already exists" }
        require(findKey(lifecycle.providerKeyId) == null) { "SSH provisioning key already exists" }
        require(!aliasInUse(lifecycle.operationalAlias)) {
            "SSH provisioning operational alias is already in use"
        }
        insertLifecycleInternal(lifecycle)
    }

    @Transaction
    open suspend fun transitionProvisioningStorageKind(
        providerKeyId: String,
        expected: SshStorageKind,
        replacement: SshStorageKind,
        updatedAt: Long,
    ) {
        require(expected != replacement) { "SSH provisioning storage kind did not change" }
        require(updatedAt > 0) { "SSH provisioning storage transition time must be positive" }
        require(findCandidates(providerKeyId).isEmpty()) {
            "SSH provisioning storage cannot change after protected material is journaled"
        }
        check(
            transitionProvisioningStorageKindInternal(
                providerKeyId = providerKeyId,
                expected = expected,
                replacement = replacement,
                updatedAt = updatedAt,
            ) == 1,
        ) { "SSH provisioning storage transition was lost" }
    }

    @Query("DELETE FROM ssh_key_lifecycle WHERE provider_key_id = :providerKeyId")
    protected abstract suspend fun deleteLifecycleInternal(providerKeyId: String): Int

    @Query("DELETE FROM ssh_key_lifecycle WHERE provider_key_id = :providerKeyId")
    abstract suspend fun deleteLifecycle(providerKeyId: String): Int

    @Query("DELETE FROM ssh_key WHERE provider_key_id = :providerKeyId")
    protected abstract suspend fun deleteKeyInternal(providerKeyId: String): Int

    @Transaction
    open suspend fun beginLifecycle(
        lifecycle: SshKeyLifecycleEntity,
        candidates: List<SshKeyLifecycleCandidateEntity>,
    ) {
        lifecycle.requireValid()
        if (lifecycle.state == SshKeyLifecycleState.PROVISIONING) {
            require(!resetInProgress()) { "SSH provisioning is fenced during reset" }
        }
        require(findLifecycle(lifecycle.providerKeyId) == null) { "SSH key lifecycle already exists" }
        require(candidates.map { it.purpose }.toSet().size == candidates.size) {
            "SSH key lifecycle contains duplicate candidate purposes"
        }
        require(candidates.map { it.keyAlias }.toSet().size == candidates.size) {
            "SSH key lifecycle contains duplicate candidate aliases"
        }
        val operationalCandidate = candidates.singleOrNull {
            it.purpose == SshLifecycleCandidatePurpose.OPERATIONAL
        }
        when (lifecycle.state) {
            SshKeyLifecycleState.PROVISIONING -> {
                require(findKey(lifecycle.providerKeyId) == null) { "SSH provisioning key already exists" }
                require(!aliasInUse(lifecycle.operationalAlias)) {
                    "SSH provisioning operational alias is already in use"
                }
                if (lifecycle.storageKind == SshStorageKind.DIRECT) {
                    require(operationalCandidate == null) {
                        "direct SSH provisioning cannot persist wrapped operational material"
                    }
                } else {
                    requireNotNull(operationalCandidate) {
                        "wrapped SSH provisioning requires protected operational material"
                    }
                }
                candidates.forEach { candidate ->
                    require(!aliasInUse(candidate.keyAlias)) {
                        "SSH provisioning candidate alias is already in use"
                    }
                }
            }
            SshKeyLifecycleState.DELETING -> {
                requireNotNull(findKey(lifecycle.providerKeyId)) {
                    "SSH deletion key does not exist"
                }
                require(candidates.isEmpty()) {
                    "SSH deletion cannot add provisioning candidates"
                }
                val operationalKey = requireNotNull(findOperationalKey(lifecycle.providerKeyId)) {
                    "SSH operational key is missing"
                }
                val activeStorageKind = if (findWrappedOperationalMaterial(lifecycle.providerKeyId) == null) {
                    SshStorageKind.DIRECT
                } else {
                    SshStorageKind.WRAPPED
                }
                require(lifecycle.storageKind == activeStorageKind) {
                    "SSH deletion lifecycle has a different storage discriminator"
                }
                require(lifecycle.operationalAlias == operationalKey.keyAlias) {
                    "SSH deletion lifecycle does not identify the active operational alias"
                }
            }
        }
        candidates.forEach { it.requireValid(lifecycle) }
        insertLifecycleInternal(lifecycle)
        if (candidates.isNotEmpty()) insertCandidatesInternal(candidates)
    }

    @Transaction
    open suspend fun finalizeDirectProvisioning(
        key: SshKeyEntity,
        operationalKey: SshOperationalKeyEntity,
        exportCopy: SshExportCopyEntity?,
        nextProviderState: SshProviderStateEntity,
    ) {
        require(!resetInProgress()) { "SSH provisioning is fenced during reset" }
        key.requireValid()
        operationalKey.requireValid(key)
        exportCopy?.requireValid(key)
        require(findWrappedOperationalMaterial(key.providerKeyId) == null) {
            "wrapped SSH material already exists for direct key"
        }
        val lifecycle = requireNotNull(findLifecycle(key.providerKeyId)) { "SSH provisioning journal is missing" }
        require(
            lifecycle.state == SshKeyLifecycleState.PROVISIONING &&
                lifecycle.storageKind == SshStorageKind.DIRECT &&
                lifecycle.operationalAlias == operationalKey.keyAlias,
        ) { "SSH direct provisioning journal does not match the finalized key" }
        require(findCandidate(key.providerKeyId, SshLifecycleCandidatePurpose.OPERATIONAL) == null) {
            "direct SSH provisioning retained a wrapped candidate"
        }
        requireExportCandidateMatches(key.providerKeyId, exportCopy)
        requireProviderRevision(nextProviderState)
        insertKeyInternal(key)
        insertOperationalInternal(operationalKey)
        exportCopy?.let { insertExportCopyInternal(it) }
        check(deleteLifecycleInternal(key.providerKeyId) == 1) { "SSH provisioning journal was lost" }
        upsertProviderStateInternal(nextProviderState)
    }

    @Transaction
    open suspend fun finalizeWrappedProvisioning(
        key: SshKeyEntity,
        operationalKey: SshOperationalKeyEntity,
        wrappedMaterial: SshWrappedOperationalMaterialEntity,
        exportCopy: SshExportCopyEntity?,
        nextProviderState: SshProviderStateEntity,
    ) {
        require(!resetInProgress()) { "SSH provisioning is fenced during reset" }
        key.requireValid()
        operationalKey.requireValid(key)
        wrappedMaterial.requireValid(operationalKey)
        exportCopy?.requireValid(key)
        val lifecycle = requireNotNull(findLifecycle(key.providerKeyId)) { "SSH provisioning journal is missing" }
        val candidate = requireNotNull(
            findCandidate(key.providerKeyId, SshLifecycleCandidatePurpose.OPERATIONAL),
        ) {
            "SSH wrapped provisioning candidate is missing"
        }
        require(
            lifecycle.state == SshKeyLifecycleState.PROVISIONING &&
                lifecycle.storageKind == SshStorageKind.WRAPPED &&
                lifecycle.operationalAlias == operationalKey.keyAlias &&
                candidate.keyAlias == operationalKey.keyAlias &&
                candidate.privateKeyCiphertext.contentEquals(wrappedMaterial.privateKeyCiphertext) &&
                candidate.privateKeyNonce.contentEquals(wrappedMaterial.privateKeyNonce) &&
                candidate.securityLevel == operationalKey.securityLevel,
        ) { "SSH wrapped provisioning journal does not match the finalized key" }
        requireExportCandidateMatches(key.providerKeyId, exportCopy)
        requireProviderRevision(nextProviderState)
        insertKeyInternal(key)
        insertOperationalInternal(operationalKey)
        insertWrappedMaterialInternal(wrappedMaterial)
        exportCopy?.let { insertExportCopyInternal(it) }
        check(deleteLifecycleInternal(key.providerKeyId) == 1) { "SSH provisioning journal was lost" }
        upsertProviderStateInternal(nextProviderState)
    }

    @Transaction
    open suspend fun finalizeDeletion(
        providerKeyId: String,
        nextProviderState: SshProviderStateEntity,
    ): Boolean {
        val lifecycle = findLifecycle(providerKeyId) ?: return false
        require(lifecycle.state == SshKeyLifecycleState.DELETING) { "SSH key is not deleting" }
        requireProviderRevision(nextProviderState)
        check(deleteKeyInternal(providerKeyId) == 1) { "SSH deleting key disappeared" }
        check(deleteLifecycleInternal(providerKeyId) == 1) { "SSH deletion journal disappeared" }
        upsertProviderStateInternal(nextProviderState)
        return true
    }

    private suspend fun requireProviderRevision(next: SshProviderStateEntity) {
        require(next.singletonId == OperationalSingletons.ID) { "invalid SSH provider-state singleton id" }
        requireIdentifier(next.inventoryGeneration, "SSH inventory generation")
        require(next.updatedAt > 0) { "SSH provider-state update time must be positive" }
        val current = providerState()
        require(
            (current == null && next.revision == 1L) ||
                (current != null && current.inventoryGeneration == next.inventoryGeneration &&
                    next.revision == current.revision + 1),
        ) { "SSH inventory revision is not the next monotonic revision" }
    }

    private fun SshKeyEntity.requireValid() {
        requireIdentifier(providerKeyId, "SSH provider key id")
        require(publicBlob.isNotEmpty()) { "SSH public blob must not be empty" }
        requireSha256Projection(publicBlob, publicHash, "SSH public hash")
        require(displayName.isNotBlank() && displayName.length <= OperationalStorageLimits.MAX_DISPLAY_CHARS) {
            "SSH key display name is invalid"
        }
        require(createdAt > 0 && updatedAt >= createdAt) { "SSH key timestamps are invalid" }
        require(expiresAt == null || expiresAt > createdAt) { "SSH key expiry is invalid" }
    }

    private fun SshOperationalKeyEntity.requireValid(key: SshKeyEntity) {
        require(providerKeyId == key.providerKeyId) { "SSH operational key has a different key id" }
        requireIdentifier(keyAlias, "SSH operational key alias")
        require(strongBoxFactsAreValid(securityLevel, strongBoxAttempted, strongBoxFallback)) {
            "SSH operational StrongBox facts are inconsistent"
        }
        require(lastVerifiedAt > 0) { "SSH operational verification time must be positive" }
    }

    private fun SshWrappedOperationalMaterialEntity.requireValid(operationalKey: SshOperationalKeyEntity) {
        require(providerKeyId == operationalKey.providerKeyId) {
            "SSH wrapped material has a different key id"
        }
        requireSshWrappedKeyMaterial(
            operationalKey.keyAlias,
            privateKeyCiphertext,
            privateKeyNonce,
        )
    }

    private fun SshExportCopyEntity.requireValid(key: SshKeyEntity) {
        require(providerKeyId == key.providerKeyId) { "SSH export copy has a different key id" }
        requireSshWrappedKeyMaterial(keyAlias, privateKeyCiphertext, privateKeyNonce)
        require(strongBoxFactsAreValid(securityLevel, strongBoxAttempted, strongBoxFallback)) {
            "SSH export-copy StrongBox facts are inconsistent"
        }
        require(backendPolicy != SshExportBackendToken.TEE_ONLY || !strongBoxAttempted) {
            "default-backend SSH export copy attempted StrongBox"
        }
        require(lastVerifiedAt > 0) { "SSH export-copy verification time must be positive" }
    }

    private fun SshKeyLifecycleEntity.requireValid() {
        requireIdentifier(providerKeyId, "SSH lifecycle key id")
        requireIdentifier(operationalAlias, "SSH lifecycle operational alias")
        require(createdAt > 0 && updatedAt >= createdAt) { "SSH lifecycle timestamps are invalid" }
    }

    private fun SshKeyLifecycleCandidateEntity.requireValid(lifecycle: SshKeyLifecycleEntity) {
        require(providerKeyId == lifecycle.providerKeyId) { "SSH lifecycle candidate has a different key id" }
        requireSshWrappedKeyMaterial(keyAlias, privateKeyCiphertext, privateKeyNonce)
        if (purpose == SshLifecycleCandidatePurpose.OPERATIONAL) {
            require(lifecycle.storageKind == SshStorageKind.WRAPPED) {
                "only wrapped SSH lifecycle may persist an operational candidate"
            }
            require(keyAlias == lifecycle.operationalAlias) {
                "SSH operational candidate alias does not match its lifecycle"
            }
        } else {
            require(keyAlias != lifecycle.operationalAlias) {
                "SSH export candidate must use a distinct wrapping alias"
            }
        }
    }

    private suspend fun requireExportCandidateMatches(
        providerKeyId: String,
        exportCopy: SshExportCopyEntity?,
    ) {
        val candidate = findCandidate(providerKeyId, SshLifecycleCandidatePurpose.EXPORT)
        require((candidate == null) == (exportCopy == null)) {
            "SSH export candidate and finalized export copy must be present together"
        }
        if (candidate != null && exportCopy != null) {
            require(
                candidate.keyAlias == exportCopy.keyAlias &&
                    candidate.privateKeyCiphertext.contentEquals(exportCopy.privateKeyCiphertext) &&
                    candidate.privateKeyNonce.contentEquals(exportCopy.privateKeyNonce) &&
                    candidate.securityLevel == exportCopy.securityLevel,
            ) { "SSH export candidate does not match the finalized export copy" }
        }
    }

    private fun requireSshWrappedKeyMaterial(
        keyAlias: String,
        ciphertext: ByteArray,
        nonce: ByteArray,
    ) {
        requireIdentifier(keyAlias, "SSH wrapping key alias")
        require(ciphertext.size >= 16) { "SSH wrapped key ciphertext is too short" }
        require(ciphertext.size <= OperationalStorageLimits.MAX_PROTECTED_BLOB_BYTES) {
            "SSH wrapped key ciphertext exceeds the schema bound"
        }
        require(nonce.size == 12) { "SSH wrapped key nonce is invalid" }
    }
}

@Dao
internal abstract class SshAuthorizationDao {
    @Query("SELECT * FROM ssh_known_host ORDER BY last_approved_at DESC, host_key_sha256 ASC")
    abstract fun observeKnownHosts(): Flow<List<SshKnownHostEntity>>

    @Query("SELECT * FROM ssh_peer_authorization ORDER BY created_at DESC, authorization_id ASC")
    abstract fun observePeerAuthorizations(): Flow<List<SshPeerAuthorizationEntity>>

    @Query("SELECT * FROM ssh_host_authorization ORDER BY created_at DESC, authorization_id ASC")
    abstract fun observeHostAuthorizations(): Flow<List<SshHostAuthorizationEntity>>

    @Query("SELECT * FROM ssh_known_host WHERE host_key_sha256 = :hostKeySha256")
    abstract suspend fun findKnownHost(hostKeySha256: ByteArray): SshKnownHostEntity?

    @Query("DELETE FROM ssh_known_host WHERE host_key_sha256 = :hostKeySha256")
    abstract suspend fun deleteKnownHost(hostKeySha256: ByteArray): Int

    @Query("SELECT * FROM ssh_peer_authorization WHERE authorization_id = :authorizationId")
    abstract suspend fun findPeerAuthorization(authorizationId: String): SshPeerAuthorizationEntity?

    @Query("SELECT * FROM ssh_host_authorization WHERE authorization_id = :authorizationId")
    abstract suspend fun findHostAuthorization(authorizationId: String): SshHostAuthorizationEntity?

    @Query(
        "SELECT * FROM ssh_authorization_floor WHERE requester_client_id = :requesterClientId " +
            "AND authorization_generation = :authorizationGeneration",
    )
    abstract suspend fun findFloor(
        requesterClientId: String,
        authorizationGeneration: String,
    ): SshAuthorizationFloorEntity?

    @Upsert
    protected abstract suspend fun upsertFloorInternal(entity: SshAuthorizationFloorEntity)

    @Query("SELECT COUNT(*) FROM ssh_peer_authorization")
    protected abstract suspend fun peerAuthorizationCount(): Int

    @Query("SELECT COUNT(*) FROM ssh_host_authorization")
    protected abstract suspend fun hostAuthorizationCount(): Int

    @Query(
        "SELECT (SELECT COUNT(*) FROM ssh_peer_authorization WHERE provider_key_id = :providerKeyId) + " +
            "(SELECT COUNT(*) FROM ssh_host_authorization WHERE provider_key_id = :providerKeyId)",
    )
    protected abstract suspend fun authorizationCountForKey(providerKeyId: String): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM ssh_peer_authorization WHERE authorization_id = :authorizationId) " +
            "OR EXISTS(SELECT 1 FROM ssh_host_authorization WHERE authorization_id = :authorizationId)",
    )
    protected abstract suspend fun authorizationIdExists(authorizationId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPeerAuthorizationInternal(entity: SshPeerAuthorizationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertHostAuthorizationInternal(entity: SshHostAuthorizationEntity)

    @Upsert
    protected abstract suspend fun upsertKnownHostInternal(entity: SshKnownHostEntity)

    @Query("DELETE FROM ssh_peer_authorization WHERE authorization_id = :authorizationId")
    protected abstract suspend fun deletePeerAuthorization(authorizationId: String): Int

    @Query("DELETE FROM ssh_host_authorization WHERE authorization_id = :authorizationId")
    protected abstract suspend fun deleteHostAuthorization(authorizationId: String): Int

    @Transaction
    open suspend fun advanceFloor(entity: SshAuthorizationFloorEntity): Boolean {
        entity.requireValid()
        val current = findFloor(entity.requesterClientId, entity.authorizationGeneration)
        if (current != null && entity.invalidatedThroughEpoch < current.invalidatedThroughEpoch) return false
        upsertFloorInternal(entity)
        deleteInvalidatedPeer(
            entity.requesterClientId,
            entity.authorizationGeneration,
            entity.invalidatedThroughEpoch,
        )
        deleteInvalidatedHost(
            entity.requesterClientId,
            entity.authorizationGeneration,
            entity.invalidatedThroughEpoch,
        )
        return current == null || entity.invalidatedThroughEpoch > current.invalidatedThroughEpoch
    }

    @Query(
        "DELETE FROM ssh_peer_authorization WHERE requester_client_id = :requesterClientId " +
            "AND authorization_generation = :authorizationGeneration AND authorization_epoch <= :floor",
    )
    protected abstract suspend fun deleteInvalidatedPeer(
        requesterClientId: String,
        authorizationGeneration: String,
        floor: Long,
    ): Int

    @Query(
        "DELETE FROM ssh_host_authorization WHERE requester_client_id = :requesterClientId " +
            "AND authorization_generation = :authorizationGeneration AND authorization_epoch <= :floor",
    )
    protected abstract suspend fun deleteInvalidatedHost(
        requesterClientId: String,
        authorizationGeneration: String,
        floor: Long,
    ): Int

    @Transaction
    open suspend fun rememberPeer(entity: SshPeerAuthorizationEntity): Boolean {
        entity.requireValid()
        val floor = findFloor(entity.requesterClientId, entity.authorizationGeneration)?.invalidatedThroughEpoch
            ?: Long.MIN_VALUE
        require(entity.authorizationEpoch > floor) { "SSH authorization is already invalidated" }
        require(!authorizationIdExists(entity.authorizationId)) { "SSH authorization id already exists" }
        require(peerAuthorizationCount() + hostAuthorizationCount() <
            OperationalRetention.SSH_MAX_REMEMBERED_AUTHORIZATIONS_GLOBAL) {
            "SSH global authorization limit reached"
        }
        require(authorizationCountForKey(entity.providerKeyId) <
            OperationalRetention.SSH_MAX_REMEMBERED_AUTHORIZATIONS_PER_KEY) {
            "SSH per-key authorization limit reached"
        }
        insertPeerAuthorizationInternal(entity)
        return true
    }

    @Transaction
    open suspend fun rememberHost(
        knownHost: SshKnownHostEntity,
        authorization: SshHostAuthorizationEntity,
    ): Boolean {
        knownHost.requireValid()
        authorization.requireValid(knownHost)
        val floor = findFloor(
            authorization.requesterClientId,
            authorization.authorizationGeneration,
        )?.invalidatedThroughEpoch ?: Long.MIN_VALUE
        require(authorization.authorizationEpoch > floor) { "SSH host authorization is already invalidated" }
        require(!authorizationIdExists(authorization.authorizationId)) { "SSH authorization id already exists" }
        require(peerAuthorizationCount() + hostAuthorizationCount() <
            OperationalRetention.SSH_MAX_REMEMBERED_AUTHORIZATIONS_GLOBAL) {
            "SSH global authorization limit reached"
        }
        require(authorizationCountForKey(authorization.providerKeyId) <
            OperationalRetention.SSH_MAX_REMEMBERED_AUTHORIZATIONS_PER_KEY) {
            "SSH per-key authorization limit reached"
        }
        upsertKnownHostInternal(knownHost)
        insertHostAuthorizationInternal(authorization)
        return true
    }

    @Transaction
    open suspend fun forget(authorizationId: String): Boolean {
        requireIdentifier(authorizationId, "SSH authorization id")
        return deletePeerAuthorization(authorizationId) + deleteHostAuthorization(authorizationId) == 1
    }

    private fun SshAuthorizationFloorEntity.requireValid() {
        requireIdentifier(requesterClientId, "SSH authorization-floor requester")
        requireIdentifier(authorizationGeneration, "SSH authorization generation")
        require(invalidatedThroughEpoch >= 0 && updatedAt > 0) { "SSH authorization floor is invalid" }
    }

    private fun SshPeerAuthorizationEntity.requireValid() {
        requireIdentifier(providerKeyId, "SSH authorization key id")
        requireIdentifier(requesterClientId, "SSH authorization requester")
        requireIdentifier(authorizationGeneration, "SSH authorization generation")
        requireIdentifier(authorizationId, "SSH authorization id")
        require(authorizationEpoch >= 0 && createdAt > 0) { "SSH authorization metadata is invalid" }
    }

    private fun SshKnownHostEntity.requireValid() {
        require(hostKeySha256.size == OperationalStorageLimits.SHA256_BYTES) {
            "SSH known-host key digest must be SHA-256"
        }
        require(firstApprovedAt > 0 && lastApprovedAt >= firstApprovedAt) {
            "SSH known-host timestamps are invalid"
        }
    }

    private fun SshHostAuthorizationEntity.requireValid(host: SshKnownHostEntity) {
        requireIdentifier(providerKeyId, "SSH host authorization key id")
        requireIdentifier(requesterClientId, "SSH host authorization requester")
        requireIdentifier(authorizationGeneration, "SSH host authorization generation")
        requireIdentifier(authorizationId, "SSH host authorization id")
        require(hostKeySha256.contentEquals(host.hostKeySha256)) {
            "SSH host authorization has a different host digest"
        }
        require(authorizationEpoch >= 0 && createdAt > 0) { "SSH host authorization metadata is invalid" }
    }
}

internal enum class SshProviderAcceptResult {
    STORED,
    DUPLICATE,
    CONFLICT,
    RATE_LIMITED,
}

internal data class SshProviderOutcomeTransition(
    val requestId: String,
    val outcome: SshProviderRequestOutcome,
    val resultAt: Long,
    /** Present only for a signed result whose exact signature bytes cannot be reconstructed. */
    val responseCustody: SshProviderResponseCustodyEntity?,
    val activity: ActivityEventEntity?,
)

internal enum class SshProviderResponsePrepareResult { UPDATED, ALREADY_PREPARED, NOT_FOUND, STALE, CONFLICT }

internal enum class SshProviderResponseCompleteResult { SENT, ALREADY_SENT, NOT_READY, CONFLICT }

@Dao
internal abstract class SshRequestDao : OperationalReceiptOwningDao() {
    @Query("SELECT * FROM ssh_provider_request WHERE request_id = :requestId")
    abstract suspend fun findProviderRequest(requestId: String): SshProviderRequestEntity?

    @Query("SELECT * FROM ssh_provider_pending_payload WHERE request_id = :requestId")
    abstract suspend fun findProviderPendingPayload(requestId: String): SshProviderPendingPayloadEntity?

    @Query("SELECT * FROM ssh_provider_response_custody WHERE request_id = :requestId")
    abstract suspend fun findProviderResponseCustody(requestId: String): SshProviderResponseCustodyEntity?

    @Query("SELECT * FROM ssh_provider_request ORDER BY updated_at DESC, request_id ASC LIMIT :limit")
    abstract fun observeProviderHistory(limit: Int = OperationalRetention.SSH_MAX_HISTORY_ROWS):
        Flow<List<SshProviderRequestEntity>>

    @Query(
        "UPDATE ssh_provider_request SET state = :nextState, updated_at = :updatedAt " +
            "WHERE request_id = :requestId AND state = :expectedState AND expires_at >= :updatedAt",
    )
    abstract suspend fun transitionProviderRequest(
        requestId: String,
        expectedState: SshProviderRequestState,
        nextState: SshProviderRequestState,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE ssh_provider_request SET state = :nextState, outcome = :outcome, result_at = :resultAt, " +
            "updated_at = :resultAt WHERE request_id = :requestId AND state = :expectedState",
    )
    abstract suspend fun terminalProviderRequest(
        requestId: String,
        expectedState: SshProviderRequestState,
        nextState: SshProviderRequestState,
        outcome: SshProviderRequestOutcome,
        resultAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertProviderRequest(entity: SshProviderRequestEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertProviderPendingPayload(entity: SshProviderPendingPayloadEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertProviderResponseCustody(entity: SshProviderResponseCustodyEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertActivity(entity: ActivityEventEntity): Long

    @Query(
        "SELECT COUNT(*) FROM ssh_provider_request WHERE state = :pendingState AND expires_at >= :now",
    )
    protected abstract suspend fun pendingProviderCount(
        now: Long,
        pendingState: SshProviderRequestState = SshProviderRequestState.PENDING_REVIEW,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM ssh_provider_request WHERE requester_client_id = :requesterClientId " +
            "AND state = :pendingState AND expires_at >= :now",
    )
    protected abstract suspend fun pendingProviderCountForRequester(
        requesterClientId: String,
        now: Long,
        pendingState: SshProviderRequestState = SshProviderRequestState.PENDING_REVIEW,
    ): Int

    @Query("DELETE FROM ssh_provider_pending_payload WHERE request_id = :requestId")
    protected abstract suspend fun deleteProviderPendingPayload(requestId: String): Int

    @Query(
        "UPDATE ssh_provider_request SET state = :responseState, outcome = :outcome, result_at = :resultAt, " +
            "updated_at = :resultAt WHERE request_id = :requestId AND state = :pendingState",
    )
    protected abstract suspend fun recordProviderOutcomeInternal(
        requestId: String,
        outcome: SshProviderRequestOutcome,
        resultAt: Long,
        responseState: SshProviderRequestState = SshProviderRequestState.RESPONSE_QUEUED,
        pendingState: SshProviderRequestState = SshProviderRequestState.PENDING_REVIEW,
    ): Int

    @Transaction
    open suspend fun acceptProviderRequest(
        request: SshProviderRequestEntity,
        pendingPayload: SshProviderPendingPayloadEntity,
        activity: ActivityEventEntity?,
        now: Long,
    ): SshProviderAcceptResult {
        request.requireValidNew()
        pendingPayload.requireValid(request)
        activity?.requireValid()
        findProviderRequest(request.requestId)?.let { current ->
            return if (current.requestFingerprint.contentEquals(request.requestFingerprint)) {
                SshProviderAcceptResult.DUPLICATE
            } else {
                SshProviderAcceptResult.CONFLICT
            }
        }
        if (
            pendingProviderCount(now) >= OperationalRetention.SSH_MAX_PENDING_GLOBAL ||
            pendingProviderCountForRequester(request.requesterClientId, now) >=
            OperationalRetention.SSH_MAX_PENDING_PER_REQUESTER
        ) return SshProviderAcceptResult.RATE_LIMITED
        insertProviderRequest(request)
        insertProviderPendingPayload(pendingPayload)
        activity?.let { insertActivity(it) }
        return SshProviderAcceptResult.STORED
    }

    /** Atomically accepts an SSH provider request and commits its broker receipt evidence. */
    suspend fun acceptProviderRequestWithReceipt(
        request: SshProviderRequestEntity,
        pendingPayload: SshProviderPendingPayloadEntity,
        receipt: PreparedOperationalReceipt,
        now: Long,
    ): OperationalFeatureCommitResult = runOwnedReceiptTransaction {
        acceptProviderRequestWithReceiptInternal(request, pendingPayload, receipt, now)
    }

    @Transaction
    protected open suspend fun acceptProviderRequestWithReceiptInternal(
        request: SshProviderRequestEntity,
        pendingPayload: SshProviderPendingPayloadEntity,
        receipt: PreparedOperationalReceipt,
        now: Long,
    ): OperationalFeatureCommitResult {
        existingReceiptResult(receipt)?.let { return it }
        return when (acceptProviderRequest(request, pendingPayload, activity = null, now = now)) {
            SshProviderAcceptResult.STORED ->
                finalizeOwnedReceipt(
                    receipt,
                    OperationalReceiptDisposition.APPLIED,
                    persistActivity = receipt.activity != null,
                )
            SshProviderAcceptResult.DUPLICATE ->
                finalizeOwnedReceipt(
                    receipt,
                    OperationalReceiptDisposition.DUPLICATE,
                    persistActivity = false,
                )
            SshProviderAcceptResult.CONFLICT -> OperationalFeatureCommitResult.ConflictNoAck
            SshProviderAcceptResult.RATE_LIMITED ->
                OperationalFeatureCommitResult.RetryRequired("ssh_pending_capacity")
        }
    }

    @Transaction
    open suspend fun recordProviderOutcomeAndQueueResponse(
        transition: SshProviderOutcomeTransition,
    ): Boolean {
        transition.requireValid()
        val responseState = if (transition.responseCustody != null) {
            SshProviderRequestState.RESPONSE_QUEUED
        } else {
            transition.outcome.terminalStateWithoutResponse()
        }
        if (transition.responseCustody == null) {
            check(findProviderResponseCustody(transition.requestId) == null) {
                "SSH non-custody outcome encountered existing protected response custody"
            }
        }
        if (recordProviderOutcomeInternal(
                requestId = transition.requestId,
                outcome = transition.outcome,
                resultAt = transition.resultAt,
                responseState = responseState,
            ) != 1
        ) return false
        check(deleteProviderPendingPayload(transition.requestId) == 1) {
            "SSH provider outcome did not erase exactly one pending payload"
        }
        transition.responseCustody?.let { insertProviderResponseCustody(it) }
        transition.activity?.let { insertActivity(it) }
        return true
    }

    @Query(
        "UPDATE ssh_provider_response_custody SET payload_format = :preparedFormat, " +
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
    protected abstract suspend fun prepareProviderResponseInternal(
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
        bodyFormat: SshProviderResponsePayloadFormat = SshProviderResponsePayloadFormat.BODY,
        preparedFormat: SshProviderResponsePayloadFormat = SshProviderResponsePayloadFormat.PREPARED_ENVELOPE,
    ): Int

    @Transaction
    open suspend fun prepareProviderResponse(
        expected: SshProviderResponseCustodyEntity,
        prepared: SshProviderResponseCustodyEntity,
    ): SshProviderResponsePrepareResult {
        expected.requireValidResponseCustody(SshProviderResponsePayloadFormat.BODY)
        prepared.requireValidResponseCustody(SshProviderResponsePayloadFormat.PREPARED_ENVELOPE)
        require(expected.requestId == prepared.requestId) { "SSH response custody request id changed" }
        require(expected.createdAt == prepared.createdAt && prepared.updatedAt > expected.updatedAt) {
            "SSH response preparation timestamps are invalid"
        }
        val current = findProviderResponseCustody(expected.requestId)
            ?: return SshProviderResponsePrepareResult.NOT_FOUND
        if (current.hasSamePersistedProjection(prepared)) {
            return SshProviderResponsePrepareResult.ALREADY_PREPARED
        }
        if (!current.hasSamePersistedProjection(expected)) return SshProviderResponsePrepareResult.STALE
        val changed = prepareProviderResponseInternal(
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
        if (changed != 1) return SshProviderResponsePrepareResult.STALE
        return if (findProviderResponseCustody(expected.requestId)?.hasSamePersistedProjection(prepared) == true) {
            SshProviderResponsePrepareResult.UPDATED
        } else {
            SshProviderResponsePrepareResult.CONFLICT
        }
    }

    @Query(
        "UPDATE ssh_provider_request SET state = :sentState, updated_at = :sentAt " +
            "WHERE request_id = :requestId AND state = :responseState " +
            "AND updated_at = :expectedUpdatedAt AND :sentAt > updated_at",
    )
    protected abstract suspend fun markProviderSent(
        requestId: String,
        expectedUpdatedAt: Long,
        sentAt: Long,
        sentState: SshProviderRequestState = SshProviderRequestState.SENT,
        responseState: SshProviderRequestState = SshProviderRequestState.RESPONSE_QUEUED,
    ): Int

    @Query("DELETE FROM ssh_provider_response_custody WHERE request_id = :requestId")
    protected abstract suspend fun deleteProviderResponseCustody(requestId: String): Int

    @Transaction
    open suspend fun completeProviderResponse(
        expectedCustody: SshProviderResponseCustodyEntity,
        sentAt: Long,
    ): SshProviderResponseCompleteResult {
        val requestId = expectedCustody.requestId
        requireIdentifier(requestId, "SSH provider request id")
        require(sentAt > 0) { "SSH provider response time must be positive" }
        expectedCustody.requireValidResponseCustody(SshProviderResponsePayloadFormat.PREPARED_ENVELOPE)
        val request = findProviderRequest(requestId) ?: return SshProviderResponseCompleteResult.NOT_READY
        val custody = findProviderResponseCustody(requestId)
        if (request.state == SshProviderRequestState.SENT && custody == null) {
            return SshProviderResponseCompleteResult.ALREADY_SENT
        }
        if (request.state != SshProviderRequestState.RESPONSE_QUEUED ||
            custody?.hasSamePersistedProjection(expectedCustody) != true
        ) return SshProviderResponseCompleteResult.CONFLICT
        if (markProviderSent(requestId, request.updatedAt, sentAt) != 1) {
            return SshProviderResponseCompleteResult.CONFLICT
        }
        check(deleteProviderResponseCustody(requestId) == 1) {
            "SSH provider response custody disappeared during accepted-send completion"
        }
        return SshProviderResponseCompleteResult.SENT
    }

    @Query(
        "DELETE FROM ssh_provider_request WHERE request_id IN (SELECT request_id FROM ssh_provider_request " +
            "WHERE state NOT IN (:pendingState, :responseState) AND updated_at < :cutoff " +
            "ORDER BY updated_at ASC, request_id ASC LIMIT :limit)",
    )
    abstract suspend fun pruneProviderHistory(
        cutoff: Long,
        limit: Int,
        pendingState: SshProviderRequestState = SshProviderRequestState.PENDING_REVIEW,
        responseState: SshProviderRequestState = SshProviderRequestState.RESPONSE_QUEUED,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM ssh_provider_request WHERE state NOT IN (:pendingState, :responseState)",
    )
    protected abstract suspend fun terminalProviderHistoryCount(
        pendingState: SshProviderRequestState = SshProviderRequestState.PENDING_REVIEW,
        responseState: SshProviderRequestState = SshProviderRequestState.RESPONSE_QUEUED,
    ): Int

    @Query(
        "DELETE FROM ssh_provider_request WHERE request_id IN (SELECT request_id FROM ssh_provider_request " +
            "WHERE state NOT IN (:pendingState, :responseState) " +
            "ORDER BY updated_at ASC, request_id ASC LIMIT :limit)",
    )
    protected abstract suspend fun pruneOldestProviderHistory(
        limit: Int,
        pendingState: SshProviderRequestState = SshProviderRequestState.PENDING_REVIEW,
        responseState: SshProviderRequestState = SshProviderRequestState.RESPONSE_QUEUED,
    ): Int

    @Transaction
    open suspend fun pruneHistoryBatch(): Int {
        val providerOverflow =
            (terminalProviderHistoryCount() - OperationalRetention.SSH_MAX_HISTORY_ROWS).coerceAtLeast(0)
        return pruneOldestProviderHistory(minOf(providerOverflow, OperationalRetention.SSH_PRUNE_BATCH_SIZE))
    }

    private fun SshProviderRequestEntity.requireValidNew() {
        requireIdentifier(requestId, "SSH provider request id")
        requireIdentifier(requesterClientId, "SSH provider requester id")
        require(requestFingerprint.isNotEmpty()) { "SSH provider request fingerprint must not be empty" }
        requireProtectedBlob(
            historyProtectionScheme,
            historyProtectionVersion,
            historyProtectionKeyRef,
            historyProtectionGeneration,
            historyPayloadCodecVersion,
            historyCiphertext,
            historyNonce,
        )
        require(state == SshProviderRequestState.PENDING_REVIEW && outcome == null && resultAt == null) {
            "new SSH provider request must be pending review"
        }
        require(createdAt > 0 && expiresAt > createdAt && updatedAt >= createdAt) {
            "SSH provider request timestamps are invalid"
        }
    }

    private fun SshProviderPendingPayloadEntity.requireValid(request: SshProviderRequestEntity) {
        require(requestId == request.requestId) { "SSH pending payload has a different request id" }
        requireProtectedBlob(
            protectionScheme,
            protectionVersion,
            protectionKeyRef,
            protectionGeneration,
            payloadCodecVersion,
            requestCiphertext,
            requestNonce,
        )
        require(createdAt > 0) { "SSH pending-payload creation time must be positive" }
    }

    private fun SshProviderOutcomeTransition.requireValid() {
        requireIdentifier(requestId, "SSH provider outcome request id")
        require(resultAt > 0) { "SSH provider outcome time must be positive" }
        when (outcome) {
            SshProviderRequestOutcome.SIGNED -> {
                val custody = requireNotNull(responseCustody) {
                    "signed SSH provider outcome requires protected response custody"
                }
                custody.requireValidResponseCustody(SshProviderResponsePayloadFormat.BODY)
                require(custody.requestId == requestId) {
                    "SSH provider response custody request id must match outcome"
                }
                require(custody.createdAt == resultAt && custody.updatedAt == resultAt) {
                    "new SSH provider response custody timestamps must equal the outcome time"
                }
            }
            SshProviderRequestOutcome.IMPORTED,
            SshProviderRequestOutcome.ALREADY_PRESENT,
            SshProviderRequestOutcome.REJECTED,
            SshProviderRequestOutcome.FAILED,
            SshProviderRequestOutcome.CANCELLED,
            SshProviderRequestOutcome.EXPIRED,
            -> require(responseCustody == null) {
                "non-signed SSH provider outcome must not carry response custody"
            }
        }
        activity?.requireValid()
    }

    /**
     * Non-custody outcomes are durably completed locally, but their best-effort notifications do
     * not have transport acceptance evidence. Keep SENT exclusive to accepted custody sends.
     * Explicit cancellation/expiry retain their dedicated terminal states.
     */
    private fun SshProviderRequestOutcome.terminalStateWithoutResponse(): SshProviderRequestState = when (this) {
        SshProviderRequestOutcome.CANCELLED -> SshProviderRequestState.CANCELLED
        SshProviderRequestOutcome.EXPIRED -> SshProviderRequestState.EXPIRED
        SshProviderRequestOutcome.SIGNED -> error("signed SSH provider outcome requires response custody")
        SshProviderRequestOutcome.IMPORTED,
        SshProviderRequestOutcome.ALREADY_PRESENT,
        SshProviderRequestOutcome.REJECTED,
        SshProviderRequestOutcome.FAILED,
        -> SshProviderRequestState.COMPLETED
    }

    private fun SshProviderResponseCustodyEntity.requireValidResponseCustody(
        expectedFormat: SshProviderResponsePayloadFormat,
    ) {
        requireIdentifier(requestId, "SSH provider response custody request id")
        require(payloadFormat == expectedFormat) { "SSH response custody format is invalid for this transition" }
        require(protectionGeneration > 0) { "SSH response protection generation must be positive" }
        requireProtectedBlob(
            protectionScheme,
            protectionVersion,
            protectionKeyRef,
            protectionGeneration,
            payloadCodecVersion,
            payloadCiphertext,
            payloadNonce,
        )
        require(createdAt > 0 && updatedAt >= createdAt) { "SSH response custody timestamps are invalid" }
    }

    private fun SshProviderResponseCustodyEntity.hasSamePersistedProjection(
        other: SshProviderResponseCustodyEntity,
    ): Boolean = requestId == other.requestId && payloadFormat == other.payloadFormat &&
        protectionScheme == other.protectionScheme && protectionVersion == other.protectionVersion &&
        protectionKeyRef == other.protectionKeyRef && protectionGeneration == other.protectionGeneration &&
        payloadCodecVersion == other.payloadCodecVersion &&
        payloadCiphertext.contentEquals(other.payloadCiphertext) && payloadNonce.contentEquals(other.payloadNonce) &&
        createdAt == other.createdAt && updatedAt == other.updatedAt
}

@Dao
internal abstract class SshResetDao {
    @Query("SELECT * FROM ssh_reset_journal WHERE singleton_id = 1")
    abstract suspend fun journal(): SshResetJournalEntity?

    @Query("SELECT * FROM ssh_reset_alias ORDER BY key_alias")
    abstract suspend fun aliases(): List<SshResetAliasEntity>

    @Query("SELECT * FROM ssh_reset_alias WHERE key_alias = :keyAlias")
    protected abstract suspend fun findAlias(keyAlias: String): SshResetAliasEntity?

    @Query(
        "SELECT alias_row.* FROM ssh_reset_alias AS alias_row " +
            "JOIN ssh_reset_journal AS journal_row " +
            "ON journal_row.singleton_id = alias_row.reset_singleton_id " +
            "WHERE journal_row.reset_id = :resetId AND alias_row.key_alias = :keyAlias",
    )
    protected abstract suspend fun findAliasForReset(
        resetId: String,
        keyAlias: String,
    ): SshResetAliasEntity?

    @Query("SELECT * FROM ssh_provider_state WHERE singleton_id = 1")
    protected abstract suspend fun providerStateForReset(): SshProviderStateEntity?

    @Query(
        "SELECT key_alias FROM ssh_operational_key UNION " +
            "SELECT operational_alias AS key_alias FROM ssh_key_lifecycle UNION " +
            "SELECT key_alias FROM ssh_key_lifecycle_candidate WHERE purpose = :operationalPurpose",
    )
    protected abstract suspend fun knownOperationalAliases(
        operationalPurpose: SshLifecycleCandidatePurpose = SshLifecycleCandidatePurpose.OPERATIONAL,
    ): List<String>

    @Query(
        "SELECT key_alias FROM ssh_export_copy UNION " +
            "SELECT key_alias FROM ssh_key_lifecycle_candidate WHERE purpose = :exportPurpose",
    )
    protected abstract suspend fun knownExportAliases(
        exportPurpose: SshLifecycleCandidatePurpose = SshLifecycleCandidatePurpose.EXPORT,
    ): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertJournalInternal(entity: SshResetJournalEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAliasInternal(entity: SshResetAliasEntity)

    @Query(
        "UPDATE ssh_reset_alias SET state = :nextState, attempt_count = :expectedAttemptCount + 1, " +
            "updated_at = :updatedAt, last_error_code = :errorCode " +
            "WHERE key_alias = :keyAlias AND state = :expectedState " +
            "AND attempt_count = :expectedAttemptCount " +
            "AND reset_singleton_id = (SELECT singleton_id FROM ssh_reset_journal WHERE reset_id = :resetId)",
    )
    protected abstract suspend fun recordAliasAttemptInternal(
        resetId: String,
        keyAlias: String,
        expectedState: SshResetAliasState,
        expectedAttemptCount: Int,
        nextState: SshResetAliasState,
        updatedAt: Long,
        errorCode: String?,
    ): Int

    @Query(
        "UPDATE ssh_reset_alias SET state = :retryState, updated_at = :updatedAt " +
            "WHERE key_alias = :keyAlias AND state = :blockedState " +
            "AND attempt_count = :expectedAttemptCount " +
            "AND reset_singleton_id = (SELECT singleton_id FROM ssh_reset_journal WHERE reset_id = :resetId)",
    )
    protected abstract suspend fun unblockAliasInternal(
        resetId: String,
        keyAlias: String,
        expectedAttemptCount: Int,
        updatedAt: Long,
        blockedState: SshResetAliasState = SshResetAliasState.BLOCKED,
        retryState: SshResetAliasState = SshResetAliasState.RETRY_WAIT,
    ): Int

    @Query(
        "UPDATE ssh_reset_journal SET state = :nextState, updated_at = :updatedAt, " +
            "last_error_code = :errorCode WHERE singleton_id = 1 AND reset_id = :resetId " +
            "AND state = :expectedState AND updated_at = :expectedUpdatedAt",
    )
    protected abstract suspend fun advanceJournalInternal(
        resetId: String,
        expectedState: SshResetState,
        expectedUpdatedAt: Long,
        nextState: SshResetState,
        updatedAt: Long,
        errorCode: String?,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM ssh_reset_alias AS alias_row " +
            "JOIN ssh_reset_journal AS journal_row " +
            "ON journal_row.singleton_id = alias_row.reset_singleton_id " +
            "WHERE journal_row.reset_id = :resetId AND alias_row.state NOT IN (:deleted, :notFound)",
    )
    protected abstract suspend fun incompleteAliasCount(
        resetId: String,
        deleted: SshResetAliasState = SshResetAliasState.DELETED,
        notFound: SshResetAliasState = SshResetAliasState.NOT_FOUND,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM ssh_reset_alias AS alias_row " +
            "JOIN ssh_reset_journal AS journal_row " +
            "ON journal_row.singleton_id = alias_row.reset_singleton_id " +
            "WHERE journal_row.reset_id = :resetId AND alias_row.state = :blocked",
    )
    protected abstract suspend fun blockedAliasCount(
        resetId: String,
        blocked: SshResetAliasState = SshResetAliasState.BLOCKED,
    ): Int

    @Query("DELETE FROM ssh_provider_request")
    protected abstract suspend fun deleteProviderRequests(): Int

    @Query("DELETE FROM ssh_authorization_floor")
    protected abstract suspend fun deleteAuthorizationFloors(): Int

    @Query("DELETE FROM ssh_known_host")
    protected abstract suspend fun deleteKnownHosts(): Int

    @Query("DELETE FROM ssh_key_lifecycle")
    protected abstract suspend fun deleteKeyLifecycles(): Int

    @Query("DELETE FROM ssh_key")
    protected abstract suspend fun deleteKeys(): Int

    @Query("DELETE FROM ssh_provider_state")
    protected abstract suspend fun deleteProviderState(): Int

    @Query("DELETE FROM activity_event WHERE feature = :feature")
    protected abstract suspend fun deleteSshActivity(feature: ActivityFeature = ActivityFeature.SSH_AGENT): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertProviderState(entity: SshProviderStateEntity)

    @Query(
        "DELETE FROM ssh_reset_journal WHERE singleton_id = 1 AND reset_id = :resetId " +
            "AND state = :expectedState AND updated_at = :expectedUpdatedAt",
    )
    protected abstract suspend fun deleteJournal(
        resetId: String,
        expectedState: SshResetState,
        expectedUpdatedAt: Long,
    ): Int

    @Transaction
    open suspend fun beginReset(
        journal: SshResetJournalEntity,
        aliases: List<SshResetAliasEntity>,
    ) {
        journal.requireValidNew()
        require(aliases.map { it.keyAlias }.toSet().size == aliases.size) { "SSH reset contains duplicate aliases" }
        aliases.forEach { it.requireValid(journal) }
        require(journal.oldInventoryGeneration == providerStateForReset()?.inventoryGeneration) {
            "SSH reset old inventory generation does not match provider state"
        }
        val aliasesByName = aliases.associateBy { it.keyAlias }
        val operationalAliases = knownOperationalAliases().toSet()
        val exportAliases = knownExportAliases().toSet()
        require(operationalAliases.intersect(exportAliases).isEmpty()) {
            "SSH storage contains an alias with conflicting custody roles"
        }
        operationalAliases.forEach { keyAlias ->
            require(aliasesByName[keyAlias]?.aliasKind == SshResetAliasKind.OPERATIONAL) {
                "SSH reset omitted or misclassified a known operational alias"
            }
        }
        exportAliases.forEach { keyAlias ->
            require(aliasesByName[keyAlias]?.aliasKind == SshResetAliasKind.EXPORT_COPY) {
                "SSH reset omitted or misclassified a known export-copy alias"
            }
        }
        val current = journal()
        if (current == null) {
            insertJournalInternal(journal)
        } else {
            require(
                current.state == SshResetState.JOURNALED &&
                    current.resetId == journal.resetId &&
                    current.oldInventoryGeneration == journal.oldInventoryGeneration &&
                    current.newInventoryGeneration == journal.newInventoryGeneration &&
                    current.startedAt == journal.startedAt,
            ) {
                "a different SSH reset is already in progress"
            }
        }
        aliases.forEach { alias ->
            val existing = findAlias(alias.keyAlias)
            if (existing == null) {
                insertAliasInternal(alias)
            } else {
                require(existing == alias) { "SSH reset alias changed during idempotent journaling" }
            }
        }
    }

    @Transaction
    open suspend fun recordAliasAttempt(
        resetId: String,
        keyAlias: String,
        expectedState: SshResetAliasState,
        expectedAttemptCount: Int,
        outcomeState: SshResetAliasState,
        updatedAt: Long,
        errorCode: String?,
    ): Boolean {
        requireIdentifier(resetId, "SSH reset id")
        requireIdentifier(keyAlias, "SSH reset alias")
        require(expectedState == SshResetAliasState.PENDING || expectedState == SshResetAliasState.RETRY_WAIT) {
            "SSH reset alias attempts can start only from pending or retry-wait"
        }
        require(expectedAttemptCount >= 0) { "SSH reset alias expected attempt count must not be negative" }
        require(updatedAt > 0) { "SSH reset alias update time must be positive" }
        requireCode(errorCode, "SSH reset alias error code")
        require(
            outcomeState == SshResetAliasState.DELETED ||
                outcomeState == SshResetAliasState.NOT_FOUND ||
                outcomeState == SshResetAliasState.RETRY_WAIT ||
                outcomeState == SshResetAliasState.BLOCKED,
        ) { "invalid SSH reset alias attempt outcome" }
        when (outcomeState) {
            SshResetAliasState.DELETED,
            SshResetAliasState.NOT_FOUND -> require(errorCode == null) {
                "completed SSH reset alias deletion cannot retain an error"
            }
            SshResetAliasState.RETRY_WAIT,
            SshResetAliasState.BLOCKED -> require(errorCode != null) {
                "incomplete SSH reset alias deletion requires an error code"
            }
            SshResetAliasState.PENDING -> error("invalid SSH reset alias attempt outcome")
        }
        val currentJournal = journal() ?: return false
        if (currentJournal.resetId != resetId || currentJournal.state != SshResetState.DELETING_ALIASES) return false
        val currentAlias = findAliasForReset(resetId, keyAlias) ?: return false
        if (currentAlias.state != expectedState || currentAlias.attemptCount != expectedAttemptCount) return false
        require(updatedAt >= currentJournal.updatedAt && updatedAt >= currentAlias.updatedAt) {
            "SSH reset alias update time regressed"
        }
        check(
            recordAliasAttemptInternal(
                resetId = resetId,
                keyAlias = keyAlias,
                expectedState = expectedState,
                expectedAttemptCount = expectedAttemptCount,
                nextState = outcomeState,
                updatedAt = updatedAt,
                errorCode = errorCode,
            ) == 1,
        ) { "SSH reset alias transition was lost" }
        if (outcomeState == SshResetAliasState.BLOCKED) {
            check(
                advanceJournalInternal(
                    resetId = resetId,
                    expectedState = SshResetState.DELETING_ALIASES,
                    expectedUpdatedAt = currentJournal.updatedAt,
                    nextState = SshResetState.BLOCKED,
                    updatedAt = updatedAt,
                    errorCode = errorCode,
                ) == 1,
            ) { "SSH reset journal block transition was lost" }
        }
        return true
    }

    @Transaction
    open suspend fun unblockAlias(
        resetId: String,
        keyAlias: String,
        expectedAttemptCount: Int,
        updatedAt: Long,
    ): Boolean {
        requireIdentifier(resetId, "SSH reset id")
        requireIdentifier(keyAlias, "SSH reset alias")
        require(expectedAttemptCount >= 0) { "SSH reset alias expected attempt count must not be negative" }
        require(updatedAt > 0) { "SSH reset alias update time must be positive" }
        val currentJournal = journal() ?: return false
        if (currentJournal.resetId != resetId || currentJournal.state != SshResetState.BLOCKED) return false
        val currentAlias = findAliasForReset(resetId, keyAlias) ?: return false
        if (currentAlias.state != SshResetAliasState.BLOCKED ||
            currentAlias.attemptCount != expectedAttemptCount
        ) return false
        require(updatedAt >= currentJournal.updatedAt && updatedAt >= currentAlias.updatedAt) {
            "SSH reset alias update time regressed"
        }
        check(
            unblockAliasInternal(
                resetId = resetId,
                keyAlias = keyAlias,
                expectedAttemptCount = expectedAttemptCount,
                updatedAt = updatedAt,
            ) == 1,
        ) { "SSH reset alias unblock transition was lost" }
        return true
    }

    @Transaction
    open suspend fun resumeAliasDeletion(
        resetId: String,
        expectedUpdatedAt: Long,
        updatedAt: Long,
    ): Boolean {
        requireIdentifier(resetId, "SSH reset id")
        require(expectedUpdatedAt > 0 && updatedAt >= expectedUpdatedAt) {
            "SSH reset resume timestamps are invalid"
        }
        val current = journal() ?: return false
        if (
            current.resetId != resetId || current.state != SshResetState.BLOCKED ||
            current.updatedAt != expectedUpdatedAt
        ) return false
        require(blockedAliasCount(resetId) == 0) { "SSH reset still has blocked aliases" }
        return advanceJournalInternal(
            resetId = resetId,
            expectedState = SshResetState.BLOCKED,
            expectedUpdatedAt = expectedUpdatedAt,
            nextState = SshResetState.DELETING_ALIASES,
            updatedAt = updatedAt,
            errorCode = null,
        ) == 1
    }

    @Transaction
    open suspend fun advanceJournal(
        resetId: String,
        expectedState: SshResetState,
        expectedUpdatedAt: Long,
        nextState: SshResetState,
        updatedAt: Long,
        errorCode: String?,
    ): Boolean {
        requireIdentifier(resetId, "SSH reset id")
        require(expectedUpdatedAt > 0 && updatedAt >= expectedUpdatedAt) {
            "SSH reset journal timestamps are invalid"
        }
        requireCode(errorCode, "SSH reset error code")
        require(expectedState != SshResetState.BLOCKED) {
            "blocked SSH reset must resume through resumeAliasDeletion"
        }
        require(nextState in expectedState.allowedNextStates()) { "invalid SSH reset transition" }
        require((nextState == SshResetState.BLOCKED) == (errorCode != null)) {
            "SSH reset journal error must match blocked state"
        }
        val current = journal() ?: return false
        if (
            current.resetId != resetId || current.state != expectedState ||
            current.updatedAt != expectedUpdatedAt
        ) return false
        if (nextState == SshResetState.FINALIZING) {
            require(incompleteAliasCount(resetId) == 0) { "SSH reset still has aliases owed deletion" }
        }
        return advanceJournalInternal(
            resetId = resetId,
            expectedState = expectedState,
            expectedUpdatedAt = expectedUpdatedAt,
            nextState = nextState,
            updatedAt = updatedAt,
            errorCode = errorCode,
        ) == 1
    }

    @Transaction
    open suspend fun finalizeReset(
        resetId: String,
        expectedUpdatedAt: Long,
        finalizedAt: Long,
    ): Boolean {
        requireIdentifier(resetId, "SSH reset id")
        require(expectedUpdatedAt > 0 && finalizedAt >= expectedUpdatedAt) {
            "SSH reset completion timestamps are invalid"
        }
        val current = journal() ?: return false
        if (
            current.resetId != resetId || current.state != SshResetState.FINALIZING ||
            current.updatedAt != expectedUpdatedAt
        ) return false
        require(incompleteAliasCount(resetId) == 0) { "SSH reset still has aliases owed deletion" }

        // Only SSH-owned tables plus its Activity projections are cleared. Provider-request
        // deletion cascades through pending and response-custody children.
        deleteProviderRequests()
        deleteAuthorizationFloors()
        deleteKnownHosts()
        deleteKeyLifecycles()
        deleteKeys()
        deleteProviderState()
        deleteSshActivity()
        insertProviderState(
            SshProviderStateEntity(
                inventoryGeneration = current.newInventoryGeneration,
                revision = 1,
                updatedAt = finalizedAt,
            ),
        )
        check(
            deleteJournal(
                resetId = resetId,
                expectedState = SshResetState.FINALIZING,
                expectedUpdatedAt = expectedUpdatedAt,
            ) == 1,
        ) { "SSH reset journal disappeared before completion" }
        return true
    }

    private fun SshResetJournalEntity.requireValidNew() {
        requireValidProgress()
        require(state == SshResetState.JOURNALED) { "new SSH reset must be journaled" }
    }

    private fun SshResetJournalEntity.requireValidProgress() {
        require(singletonId == OperationalSingletons.ID) { "invalid SSH reset singleton id" }
        requireIdentifier(resetId, "SSH reset id")
        oldInventoryGeneration?.let { requireIdentifier(it, "old SSH inventory generation") }
        requireIdentifier(newInventoryGeneration, "new SSH inventory generation")
        require(newInventoryGeneration != oldInventoryGeneration) {
            "SSH reset must install a fresh inventory generation"
        }
        require(startedAt > 0 && updatedAt >= startedAt) { "SSH reset timestamps are invalid" }
        requireCode(lastErrorCode, "SSH reset error code")
        require((state == SshResetState.BLOCKED) == (lastErrorCode != null)) {
            "SSH reset journal error must match blocked state"
        }
    }

    private fun SshResetAliasEntity.requireValid(journal: SshResetJournalEntity) {
        requireIdentifier(keyAlias, "SSH reset alias")
        require(resetSingletonId == journal.singletonId) { "SSH reset alias has a different journal" }
        require(state == SshResetAliasState.PENDING && attemptCount == 0 && lastErrorCode == null) {
            "new SSH reset alias must be pending"
        }
        require(updatedAt >= journal.startedAt) { "SSH reset alias timestamp is invalid" }
    }

    private fun SshResetState.allowedNextStates(): Set<SshResetState> = when (this) {
        SshResetState.JOURNALED -> setOf(SshResetState.DELETING_ALIASES, SshResetState.BLOCKED)
        SshResetState.DELETING_ALIASES -> setOf(SshResetState.FINALIZING, SshResetState.BLOCKED)
        SshResetState.FINALIZING -> setOf(SshResetState.BLOCKED)
        SshResetState.BLOCKED -> emptySet()
    }
}
