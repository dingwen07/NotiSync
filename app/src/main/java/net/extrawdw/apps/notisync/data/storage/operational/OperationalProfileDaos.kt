package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.extrawdw.apps.notisync.data.incomingfilter.CanonicalIncomingFilterOrigin
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterCanonicalizer
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterRuleValue

@Dao
internal abstract class OperationalProfileDao {
    internal enum class MaintenanceInitializeResult {
        INITIALIZED,
        ALREADY_INITIALIZED,
        CONFLICT,
    }

    internal enum class MaintenancePristineInitializeResult {
        INSERTED,
        ALREADY_INITIALIZED,
        CONFLICT,
    }

    @Query("SELECT * FROM maintenance_state WHERE singleton_id = 1")
    protected abstract fun observeMaintenanceInternal(): Flow<MaintenanceStateEntity?>

    @Query("SELECT * FROM maintenance_state WHERE singleton_id = 1")
    protected abstract suspend fun readMaintenanceInternal(): MaintenanceStateEntity?

    /**
     * Compile-time-fixed evidence query for the one-time marker cutover. The maintenance marker
     * itself is intentionally omitted: its presence is handled by [initializeIfPristine]. Every
     * other Operational table is included so callers cannot race a caller-supplied "empty" hint
     * against a feature/import write between validation and marker insertion.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM local_profile LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM notification_capture_state LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM android_app_policy LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM android_subscope_policy LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM android_seen_group LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM android_seen_channel LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM incoming_filter LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM incoming_filter_rule LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ios_app_allowlist LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ios_seen_app LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM activity_event LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM message_dedup LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM relay_batch_stage LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM mirror_lifecycle LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM run_state LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM seal_enrollment LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM seal_enrollment_protected LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM seal_request LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM seal_pending_payload LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM seal_response_custody LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM screen_authorized_peer LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM screen_replay_token LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM screen_security_state LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM screen_codec_preference LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_provider_state LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_reset_journal LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_reset_alias LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_key LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_operational_key LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_wrapped_operational_material LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_export_copy LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_key_lifecycle LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_key_lifecycle_candidate LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_authorization_floor LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_peer_authorization LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_known_host LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_host_authorization LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_provider_request LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_provider_pending_payload LIMIT 1) OR " +
            "EXISTS(SELECT 1 FROM ssh_provider_response_custody LIMIT 1)",
    )
    protected abstract suspend fun hasAnyNonMaintenanceRows(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM maintenance_state LIMIT 1)")
    protected abstract suspend fun hasAnyMaintenanceRows(): Boolean

    fun observeMaintenance(): Flow<MaintenanceStateEntity?> =
        observeMaintenanceInternal().map { entity -> entity?.also { it.requireValid() } }

    suspend fun readMaintenance(): MaintenanceStateEntity? =
        readMaintenanceInternal()?.also { it.requireValid() }

    @Query("SELECT * FROM local_profile WHERE singleton_id = 1")
    abstract fun observeLocalProfile(): Flow<LocalProfileEntity?>

    @Query("SELECT * FROM notification_capture_state WHERE singleton_id = 1")
    abstract fun observeNotificationCaptureState(): Flow<NotificationCaptureStateEntity?>

    @Query("SELECT * FROM notification_capture_state WHERE singleton_id = 1")
    protected abstract suspend fun notificationCaptureState(): NotificationCaptureStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMaintenanceInternal(entity: MaintenanceStateEntity): Long

    /**
     * Atomically creates the initial Operational continuity marker only on an exact pristine target.
     * A pre-existing marker is an exact immutable-generation/incarnation replay; all other marker
     * values conflict. Mutable maintenance facts are deliberately initialized empty and can only be
     * advanced later through [replaceMaintenance].
     */
    @Transaction
    open suspend fun initializeIfPristine(
        operationalGeneration: Long,
        storageIncarnationId: String,
        updatedAt: Long,
    ): MaintenancePristineInitializeResult {
        require(operationalGeneration > 0) { "operational generation must be positive" }
        requireStorageIncarnationId(storageIncarnationId)
        require(updatedAt > 0) { "maintenance update time must be positive" }

        val current = readMaintenance()
        if (current != null) {
            return if (
                current.operationalGeneration == operationalGeneration &&
                current.storageIncarnationId == storageIncarnationId
            ) {
                MaintenancePristineInitializeResult.ALREADY_INITIALIZED
            } else {
                MaintenancePristineInitializeResult.CONFLICT
            }
        }
        // A row with an invalid/non-singleton key is corruption, not a pristine target. Do not
        // hide it by inserting the expected singleton marker alongside it.
        if (hasAnyMaintenanceRows()) return MaintenancePristineInitializeResult.CONFLICT
        if (hasAnyNonMaintenanceRows()) return MaintenancePristineInitializeResult.CONFLICT

        val inserted = insertMaintenanceInternal(
            MaintenanceStateEntity(
                operationalGeneration = operationalGeneration,
                storageIncarnationId = storageIncarnationId,
                postCutoverWriteAt = null,
                lastIntegrityCheckAt = null,
                updatedAt = updatedAt,
            ),
        )
        if (inserted != -1L) return MaintenancePristineInitializeResult.INSERTED

        // Defensive handling for a race through another database handle. SQLite serializes the
        // write transaction; this read makes the result explicit if an external writer won first.
        val raced = readMaintenance() ?: error("maintenance marker disappeared during initialization")
        return if (
            raced.operationalGeneration == operationalGeneration &&
            raced.storageIncarnationId == storageIncarnationId
        ) {
            MaintenancePristineInitializeResult.ALREADY_INITIALIZED
        } else {
            MaintenancePristineInitializeResult.CONFLICT
        }
    }

