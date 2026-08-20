package net.extrawdw.apps.notisync.data.storage.importer.target.preferences

import java.security.MessageDigest

/** Fixed target ownership used only by the combined legacy Preferences mapper. */
internal enum class StorageAggregate {
    DEVICE_PROFILE,
    ANDROID_NOTIFICATION_POLICY,
    INCOMING_FILTERS,
    IOS_APP_REGISTRY,
    SCREEN,
}

internal class OperationalPreferencesImportDigest private constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    fun copyBytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is OperationalPreferencesImportDigest &&
        MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "OperationalPreferencesImportDigest(<redacted>)"

    companion object {
        const val BYTES = 32
        fun from(bytes: ByteArray): OperationalPreferencesImportDigest {
            require(bytes.size == BYTES) { "preferences import evidence must be 32 bytes" }
            return OperationalPreferencesImportDigest(bytes)
        }
    }
}

internal sealed interface OperationalPreferencesImportCommand {
    val aggregate: StorageAggregate

    data class DeviceProfile(
        val localProfile: LocalProfileImport?,
        val lastSeenPostTime: Long?,
    ) : OperationalPreferencesImportCommand {
        override val aggregate = StorageAggregate.DEVICE_PROFILE
    }

    data class AndroidNotificationPolicy(
        val apps: List<AndroidAppImport>,
        val subscopes: List<AndroidSubscopeImport>,
        val groups: List<AndroidSeenGroupImport>,
        val channels: List<AndroidSeenChannelImport>,
    ) : OperationalPreferencesImportCommand {
        override val aggregate = StorageAggregate.ANDROID_NOTIFICATION_POLICY
    }

    data class IncomingFilters(
        val filters: List<IncomingFilterImport>,
    ) : OperationalPreferencesImportCommand {
        override val aggregate = StorageAggregate.INCOMING_FILTERS
    }

    data class IosApps(
        val allowlistedBundleIds: List<String>,
        val seenApps: List<IosSeenAppImport>,
    ) : OperationalPreferencesImportCommand {
        override val aggregate = StorageAggregate.IOS_APP_REGISTRY
    }

    data class Screen(
        val enabled: Boolean,
        val replayHealth: ImportScreenReplayHealth,
        val quarantineDigest: OperationalPreferencesImportDigest?,
        val quarantinedAt: Long?,
        val authorizedPeerIds: List<String>,
        val replayPairs: List<ScreenReplayPairImport>,
        val codecPreferences: List<ScreenCodecPreferenceImport>,
    ) : OperationalPreferencesImportCommand {
        override val aggregate = StorageAggregate.SCREEN
    }
}

internal data class LocalProfileImport(
    val deviceName: String,
    val deviceNameUpdatedAt: Long,
    val profileFingerprint: String?,
    val profileRevisionAt: Long,
)

internal data class AndroidAppImport(
    val packageName: String,
    val enabled: Boolean,
    val mirrorOngoing: Boolean,
    val updateIntervalSeconds: Int,
    val mirrorOngoingToIos: Boolean,
    val mirrorMediaPlaybackToIos: Boolean,
    val ringForCalls: Boolean,
)

internal enum class ImportAndroidSubscopeKind { CHANNEL, GROUP }

internal data class AndroidSubscopeImport(
    val packageName: String,
    val kind: ImportAndroidSubscopeKind,
    val id: String,
    val enabled: Boolean,
)

internal data class AndroidSeenGroupImport(
    val packageName: String,
    val groupId: String,
    val groupName: String?,
)

internal data class AndroidSeenChannelImport(
    val packageName: String,
    val channelId: String,
    val channelName: String?,
    val groupId: String?,
)

internal enum class ImportNotificationOrigin { ANDROID_LOCAL, IOS_ANCS }

internal data class IncomingFilterRuleImport(
    val origin: ImportNotificationOrigin,
    val appId: String?,
    val channelId: String?,
    val digest: OperationalPreferencesImportDigest,
)

internal data class IncomingFilterImport(
    val requesterClientId: String,
    val canonicalizationVersion: Int,
    val updatedAt: Long,
    val ruleSetDigest: OperationalPreferencesImportDigest,
    val rules: List<IncomingFilterRuleImport>,
)

internal data class IosSeenAppImport(
    val bundleId: String,
    val displayName: String,
    val lastSeenAt: Long,
)

internal enum class ImportScreenReplayHealth { HEALTHY, QUARANTINED }

internal data class ScreenReplayPairImport(
    val sessionDigest: OperationalPreferencesImportDigest,
    val routingTokenDigest: OperationalPreferencesImportDigest,
    val expiresAt: Long,
)

internal enum class ImportScreenCodec { H264, H265, AV1 }

internal data class ScreenCodecPreferenceImport(
    val peerId: String,
    val codec: ImportScreenCodec,
)

internal data class OperationalPreferencesImportPlan(
    val aggregate: StorageAggregate,
    val command: OperationalPreferencesImportCommand?,
    val importedRowCount: Long,
) {
    init {
        require(aggregate in LEGACY_PREFERENCES_AGGREGATES) { "aggregate has no v51 Preferences mapping" }
        require(command == null || command.aggregate == aggregate) { "preferences command has wrong aggregate" }
        require(importedRowCount >= 0) { "preferences plan row count must not be negative" }
        require(command != null || importedRowCount == 0L) { "absent preferences cannot import target rows" }
    }
}

internal data class OperationalPreferencesRebuildPlan(
    val aggregates: List<OperationalPreferencesImportPlan>,
) {
    init {
        require(aggregates.map { it.aggregate }.toSet() == LEGACY_PREFERENCES_AGGREGATES &&
            aggregates.size == LEGACY_PREFERENCES_AGGREGATES.size
        ) { "rebuild plan must contain each v51 Preferences aggregate exactly once" }
    }

    val importedRowCount: Long = aggregates.fold(0L) { total, plan ->
        Math.addExact(total, plan.importedRowCount)
    }
    val absentAggregateCount: Int = aggregates.count { it.command == null }
}

internal enum class OperationalPreferencesFailureDisposition { RETRYABLE, BLOCKED }

internal class OperationalPreferencesImportFailure(
    val disposition: OperationalPreferencesFailureDisposition,
    val errorCode: String,
    cause: Throwable? = null,
) : IllegalStateException("operational preferences import failed: $errorCode", cause) {
    init {
        requireCode(errorCode)
    }
}

private fun requireCode(value: String?) {
    require(value == null || (value.length in 1..128 && value.all { it.isLetterOrDigit() || it in "_.-" })) {
        "invalid preferences import code"
    }
}

internal val LEGACY_PREFERENCES_AGGREGATES: Set<StorageAggregate> = linkedSetOf(
    StorageAggregate.DEVICE_PROFILE,
    StorageAggregate.ANDROID_NOTIFICATION_POLICY,
    StorageAggregate.INCOMING_FILTERS,
    StorageAggregate.IOS_APP_REGISTRY,
    StorageAggregate.SCREEN,
)
