package net.extrawdw.notisync.daemon.peer.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Clock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import net.extrawdw.notisync.daemon.ApplicationProfilePublicationState
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.desktop.SecureFileSystem
import net.extrawdw.notisync.peer.channel.MessageDedup
import net.extrawdw.notisync.protocol.ProtocolCodec
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.json.json

private object Applications : Table("applications") {
    val id = text("application_id")
    val registration = json<StoredApplicationRegistration>("registration", ProtocolCodec.json)
    override val primaryKey = PrimaryKey(id)
}

private object Profile : Table("profile") {
    val id = integer("id")
    val publication = json<ApplicationProfilePublicationState>("publication", ProtocolCodec.json)
    override val primaryKey = PrimaryKey(id)
}

private object Dedup : Table("dedup") {
    val messageId = varchar("message_id", 512)
    val recordedAt = long("recorded_at")
    override val primaryKey = PrimaryKey(messageId)
    init { index("dedup_oldest", false, recordedAt, messageId) }
}

internal val daemonDatabaseTables = arrayOf<Table>(Applications, Profile, Dedup)

/** Exposed/SQLite owns durable metadata and dedup. Application queues remain process-local. */
class DaemonDatabaseRepository(
    layout: DaemonStorageLayout,
    private val clock: Clock = Clock.systemUTC(),
    private val maximumDedupEntries: Int = 50_000,
    fileSystem: SecureFileSystem = SecureFileSystem(),
) : MessageDedup, AutoCloseable {
    private val lock = ReentrantLock()
    private val pool: HikariDataSource
    private val database: Database
    private var closed = false

    init {
        require(maximumDedupEntries > 0) { "maximumDedupEntries must be positive" }
        layout.prepare(fileSystem)
        val path = layout.databaseFile
        val sidecars = listOf("-wal", "-shm", "-journal").map { path.resolveSibling("${path.fileName}$it") }
        sidecars.forEach {
            fileSystem.rejectSymbolicLinkComponents(it)
            if (Files.exists(it, LinkOption.NOFOLLOW_LINKS)) fileSystem.validatePrivateFile(it)
        }
        // Reset only non-SQLite files, as requested. An incompatible/damaged SQLite database fails
        // intact. Trust, identity keys, auth and config live outside this database and are retained.
        if (Files.exists(path) && !Files.newInputStream(path).use {
                it.readNBytes(16).contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
            }) {
            fileSystem.deletePrivateFileIfExists(path)
            sidecars.forEach(fileSystem::deletePrivateFileIfExists)
        }
        fileSystem.ensurePrivateFile(path)
        pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$path"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 1
            addDataSourceProperty("journal_mode", "WAL")
            addDataSourceProperty("synchronous", "FULL")
            addDataSourceProperty("busy_timeout", "5000")
            // Acquire the write reservation before read/modify/write, including across connections.
            addDataSourceProperty("transaction_mode", "IMMEDIATE")
        })
        database = Database.connect(pool)
        try {
            DaemonDatabaseMigrations.migrate(pool)
            sidecars.filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }.forEach(fileSystem::validatePrivateFile)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun load(): DaemonDatabase = tx { readMetadata() }

    /** Change only affected metadata rows; dedup history never enters the metadata snapshot. */
    fun update(transform: (DaemonDatabase) -> DaemonDatabase): DaemonDatabase = tx {
        val before = readMetadata()
        val after = transform(before).validated()
        if (after.profilePublication != before.profilePublication) {
            Profile.update({ Profile.id eq 1 }) { it[publication] = after.profilePublication }
        }
        val removed = before.applications.keys - after.applications.keys
        if (removed.isNotEmpty()) Applications.deleteWhere { id inList removed }
        after.applications.forEach { (id, application) ->
            if (application != before.applications[id]) Applications.upsert {
                it[Applications.id] = id
                it[registration] = application
            }
        }
        after
    }

    override fun seen(messageId: String): Boolean {
        validateMessageId(messageId)
        return tx { !Dedup.selectAll().where { Dedup.messageId eq messageId }.empty() }
    }

    override fun record(messageId: String) {
        validateMessageId(messageId)
        tx {
            val inserted = Dedup.insertIgnore {
                it[Dedup.messageId] = messageId
                it[recordedAt] = clock.millis()
            }.insertedCount
            if (inserted == 0) return@tx
            val excess = Dedup.selectAll().count() - maximumDedupEntries
            if (excess > 0) {
                val oldest = Dedup.selectAll().orderBy(Dedup.recordedAt to SortOrder.ASC, Dedup.messageId to SortOrder.ASC)
                    .limit(excess.toInt()).map { it[Dedup.messageId] }
                oldest.chunked(500).forEach { ids -> Dedup.deleteWhere { Dedup.messageId inList ids } }
            }
        }
    }

    private fun readMetadata(): DaemonDatabase = DaemonDatabase(
        profilePublication = Profile.selectAll().where { Profile.id eq 1 }.single()[Profile.publication],
        applications = Applications.selectAll().orderBy(Applications.id).associate {
            it[Applications.id] to it[Applications.registration]
        },
    ).validated()

    private fun <T> tx(block: JdbcTransaction.() -> T): T = lock.withLock {
        check(!closed) { "daemon database is closed" }
        transaction(database) {
            // Repository transforms may have caller-visible results; never run them twice implicitly.
            maxAttempts = 1
            block()
        }
    }

    override fun close() = lock.withLock {
        if (!closed) {
            closed = true
            TransactionManager.closeAndUnregister(database)
            pool.close()
        }
    }

    private fun validateMessageId(id: String) {
        require(id.isNotBlank() && id.length <= 512) { "invalid message id" }
    }
}
