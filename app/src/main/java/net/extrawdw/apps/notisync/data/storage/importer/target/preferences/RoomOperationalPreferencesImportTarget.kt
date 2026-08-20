package net.extrawdw.apps.notisync.data.storage.importer.target.preferences

import androidx.room3.useReaderConnection
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import net.extrawdw.apps.notisync.data.storage.operational.AndroidAppPolicyEntity
import net.extrawdw.apps.notisync.data.storage.operational.AndroidPolicyScope
import net.extrawdw.apps.notisync.data.storage.operational.AndroidSeenChannelEntity
import net.extrawdw.apps.notisync.data.storage.operational.AndroidSeenGroupEntity
import net.extrawdw.apps.notisync.data.storage.operational.AndroidSubscopePolicyEntity
import net.extrawdw.apps.notisync.data.storage.operational.IncomingFilterEntity
import net.extrawdw.apps.notisync.data.storage.operational.IncomingFilterReplaceResult
import net.extrawdw.apps.notisync.data.storage.operational.IncomingFilterRuleEntity
import net.extrawdw.apps.notisync.data.storage.operational.IosSeenAppEntity
import net.extrawdw.apps.notisync.data.storage.operational.LocalProfileEntity
import net.extrawdw.apps.notisync.data.storage.operational.NotificationOriginPlatform
import net.extrawdw.apps.notisync.data.storage.operational.NotificationCaptureStateEntity
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.ScreenAuthorizedPeerEntity
import net.extrawdw.apps.notisync.data.storage.operational.ScreenCodecPreferenceEntity
import net.extrawdw.apps.notisync.data.storage.operational.ScreenCodecToken
import net.extrawdw.apps.notisync.data.storage.operational.ScreenReplayConsumeResult
import net.extrawdw.apps.notisync.data.storage.operational.ScreenReplayHealth
import net.extrawdw.apps.notisync.data.storage.operational.ScreenReplayKind
import net.extrawdw.apps.notisync.data.storage.operational.ScreenSecurityStateEntity

/**
 * In-transaction Room projection helper. The rebuild target owns the surrounding transaction and
 * continuity fence; no legacy DataStore DTO crosses into this file.
 */
