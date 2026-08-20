package net.extrawdw.apps.notisync.data.storage.importer.legacy.preferences

import kotlinx.serialization.Serializable

/** Exact v51 keys owned by the Operational Preferences cutover. */
internal object LegacyOperationalPreferencesContract {
    const val DEVICE_NAME = "device_name"
    const val DEVICE_NAME_UPDATED_AT = "device_name_updated_at"
    const val SELF_PROFILE_FINGERPRINT = "self_profile_fingerprint"
    const val SELF_PROFILE_UPDATED_AT = "self_profile_updated_at"
    const val LAST_SEEN_POST_TIME = "last_seen_post_time"
    const val CORE_OWNED_TRUST_CLEANUP_COMPLETED = "unverified_device_cleanup_v1_completed"

    const val ANDROID_ENABLED_PACKAGES = "enabled_packages_json"
    const val ANDROID_APP_CONFIG = "per_app_config_json"
    const val ANDROID_SEEN_CHANNELS = "per_app_seen_channels_json"

    const val INCOMING_FILTERS = "received_notification_filters_json"

    const val IOS_ENABLED_BUNDLES = "ancs_enabled_bundles_json"
    const val IOS_DISCOVERED_APPS = "ancs_discovered_apps_json"

    const val SCREEN_AUTHORIZED_PEERS = "screen_mirror_authorized_peer_ids"
    const val SCREEN_REPLAY = "screen_mirror_request_replay_v1"
    const val SCREEN_REPLAY_BLOCKED = "screen_mirror_request_replay_v1_blocked"
    const val SCREEN_REPLAY_QUARANTINE_DIGEST = "screen_mirror_request_replay_v1_quarantine_digest"
    const val SCREEN_REPLAY_QUARANTINED_AT = "screen_mirror_request_replay_v1_quarantined_at"
    const val SCREEN_ENABLED = "screen_mirroring_enabled"
    const val SCREEN_CODEC_PREFERENCES = "screen_mirror_codec_preferences_v1"

    /** These values remain in the one application-scoped DataStore and are deliberately never fingerprinted. */
    val retainedScalarKeyNames: Set<String> = setOf(
        "batch_low_priority",
        "advanced_diagnostics",
        "analytics_enabled",
        "onboarding_completed",
        "call_ringer_enabled",
        "lock_screen_public_identity",
        "ancs_bridge_enabled",
        "ancs_local_display",
        "ancs_mesh_mirror",
        "ancs_media_mirror",
        "screen_viewer_toolbar_edge_v1",
        "screen_viewer_toolbar_controls_v1",
        "screen_viewer_toolbar_order_v1",
    )

    /** Catalogued here only to prove the Operational importer never reads or fingerprints Core-owned state. */
    val coreOwnedKeyNames: Set<String> = setOf(CORE_OWNED_TRUST_CLEANUP_COMPLETED)
}

internal enum class LegacyOperationalPreferenceAggregate {
    DEVICE_PROFILE,
    ANDROID_NOTIFICATION_POLICY,
    INCOMING_FILTERS,
    IOS_APP_REGISTRY,
    SCREEN,
}

