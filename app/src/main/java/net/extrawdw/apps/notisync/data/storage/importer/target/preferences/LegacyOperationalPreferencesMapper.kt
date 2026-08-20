package net.extrawdw.apps.notisync.data.storage.importer.target.preferences

import java.util.Base64
import net.extrawdw.apps.notisync.data.incomingfilter.CanonicalIncomingFilterOrigin
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterCanonicalizer
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterRuleValue
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyAndroidNotificationPreferences
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyDeviceProfilePreferences
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyIncomingFilterPreferences
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyIosAppPreferences
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyNotificationOrigin
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferenceRead
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferenceAggregate
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesReadStatus
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyOperationalPreferencesSnapshot
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyScreenCodec
import net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences.LegacyScreenPreferences

internal data class LegacyDeviceProfileImportDefaults(val defaultDeviceName: String) {
    init {
        require(defaultDeviceName.isNotBlank() && defaultDeviceName.length <= 1_024 &&
            defaultDeviceName.none(Char::isISOControl)
        ) { "legacy default device name is invalid" }
    }
}

/** Legacy-aware mapper; clean Room commands contain no DataStore or source-version type. */
internal class LegacyOperationalPreferencesMapper(
    private val deviceProfileDefaults: LegacyDeviceProfileImportDefaults,
) {
    fun map(
        aggregate: LegacyOperationalPreferenceAggregate,
        read: LegacyOperationalPreferenceRead,
        importStartedAt: Long,
    ): OperationalPreferencesImportPlan {
        require(importStartedAt > 0) { "preferences import start time must be positive" }
        require(read.aggregate == aggregate) { "preferences read has the wrong aggregate" }
        val targetAggregate = aggregate.toStorageAggregate()
        if (read.status == LegacyOperationalPreferencesReadStatus.ABSENT) {
            return OperationalPreferencesImportPlan(
                aggregate = targetAggregate,
                command = null,
                importedRowCount = 0,
            )
        }
        require(read.status == LegacyOperationalPreferencesReadStatus.READY) {
            "recovery-required preferences cannot be mapped"
        }
        val command = when (val values = requireNotNull(read.values)) {
            is LegacyDeviceProfilePreferences -> mapDeviceProfile(values)
            is LegacyAndroidNotificationPreferences -> mapAndroid(values)
            is LegacyIncomingFilterPreferences -> mapIncomingFilters(values)
            is LegacyIosAppPreferences -> mapIos(values)
            is LegacyScreenPreferences -> mapScreen(values, importStartedAt)
        }
        require(command.aggregate == targetAggregate) { "legacy preferences mapped to the wrong aggregate" }
        return OperationalPreferencesImportPlan(
            aggregate = targetAggregate,
            command = command,
            importedRowCount = command.rowCount(),
        )
    }

    fun mapAll(
        snapshot: LegacyOperationalPreferencesSnapshot,
        importStartedAt: Long,
    ): OperationalPreferencesRebuildPlan = OperationalPreferencesRebuildPlan(
        LegacyOperationalPreferenceAggregate.entries.map { aggregate ->
            map(aggregate, snapshot.read(aggregate), importStartedAt)
        },
    )

    private fun LegacyOperationalPreferenceAggregate.toStorageAggregate(): StorageAggregate = when (this) {
        LegacyOperationalPreferenceAggregate.DEVICE_PROFILE -> StorageAggregate.DEVICE_PROFILE
        LegacyOperationalPreferenceAggregate.ANDROID_NOTIFICATION_POLICY ->
            StorageAggregate.ANDROID_NOTIFICATION_POLICY
        LegacyOperationalPreferenceAggregate.INCOMING_FILTERS -> StorageAggregate.INCOMING_FILTERS
        LegacyOperationalPreferenceAggregate.IOS_APP_REGISTRY -> StorageAggregate.IOS_APP_REGISTRY
        LegacyOperationalPreferenceAggregate.SCREEN -> StorageAggregate.SCREEN
    }

    private fun mapDeviceProfile(source: LegacyDeviceProfilePreferences): OperationalPreferencesImportCommand.DeviceProfile {
        val hasProfile = source.deviceName != null || source.deviceNameUpdatedAt != null ||
            source.selfProfileFingerprint != null || source.selfProfileUpdatedAt != null
        val deviceNameUpdatedAt = source.deviceNameUpdatedAt ?: 0L
        return OperationalPreferencesImportCommand.DeviceProfile(
            localProfile = if (hasProfile) {
                LocalProfileImport(
                    deviceName = source.deviceName ?: deviceProfileDefaults.defaultDeviceName,
                    deviceNameUpdatedAt = deviceNameUpdatedAt,
                    profileFingerprint = source.selfProfileFingerprint,
                    profileRevisionAt = source.selfProfileUpdatedAt ?: deviceNameUpdatedAt,
                )
            } else {
                null
            },
            lastSeenPostTime = source.lastSeenPostTime,
        )
    }

    private fun mapAndroid(source: LegacyAndroidNotificationPreferences):
        OperationalPreferencesImportCommand.AndroidNotificationPolicy {
        val packages = (source.enabledPackages + source.appConfigs.keys + source.seenChannels.keys).sorted()
        val apps = packages.map { packageName ->
            val config = source.appConfigs[packageName]
            AndroidAppImport(
                packageName = packageName,
                enabled = packageName in source.enabledPackages,
                mirrorOngoing = config?.mirrorOngoing ?: false,
                updateIntervalSeconds = config?.updateIntervalSec ?: 0,
                mirrorOngoingToIos = config?.mirrorOngoingToIos ?: false,
                mirrorMediaPlaybackToIos = config?.mirrorMediaPlaybackToIos ?: false,
                ringForCalls = config?.ringForCalls ?: true,
            )
        }
        val subscopes = packages.flatMap { packageName ->
            val config = source.appConfigs[packageName] ?: return@flatMap emptyList()
            config.disabledChannelIds.sorted().map { id ->
                AndroidSubscopeImport(packageName, ImportAndroidSubscopeKind.CHANNEL, id, enabled = false)
            } + config.disabledGroupIds.sorted().map { id ->
                AndroidSubscopeImport(packageName, ImportAndroidSubscopeKind.GROUP, id, enabled = false)
            }
        }
        val groupNames = linkedMapOf<Pair<String, String>, String?>()
        val channels = packages.flatMap { packageName ->
            source.seenChannels[packageName].orEmpty()
                .sortedBy { it.channelId }
                .map { channel ->
                    channel.groupId?.let { groupId ->
                        val key = packageName to groupId
                        val knownName = groupNames[key]
                        if (knownName != null && channel.groupName != null && knownName != channel.groupName) {
                            blocked("android_seen_group_name_conflict")
                        }
                        if (key !in groupNames || knownName == null) groupNames[key] = channel.groupName
                    }
                    AndroidSeenChannelImport(
                        packageName,
                        channel.channelId,
                        channel.channelName,
                        channel.groupId,
                    )
                }
        }
        val groups = groupNames.entries.map { (key, name) -> AndroidSeenGroupImport(key.first, key.second, name) }
        return OperationalPreferencesImportCommand.AndroidNotificationPolicy(apps, subscopes, groups, channels)
    }

    private fun mapIncomingFilters(source: LegacyIncomingFilterPreferences):
        OperationalPreferencesImportCommand.IncomingFilters {
        val filters = source.filters.entries.sortedBy { it.key }.map { (requester, filter) ->
            val canonical = IncomingFilterCanonicalizer.canonicalize(
                filter.rules.map { rule ->
                    IncomingFilterRuleValue(
                        origin = when (rule.originPlatform) {
                            LegacyNotificationOrigin.ANDROID_LOCAL -> CanonicalIncomingFilterOrigin.ANDROID_LOCAL
                            LegacyNotificationOrigin.IOS_ANCS -> CanonicalIncomingFilterOrigin.IOS_ANCS
                        },
                        appId = rule.appId,
                        channelId = rule.channelId,
                    )
                },
            )
            IncomingFilterImport(
                requesterClientId = requester,
                canonicalizationVersion = IncomingFilterCanonicalizer.VERSION,
                updatedAt = filter.updatedAt,
                ruleSetDigest = OperationalPreferencesImportDigest.from(canonical.digestCopy()),
                rules = canonical.rules.map { rule ->
                    IncomingFilterRuleImport(
                        origin = when (rule.value.origin) {
                            CanonicalIncomingFilterOrigin.ANDROID_LOCAL -> ImportNotificationOrigin.ANDROID_LOCAL
                            CanonicalIncomingFilterOrigin.IOS_ANCS -> ImportNotificationOrigin.IOS_ANCS
                        },
                        appId = rule.value.appId,
                        channelId = rule.value.channelId,
                        digest = OperationalPreferencesImportDigest.from(rule.digestCopy()),
                    )
                },
            )
        }
        return OperationalPreferencesImportCommand.IncomingFilters(filters)
    }

    private fun mapIos(source: LegacyIosAppPreferences): OperationalPreferencesImportCommand.IosApps {
        val allowlisted = source.enabledBundleIds
            .filterNot(::isExcludedIosBundle)
            .sorted()
        val seen = source.discoveredApps.entries.sortedBy { it.key }.map { (bundleId, app) ->
            IosSeenAppImport(
                bundleId = bundleId,
                displayName = app.displayName,
                lastSeenAt = app.lastSeen,
            )
        }
        return OperationalPreferencesImportCommand.IosApps(allowlisted, seen)
    }

    private fun mapScreen(
        source: LegacyScreenPreferences,
        importStartedAt: Long,
    ): OperationalPreferencesImportCommand.Screen {
        if (source.replayBlocked == true) {
            return OperationalPreferencesImportCommand.Screen(
                enabled = false,
                replayHealth = ImportScreenReplayHealth.QUARANTINED,
                quarantineDigest = OperationalPreferencesImportDigest.from(
                    decodeSha256(requireNotNull(source.replayQuarantineDigest)),
                ),
                quarantinedAt = requireNotNull(source.replayQuarantinedAt),
                authorizedPeerIds = source.authorizedPeerIds.sorted(),
                replayPairs = emptyList(),
                codecPreferences = source.codecPreferences.toTargetCodecs(),
            )
        }
        val activeReplay = source.replayEntries.filter { (_, expiresAt) -> expiresAt > importStartedAt }
        if (activeReplay.size % 2 != 0) blocked("screen_replay_pairing_invalid")
        val pairs = activeReplay.indices.step(2).map { index ->
            val session = activeReplay[index]
            val token = activeReplay[index + 1]
            if (session.second != token.second) blocked("screen_replay_pairing_invalid")
            ScreenReplayPairImport(
                sessionDigest = OperationalPreferencesImportDigest.from(decodeSha256(session.first)),
                routingTokenDigest = OperationalPreferencesImportDigest.from(decodeSha256(token.first)),
                expiresAt = session.second,
            )
        }
        return OperationalPreferencesImportCommand.Screen(
            enabled = source.enabled ?: false,
            replayHealth = ImportScreenReplayHealth.HEALTHY,
            quarantineDigest = null,
            quarantinedAt = null,
            authorizedPeerIds = source.authorizedPeerIds.sorted(),
            replayPairs = pairs,
            codecPreferences = source.codecPreferences.toTargetCodecs(),
        )
    }

    private fun Map<String, LegacyScreenCodec>.toTargetCodecs(): List<ScreenCodecPreferenceImport> =
        entries.sortedBy { it.key }.map { (peer, codec) ->
            ScreenCodecPreferenceImport(
                peer,
                when (codec) {
                    LegacyScreenCodec.H264 -> ImportScreenCodec.H264
                    LegacyScreenCodec.H265 -> ImportScreenCodec.H265
                    LegacyScreenCodec.AV1 -> ImportScreenCodec.AV1
                },
            )
        }

    private fun OperationalPreferencesImportCommand.rowCount(): Long = when (this) {
        is OperationalPreferencesImportCommand.DeviceProfile ->
            listOfNotNull(localProfile, lastSeenPostTime).size.toLong()
        is OperationalPreferencesImportCommand.AndroidNotificationPolicy ->
            (apps.size + subscopes.size + groups.size + channels.size).toLong()
        is OperationalPreferencesImportCommand.IncomingFilters ->
            filters.sumOf { 1L + it.rules.size }
        is OperationalPreferencesImportCommand.IosApps ->
            (allowlistedBundleIds.size + seenApps.size).toLong()
        is OperationalPreferencesImportCommand.Screen ->
            1L + authorizedPeerIds.size + replayPairs.size * 2L + codecPreferences.size
    }

    private fun decodeSha256(value: String): ByteArray = try {
        Base64.getUrlDecoder().decode(value).also {
            if (it.size != OperationalPreferencesImportDigest.BYTES) blocked("screen_digest_decode_failed")
        }
    } catch (_: IllegalArgumentException) {
        blocked("screen_digest_decode_failed")
    }

    private fun isExcludedIosBundle(bundleId: String): Boolean = bundleId in IOS_EXCLUSIONS

    private fun blocked(code: String): Nothing = throw OperationalPreferencesImportFailure(
        OperationalPreferencesFailureDisposition.BLOCKED,
        code,
    )

    private companion object {
        val IOS_EXCLUSIONS = setOf(
            "net.extrawdw.apps.notisync",
            "net.extrawdw.apps.notisync.notificationservice",
        )
    }
}
