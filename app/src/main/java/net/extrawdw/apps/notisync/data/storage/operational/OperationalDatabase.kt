package net.extrawdw.apps.notisync.data.storage.operational

import android.content.Context
import androidx.room3.Database
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL

/**
 * Operational Room contains application-domain state. Existing table and column names remain
 * unchanged so the proven stores can continue to provide runtime behavior while Room owns schema
 * migrations.
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
        SshWebAuthnCredentialEntity::class,
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
        const val VERSION = 3

        val MIGRATION_1_2 = Migration(1, 2) { connection ->
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ssh_webauthn_credentials` (
                    `provider_key_id` TEXT NOT NULL,
                    `credential_id` BLOB NOT NULL,
                    `user_handle` BLOB NOT NULL,
                    `rp_id` TEXT NOT NULL,
                    `cose_public_key` BLOB NOT NULL,
                    `created_origin` TEXT NOT NULL,
                    `backup_eligible` INTEGER NOT NULL,
                    `backup_state` INTEGER NOT NULL,
                    PRIMARY KEY(`provider_key_id`),
                    FOREIGN KEY(`provider_key_id`) REFERENCES `ssh_keys`(`provider_key_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `ssh_webauthn_credential_id_unique`
                ON `ssh_webauthn_credentials` (`credential_id`)
                """.trimIndent(),
            )
        }

        val MIGRATION_2_3 = Migration(2, 3) { connection ->
            connection.execSQL("ALTER TABLE `ssh_webauthn_credentials` DROP COLUMN `created_origin`")
        }

        fun create(context: Context): OperationalDatabase =
            Room.databaseBuilder<OperationalDatabase>(context.applicationContext, DATABASE_NAME)
                .setDriver(AndroidSQLiteDriver())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
