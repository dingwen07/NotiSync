package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

internal object OperationalSingletons {
    const val ID = 1
}

@Entity(tableName = "maintenance_state")
internal data class MaintenanceStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = OperationalSingletons.ID,
    /** Monotonic generation coordinated with Core continuity state. */
    @ColumnInfo(name = "operational_generation")
    val operationalGeneration: Long,
    /** Opaque identity of this physical OperationalDatabase incarnation. */
    @ColumnInfo(name = "storage_incarnation_id")
    val storageIncarnationId: String,
    @ColumnInfo(name = "post_cutover_write_at")
    val postCutoverWriteAt: Long?,
    @ColumnInfo(name = "last_integrity_check_at")
    val lastIntegrityCheckAt: Long?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(tableName = "local_profile")
internal data class LocalProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = OperationalSingletons.ID,
    @ColumnInfo(name = "device_name")
    val deviceName: String,
    @ColumnInfo(name = "device_name_updated_at")
    val deviceNameUpdatedAt: Long,
    @ColumnInfo(name = "profile_fingerprint")
    val profileFingerprint: String?,
    @ColumnInfo(name = "profile_revision_at")
    val profileRevisionAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(tableName = "notification_capture_state")
internal data class NotificationCaptureStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = OperationalSingletons.ID,
    @ColumnInfo(name = "last_seen_post_time")
    val lastSeenPostTime: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "android_app_policy",
    indices = [
        Index(value = ["enabled", "last_seen_at"], name = "index_android_app_policy_enabled_last_seen_at"),
    ],
)
internal data class AndroidAppPolicyEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "mirror_ongoing")
    val mirrorOngoing: Boolean,
    @ColumnInfo(name = "update_interval_seconds")
    val updateIntervalSeconds: Int,
    @ColumnInfo(name = "mirror_ongoing_to_ios")
    val mirrorOngoingToIos: Boolean,
    @ColumnInfo(name = "mirror_media_playback_to_ios")
    val mirrorMediaPlaybackToIos: Boolean,
    @ColumnInfo(name = "ring_for_calls")
    val ringForCalls: Boolean,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "android_subscope_policy",
    primaryKeys = ["package_name", "scope_kind", "scope_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidAppPolicyEntity::class,
            parentColumns = ["package_name"],
            childColumns = ["package_name"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class AndroidSubscopePolicyEntity(
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "scope_kind")
    val scopeKind: AndroidPolicyScope,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "android_seen_group",
    primaryKeys = ["package_name", "group_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidAppPolicyEntity::class,
            parentColumns = ["package_name"],
            childColumns = ["package_name"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["package_name", "last_seen_at"], name = "index_android_seen_group_package_last_seen_at"),
    ],
)
internal data class AndroidSeenGroupEntity(
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "group_id")
    val groupId: String,
    @ColumnInfo(name = "group_name")
    val groupName: String?,
    @ColumnInfo(name = "first_seen_at")
    val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,
)

