package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction

internal const val OPERATIONAL_SINGLETON_ID = 1

@Entity(tableName = "notification_capture_state")
internal data class NotificationCaptureStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id") val singletonId: Int = OPERATIONAL_SINGLETON_ID,
    @ColumnInfo(name = "last_seen_post_time") val lastSeenPostTime: Long,
)

/** One row per Android package; nullable aggregates preserve the known-good JSON codecs. */
@Entity(tableName = "android_apps")
internal data class AndroidAppEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "config_json") val configJson: String?,
    @ColumnInfo(name = "seen_channels_json") val seenChannelsJson: String?,
)

/** One full last-writer-wins filter snapshot per requesting peer. */
@Entity(tableName = "incoming_notification_filters")
internal data class IncomingNotificationFilterEntity(
    @PrimaryKey
    @ColumnInfo(name = "requester_client_id") val requesterClientId: String,
    @ColumnInfo(name = "filter_json") val filterJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * ANCS enablement and discovery share one identity and therefore one row. Enabled-but-undiscovered
 * bundles have null display/last-seen columns; discovered-but-disabled apps keep their metadata.
 */
@Entity(tableName = "ios_apps")
internal data class IosAppEntity(
    @PrimaryKey
    @ColumnInfo(name = "bundle_id") val bundleId: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?,
)

/** The known-good screen authorization and replay aggregate, committed as one row. */
@Entity(tableName = "screen_mirror_state")
internal data class ScreenMirrorStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id") val singletonId: Int = OPERATIONAL_SINGLETON_ID,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "authorized_peer_ids_json") val authorizedPeerIdsJson: String,
    @ColumnInfo(name = "request_replay_json") val requestReplayJson: String?,
    @ColumnInfo(name = "replay_blocked") val replayBlocked: Boolean,
    @ColumnInfo(name = "replay_quarantine_digest") val replayQuarantineDigest: String?,
    @ColumnInfo(name = "replay_quarantined_at") val replayQuarantinedAt: Long?,
)

@Entity(tableName = "screen_codec_preferences")
internal data class ScreenCodecPreferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "codec") val codec: String,
)

/** Locally selected OpenPGP provider identity; request history remains in sign_requests. */
@Entity(tableName = "openpgp_enrollment")
internal data class OpenPgpEnrollmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id") val singletonId: Int = OPERATIONAL_SINGLETON_ID,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "provider_id") val providerId: String?,
    @ColumnInfo(name = "provider_key_reference") val providerKeyReference: String?,
    @ColumnInfo(name = "primary_key_id") val primaryKeyId: String?,
    @ColumnInfo(name = "display_identity") val displayIdentity: String?,
    @ColumnInfo(name = "enrolled_at") val enrolledAt: Long?,
)

@Dao
internal interface OperationalApplicationDao {
    @Query("SELECT * FROM notification_capture_state WHERE singleton_id = 1")
    suspend fun notificationCaptureState(): NotificationCaptureStateEntity?

    @Query(
        """
        INSERT INTO notification_capture_state(singleton_id, last_seen_post_time)
        VALUES (1, :timeMillis)
        ON CONFLICT(singleton_id) DO UPDATE SET
            last_seen_post_time = MAX(last_seen_post_time, excluded.last_seen_post_time)
        """,
    )
    suspend fun advanceLastSeenPostTime(timeMillis: Long)

    @Query("SELECT * FROM android_apps")
    suspend fun androidApps(): List<AndroidAppEntity>

    @Query(
        """
        INSERT INTO android_apps(package_name, enabled, config_json, seen_channels_json)
        VALUES (:packageName, :enabled, NULL, NULL)
        ON CONFLICT(package_name) DO UPDATE SET enabled = excluded.enabled
        """,
    )
    suspend fun setAndroidAppEnabled(packageName: String, enabled: Boolean)

    @Query("UPDATE android_apps SET enabled = 0 WHERE enabled = 1")
    suspend fun disableAllAndroidApps()

    @Query(
        "DELETE FROM android_apps WHERE enabled = 0 AND config_json IS NULL AND seen_channels_json IS NULL",
    )
    suspend fun deleteEmptyAndroidApps()

    @Transaction
    suspend fun replaceAndroidEnabledPackages(packageNames: Set<String>) {
        disableAllAndroidApps()
        packageNames.forEach { setAndroidAppEnabled(it, true) }
        deleteEmptyAndroidApps()
    }

    @Query(
        """
        INSERT INTO android_apps(package_name, enabled, config_json, seen_channels_json)
        VALUES (:packageName, 0, :json, NULL)
        ON CONFLICT(package_name) DO UPDATE SET config_json = excluded.config_json
        """,
    )
    suspend fun setAndroidAppConfig(packageName: String, json: String)

