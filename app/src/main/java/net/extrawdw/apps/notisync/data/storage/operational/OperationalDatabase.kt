package net.extrawdw.apps.notisync.data.storage.operational

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

/**
 * Resettable operational state, isolated from identity/trust/key-custody continuity in CoreDatabase.
 *
 * Protected tuples persist the reviewed format, codec, AAD address, key reference, and Operational
 * generation needed for fail-closed reopen after process death. Scheme columns intentionally remain
 * raw strings so unknown or restored formats hydrate into typed recovery instead of failing Room
 * conversion; current writes and feature wiring still pass through the role-bound protection contract
 * and the shared maintenance/generation gate.
 */
@Database(
    entities = [
        MaintenanceStateEntity::class,
        LocalProfileEntity::class,
        NotificationCaptureStateEntity::class,
        AndroidAppPolicyEntity::class,
        AndroidSubscopePolicyEntity::class,
        AndroidSeenGroupEntity::class,
        AndroidSeenChannelEntity::class,
        IncomingFilterEntity::class,
        IncomingFilterRuleEntity::class,
        IosAppAllowlistEntity::class,
        IosSeenAppEntity::class,
        ActivityEventEntity::class,
        MessageDedupEntity::class,
        RelayBatchStageEntity::class,
        MirrorLifecycleEntity::class,
        RunStateEntity::class,
        SealEnrollmentEntity::class,
        SealEnrollmentProtectedEntity::class,
        SealRequestEntity::class,
        SealPendingPayloadEntity::class,
        SealResponseCustodyEntity::class,
        ScreenAuthorizedPeerEntity::class,
        ScreenReplayTokenEntity::class,
        ScreenSecurityStateEntity::class,
        ScreenCodecPreferenceEntity::class,
        SshProviderStateEntity::class,
        SshResetJournalEntity::class,
        SshResetAliasEntity::class,
        SshKeyEntity::class,
        SshOperationalKeyEntity::class,
        SshWrappedOperationalMaterialEntity::class,
        SshExportCopyEntity::class,
        SshKeyLifecycleEntity::class,
        SshKeyLifecycleCandidateEntity::class,
        SshAuthorizationFloorEntity::class,
        SshPeerAuthorizationEntity::class,
        SshKnownHostEntity::class,
        SshHostAuthorizationEntity::class,
        SshProviderRequestEntity::class,
        SshProviderPendingPayloadEntity::class,
        SshProviderResponseCustodyEntity::class,
    ],
    version = OperationalDatabase.VERSION,
    exportSchema = true,
)
@ColumnTypeConverters(
    OperationalFoundationTypeConverters::class,
    OperationalSecurityTypeConverters::class,
    OperationalSshTypeConverters::class,
)
internal abstract class OperationalDatabase : RoomDatabase() {
    abstract fun profileDao(): OperationalProfileDao
    abstract fun notificationPolicyDao(): AndroidNotificationPolicyDao
    abstract fun incomingFilterDao(): IncomingFilterDao
    abstract fun iosAppDao(): IosAppDao
    abstract fun activityDao(): ActivityDao
    abstract fun relayDao(): RelayDao
    abstract fun relayBatchStageDao(): RelayBatchStageDao
    abstract fun mirrorLifecycleDao(): MirrorLifecycleDao
    abstract fun runDao(): RunDao
    abstract fun sealDao(): SealDao
    abstract fun screenDao(): ScreenDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun sshAuthorizationDao(): SshAuthorizationDao
    abstract fun sshRequestDao(): SshRequestDao
    abstract fun sshResetDao(): SshResetDao

    companion object {
        const val DATABASE_NAME = "notisync-operational.db"
        const val VERSION = 1

        /** Creates a file-backed database. The application-scoped singleton is [OperationalDatabaseFactory]. */
        fun create(context: Context): OperationalDatabase =
            Room.databaseBuilder<OperationalDatabase>(context.applicationContext, DATABASE_NAME)
                .setDriver(AndroidSQLiteDriver())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

/** Ensures all application graph owners share one process-local handle to the operational file. */
internal object OperationalDatabaseFactory {
    @Volatile
    private var instance: OperationalDatabase? = null

    fun get(context: Context): OperationalDatabase = instance ?: synchronized(this) {
        instance ?: OperationalDatabase.create(context).also { instance = it }
    }

    internal fun closeForTests() = synchronized(this) {
        instance?.close()
        instance = null
    }
}
