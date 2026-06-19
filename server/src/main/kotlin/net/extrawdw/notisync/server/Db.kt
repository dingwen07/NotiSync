package net.extrawdw.notisync.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * Recoverable broker cache. Binary values are stored base64-encoded in TEXT columns to keep the
 * schema portable across SQLite/Postgres without binary-column quirks; the broker is a cache, not
 * a hot path. SQLite runs in WAL mode with a single-writer pool to avoid SQLITE_BUSY.
 */
object Cards : Table("cards") {
    val clientId = varchar("client_id", 64)
    val signedBlobB64 = text("signed_blob_b64") // the verbatim SignedBlob(client-card) CBOR, base64
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(clientId)
}

object Routes : Table("routes") {
    val id = long("id").autoIncrement()
    val clientId = varchar("client_id", 64).index()
    val transport = varchar("transport", 16)
    val routeRef = text("route_ref")
    val epoch = integer("epoch")
    val state = varchar("state", 24)
    val signedBlobB64 = text("signed_blob_b64")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object Relay : Table("relay") {
    val id = long("id").autoIncrement()
    val recipientId = varchar("recipient_id", 64).index()
    val messageId = varchar("message_id", 64)
    val envelopeB64 = text("envelope_b64")
    val urgency = varchar("urgency", 8)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(id)
}

object Blobs : Table("blobs") {
    val blobId = varchar("blob_id", 96)
    val dataB64 = text("data_b64")
    val sizeBytes = integer("size_bytes")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(blobId)
}

class NotiSyncDb(private val database: Database) {
    suspend fun <T> tx(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(database) { block() } }

    companion object {
        fun connect(config: ServerConfig): NotiSyncDb {
            File(config.dbPath).absoluteFile.parentFile?.mkdirs()
            val ds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${config.dbPath}"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 1 // single writer -> serialized writes, no SQLITE_BUSY
                connectionInitSql = "PRAGMA journal_mode=WAL"
            })
            val database = Database.connect(ds)
            transaction(database) {
                SchemaUtils.create(Cards, Routes, Relay, Blobs)
            }
            return NotiSyncDb(database)
        }
    }
}
