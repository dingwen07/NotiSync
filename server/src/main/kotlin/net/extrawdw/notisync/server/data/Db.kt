package net.extrawdw.notisync.server.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.extrawdw.notisync.server.ServerConfig
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

/**
 * NS2 operational key-epoch certificates, one row per (clientId, epoch). The broker stores the verbatim
 * identity-signed [net.extrawdw.notisync.protocol.ClientKeyEpoch] [SignedBlob] (so peers re-verify it
 * client-side) plus the columns it gates on: [minEpoch] (the downgrade floor) and the validity window.
 */
object Epochs : Table("key_epochs") {
    val clientId = varchar("client_id", 64)
    val epoch = integer("epoch")
    val minEpoch = integer("min_epoch")
    val notBefore = long("not_before")
    val notAfter = long("not_after")
    val signedBlobB64 = text("signed_blob_b64") // the verbatim SignedBlob(key-epoch) CBOR, base64
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(clientId, epoch)
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

/**
 * Opaque private-asset blobs. Keyed only by the client-chosen random ([sourceClientId], [assetId]) —
 * NEVER by content hash, package, sender, or role — so the broker cannot correlate who the user is
 * messaging. The value is AEAD ciphertext the broker cannot read.
 */
object PrivateAssets : Table("private_assets") {
    val sourceClientId = varchar("source_client_id", 64)
    val assetId = varchar("asset_id", 64)
    val dataB64 = text("data_b64")          // base64 of the AEAD ciphertext
    val sizeBytes = integer("size_bytes")   // ciphertext size
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(sourceClientId, assetId)
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
                SchemaUtils.create(Routes, Relay, PrivateAssets, Epochs)
            }
            return NotiSyncDb(database)
        }
    }
}