    @Query(
        "UPDATE maintenance_state SET " +
            "post_cutover_write_at = :postCutoverWriteAt, " +
            "last_integrity_check_at = :lastIntegrityCheckAt, updated_at = :updatedAt " +
            "WHERE singleton_id = 1 AND operational_generation = :expectedGeneration " +
            "AND storage_incarnation_id = :expectedIncarnationId " +
            "AND updated_at = :expectedUpdatedAt",
    )
    protected abstract suspend fun updateMaintenanceMutableInternal(
        expectedGeneration: Long,
        expectedIncarnationId: String,
        expectedUpdatedAt: Long,
        postCutoverWriteAt: Long?,
        lastIntegrityCheckAt: Long?,
        updatedAt: Long,
    ): Int

    @Upsert
    protected abstract suspend fun upsertProfileInternal(entity: LocalProfileEntity)

    @Upsert
    protected abstract suspend fun upsertNotificationCaptureStateInternal(entity: NotificationCaptureStateEntity)

    /**
     * Establishes this physical database's continuity marker exactly once. The caller supplies the
     * incarnation token; Room never generates or defaults security-relevant continuity evidence.
     */
    open suspend fun initializeMaintenance(entity: MaintenanceStateEntity): MaintenanceInitializeResult {
        entity.requireValid()
        require(entity.postCutoverWriteAt == null && entity.lastIntegrityCheckAt == null) {
            "initial maintenance marker cannot include mutable evidence"
        }
        return when (
            initializeIfPristine(
                operationalGeneration = entity.operationalGeneration,
                storageIncarnationId = entity.storageIncarnationId,
                updatedAt = entity.updatedAt,
            )
        ) {
            MaintenancePristineInitializeResult.INSERTED -> MaintenanceInitializeResult.INITIALIZED
            MaintenancePristineInitializeResult.ALREADY_INITIALIZED ->
                MaintenanceInitializeResult.ALREADY_INITIALIZED
            MaintenancePristineInitializeResult.CONFLICT -> MaintenanceInitializeResult.CONFLICT
        }
    }

    /** Updates mutable maintenance facts without including either continuity-marker column in SQL. */
    @Transaction
    open suspend fun replaceMaintenance(entity: MaintenanceStateEntity) {
        entity.requireValid()
        val current = readMaintenance() ?: error("maintenance marker must be initialized first")
        require(
            entity.operationalGeneration == current.operationalGeneration &&
                entity.storageIncarnationId == current.storageIncarnationId,
        ) { "maintenance continuity marker is immutable" }
        require(current.postCutoverWriteAt == null || entity.postCutoverWriteAt == current.postCutoverWriteAt) {
            "post-cutover write evidence cannot be cleared or changed"
        }
        require(
            current.lastIntegrityCheckAt == null ||
                (entity.lastIntegrityCheckAt != null && entity.lastIntegrityCheckAt >= current.lastIntegrityCheckAt),
        ) { "last integrity-check time cannot clear or regress" }
        require(entity.updatedAt >= current.updatedAt) { "maintenance update time must not regress" }
        val factsChanged = entity.postCutoverWriteAt != current.postCutoverWriteAt ||
                entity.lastIntegrityCheckAt != current.lastIntegrityCheckAt
        require(!factsChanged || entity.updatedAt > current.updatedAt) {
            "a changed maintenance row requires a newer update time"
        }
        check(
            updateMaintenanceMutableInternal(
                expectedGeneration = current.operationalGeneration,
                expectedIncarnationId = current.storageIncarnationId,
                expectedUpdatedAt = current.updatedAt,
                postCutoverWriteAt = entity.postCutoverWriteAt,
                lastIntegrityCheckAt = entity.lastIntegrityCheckAt,
                updatedAt = entity.updatedAt,
            ) == 1,
        ) { "maintenance update lost its continuity marker" }
    }