@Entity(
    tableName = "android_seen_channel",
    primaryKeys = ["package_name", "channel_id"],
    foreignKeys = [
        ForeignKey(
            entity = AndroidAppPolicyEntity::class,
            parentColumns = ["package_name"],
            childColumns = ["package_name"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AndroidSeenGroupEntity::class,
            parentColumns = ["package_name", "group_id"],
            childColumns = ["package_name", "group_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["package_name", "last_seen_at"], name = "index_android_seen_channel_package_last_seen_at"),
        Index(value = ["package_name", "group_id"], name = "index_android_seen_channel_package_group"),
    ],
)
internal data class AndroidSeenChannelEntity(
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "channel_name")
    val channelName: String?,
    @ColumnInfo(name = "group_id")
    val groupId: String?,
    @ColumnInfo(name = "first_seen_at")
    val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,
)

@Entity(tableName = "incoming_filter")
internal data class IncomingFilterEntity(
    @PrimaryKey
    @ColumnInfo(name = "requester_client_id")
    val requesterClientId: String,
    @ColumnInfo(name = "canonicalization_version")
    val canonicalizationVersion: Int,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    /** Authenticated logical identity used for same-version conflict detection, not a cached child projection. */
    @ColumnInfo(name = "rule_set_digest", typeAffinity = ColumnInfo.BLOB)
    val ruleSetDigest: ByteArray,
)

@Entity(
    tableName = "incoming_filter_rule",
    primaryKeys = ["requester_client_id", "rule_digest"],
    foreignKeys = [
        ForeignKey(
            entity = IncomingFilterEntity::class,
            parentColumns = ["requester_client_id"],
            childColumns = ["requester_client_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["requester_client_id", "position"],
            unique = true,
            name = "index_incoming_filter_rule_requester_position",
        ),
        Index(
            value = ["origin_platform", "app_id", "channel_id"],
            name = "index_incoming_filter_rule_match",
        ),
    ],
)
internal data class IncomingFilterRuleEntity(
    @ColumnInfo(name = "requester_client_id")
    val requesterClientId: String,
    @ColumnInfo(name = "rule_digest", typeAffinity = ColumnInfo.BLOB)
    val ruleDigest: ByteArray,
    @ColumnInfo(name = "position")
    val position: Int,
    @ColumnInfo(name = "origin_platform")
    val originPlatform: NotificationOriginPlatform,
    @ColumnInfo(name = "app_id")
    val appId: String?,
    @ColumnInfo(name = "channel_id")
    val channelId: String?,
)

@Entity(tableName = "ios_app_allowlist")
internal data class IosAppAllowlistEntity(
    @PrimaryKey
    @ColumnInfo(name = "bundle_id")
    val bundleId: String,
)

@Entity(
    tableName = "ios_seen_app",
    indices = [
        Index(value = ["last_seen_at", "bundle_id"], name = "index_ios_seen_app_last_seen_bundle"),
    ],
)
internal data class IosSeenAppEntity(
    @PrimaryKey
    @ColumnInfo(name = "bundle_id")
    val bundleId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,
)

@Entity(
    tableName = "activity_event",
    indices = [
        Index(value = ["recorded_at", "event_id"], name = "index_activity_event_recorded_at_event_id"),
        Index(value = ["feature", "recorded_at"], name = "index_activity_event_feature_recorded_at"),
        Index(value = ["coalescing_key_token"], name = "index_activity_event_coalescing_key_token"),
    ],
)
internal data class ActivityEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,
    @ColumnInfo(name = "recorded_at")
    val recordedAt: Long,
    @ColumnInfo(name = "feature")
    val feature: ActivityFeature,
    @ColumnInfo(name = "semantic_action")
    val semanticAction: ActivityAction,
    @ColumnInfo(name = "direction")
    val direction: ActivityDirection,
    @ColumnInfo(name = "outcome")
    val outcome: ActivityOutcome,
    @ColumnInfo(name = "peer_client_id")
    val peerClientId: String?,
    @ColumnInfo(name = "correlation_id")
    val correlationId: String?,
    @ColumnInfo(name = "delivery_mode")
    val deliveryMode: OperationalDeliveryMode?,
    @ColumnInfo(name = "render_args_version")
    val renderArgsVersion: Int,
    /** Versioned, bounded, privacy-reviewed renderer arguments; never localized prose or raw payload. */
    @ColumnInfo(name = "render_args", typeAffinity = ColumnInfo.BLOB)
    val renderArgs: ByteArray,
    /** Optional keyed opaque equality token; never raw or an unkeyed payload-derived digest. */
    @ColumnInfo(name = "coalescing_key_token", typeAffinity = ColumnInfo.BLOB)
    val coalescingKeyToken: ByteArray?,
    @ColumnInfo(name = "coalesced_count")
    val coalescedCount: Int,
)

@Entity(
    tableName = "message_dedup",
    indices = [
        Index(value = ["handled_at"], name = "index_message_dedup_handled_at"),
    ],
)
internal data class MessageDedupEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "authenticated_fingerprint", typeAffinity = ColumnInfo.BLOB)
    val authenticatedFingerprint: ByteArray?,
    @ColumnInfo(name = "evidence_kind")
    val evidenceKind: MessageDedupEvidenceKind,
    @ColumnInfo(name = "handled_at")
    val handledAt: Long,
)

/**
 * Disposable metadata-only integrity scratch for one live relay drain. It is cleared at every
 * drain boundary and is neither broker custody nor resumable work authority.
 */
@Entity(
    tableName = "relay_batch_stage",
    indices = [
        Index(
            value = ["presentation_kind", "message_id"],
            name = "index_relay_batch_stage_presentation_message",
        ),
    ],
)
internal data class RelayBatchStageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "authenticated_fingerprint", typeAffinity = ColumnInfo.BLOB)
    val authenticatedFingerprint: ByteArray,
    @ColumnInfo(name = "conflict")
    val conflict: Boolean,
    @ColumnInfo(name = "presentation_kind")
    val presentationKind: RelayBatchPresentationKind,
)

@Entity(
    tableName = "mirror_lifecycle",
    primaryKeys = ["source_client_id", "source_key"],
    indices = [
        Index(value = ["updated_at"], name = "index_mirror_lifecycle_updated_at"),
    ],
)
internal data class MirrorLifecycleEntity(
    @ColumnInfo(name = "source_client_id")
    val sourceClientId: String,
    @ColumnInfo(name = "source_key")
    val sourceKey: String,
    @ColumnInfo(name = "post_time")
    val postTime: Long?,
    @ColumnInfo(name = "dismissed_at")
    val dismissedAt: Long?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "run_state",
    primaryKeys = ["host_client_id", "run_id"],
    indices = [
        Index(value = ["active", "updated_at"], name = "index_run_state_active_updated_at"),
        Index(value = ["active", "received_at"], name = "index_run_state_active_received_at"),
    ],
)
internal data class RunStateEntity(
    @ColumnInfo(name = "host_client_id")
    val hostClientId: String,
    @ColumnInfo(name = "run_id")
    val runId: String,
    @ColumnInfo(name = "revision")
    val revision: Long,
    @ColumnInfo(name = "phase")
    val phase: RunPhaseToken,
    @ColumnInfo(name = "presented_revision")
    val presentedRevision: Long,
    /** Local presentation/retention eligibility; it may be cleared without mutating the authenticated payload. */
    @ColumnInfo(name = "active")
    val active: Boolean,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "ended_at")
    val endedAt: Long?,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB)
    val payload: ByteArray,
    @ColumnInfo(name = "payload_digest", typeAffinity = ColumnInfo.BLOB)
    val payloadDigest: ByteArray,
)
