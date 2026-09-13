package net.extrawdw.notisync.daemon.peer.storage

import java.nio.file.Files
import net.extrawdw.notisync.daemon.storage.DaemonStorageLayout
import net.extrawdw.notisync.daemon.storage.StorageTestSupport
import org.flywaydb.core.api.FlywayException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sqlite.SQLiteDataSource

class DaemonDatabaseMigrationsTest : StorageTestSupport() {
    @Test
    fun `packaged migrations match the Exposed model and repeat without changing data`() {
        val layout = layout()
        DaemonDatabaseRepository(layout).use { it.record("retained") }
        val source = source(layout)
        val database = Database.connect(source)
        try {
            val differences = transaction(database) {
                MigrationUtils.statementsRequiredForDatabaseMigration(*daemonDatabaseTables, withLogs = false)
            }
            assertEquals(emptyList<String>(), differences)
            assertEquals(0, DaemonDatabaseMigrations.configure(source).migrate().migrationsExecuted)
        } finally {
            TransactionManager.closeAndUnregister(database)
        }
        DaemonDatabaseRepository(layout).use { assertTrue(it.seen("retained")) }
    }

    @Test
    fun `modified migration checksum is rejected without deleting SQLite data`() {
        val layout = layout()
        DaemonDatabaseRepository(layout).use { it.record("retained") }
        source(layout).connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("UPDATE flyway_schema_history SET checksum = 0 WHERE version = '1'") }
        }
        assertThrows(FlywayException::class.java) { DaemonDatabaseRepository(layout).close() }
        assertTrue(Files.exists(layout.databaseFile))
        source(layout).connection.use { connection ->
            connection.createStatement().use { sql ->
                assertTrue(sql.executeQuery("SELECT 1 FROM dedup WHERE message_id = 'retained'").use { it.next() })
            }
        }
    }

    private fun layout() = DaemonStorageLayout(temporaryDirectory.resolve(".notisync"))
    private fun source(layout: DaemonStorageLayout) = SQLiteDataSource().apply { url = "jdbc:sqlite:${layout.databaseFile}" }
}