    @Query(
        """
        INSERT INTO android_apps(package_name, enabled, config_json, seen_channels_json)
        VALUES (:packageName, 0, NULL, :json)
        ON CONFLICT(package_name) DO UPDATE SET seen_channels_json = excluded.seen_channels_json
        """,
    )
    suspend fun setAndroidSeenChannels(packageName: String, json: String)

    @Query("SELECT * FROM incoming_notification_filters")
    suspend fun incomingNotificationFilters(): List<IncomingNotificationFilterEntity>

    @Query(
        """
        INSERT INTO incoming_notification_filters(requester_client_id, filter_json, updated_at)
        VALUES (:requesterClientId, :filterJson, :updatedAt)
        ON CONFLICT(requester_client_id) DO UPDATE SET
            filter_json = excluded.filter_json,
            updated_at = excluded.updated_at
        WHERE excluded.updated_at >= incoming_notification_filters.updated_at
        """,
    )
    suspend fun upsertIncomingNotificationFilter(
        requesterClientId: String,
        filterJson: String,
        updatedAt: Long,
    )

    @Query("DELETE FROM incoming_notification_filters WHERE requester_client_id = :requesterClientId")
    suspend fun deleteIncomingNotificationFilter(requesterClientId: String)

    @Query("SELECT * FROM ios_apps")
    suspend fun iosApps(): List<IosAppEntity>

    @Query(
        """
        INSERT INTO ios_apps(bundle_id, enabled, display_name, last_seen_at)
        VALUES (:bundleId, :enabled, NULL, NULL)
        ON CONFLICT(bundle_id) DO UPDATE SET enabled = excluded.enabled
        """,
    )
    suspend fun setIosAppEnabled(bundleId: String, enabled: Boolean)

    @Query("UPDATE ios_apps SET enabled = 0 WHERE enabled = 1")
    suspend fun disableAllIosApps()

    @Query("DELETE FROM ios_apps WHERE enabled = 0 AND display_name IS NULL AND last_seen_at IS NULL")
    suspend fun deleteEmptyIosApps()

    @Transaction
    suspend fun replaceEnabledIosApps(bundleIds: Set<String>) {
        disableAllIosApps()
        bundleIds.forEach { setIosAppEnabled(it, true) }
        deleteEmptyIosApps()
    }

    @Query(
        """
        INSERT INTO ios_apps(bundle_id, enabled, display_name, last_seen_at)
        VALUES (:bundleId, 0, :displayName, :lastSeenAt)
        ON CONFLICT(bundle_id) DO UPDATE SET
            display_name = excluded.display_name,
            last_seen_at = excluded.last_seen_at
        """,
    )
    suspend fun recordIosApp(bundleId: String, displayName: String, lastSeenAt: Long)

    @Query("UPDATE ios_apps SET display_name = NULL, last_seen_at = NULL WHERE bundle_id = :bundleId")
    suspend fun clearIosAppDiscovery(bundleId: String)

    @Query(
        "DELETE FROM ios_apps WHERE bundle_id = :bundleId AND enabled = 0 " +
            "AND display_name IS NULL AND last_seen_at IS NULL",
    )
    suspend fun deleteEmptyIosApp(bundleId: String)

    @Transaction
    suspend fun forgetIosApp(bundleId: String) {
        clearIosAppDiscovery(bundleId)
        deleteEmptyIosApp(bundleId)
    }

    @Query("SELECT * FROM screen_mirror_state WHERE singleton_id = 1")
    suspend fun screenMirrorState(): ScreenMirrorStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceScreenMirrorState(entity: ScreenMirrorStateEntity)

    @Query("SELECT * FROM screen_codec_preferences")
    suspend fun screenCodecPreferences(): List<ScreenCodecPreferenceEntity>

    @Query("DELETE FROM screen_codec_preferences")
    suspend fun deleteScreenCodecPreferences()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScreenCodecPreferences(entities: List<ScreenCodecPreferenceEntity>)

    @Transaction
    suspend fun replaceScreenCodecPreferences(entities: List<ScreenCodecPreferenceEntity>) {
        deleteScreenCodecPreferences()
        if (entities.isNotEmpty()) insertScreenCodecPreferences(entities)
    }

    @Query("SELECT * FROM openpgp_enrollment WHERE singleton_id = 1")
    suspend fun openPgpEnrollment(): OpenPgpEnrollmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceOpenPgpEnrollment(entity: OpenPgpEnrollmentEntity)
}
