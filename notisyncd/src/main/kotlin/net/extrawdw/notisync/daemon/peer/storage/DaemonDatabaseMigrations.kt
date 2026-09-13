package net.extrawdw.notisync.daemon.peer.storage

import javax.sql.DataSource
import org.flywaydb.core.Flyway

/** Only reviewed, packaged migrations run at startup, under the daemon's exclusive instance lock. */
internal object DaemonDatabaseMigrations {
    const val LOCATION = "classpath:db/notisyncd"

    fun configure(dataSource: DataSource): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations(LOCATION)
        .validateMigrationNaming(true)
        .validateOnMigrate(true)
        .cleanDisabled(true)
        .baselineOnMigrate(false)
        // Flyway normally tolerates future migrations. A downgraded daemon must fail intact instead.
        .ignoreMigrationPatterns(*emptyArray<String>())
        .load()

    fun migrate(dataSource: DataSource) {
        configure(dataSource).migrate()
    }
}
