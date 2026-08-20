package net.extrawdw.apps.notisync.data.storage.core

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

/**
 * Foundation database for identity, trust, key custody, and authenticated transport continuity.
 *
 * This database intentionally contains no feature delivery tables.  Its smaller corruption domain is what lets
 * operational reset preserve the device identity while still requiring an explicit replay fence before reconnect.
 */
@Database(
    entities = [
        CoreMaintenanceStateEntity::class,
        IdentityMetadataEntity::class,
        TrustSnapshotEntity::class,
        CryptoEpochEntity::class,
        BrokerAuthTokenEntity::class,
        CoreTransportStateEntity::class,
        KeystoreOperationEntity::class,
        CoreCommandAppliedEntity::class,
        CoreActivityOutboxEntity::class,
    ],
    version = CoreDatabase.VERSION,
    exportSchema = true,
)
@ColumnTypeConverters(
    TrustCleanupStateConverter::class,
    IdentitySecurityLevelConverter::class,
    IdentityLifecycleStateConverter::class,
    CryptoEpochSecurityLevelConverter::class,
    CryptoEpochStateConverter::class,
    ReplayFenceStateConverter::class,
    OperationalContinuityOriginConverter::class,
    KeystoreOperationKindConverter::class,
    KeystoreOperationTargetConverter::class,
    KeystoreOperationStateConverter::class,
    CoreCommandOutcomeConverter::class,
)
internal abstract class CoreDatabase : RoomDatabase() {
    abstract fun maintenanceStateDao(): CoreMaintenanceStateDao
    abstract fun identityMetadataDao(): IdentityMetadataDao
    abstract fun trustSnapshotDao(): TrustSnapshotDao
    abstract fun cryptoEpochDao(): CryptoEpochDao
    abstract fun brokerAuthTokenDao(): BrokerAuthTokenDao
    abstract fun transportStateDao(): CoreTransportStateDao
    abstract fun keystoreOperationDao(): KeystoreOperationDao
    abstract fun commandAppliedDao(): CoreCommandAppliedDao
    abstract fun activityOutboxDao(): CoreActivityOutboxDao

    companion object {
        const val DATABASE_NAME = "notisync-core.db"
        const val VERSION = 1

        /** Creates a file-backed database. The application-scoped singleton is [CoreDatabaseFactory]. */
        fun create(context: Context): CoreDatabase =
            Room.databaseBuilder<CoreDatabase>(context.applicationContext, DATABASE_NAME)
                .setDriver(AndroidSQLiteDriver())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

/**
 * Process-local singleton factory. Room itself serializes database access; this factory prevents independent
 * application graph owners from opening separate Core instances against the same file.
 */
internal object CoreDatabaseFactory {
    @Volatile
    private var instance: CoreDatabase? = null

    fun get(context: Context): CoreDatabase = instance ?: synchronized(this) {
        instance ?: CoreDatabase.create(context).also { instance = it }
    }

    internal fun closeForTests() = synchronized(this) {
        instance?.close()
        instance = null
    }
}
