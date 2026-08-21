package net.extrawdw.notisync.sshagent.cache

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import net.extrawdw.notisync.desktop.PrivateFiles
import net.extrawdw.notisync.desktop.SecureFileSystem

internal class UnsupportedAgentDatabaseSchemaException(
    val actualVersion: Int,
    val expectedVersion: Int,
) : IllegalStateException(
    "Unsupported SSH Agent database schema $actualVersion; expected $expectedVersion. " +
        "The database was not modified.",
)

class AgentDatabase(path: Path) : AutoCloseable {
    val connection: Connection

    init {
        PrivateFiles.ensureDirectory(requireNotNull(path.toAbsolutePath().parent))
        val opened = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        connection = opened
        try {
            opened.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA busy_timeout=5000")
            }
            initializeOrValidateSchema()
            PrivateFiles.validatePrivateFile(path)
        } catch (failure: Throwable) {
            runCatching { opened.close() }
            throw failure
        }
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

    /** Creates a new v1 database or validates an existing v1 database. Never migrates or deletes user data. */
    private fun initializeOrValidateSchema() = transaction { database ->
        val version = database.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result -> result.getInt(1) }
        }
        when (version) {
            0 -> {
                check(userTables(database).isEmpty()) {
                    "Unversioned SSH Agent database is not empty; refusing to alter it"
                }
                database.createStatement().use { statement ->
                    listOf(
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
                        """,
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
                        """,
                        """
                        CREATE TABLE retired_inventory_generations(
                            provider_id TEXT NOT NULL,
                            generation TEXT NOT NULL,
                            retired_at INTEGER NOT NULL,
                            PRIMARY KEY(provider_id, generation)
                        )
                        """,
                        """
                        CREATE TABLE local_visibility(
                            public_blob_hash BLOB PRIMARY KEY,
                            hidden_at INTEGER NOT NULL,
                            reason TEXT NOT NULL,
                            expires_at INTEGER
                        )
                        """,
                        """
                        CREATE TABLE agent_metadata(
                            key TEXT PRIMARY KEY,
                            value TEXT NOT NULL
                        )
                        """,
                        """
                        CREATE TABLE sign_operation_log(
                            request_id TEXT PRIMARY KEY,
                            public_blob_hash BLOB NOT NULL,
                            data_sha256 BLOB NOT NULL,
                            eligible_provider_ids TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            terminal_at INTEGER,
                            terminal_kind TEXT,
                            winning_provider TEXT
                        )
                        """,
                        """
                        CREATE TABLE provider_outcomes(
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            request_id TEXT NOT NULL,
                            provider_id TEXT NOT NULL,
                            outcome_kind TEXT NOT NULL,
                            received_at INTEGER NOT NULL,
                            details TEXT
                        )
                        """,
                        """
                        CREATE TABLE authorization_forget_outbox(
                            request_id TEXT PRIMARY KEY,
                            request_cbor BLOB NOT NULL,
                            created_at INTEGER NOT NULL
                        )
                        """,
                    ).forEach { statement.execute(it.trimIndent()) }
                    statement.execute("PRAGMA user_version=$SCHEMA_VERSION")
                }
            }
            SCHEMA_VERSION -> Unit
            else -> throw UnsupportedAgentDatabaseSchemaException(version, SCHEMA_VERSION)
        }
        validateSchema(database)
    }

    private fun validateSchema(database: Connection) {
        check(userTables(database) == EXPECTED_SCHEMA.keys) {
            "SSH Agent database schema $SCHEMA_VERSION has an unexpected table set; the database was not modified"
        }
        EXPECTED_SCHEMA.forEach { (table, expectedColumns) ->
            val actualColumns = database.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info($table)").use { result ->
                    buildSet { while (result.next()) add(result.getString("name")) }
                }
            }
            check(actualColumns == expectedColumns) {
                "SSH Agent database schema $SCHEMA_VERSION has incompatible columns in $table; " +
                    "the database was not modified"
            }
        }
        val integrity = database.createStatement().use { statement ->
            statement.executeQuery("PRAGMA quick_check(1)").use { result ->
                check(result.next())
                result.getString(1)
            }
        }
        check(integrity == "ok") { "SSH Agent database integrity check failed: $integrity" }
        database.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_check").use { result ->
                check(!result.next()) { "SSH Agent database contains foreign-key violations" }
            }
        }
    }

    private fun userTables(database: Connection): Set<String> = database.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        ).use(ResultSet::toStringSet)
    }

    override fun close() = connection.close()

    companion object {
        private const val SCHEMA_VERSION = 1

        /** Quarantines this cache after any open or validation failure, then retries once. */
        internal fun openRecoveringOnFailure(
            path: Path,
            recoveryRoot: Path = requireNotNull(path.toAbsolutePath().parent).resolve("recovery"),
            onReset: (failure: Throwable, backupDirectory: Path?) -> Unit = { _, _ -> },
        ): AgentDatabase = try {
            AgentDatabase(path)
        } catch (failure: Throwable) {
            val backup = try {
                quarantineDatabaseFiles(path, recoveryRoot)
            } catch (backupFailure: Throwable) {
                backupFailure.addSuppressed(failure)
                throw backupFailure
            }
            onReset(failure, backup)
            try {
                AgentDatabase(path)
            } catch (retryFailure: Throwable) {
                retryFailure.addSuppressed(failure)
                throw retryFailure
            }
        }

        private fun quarantineDatabaseFiles(path: Path, recoveryRoot: Path): Path? {
            val absolute = path.toAbsolutePath().normalize()
            val candidates = (listOf(absolute) + SQLITE_SIDECAR_SUFFIXES.map { suffix ->
                absolute.resolveSibling(absolute.fileName.toString() + suffix)
            }).filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            if (candidates.isEmpty()) return null

            val files = SecureFileSystem()
            val backupDirectory = recoveryRoot.toAbsolutePath().normalize()
                .resolve("ssh-agent-db-${UUID.randomUUID()}")
            files.ensurePrivateDirectory(backupDirectory)
            candidates.forEach { candidate ->
                files.movePrivateFileIfExists(candidate, backupDirectory.resolve(candidate.fileName))
            }
            return backupDirectory
        }

        private val SQLITE_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

        private val EXPECTED_SCHEMA = mapOf(
            "provider_snapshots" to setOf(
                "provider_id", "inventory_generation", "revision", "generated_at", "received_at",
                "canonical_hash", "provider_health",
            ),
            "provider_keys" to setOf(
                "provider_id", "provider_key_id", "public_blob_hash", "public_blob", "descriptor_cbor",
            ),
            "retired_inventory_generations" to setOf("provider_id", "generation", "retired_at"),
            "local_visibility" to setOf("public_blob_hash", "hidden_at", "reason", "expires_at"),
            "agent_metadata" to setOf("key", "value"),
            "sign_operation_log" to setOf(
                "request_id", "public_blob_hash", "data_sha256", "eligible_provider_ids", "created_at",
                "terminal_at", "terminal_kind", "winning_provider",
            ),
            "provider_outcomes" to setOf(
                "id", "request_id", "provider_id", "outcome_kind", "received_at", "details",
            ),
            "authorization_forget_outbox" to setOf("request_id", "request_cbor", "created_at"),
        )
    }
}

private fun ResultSet.toStringSet(): Set<String> = buildSet {
    while (next()) add(getString(1))
}