internal enum class LegacyOperationalPreferenceField(val keyName: String) {
    DEVICE_NAME(LegacyOperationalPreferencesContract.DEVICE_NAME),
    DEVICE_NAME_UPDATED_AT(LegacyOperationalPreferencesContract.DEVICE_NAME_UPDATED_AT),
    SELF_PROFILE_FINGERPRINT(LegacyOperationalPreferencesContract.SELF_PROFILE_FINGERPRINT),
    SELF_PROFILE_UPDATED_AT(LegacyOperationalPreferencesContract.SELF_PROFILE_UPDATED_AT),
    LAST_SEEN_POST_TIME(LegacyOperationalPreferencesContract.LAST_SEEN_POST_TIME),
    ANDROID_ENABLED_PACKAGES(LegacyOperationalPreferencesContract.ANDROID_ENABLED_PACKAGES),
    ANDROID_APP_CONFIG(LegacyOperationalPreferencesContract.ANDROID_APP_CONFIG),
    ANDROID_SEEN_CHANNELS(LegacyOperationalPreferencesContract.ANDROID_SEEN_CHANNELS),
    INCOMING_FILTERS(LegacyOperationalPreferencesContract.INCOMING_FILTERS),
    IOS_ENABLED_BUNDLES(LegacyOperationalPreferencesContract.IOS_ENABLED_BUNDLES),
    IOS_DISCOVERED_APPS(LegacyOperationalPreferencesContract.IOS_DISCOVERED_APPS),
    SCREEN_AUTHORIZED_PEERS(LegacyOperationalPreferencesContract.SCREEN_AUTHORIZED_PEERS),
    SCREEN_REPLAY(LegacyOperationalPreferencesContract.SCREEN_REPLAY),
    SCREEN_REPLAY_BLOCKED(LegacyOperationalPreferencesContract.SCREEN_REPLAY_BLOCKED),
    SCREEN_REPLAY_QUARANTINE_DIGEST(LegacyOperationalPreferencesContract.SCREEN_REPLAY_QUARANTINE_DIGEST),
    SCREEN_REPLAY_QUARANTINED_AT(LegacyOperationalPreferencesContract.SCREEN_REPLAY_QUARANTINED_AT),
    SCREEN_ENABLED(LegacyOperationalPreferencesContract.SCREEN_ENABLED),
    SCREEN_CODEC_PREFERENCES(LegacyOperationalPreferencesContract.SCREEN_CODEC_PREFERENCES),
}

internal enum class LegacyOperationalPreferencesReadStatus {
    ABSENT,
    READY,
    RECOVERY_REQUIRED,
}

internal enum class LegacyOperationalPreferencesIssueKind {
    WRONG_VALUE_TYPE,
    VALUE_TOO_LARGE,
    MALFORMED_JSON,
    TOO_MANY_ROWS,
    INVALID_IDENTIFIER,
    INVALID_DISPLAY_VALUE,
    INVALID_TIMESTAMP,
    INVALID_POLICY_VALUE,
    INVALID_RELATIONSHIP,
    DUPLICATE_ROW,
    UNKNOWN_TOKEN,
    MALFORMED_SECURITY_STATE,
}

/** Value-free and safe for a journal/readiness diagnostic. */
internal data class LegacyOperationalPreferencesIssue(
    val kind: LegacyOperationalPreferencesIssueKind,
    val field: LegacyOperationalPreferenceField,
)

internal sealed interface LegacyOperationalPreferenceValues {
    val aggregate: LegacyOperationalPreferenceAggregate
}

internal data class LegacyDeviceProfilePreferences(
    val deviceName: String?,
    val deviceNameUpdatedAt: Long?,
    val selfProfileFingerprint: String?,
    val selfProfileUpdatedAt: Long?,
    val lastSeenPostTime: Long?,
) : LegacyOperationalPreferenceValues {
    override val aggregate = LegacyOperationalPreferenceAggregate.DEVICE_PROFILE
}

internal data class LegacyAndroidNotificationPreferences(
    val enabledPackages: Set<String>,
    val appConfigs: Map<String, LegacyPerAppConfig>,
    val seenChannels: Map<String, List<LegacySeenChannel>>,
) : LegacyOperationalPreferenceValues {
    override val aggregate = LegacyOperationalPreferenceAggregate.ANDROID_NOTIFICATION_POLICY
}

@Serializable
internal data class LegacyPerAppConfig(
    val mirrorOngoing: Boolean = false,
    val updateIntervalSec: Int = 0,
    val mirrorOngoingToIos: Boolean = false,
    val mirrorMediaPlaybackToIos: Boolean = false,
    val disabledChannelIds: Set<String> = emptySet(),
    val disabledGroupIds: Set<String> = emptySet(),
    val ringForCalls: Boolean = true,
)

@Serializable
internal data class LegacySeenChannel(
    val channelId: String,
    val channelName: String? = null,
    val groupId: String? = null,
    val groupName: String? = null,
)