    suspend fun replaceLocalProfile(entity: LocalProfileEntity) {
        require(entity.singletonId == OperationalSingletons.ID) { "invalid local-profile singleton id" }
        require(entity.deviceName.isNotBlank() && entity.deviceName.length <= OperationalStorageLimits.MAX_DISPLAY_CHARS) {
            "local-profile device name is invalid"
        }
        require(entity.deviceName.none(Char::isISOControl)) { "local-profile device name contains control characters" }
        require(entity.deviceNameUpdatedAt >= 0 && entity.profileRevisionAt >= 0 && entity.updatedAt > 0) {
            "local-profile timestamps are invalid"
        }
        upsertProfileInternal(entity)
    }

    @Transaction
    open suspend fun advanceNotificationCaptureLastSeenPostTime(timeMillis: Long, updatedAt: Long): Long {
        require(timeMillis >= 0 && updatedAt > 0) { "notification-capture watermark timestamps are invalid" }
        val current = notificationCaptureState()
        val next = maxOf(timeMillis, current?.lastSeenPostTime ?: 0L)
        val persistedUpdatedAt = maxOf(updatedAt, current?.updatedAt ?: 0L)
        upsertNotificationCaptureStateInternal(
            NotificationCaptureStateEntity(
                lastSeenPostTime = next,
                updatedAt = persistedUpdatedAt,
            ),
        )
        return next
    }

    private fun MaintenanceStateEntity.requireValid() {
        require(singletonId == OperationalSingletons.ID) { "invalid maintenance singleton id" }
        require(operationalGeneration > 0 && updatedAt > 0) {
            "invalid maintenance record"
        }
        requireStorageIncarnationId(storageIncarnationId)
        require(postCutoverWriteAt == null || postCutoverWriteAt > 0) {
            "post-cutover write time must be positive"
        }
        require(lastIntegrityCheckAt == null || lastIntegrityCheckAt > 0) {
            "last integrity-check time must be positive"
        }
    }
}

@Dao
internal abstract class AndroidNotificationPolicyDao {
    @Query("SELECT * FROM android_app_policy ORDER BY package_name")
    abstract fun observeApps(): Flow<List<AndroidAppPolicyEntity>>

    @Query("SELECT * FROM android_app_policy WHERE package_name = :packageName")
    abstract suspend fun findApp(packageName: String): AndroidAppPolicyEntity?

    @Query(
        "SELECT * FROM android_subscope_policy WHERE package_name = :packageName " +
            "AND scope_kind = :scopeKind ORDER BY scope_id",
    )
    abstract fun observeSubscopes(
        packageName: String,
        scopeKind: AndroidPolicyScope,
    ): Flow<List<AndroidSubscopePolicyEntity>>

    @Query(
        "SELECT * FROM android_seen_channel WHERE package_name = :packageName " +
            "ORDER BY last_seen_at DESC, channel_id ASC",
    )
    abstract fun observeSeenChannels(packageName: String): Flow<List<AndroidSeenChannelEntity>>

    @Query(
        "SELECT * FROM android_seen_group WHERE package_name = :packageName " +
            "ORDER BY last_seen_at DESC, group_id ASC",
    )
    abstract fun observeSeenGroups(packageName: String): Flow<List<AndroidSeenGroupEntity>>

    @Upsert
    protected abstract suspend fun upsertAppInternal(entity: AndroidAppPolicyEntity)

    @Upsert
    protected abstract suspend fun upsertSubscopeInternal(entity: AndroidSubscopePolicyEntity)

