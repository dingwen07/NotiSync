package net.extrawdw.notisync.sshagent.cache

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import net.extrawdw.notisync.desktop.PrivateFiles

class AgentDatabase(path: Path) : AutoCloseable {
    val connection: Connection

    init {
        PrivateFiles.ensureDirectory(requireNotNull(path.toAbsolutePath().parent))
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA busy_timeout=5000")
        }
        migrate()
        PrivateFiles.validatePrivateFile(path)
    }

    @Synchronized
    fun <T> transaction(block: (Connection) -> T): T {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        return try {
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    @Synchronized
    fun <T> read(block: (Connection) -> T): T = block(connection)

    private fun migrate() = transaction { database ->
        val version = database.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result -> result.getInt(1) }
        }
        require(version in 0..SCHEMA_VERSION) { "unsupported SSH Agent database schema $version" }
        if (version == 0) {
            database.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE provider_snapshots(
                        provider_id TEXT PRIMARY KEY,
                        inventory_generation TEXT NOT NULL,
                        revision INTEGER NOT NULL CHECK(revision > 0),
                        generated_at INTEGER NOT NULL,
                        received_at INTEGER NOT NULL,
                        canonical_hash BLOB NOT NULL,
                        provider_health TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE provider_keys(
                        provider_id TEXT NOT NULL REFERENCES provider_snapshots(provider_id) ON DELETE CASCADE,
                        provider_key_id TEXT NOT NULL,
                        public_blob_hash BLOB NOT NULL,
                        public_blob BLOB NOT NULL,
                        descriptor_cbor BLOB NOT NULL,
                        PRIMARY KEY(provider_id, provider_key_id),
                        UNIQUE(provider_id, public_blob_hash)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE retired_inventory_generations(
                        provider_id TEXT NOT NULL,
                        generation TEXT NOT NULL,
                        retired_at INTEGER NOT NULL,
                        PRIMARY KEY(provider_id, generation)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE local_visibility(
                        public_blob_hash BLOB PRIMARY KEY,
                        hidden_at INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        expires_at INTEGER
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE agent_metadata(
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE sign_operation_log(
                        request_id TEXT PRIMARY KEY,
                        request_digest BLOB NOT NULL,
                        public_blob_hash BLOB NOT NULL,
                        data_sha256 BLOB NOT NULL,
                        eligible_provider_ids TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        terminal_at INTEGER,
                        terminal_kind TEXT,
                        winning_provider TEXT
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE provider_outcomes(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        request_id TEXT NOT NULL,
                        provider_id TEXT NOT NULL,
                        outcome_kind TEXT NOT NULL,
                        received_at INTEGER NOT NULL,
                        details TEXT
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE authorization_forget_outbox(
                        request_id TEXT PRIMARY KEY,
                        request_cbor BLOB NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute("PRAGMA user_version=$SCHEMA_VERSION")
            }
        }
    }

    override fun close() = connection.close()

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