internal data class LegacyIncomingFilterPreferences(
    val filters: Map<String, LegacyFilterSync>,
) : LegacyOperationalPreferenceValues {
    override val aggregate = LegacyOperationalPreferenceAggregate.INCOMING_FILTERS
}

@Serializable
internal data class LegacyFilterSync(
    val rules: List<LegacyNotificationFilterRule>,
    val updatedAt: Long,
)

@Serializable
internal data class LegacyNotificationFilterRule(
    val originPlatform: LegacyNotificationOrigin,
    val appId: String? = null,
    val channelId: String? = null,
)

@Serializable
internal enum class LegacyNotificationOrigin {
    ANDROID_LOCAL,
    IOS_ANCS,
}

internal data class LegacyIosAppPreferences(
    val enabledBundleIds: Set<String>,
    val discoveredApps: Map<String, LegacyIosApp>,
) : LegacyOperationalPreferenceValues {
    override val aggregate = LegacyOperationalPreferenceAggregate.IOS_APP_REGISTRY
}

@Serializable
internal data class LegacyIosApp(
    val bundleId: String,
    val displayName: String,
    val lastSeen: Long,
)

internal data class LegacyScreenPreferences(
    val enabled: Boolean?,
    val authorizedPeerIds: Set<String>,
    /** Ordered physical v51 map entries; pairing semantics are validated by the target mapper. */
    val replayEntries: List<Pair<String, Long>>,
    val replayBlocked: Boolean?,
    val replayQuarantineDigest: String?,
    val replayQuarantinedAt: Long?,
    val codecPreferences: Map<String, LegacyScreenCodec>,
) : LegacyOperationalPreferenceValues {
    override val aggregate = LegacyOperationalPreferenceAggregate.SCREEN
}

@Serializable
internal enum class LegacyScreenCodec {
    H264,
    H265,
    AV1,
}

internal class LegacyOperationalPreferenceRead(
    val aggregate: LegacyOperationalPreferenceAggregate,
    val status: LegacyOperationalPreferencesReadStatus,
    val presentKeyCount: Int,
    val values: LegacyOperationalPreferenceValues?,
    issues: Set<LegacyOperationalPreferencesIssue>,
) {
    val issues: Set<LegacyOperationalPreferencesIssue> = issues.toSet()

    init {
        require(presentKeyCount >= 0) { "present preference-key count must not be negative" }
        require((status == LegacyOperationalPreferencesReadStatus.READY) == (values != null)) {
            "only a ready preferences read may expose values"
        }
        require(values == null || values.aggregate == aggregate) { "preferences values have the wrong aggregate" }
        require((status == LegacyOperationalPreferencesReadStatus.RECOVERY_REQUIRED) == this.issues.isNotEmpty()) {
            "recovery-required preferences need value-free issues"
        }
        require(status != LegacyOperationalPreferencesReadStatus.ABSENT || presentKeyCount == 0) {
            "absent preferences cannot contain owned keys"
        }
    }

    override fun toString(): String =
        "LegacyOperationalPreferenceRead(aggregate=$aggregate,status=$status,presentKeyCount=$presentKeyCount," +
            "issues=$issues,values=<redacted>)"
}

internal class LegacyOperationalPreferencesSnapshot(
    reads: Map<LegacyOperationalPreferenceAggregate, LegacyOperationalPreferenceRead>,
) {
    private val readsByAggregate = reads.toMap()

    init {
        require(readsByAggregate.all { (aggregate, read) -> aggregate == read.aggregate }) {
            "preferences snapshot map is inconsistent"
        }
    }

    val aggregates: Set<LegacyOperationalPreferenceAggregate> get() = readsByAggregate.keys

    fun read(aggregate: LegacyOperationalPreferenceAggregate): LegacyOperationalPreferenceRead =
        requireNotNull(readsByAggregate[aggregate]) { "aggregate was not requested from this snapshot" }

    override fun toString(): String = "LegacyOperationalPreferencesSnapshot(aggregates=$aggregates,values=<redacted>)"
}
