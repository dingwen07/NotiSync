package net.extrawdw.apps.notisync.data.storage.operational

import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import net.extrawdw.apps.notisync.run.StoredRun
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState

/** The legacy table retained only the latest snapshot; earlier revisions cannot be recovered. */
internal fun migrateRuns4To5(connection: SQLiteConnection) {
    connection.execSQL("ALTER TABLE runs RENAME TO runs_v4")
    connection.execSQL("DROP INDEX runs_order_idx")
    connection.execSQL("DROP INDEX runs_retention_idx")
    connection.execSQL(
        """
        CREATE TABLE runs (
            host_client TEXT NOT NULL,
            run_id TEXT NOT NULL,
            current_revision INTEGER NOT NULL,
            presented_revision INTEGER NOT NULL,
            active INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            received_at INTEGER NOT NULL,
            PRIMARY KEY(host_client, run_id)
        )
        """.trimIndent(),
    )
    connection.execSQL("CREATE INDEX runs_order_idx ON runs(active DESC, updated_at DESC, host_client ASC, run_id ASC)")
    connection.execSQL("CREATE INDEX runs_retention_idx ON runs(active, received_at)")
    connection.execSQL(
        """
        CREATE TABLE run_revisions (
            host_client TEXT NOT NULL,
            run_id TEXT NOT NULL,
            revision INTEGER NOT NULL,
            phase TEXT NOT NULL,
            update_reason TEXT NOT NULL,
            started_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            argv_json TEXT NOT NULL,
            cwd TEXT NOT NULL,
            uses_pty INTEGER NOT NULL,
            terminal_text TEXT NOT NULL,
            terminal_truncated INTEGER NOT NULL,
            terminal_raw_bytes_seen INTEGER NOT NULL,
            interaction_generation INTEGER NOT NULL,
            ended_at INTEGER,
            duration_ms INTEGER,
            blocked_reason TEXT,
            prompt TEXT,
            progress_current INTEGER,
            progress_total INTEGER,
            progress_indeterminate INTEGER,
            exit_code INTEGER,
            failure_message TEXT,
            llm_title TEXT,
            llm_text TEXT,
            llm_expanded_text TEXT,
            response_to_request_id TEXT,
            received_at INTEGER NOT NULL,
            PRIMARY KEY(host_client, run_id, revision),
            FOREIGN KEY(host_client, run_id) REFERENCES runs(host_client, run_id)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    var droppedRows = 0
    connection.prepare(
        "SELECT host_client,run_id,revision,presented_revision,active,updated_at,ended_at," +
            "received_at,payload FROM runs_v4",
    ).use { row ->
        while (row.step()) {
            if (!connection.migrateRunRow {
                    val state = ProtocolCodec.decodeFromCbor<RunState>(row.getBlob(8))
                    check(state.hostClientId.value == row.getText(0) && state.runId == row.getText(1) &&
                        state.revision == row.getLong(2) && state.updatedAt == row.getLong(5) &&
                        state.endedAt == row.longOrNull(6)
                    ) { "Legacy Run metadata does not match its snapshot" }
                    val presentedRevision = row.getLong(3)
                    val active = row.getLong(4)
                    val receivedAt = row.getLong(7)
                    check(presentedRevision in -1L..state.revision && active in 0L..1L && receivedAt >= 0 &&
                        (active == 0L || state.phase == RunPhase.RUNNING || state.phase == RunPhase.BLOCKED)
                    ) {
                        "Invalid local Run state"
                    }
                    val stored = StoredRun(state, receivedAt, presentedRevision, active == 1L)
                    connection.insertRunValues("runs", RunStateStorage.sessionValues(stored))
                    connection.insertRunValues("run_revisions", RunStateStorage.revisionValues(state, receivedAt))
                }
            ) droppedRows++
        }
    }
    connection.execSQL("DROP TABLE runs_v4")
    if (droppedRows > 0) {
        Log.w("RunStorageMigration", "Dropped $droppedRows invalid legacy Run records")
    }
}

internal fun migrateRunControls4To5(connection: SQLiteConnection) {
    connection.execSQL("ALTER TABLE controls RENAME TO controls_v4")
    connection.execSQL("DROP INDEX controls_order_idx")
    connection.execSQL(
        """
        CREATE TABLE run_controls (
            request_id TEXT NOT NULL PRIMARY KEY,
            host_client TEXT NOT NULL,
            run_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            requested_at INTEGER NOT NULL,
            interaction_generation INTEGER,
            input_text TEXT,
            signal TEXT
        )
        """.trimIndent(),
    )
    connection.execSQL("CREATE INDEX run_controls_order_idx ON run_controls(requested_at, request_id)")
    var droppedRows = 0
    connection.prepare("SELECT request_id,requested_at,payload FROM controls_v4").use { row ->
        while (row.step()) {
            if (!connection.migrateRunRow {
                    val control = ProtocolCodec.decodeFromCbor<RunControl>(row.getBlob(2))
                    check(control.requestId == row.getText(0) && control.requestedAt == row.getLong(1)) {
                        "Legacy Run control metadata does not match its payload"
                    }
                    connection.insertRunValues("run_controls", RunControlStorage.values(control))
                }
            ) droppedRows++
        }
    }
    connection.execSQL("DROP TABLE controls_v4")
    if (droppedRows > 0) {
        Log.w("RunStorageMigration", "Dropped $droppedRows invalid legacy Run control records")
    }
}

private inline fun SQLiteConnection.migrateRunRow(migrate: () -> Unit): Boolean {
    execSQL("SAVEPOINT run_migration_row")
    try {
        migrate()
        execSQL("RELEASE SAVEPOINT run_migration_row")
        return true
    } catch (failure: Exception) {
        execSQL("ROLLBACK TO SAVEPOINT run_migration_row")
        execSQL("RELEASE SAVEPOINT run_migration_row")
        // Invalid legacy data must not block startup. SQLite I/O, capacity and schema failures
        // still propagate; dropping records cannot resolve those failures safely.
        if (failure !is IllegalArgumentException && failure !is IllegalStateException) throw failure
        return false
    }
}

private fun SQLiteStatement.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

private fun SQLiteConnection.insertRunValues(table: String, values: Map<String, Any?>) {
    prepare(
        "INSERT INTO $table (${values.keys.joinToString(",")}) " +
            "VALUES (${values.keys.joinToString(",") { "?" }})",
    ).use { insert ->
        values.values.forEachIndexed { index, value ->
            when (value) {
                null -> insert.bindNull(index + 1)
                is String -> insert.bindText(index + 1, value)
                is Long -> insert.bindLong(index + 1, value)
                is Int -> insert.bindLong(index + 1, value.toLong())
                else -> error("Unsupported Run migration column type")
            }
        }
        insert.step()
    }
}
