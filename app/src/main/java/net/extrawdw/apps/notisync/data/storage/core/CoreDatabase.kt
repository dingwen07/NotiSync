package net.extrawdw.apps.notisync.data.storage.core

import android.content.Context
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

/** Relay-message ids already committed by the shared secure-channel substrate. */
@Entity(tableName = "dedup", primaryKeys = ["message_id"])
internal data class MessageDedupEntity(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "handled_at") val handledAt: Long,
)

/** Relay acknowledgements durably queued for the next transport flush. */
@Entity(tableName = "pending_ack", primaryKeys = ["message_id"])
internal data class PendingAckEntity(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "queued_at") val queuedAt: Long,
)

/** Authenticated relay work retained until the shared channel can reduce it. */
@Entity(
    tableName = "relay_inbox",
    primaryKeys = ["message_id"],
    indices = [Index(value = ["accepted_at", "received_at"], name = "relay_inbox_accepted_idx")],
)
internal data class RelayInboxEntity(
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "envelope", typeAffinity = ColumnInfo.BLOB) val envelope: ByteArray,
    @ColumnInfo(name = "accepted_at") val acceptedAt: Long,
    @ColumnInfo(name = "delivery_mode") val deliveryMode: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "early_ack") val earlyAck: Int,
)

/** Small relay-substrate watermarks that must be committed with inbox work. */
@Entity(tableName = "message_meta", primaryKeys = ["name"])
internal data class MessageMetaEntity(
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "long_value") val longValue: Long,
)

/**
 * Core Room contains only relational state owned by the shared messaging substrate. Small
 * configuration, trust, and user-preference values remain in the existing Preferences DataStore;
 * application-owned state lives in Operational Room. Android Keystore keys and atomic key/token files
 * remain in their known-good custody locations.
 */
@Database(
    entities = [
        MessageDedupEntity::class,
        PendingAckEntity::class,
        RelayInboxEntity::class,
        MessageMetaEntity::class,
    ],
    version = CoreDatabase.VERSION,
    exportSchema = true,
)
internal abstract class CoreDatabase : RoomDatabase() {
    abstract fun metadata(): CoreMetadataDao

    companion object {
        const val DATABASE_NAME = "notisync-core.db"
        const val VERSION = 1

        fun create(context: Context): CoreDatabase =
            Room.databaseBuilder<CoreDatabase>(context.applicationContext, DATABASE_NAME)
                .setDriver(AndroidSQLiteDriver())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

@Dao
internal interface CoreMetadataDao {
    @Query("SELECT COUNT(*) FROM sqlite_master")
    suspend fun schemaObjectCount(): Int
}

internal object CoreDatabaseFactory {
    private val instances = mutableMapOf<String, CoreDatabase>()

    fun get(context: Context): CoreDatabase = synchronized(this) {
        val key = context.applicationContext.getDatabasePath(CoreDatabase.DATABASE_NAME).absolutePath
        instances.getOrPut(key) { CoreDatabase.create(context) }
    }

    fun close(context: Context) = synchronized(this) {
        val key = context.applicationContext.getDatabasePath(CoreDatabase.DATABASE_NAME).absolutePath
        instances.remove(key)?.close()
    }
}