    @Upsert
    protected abstract suspend fun upsertSeenChannelInternal(entity: AndroidSeenChannelEntity)

    @Upsert
    protected abstract suspend fun upsertSeenGroupInternal(entity: AndroidSeenGroupEntity)

    @Query(
        "DELETE FROM android_subscope_policy WHERE package_name = :packageName " +
            "AND scope_kind = :scopeKind AND scope_id = :scopeId",
    )
    abstract suspend fun deleteSubscopePolicy(
        packageName: String,
        scopeKind: AndroidPolicyScope,
        scopeId: String,
    ): Int

    @Query("DELETE FROM android_seen_channel WHERE package_name = :packageName AND channel_id = :channelId")
    abstract suspend fun forgetSeenChannel(packageName: String, channelId: String): Int

    @Query("DELETE FROM android_seen_group WHERE package_name = :packageName AND group_id = :groupId")
    abstract suspend fun forgetSeenGroup(packageName: String, groupId: String): Int

    @Query("DELETE FROM android_app_policy WHERE package_name = :packageName")
    abstract suspend fun forgetApp(packageName: String): Int

    suspend fun upsertApp(entity: AndroidAppPolicyEntity) {
        entity.requireValid()
        upsertAppInternal(entity)
    }

    suspend fun upsertSubscope(entity: AndroidSubscopePolicyEntity) {
        requirePackageName(entity.packageName)
        requireIdentifier(entity.scopeId, "notification subscope id")
        require(entity.updatedAt > 0) { "notification subscope policy update time must be positive" }
        upsertSubscopeInternal(entity)
    }

    @Transaction
    open suspend fun recordSeenChannel(
        group: AndroidSeenGroupEntity?,
        channel: AndroidSeenChannelEntity,
    ) {
        requirePackageName(channel.packageName)
        requireIdentifier(channel.channelId, "seen notification channel id")
        require(channel.channelName == null || channel.channelName.length <= OperationalStorageLimits.MAX_DISPLAY_CHARS) {
            "notification channel name is too long"
        }
        require(channel.channelName == null || channel.channelName.none(Char::isISOControl)) {
            "notification channel name contains control characters"
        }
        require(channel.groupId == null || channel.groupId.length <= OperationalStorageLimits.MAX_ID_CHARS) {
            "notification group id is too long"
        }
        require(channel.firstSeenAt > 0 && channel.lastSeenAt >= channel.firstSeenAt) {
            "seen notification channel timestamps are invalid"
        }
        require((channel.groupId == null) == (group == null)) {
            "seen notification channel and group must be present together"
        }
        group?.let {
            require(it.packageName == channel.packageName && it.groupId == channel.groupId) {
                "seen notification group does not own the channel"
            }
            requirePackageName(it.packageName)
            requireIdentifier(it.groupId, "seen notification group id")
            require(it.groupName == null || it.groupName.length <= OperationalStorageLimits.MAX_DISPLAY_CHARS) {
                "notification group name is too long"
            }
            require(it.groupName == null || it.groupName.none(Char::isISOControl)) {
                "notification group name contains control characters"
            }
            require(it.firstSeenAt > 0 && it.lastSeenAt >= it.firstSeenAt) {
                "seen notification group timestamps are invalid"
            }
            upsertSeenGroupInternal(it)
        }
        upsertSeenChannelInternal(channel)
    }

    private fun AndroidAppPolicyEntity.requireValid() {
        requirePackageName(packageName)
        require(updateIntervalSeconds >= -1) { "notification update interval must be -1 or non-negative" }
        require(lastSeenAt == null || lastSeenAt > 0) { "notification app last-seen time is invalid" }
        require(updatedAt > 0) { "notification app policy update time must be positive" }
    }

    private fun requirePackageName(packageName: String) {
        require(packageName.isNotBlank() && packageName.length <= OperationalStorageLimits.MAX_PACKAGE_CHARS) {
            "package name is invalid"
        }
        require(packageName.none(Char::isISOControl)) { "package name contains control characters" }
    }
}

internal enum class IncomingFilterReplaceResult {
    INSERTED,
    REPLACED,
    UNCHANGED,
    STALE,
    CONFLICT,
}

/** Transactionally consistent Room relation used by the storage-independent Incoming Filter repository. */
internal data class IncomingFilterAggregateRow(
    @Embedded
    val header: IncomingFilterEntity,
    @Relation(
        parentColumns = ["requester_client_id"],
        entityColumns = ["requester_client_id"],
    )
    var rules: List<IncomingFilterRuleEntity> = emptyList(),
)

