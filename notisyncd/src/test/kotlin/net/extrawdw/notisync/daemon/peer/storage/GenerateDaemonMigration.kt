package net.extrawdw.notisync.daemon.peer.storage

import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.sqlite.SQLiteDataSource

/** Build-only tool: compare the new table model with a disposable database built from released SQL. */
@OptIn(org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi::class)
fun main(args: Array<String>) {
    val output = Path.of(args.single()).toAbsolutePath()
    Files.createDirectories(output)
    val path = Files.createTempFile("notisyncd-migration-", ".sqlite")
    val source = SQLiteDataSource().apply { url = "jdbc:sqlite:$path" }
    val database = Database.connect(source)
    try {
        val migrations = DaemonDatabaseMigrations.configure(source)
        migrations.migrate()
        val nextVersion = migrations.info().current().version.version.toInt() + 1
        transaction(database) {
            MigrationUtils.generateMigrationScript(
                *daemonDatabaseTables,
                scriptDirectory = output.toString(),
                scriptName = "V${nextVersion}__describe_change",
            )
        }
    } finally {
        TransactionManager.closeAndUnregister(database)
        Files.deleteIfExists(path)
    }
}
