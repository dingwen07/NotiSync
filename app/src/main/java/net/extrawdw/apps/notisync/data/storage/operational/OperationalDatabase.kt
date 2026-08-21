package net.extrawdw.apps.notisync.data.storage.operational

import android.content.Context
import androidx.room3.Database
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import net.extrawdw.apps.notisync.data.storage.usesRoomStorage

/**
 * Operational v1 contains application-domain state. Table and column names remain unchanged so the
 * proven stores can continue to provide runtime behavior while Room owns the database version and
 * all future schema migrations.
 */
@Database(
    entities = [
        MirrorMessageEntity::class,
        MirrorLifecycleEntity::class,
        RunEntity::class,
        RunControlEntity::class,
        OpenPgpSignRequestEntity::class,
        SshProviderStateEntity::class,
        SshKeyEntity::class,
        SshOperationalKeyEntity::class,
        SshExportCopyEntity::class,
        SshKeyLifecycleEntity::class,
        SshAuthorizationFloorEntity::class,
        SshRememberedAuthorizationEntity::class,
        SshKnownHostEntity::class,
        SshProviderRequestEntity::class,
        NotificationCaptureStateEntity::class,
        AndroidAppEntity::class,
        IncomingNotificationFilterEntity::class,
        IosAppEntity::class,
        ScreenMirrorStateEntity::class,
        ScreenCodecPreferenceEntity::class,
        OpenPgpEnrollmentEntity::class,
    ],
    version = OperationalDatabase.VERSION,
    exportSchema = true,
)
internal abstract class OperationalDatabase : RoomDatabase() {
    abstract fun metadata(): OperationalMetadataDao
    abstract fun applicationState(): OperationalApplicationDao

    companion object {
        const val DATABASE_NAME = "notisync-operational.db"
        const val VERSION = 1

        fun create(context: Context): OperationalDatabase =
            Room.databaseBuilder<OperationalDatabase>(context.applicationContext, DATABASE_NAME)
                .setDriver(AndroidSQLiteDriver())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

internal object OperationalDatabaseFactory {
    private val instances = mutableMapOf<String, OperationalDatabase>()

    fun get(context: Context): OperationalDatabase = synchronized(this) {
        val key = context.applicationContext
            .getDatabasePath(OperationalDatabase.DATABASE_NAME)
            .absolutePath
        instances.getOrPut(key) { OperationalDatabase.create(context) }
    }

    fun close(context: Context) = synchronized(this) {
        val key = context.applicationContext
            .getDatabasePath(OperationalDatabase.DATABASE_NAME)
            .absolutePath
        instances.remove(key)?.close()
    }
}

@Dao
internal interface OperationalMetadataDao {
    @Query("SELECT COUNT(*) FROM sqlite_master")
    suspend fun schemaObjectCount(): Int
}

internal fun Context.operationalDatabaseName(legacyName: String): String =
    if (usesRoomStorage) {
        OperationalDatabase.DATABASE_NAME
    } else {
        legacyName
    }

internal fun Context.operationalDatabaseVersion(legacyVersion: Int): Int =
    if (usesRoomStorage) {
        OperationalDatabase.VERSION
    } else {
        legacyVersion
    }

internal object LegacyDatabaseNames {
    const val MESSAGE_LEDGER = "message_ledger.db"
    const val RUNS = "runs.db"
    const val RUN_CONTROL_OUTBOX = "run_control_outbox.db"
    const val OPENPGP_SIGNING = "openpgp_signing.db"
    const val SSH_KEY_PROVIDER = "ssh-key-provider.sqlite3"
}