@Dao
internal abstract class IncomingFilterDao : OperationalReceiptOwningDao() {
    @Query("SELECT * FROM incoming_filter WHERE requester_client_id = :requesterClientId")
    abstract suspend fun find(requesterClientId: String): IncomingFilterEntity?

    @Query("SELECT * FROM incoming_filter ORDER BY requester_client_id")
    abstract fun observeHeaders(): Flow<List<IncomingFilterEntity>>

    @Transaction
    @Query("SELECT * FROM incoming_filter WHERE requester_client_id = :requesterClientId")
    abstract suspend fun findAggregate(requesterClientId: String): IncomingFilterAggregateRow?

    @Transaction
    @Query("SELECT * FROM incoming_filter WHERE requester_client_id = :requesterClientId")
    abstract fun observeAggregate(requesterClientId: String): Flow<IncomingFilterAggregateRow?>

    @Transaction
    @Query("SELECT * FROM incoming_filter ORDER BY requester_client_id")
    abstract fun observeAggregates(): Flow<List<IncomingFilterAggregateRow>>

    @Query(
        "SELECT * FROM incoming_filter_rule WHERE requester_client_id = :requesterClientId " +
            "ORDER BY position ASC",
    )
    abstract fun observeRules(requesterClientId: String): Flow<List<IncomingFilterRuleEntity>>