internal class RoomOperationalPreferencesImportTarget(
    private val database: OperationalDatabase,
) {
    suspend fun applyAll(plan: OperationalPreferencesRebuildPlan, importStartedAt: Long) {
        require(importStartedAt > 0) { "preferences import start time must be positive" }
        plan.aggregates.forEach { aggregatePlan ->
            currentCoroutineContext().ensureActive()
            val command = aggregatePlan.command
            if (command == null) {
                if (!aggregateIsPristine(aggregatePlan.aggregate)) {
                    conflict("preferences_absent_target_not_pristine")
                }
            } else {
                if (!aggregateIsPristine(aggregatePlan.aggregate)) {
                    conflict("preferences_target_not_pristine")
                }
                apply(command, importStartedAt)
                if (!commandExactlyMatches(command, importStartedAt)) {
                    conflict("preferences_target_projection_mismatch")
                }
            }
        }
    }

    suspend fun verifyAll(plan: OperationalPreferencesRebuildPlan, importStartedAt: Long): Boolean =
        plan.aggregates.all { aggregatePlan ->
            aggregatePlan.command?.let { commandExactlyMatches(it, importStartedAt) }
                ?: aggregateIsPristine(aggregatePlan.aggregate)
        }

    private suspend fun commandExactlyMatches(
        command: OperationalPreferencesImportCommand,
        importStartedAt: Long,
    ): Boolean = when (command) {
        is OperationalPreferencesImportCommand.DeviceProfile -> verifyDeviceProfile(command, importStartedAt)
        is OperationalPreferencesImportCommand.AndroidNotificationPolicy -> verifyAndroid(command, importStartedAt)
        is OperationalPreferencesImportCommand.IncomingFilters -> verifyFilters(command, importStartedAt)
        is OperationalPreferencesImportCommand.IosApps -> verifyIos(command)
        is OperationalPreferencesImportCommand.Screen -> verifyScreen(command, importStartedAt)
    }

    private suspend fun aggregateIsPristine(aggregate: StorageAggregate): Boolean =
        when (aggregate) {
            StorageAggregate.DEVICE_PROFILE ->
                database.profileDao().observeLocalProfile().first() == null &&
                    database.profileDao().observeNotificationCaptureState().first() == null
            StorageAggregate.ANDROID_NOTIFICATION_POLICY ->
                database.notificationPolicyDao().observeApps().first().isEmpty()
            StorageAggregate.INCOMING_FILTERS ->
                database.incomingFilterDao().observeHeaders().first().isEmpty()
            StorageAggregate.IOS_APP_REGISTRY ->
                database.iosAppDao().observeAllowlist().first().isEmpty() &&
                    database.iosAppDao().observeSeen().first().isEmpty()
            StorageAggregate.SCREEN -> {
                val authorizations = database.screenDao().readAuthorizations()
                authorizations.securityState == null && authorizations.peers.isEmpty() &&
                    database.screenDao().observeCodecPreferences().first().isEmpty() &&
                    readScreenReplayRows().isEmpty()
            }
        }

    private suspend fun apply(command: OperationalPreferencesImportCommand, importStartedAt: Long) {
        currentCoroutineContext().ensureActive()
        when (command) {
            is OperationalPreferencesImportCommand.DeviceProfile -> applyDeviceProfile(command, importStartedAt)
            is OperationalPreferencesImportCommand.AndroidNotificationPolicy -> applyAndroid(command, importStartedAt)
            is OperationalPreferencesImportCommand.IncomingFilters -> applyFilters(command, importStartedAt)
            is OperationalPreferencesImportCommand.IosApps -> applyIos(command)
            is OperationalPreferencesImportCommand.Screen -> applyScreen(command, importStartedAt)
        }
    }

    private suspend fun applyDeviceProfile(
        command: OperationalPreferencesImportCommand.DeviceProfile,
        importStartedAt: Long,
    ) {
        command.localProfile?.let { profile ->
            val entity = profile.toEntity(importStartedAt)
            val current = database.profileDao().observeLocalProfile().first()
            if (current != null && current != entity) conflict("device_profile_target_exists")
            if (current == null) database.profileDao().replaceLocalProfile(entity)
        }
        command.lastSeenPostTime?.let { watermark ->
            val current = database.profileDao().observeNotificationCaptureState().first()?.lastSeenPostTime
            if (current != null && current != 0L && current != watermark) conflict("device_delivery_target_exists")
            if (
                database.profileDao()
                    .advanceNotificationCaptureLastSeenPostTime(watermark, importStartedAt) != watermark
            ) {
                conflict("device_delivery_target_conflict")
            }
        }
    }

    private suspend fun applyAndroid(
        command: OperationalPreferencesImportCommand.AndroidNotificationPolicy,
        importStartedAt: Long,
    ) {
        val desiredApps = command.apps.map { it.toEntity(importStartedAt) }
        val existingApps = database.notificationPolicyDao().observeApps().first()
        if (existingApps.isNotEmpty()) {
            if (!androidExactlyMatches(command, importStartedAt)) conflict("android_policy_target_exists")
            return
        }
        desiredApps.forEach { database.notificationPolicyDao().upsertApp(it) }
        command.subscopes.forEach { scope ->
            database.notificationPolicyDao().upsertSubscope(scope.toEntity(importStartedAt))
        }
        val groups = command.groups.associateBy { it.packageName to it.groupId }
        command.channels.forEach { channel ->
            database.notificationPolicyDao().recordSeenChannel(
                group = channel.groupId?.let { groupId ->
                    requireNotNull(groups[channel.packageName to groupId]).toEntity(importStartedAt)
                },
                channel = channel.toEntity(importStartedAt),
            )
        }
    }

    private suspend fun applyFilters(
        command: OperationalPreferencesImportCommand.IncomingFilters,
        importStartedAt: Long,
    ) {
        if (database.incomingFilterDao().observeHeaders().first().isNotEmpty()) {
            if (!filtersExactlyMatch(command, importStartedAt)) conflict("incoming_filters_target_exists")
            return
        }
        command.filters.forEach { filter ->
            val result = database.incomingFilterDao().replace(
                header = filter.toEntity(importStartedAt),
                rules = filter.rules.mapIndexed { position, rule -> rule.toEntity(filter.requesterClientId, position) },
            )
            if (result != IncomingFilterReplaceResult.INSERTED) conflict("incoming_filter_insert_conflict")
        }
    }

    private suspend fun applyIos(command: OperationalPreferencesImportCommand.IosApps) {
        if (database.iosAppDao().observeAllowlist().first().isNotEmpty() ||
            database.iosAppDao().observeSeen().first().isNotEmpty()
        ) {
            if (!verifyIos(command)) conflict("ios_registry_target_exists")
            return
        }
        command.allowlistedBundleIds.forEach { database.iosAppDao().putAllowlisted(it) }
        command.seenApps.forEach { app ->
            database.iosAppDao().putSeen(IosSeenAppEntity(app.bundleId, app.displayName, app.lastSeenAt))
        }
    }

    private suspend fun applyScreen(
        command: OperationalPreferencesImportCommand.Screen,
        importStartedAt: Long,
    ) {
        val existing = database.screenDao().readAuthorizations()
        val existingCodecs = database.screenDao().observeCodecPreferences().first()
        if (existing.securityState != null || existing.peers.isNotEmpty() || existingCodecs.isNotEmpty()) {
            conflict("screen_target_exists")
        }
        val peers = command.authorizedPeerIds.map { peerId ->
            ScreenAuthorizedPeerEntity(peerId, grantedAt = importStartedAt, updatedAt = importStartedAt)
        }
        val finalState = command.toSecurityState(importStartedAt)
        val temporaryState = if (
            finalState.replayHealth == ScreenReplayHealth.HEALTHY &&
            !finalState.enabled && command.replayPairs.isNotEmpty()
        ) {
            finalState.copy(enabled = true)
        } else {
            finalState
        }
        database.screenDao().replaceAuthorizations(peers, temporaryState)
        command.codecPreferences.forEach { preference ->
            database.screenDao().putCodecPreference(preference.toEntity(importStartedAt))
        }
        command.replayPairs.forEach { pair ->
            val result = database.screenDao().consumeReplay(
                pair.sessionDigest.copyBytes(),
                pair.routingTokenDigest.copyBytes(),
                pair.expiresAt,
                importStartedAt,
            )
            if (result != ScreenReplayConsumeResult.CONSUMED) conflict("screen_replay_import_conflict")
        }
        if (temporaryState != finalState) database.screenDao().replaceSecurityState(finalState)
    }

    private suspend fun verifyDeviceProfile(
        command: OperationalPreferencesImportCommand.DeviceProfile,
        importStartedAt: Long,
    ): Boolean {
        val expectedProfile = command.localProfile?.toEntity(importStartedAt)
        val expectedCaptureState = command.lastSeenPostTime?.let { watermark ->
            NotificationCaptureStateEntity(lastSeenPostTime = watermark, updatedAt = importStartedAt)
        }
        return database.profileDao().observeLocalProfile().first() == expectedProfile &&
            database.profileDao().observeNotificationCaptureState().first() == expectedCaptureState
    }

    private suspend fun verifyAndroid(
        command: OperationalPreferencesImportCommand.AndroidNotificationPolicy,
        importStartedAt: Long,
    ): Boolean = androidExactlyMatches(command, importStartedAt)

    private suspend fun androidExactlyMatches(
        command: OperationalPreferencesImportCommand.AndroidNotificationPolicy,
        importStartedAt: Long,
    ): Boolean {
        if (database.notificationPolicyDao().observeApps().first() !=
            command.apps.map { it.toEntity(importStartedAt) }
        ) return false
        for (app in command.apps) {
            for (kind in ImportAndroidSubscopeKind.entries) {
                val expected = command.subscopes.filter { it.packageName == app.packageName && it.kind == kind }
                    .map { it.toEntity(importStartedAt) }
                val actual = database.notificationPolicyDao().observeSubscopes(
                    app.packageName,
                    kind.toStorage(),
                ).first()
                if (actual != expected) return false
            }
            val expectedGroups = command.groups.filter { it.packageName == app.packageName }
                .sortedWith(compareByDescending<AndroidSeenGroupImport> { importStartedAt }.thenBy { it.groupId })
                .map { it.toEntity(importStartedAt) }
            if (database.notificationPolicyDao().observeSeenGroups(app.packageName).first() != expectedGroups) {
                return false
            }
            val expectedChannels = command.channels.filter { it.packageName == app.packageName }
                .sortedWith(compareByDescending<AndroidSeenChannelImport> { importStartedAt }.thenBy { it.channelId })
                .map { it.toEntity(importStartedAt) }
            if (database.notificationPolicyDao().observeSeenChannels(app.packageName).first() != expectedChannels) {
                return false
            }
        }
        return true
    }

    private suspend fun verifyFilters(
        command: OperationalPreferencesImportCommand.IncomingFilters,
        importStartedAt: Long,
    ): Boolean = filtersExactlyMatch(command, importStartedAt)

    private suspend fun filtersExactlyMatch(
        command: OperationalPreferencesImportCommand.IncomingFilters,
        importStartedAt: Long,
    ): Boolean {
        val expectedHeaders = command.filters.map { it.toEntity(importStartedAt) }
        if (!database.incomingFilterDao().observeHeaders().first().contentEqualsByValue(expectedHeaders)) return false
        for (filter in command.filters) {
            val expectedRules = filter.rules.mapIndexed { position, rule ->
                rule.toEntity(filter.requesterClientId, position)
            }
            if (!database.incomingFilterDao().observeRules(filter.requesterClientId).first()
                    .contentEqualsRules(expectedRules)
            ) return false
        }
        return true
    }

    private suspend fun verifyIos(command: OperationalPreferencesImportCommand.IosApps): Boolean =
        database.iosAppDao().observeAllowlist().first().map { it.bundleId } == command.allowlistedBundleIds &&
            database.iosAppDao().observeSeen().first() == command.seenApps
                .sortedWith(compareByDescending<IosSeenAppImport> { it.lastSeenAt }.thenBy { it.bundleId })
                .map { IosSeenAppEntity(it.bundleId, it.displayName, it.lastSeenAt) }

    private suspend fun verifyScreen(
        command: OperationalPreferencesImportCommand.Screen,
        importStartedAt: Long,
    ): Boolean {
        val aggregate = database.screenDao().readAuthorizations()
        if (!aggregate.securityState.contentEqualsState(command.toSecurityState(importStartedAt))) return false
        if (aggregate.peers != command.authorizedPeerIds.map {
                ScreenAuthorizedPeerEntity(it, importStartedAt, importStartedAt)
            }
        ) return false
        if (database.screenDao().observeCodecPreferences().first() != command.codecPreferences.map {
                it.toEntity(importStartedAt)
            }
        ) return false
        val expectedReplay = command.replayPairs.flatMap { pair ->
            listOf(
                ScreenReplayRow(pair.sessionDigest.copyBytes(), ScreenReplayKind.SESSION, pair.expiresAt, importStartedAt),
                ScreenReplayRow(
                    pair.routingTokenDigest.copyBytes(),
                    ScreenReplayKind.ROUTING_TOKEN,
                    pair.expiresAt,
                    importStartedAt,
                ),
            )
        }.sortedWith { left, right -> compareUnsigned(left.digest, right.digest) }
        return readScreenReplayRows().contentEqualsReplay(expectedReplay)
    }

    private fun ScreenSecurityStateEntity?.contentEqualsState(other: ScreenSecurityStateEntity): Boolean =
        this != null && singletonId == other.singletonId && enabled == other.enabled &&
            replayHealth == other.replayHealth && quarantineDigest.contentEqualsNullable(other.quarantineDigest) &&
            quarantinedAt == other.quarantinedAt && authorizationRevision == other.authorizationRevision &&
            updatedAt == other.updatedAt

    private suspend fun readScreenReplayRows(): List<ScreenReplayRow> = database.useReaderConnection { connection ->
        connection.usePrepared(
            "SELECT digest, kind, expires_at, consumed_at FROM screen_replay_token ORDER BY digest",
        ) { statement ->
            buildList {
                while (statement.step()) {
                    val digest = requireNotNull(statement.getBlob(0)).copyOf()
                    val kind = when (statement.getText(1)) {
                        "session" -> ScreenReplayKind.SESSION
                        "routing_token" -> ScreenReplayKind.ROUTING_TOKEN
                        else -> conflict("screen_replay_kind_unknown")
                    }
                    add(ScreenReplayRow(digest, kind, statement.getLong(2), statement.getLong(3)))
                }
            }
        }
    }

    private fun LocalProfileImport.toEntity(importStartedAt: Long) = LocalProfileEntity(
        deviceName = deviceName,
        deviceNameUpdatedAt = deviceNameUpdatedAt,
        profileFingerprint = profileFingerprint,
        profileRevisionAt = profileRevisionAt,
        updatedAt = importStartedAt,
    )

    private fun AndroidAppImport.toEntity(importStartedAt: Long) = AndroidAppPolicyEntity(
        packageName,
        enabled,
        mirrorOngoing,
        updateIntervalSeconds,
        mirrorOngoingToIos,
        mirrorMediaPlaybackToIos,
        ringForCalls,
        lastSeenAt = null,
        updatedAt = importStartedAt,
    )

    private fun AndroidSubscopeImport.toEntity(importStartedAt: Long) = AndroidSubscopePolicyEntity(
        packageName,
        kind.toStorage(),
        id,
        enabled,
        importStartedAt,
    )

    private fun ImportAndroidSubscopeKind.toStorage(): AndroidPolicyScope = when (this) {
        ImportAndroidSubscopeKind.CHANNEL -> AndroidPolicyScope.CHANNEL
        ImportAndroidSubscopeKind.GROUP -> AndroidPolicyScope.GROUP
    }

    private fun AndroidSeenGroupImport.toEntity(importStartedAt: Long) = AndroidSeenGroupEntity(
        packageName,
        groupId,
        groupName,
        importStartedAt,
        importStartedAt,
    )

    private fun AndroidSeenChannelImport.toEntity(importStartedAt: Long) = AndroidSeenChannelEntity(
        packageName,
        channelId,
        channelName,
        groupId,
        importStartedAt,
        importStartedAt,
    )

    private fun IncomingFilterImport.toEntity(importStartedAt: Long) = IncomingFilterEntity(
        requesterClientId,
        canonicalizationVersion,
        updatedAt,
        receivedAt = importStartedAt,
        ruleSetDigest = ruleSetDigest.copyBytes(),
    )

    private fun IncomingFilterRuleImport.toEntity(
        requesterClientId: String,
        position: Int,
    ) = IncomingFilterRuleEntity(
        requesterClientId,
        digest.copyBytes(),
        position,
        when (origin) {
            ImportNotificationOrigin.ANDROID_LOCAL -> NotificationOriginPlatform.ANDROID_LOCAL
            ImportNotificationOrigin.IOS_ANCS -> NotificationOriginPlatform.IOS_ANCS
        },
        appId,
        channelId,
    )

    private fun OperationalPreferencesImportCommand.Screen.toSecurityState(
        importStartedAt: Long,
    ) = ScreenSecurityStateEntity(
        enabled = enabled,
        replayHealth = when (replayHealth) {
            ImportScreenReplayHealth.HEALTHY -> ScreenReplayHealth.HEALTHY
            ImportScreenReplayHealth.QUARANTINED -> ScreenReplayHealth.QUARANTINED
        },
        quarantineDigest = quarantineDigest?.copyBytes(),
        quarantinedAt = quarantinedAt,
        authorizationRevision = importStartedAt,
        updatedAt = importStartedAt,
    )

    private fun ScreenCodecPreferenceImport.toEntity(importStartedAt: Long) = ScreenCodecPreferenceEntity(
        peerId,
        when (codec) {
            ImportScreenCodec.H264 -> ScreenCodecToken.H264
            ImportScreenCodec.H265 -> ScreenCodecToken.H265
            ImportScreenCodec.AV1 -> ScreenCodecToken.AV1
        },
        importStartedAt,
    )

    private fun List<IncomingFilterEntity>.contentEqualsByValue(other: List<IncomingFilterEntity>): Boolean =
        size == other.size && indices.all { index ->
            this[index].requesterClientId == other[index].requesterClientId &&
                this[index].canonicalizationVersion == other[index].canonicalizationVersion &&
                this[index].updatedAt == other[index].updatedAt && this[index].receivedAt == other[index].receivedAt &&
                MessageDigest.isEqual(this[index].ruleSetDigest, other[index].ruleSetDigest)
        }

    private fun List<IncomingFilterRuleEntity>.contentEqualsRules(other: List<IncomingFilterRuleEntity>): Boolean =
        size == other.size && indices.all { index ->
            this[index].requesterClientId == other[index].requesterClientId &&
                MessageDigest.isEqual(this[index].ruleDigest, other[index].ruleDigest) &&
                this[index].position == other[index].position &&
                this[index].originPlatform == other[index].originPlatform &&
                this[index].appId == other[index].appId && this[index].channelId == other[index].channelId
        }

    private fun List<ScreenReplayRow>.contentEqualsReplay(other: List<ScreenReplayRow>): Boolean =
        size == other.size && indices.all { index ->
            MessageDigest.isEqual(this[index].digest, other[index].digest) &&
                this[index].kind == other[index].kind && this[index].expiresAt == other[index].expiresAt &&
                this[index].consumedAt == other[index].consumedAt
        }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        if (this == null || other == null) this == null && other == null else MessageDigest.isEqual(this, other)

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private fun conflict(code: String): Nothing = throw OperationalPreferencesImportFailure(
        OperationalPreferencesFailureDisposition.BLOCKED,
        code,
    )

    private data class ScreenReplayRow(
        val digest: ByteArray,
        val kind: ScreenReplayKind,
        val expiresAt: Long,
        val consumedAt: Long,
    )

}