    @Upsert
    protected abstract suspend fun upsertHeader(entity: IncomingFilterEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRules(entities: List<IncomingFilterRuleEntity>)

    @Query("DELETE FROM incoming_filter_rule WHERE requester_client_id = :requesterClientId")
    protected abstract suspend fun deleteRules(requesterClientId: String): Int

    @Query("DELETE FROM incoming_filter WHERE requester_client_id = :requesterClientId")
    abstract suspend fun remove(requesterClientId: String): Int

    @Transaction
    open suspend fun replace(
        header: IncomingFilterEntity,
        rules: List<IncomingFilterRuleEntity>,
    ): IncomingFilterReplaceResult {
        requireIdentifier(header.requesterClientId, "filter requester id")
        require(header.canonicalizationVersion == IncomingFilterCanonicalizer.VERSION) {
            "filter canonicalization version is unsupported"
        }
        require(header.updatedAt > 0 && header.receivedAt > 0) { "filter timestamps must be positive" }
        require(header.ruleSetDigest.size == OperationalStorageLimits.SHA256_BYTES) {
            "filter set digest must be SHA-256"
        }
        rules.forEachIndexed { index, rule ->
            require(rule.requesterClientId == header.requesterClientId) { "filter rule has a different requester" }
            require(rule.position == index) { "filter rules are not in deterministic order" }
            require(rule.ruleDigest.size == OperationalStorageLimits.SHA256_BYTES) {
                "filter rule digest must be SHA-256"
            }
            require(rule.appId == null || rule.appId.length <= OperationalStorageLimits.MAX_PACKAGE_CHARS) {
                "filter app id is too long"
            }
            require(rule.channelId == null || rule.channelId.length <= OperationalStorageLimits.MAX_ID_CHARS) {
                "filter channel id is too long"
            }
        }
        require(rules.indices.none { left ->
            ((left + 1) until rules.size).any { right ->
                rules[left].ruleDigest.contentEquals(rules[right].ruleDigest)
            }
        }) { "filter contains duplicate rule digests" }

        val canonical = IncomingFilterCanonicalizer.canonicalize(
            rules.map { rule ->
                IncomingFilterRuleValue(
                    origin = when (rule.originPlatform) {
                        NotificationOriginPlatform.ANDROID_LOCAL -> CanonicalIncomingFilterOrigin.ANDROID_LOCAL
                        NotificationOriginPlatform.IOS_ANCS -> CanonicalIncomingFilterOrigin.IOS_ANCS
                    },
                    appId = rule.appId,
                    channelId = rule.channelId,
                )
            },
        )
        require(canonical.rules.size == rules.size) { "filter rules are not canonically deduplicated" }
        canonical.rules.forEachIndexed { index, canonicalRule ->
            require(canonicalRule.value.origin == when (rules[index].originPlatform) {
                NotificationOriginPlatform.ANDROID_LOCAL -> CanonicalIncomingFilterOrigin.ANDROID_LOCAL
                NotificationOriginPlatform.IOS_ANCS -> CanonicalIncomingFilterOrigin.IOS_ANCS
            } && canonicalRule.value.appId == rules[index].appId &&
                canonicalRule.value.channelId == rules[index].channelId &&
                canonicalRule.digestCopy().contentEquals(rules[index].ruleDigest)
            ) { "filter rule canonical projection mismatch" }
        }
        require(canonical.digestCopy().contentEquals(header.ruleSetDigest)) {
            "filter set canonical digest mismatch"
        }

        val current = find(header.requesterClientId)
        if (current != null) {
            if (header.updatedAt < current.updatedAt) return IncomingFilterReplaceResult.STALE
            if (header.updatedAt == current.updatedAt) {
                return if (header.ruleSetDigest.contentEquals(current.ruleSetDigest)) {
                    IncomingFilterReplaceResult.UNCHANGED
                } else {
                    IncomingFilterReplaceResult.CONFLICT
                }
            }
        }
        upsertHeader(header)
        deleteRules(header.requesterClientId)
        if (rules.isNotEmpty()) insertRules(rules)
        return if (current == null) IncomingFilterReplaceResult.INSERTED else IncomingFilterReplaceResult.REPLACED
    }

    /** Atomically replaces a canonical filter set and commits its broker receipt evidence. */
    suspend fun replaceWithReceipt(
        header: IncomingFilterEntity,
        rules: List<IncomingFilterRuleEntity>,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult = runOwnedReceiptTransaction {
        replaceWithReceiptInternal(header, rules, receipt)
    }

    @Transaction
    protected open suspend fun replaceWithReceiptInternal(
        header: IncomingFilterEntity,
        rules: List<IncomingFilterRuleEntity>,
        receipt: PreparedOperationalReceipt,
    ): OperationalFeatureCommitResult {
        existingReceiptResult(receipt)?.let { return it }
        return when (replace(header, rules)) {
            IncomingFilterReplaceResult.INSERTED,
            IncomingFilterReplaceResult.REPLACED -> finalizeOwnedReceipt(
                receipt,
                OperationalReceiptDisposition.APPLIED,
                persistActivity = receipt.activity != null,
            )
            IncomingFilterReplaceResult.UNCHANGED ->
                finalizeOwnedReceipt(
                    receipt,
                    OperationalReceiptDisposition.DUPLICATE,
                    persistActivity = false,
                )
            IncomingFilterReplaceResult.STALE ->
                finalizeOwnedReceipt(
                    receipt,
                    OperationalReceiptDisposition.SUPERSEDED,
                    persistActivity = false,
                )
            IncomingFilterReplaceResult.CONFLICT -> OperationalFeatureCommitResult.ConflictNoAck
        }
    }
}

@Dao
internal abstract class IosAppDao {
    @Query("SELECT * FROM ios_app_allowlist ORDER BY bundle_id")
    abstract fun observeAllowlist(): Flow<List<IosAppAllowlistEntity>>

    @Query("SELECT * FROM ios_seen_app ORDER BY last_seen_at DESC, bundle_id ASC")
    abstract fun observeSeen(): Flow<List<IosSeenAppEntity>>

    @Query("SELECT * FROM ios_app_allowlist WHERE bundle_id = :bundleId")
    abstract suspend fun findAllowlisted(bundleId: String): IosAppAllowlistEntity?

    @Query("SELECT * FROM ios_seen_app WHERE bundle_id = :bundleId")
    abstract suspend fun findSeen(bundleId: String): IosSeenAppEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAllowlistedInternal(entity: IosAppAllowlistEntity): Long

    @Upsert
    protected abstract suspend fun upsertSeenInternal(entity: IosSeenAppEntity)

    @Query("DELETE FROM ios_app_allowlist WHERE bundle_id = :bundleId")
    abstract suspend fun removeAllowlisted(bundleId: String): Int

    @Query("DELETE FROM ios_seen_app WHERE bundle_id = :bundleId")
    abstract suspend fun forgetSeen(bundleId: String): Int

    suspend fun putAllowlisted(bundleId: String): Boolean {
        requireBundleId(bundleId)
        return insertAllowlistedInternal(IosAppAllowlistEntity(bundleId)) != -1L
    }

    suspend fun putSeen(entity: IosSeenAppEntity) {
        entity.requireValid()
        upsertSeenInternal(entity)
    }

    @Transaction
    open suspend fun recordSeen(
        bundleId: String,
        displayName: String,
        seenAt: Long,
    ) {
        require(bundleId.isNotBlank() && bundleId.length <= OperationalStorageLimits.MAX_PACKAGE_CHARS) {
            "iOS bundle id is invalid"
        }
        require(displayName.isNotBlank() && displayName.length <= OperationalStorageLimits.MAX_DISPLAY_CHARS) {
            "iOS display name is invalid"
        }
        require(seenAt > 0) { "iOS seen time must be positive" }
        val existing = findSeen(bundleId)
        upsertSeenInternal(
            IosSeenAppEntity(
                bundleId = bundleId,
                displayName = displayName,
                lastSeenAt = maxOf(existing?.lastSeenAt ?: 0L, seenAt),
            ),
        )
    }

    @Transaction
    open suspend fun setEnabled(bundleId: String, enabled: Boolean): Boolean = if (enabled) {
        putAllowlisted(bundleId)
    } else {
        removeAllowlisted(bundleId) == 1
    }

    private fun IosSeenAppEntity.requireValid() {
        requireBundleId(bundleId)
        require(displayName.isNotBlank() && displayName.length <= OperationalStorageLimits.MAX_DISPLAY_CHARS) {
            "iOS display name is invalid"
        }
        require(displayName.none(Char::isISOControl)) { "iOS display name contains control characters" }
        require(lastSeenAt > 0) { "iOS app last-seen time is invalid" }
    }

    private fun requireBundleId(bundleId: String) {
        require(bundleId.isNotBlank() && bundleId.length <= OperationalStorageLimits.MAX_PACKAGE_CHARS) {
            "iOS bundle id is invalid"
        }
        require(bundleId.none(Char::isISOControl)) { "iOS bundle id contains control characters" }
    }
}

@Dao
internal abstract class ActivityDao {
    @Query("SELECT * FROM activity_event WHERE event_id = :eventId")
    protected abstract suspend fun findInternal(eventId: String): ActivityEventEntity?

    suspend fun find(eventId: String): ActivityEventEntity? {
        requireIdentifier(eventId, "activity event id")
        return findInternal(eventId)?.also { it.requireValid() }
    }

    @Query(
        "SELECT * FROM activity_event ORDER BY recorded_at DESC, occurred_at DESC, event_id DESC LIMIT :limit",
    )
    abstract fun observeNewest(limit: Int = OperationalRetention.ACTIVITY_MAX_ROWS): Flow<List<ActivityEventEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertInternal(entity: ActivityEventEntity): Long

    suspend fun insert(entity: ActivityEventEntity): Boolean {
        entity.requireValid()
        return insertInternal(entity) != -1L
    }

    @Query(
        "DELETE FROM activity_event WHERE event_id IN (" +
            "SELECT event_id FROM activity_event WHERE recorded_at < :cutoff " +
            "ORDER BY recorded_at ASC, event_id ASC LIMIT :limit)",
    )
    protected abstract suspend fun pruneOlderThan(cutoff: Long, limit: Int): Int

    @Query("SELECT COUNT(*) FROM activity_event")
    protected abstract suspend fun rowCount(): Int

    @Query(
        "DELETE FROM activity_event WHERE event_id IN (" +
            "SELECT event_id FROM activity_event ORDER BY recorded_at ASC, event_id ASC LIMIT :limit)",
    )
    protected abstract suspend fun pruneOldest(limit: Int): Int

    /** One intentionally small maintenance transaction; callers repeat it on later maintenance passes. */
    @Transaction
    open suspend fun pruneBatch(now: Long): Int {
        require(now > 0) { "activity prune time must be positive" }
        var removed = pruneOlderThan(
            cutoff = now - OperationalRetention.ACTIVITY_MAX_AGE_MILLIS,
            limit = OperationalRetention.ACTIVITY_PRUNE_BATCH_SIZE,
        )
        val overflow = (rowCount() - OperationalRetention.ACTIVITY_MAX_ROWS).coerceAtLeast(0)
        val remainingBatch = OperationalRetention.ACTIVITY_PRUNE_BATCH_SIZE - removed
        if (overflow > 0 && remainingBatch > 0) {
            removed += pruneOldest(minOf(overflow, remainingBatch))
        }
        return removed
    }
}
