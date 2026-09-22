package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.run.StoredRun
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunBlockedReason
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import net.extrawdw.notisync.protocol.RunLlmSummary
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunProgress
import net.extrawdw.notisync.protocol.RunPromptKind
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RunStorageMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = context.getDatabasePath(DATABASE_NAME),
        driver = AndroidSQLiteDriver(),
        databaseClass = OperationalDatabase::class,
    )

    @After
    fun deleteDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun preservesCompleteAvailableSnapshotsAndLocalStateWithoutFabricatingEarlierRevisions() = runBlocking {
        val blocked = blockedState("blocked")
        val completed = completedState("completed")
        val failed = completed.copy(
            runId = "failed",
            phase = RunPhase.FAILED_TO_START,
            updateReason = RunUpdateReason.FAILED,
            durationMs = null,
            exitCode = null,
            failureMessage = "Could not start\nPermission denied",
        )
        val indeterminate = blocked.copy(runId = "indeterminate", progress = RunProgress(indeterminate = true))
        val states = listOf(blocked, completed, failed, indeterminate)
        migrationHelper.createDatabase(4).use { connection ->
            states.forEachIndexed { index, state ->
                // A locally dismissed active remote state is valid and must stay dismissed.
                connection.insertLegacy(state, receivedAt = 9_000L + index, presentedRevision = 3, active = false)
            }
        }
        migrationHelper.runMigrationsAndValidate(
            version = 5, migrations = listOf(OperationalDatabase.MIGRATION_4_5),
        ).use { connection ->
            states.forEachIndexed { index, state ->
                val receivedAt = 9_000L + index
                connection.assertValues(
                    "runs", "run_id", state.runId,
                    RunStateStorage.sessionValues(StoredRun(state, receivedAt, presentedRevision = 3, active = false)),
                )
                connection.assertValues(
                    "run_revisions", "run_id", state.runId,
                    RunStateStorage.revisionValues(state, receivedAt),
                )
            }
            assertEquals(states.size.toLong(), connection.count("runs"))
            assertEquals(states.size.toLong(), connection.count("run_revisions"))
            assertFalse("payload" in connection.columns("runs"))
            assertFalse("payload" in connection.columns("run_revisions"))
            assertFalse("runs_v4" in connection.tables())
            connection.execSQL("PRAGMA foreign_keys=ON")
            connection.execSQL("DELETE FROM runs WHERE run_id='blocked'")
            assertEquals(states.size - 1L, connection.count("run_revisions"))
        }
    }

    @Test
    fun skipsUndecodableOrInconsistentRunsAndKeepsHealthyRows() = runBlocking {
        val healthy = blockedState("healthy")
        migrationHelper.createDatabase(4).use { connection ->
            connection.insertLegacy(healthy, presentedRevision = -1, active = true)
            connection.insertLegacy(blockedState("bad-cbor"), payload = byteArrayOf(0))
            connection.insertLegacy(blockedState("wrong-host"))
            connection.execSQL("UPDATE runs SET host_client='other-host' WHERE run_id='wrong-host'")
            connection.insertLegacy(blockedState("wrong-revision"))
            connection.execSQL("UPDATE runs SET revision=revision+1 WHERE run_id='wrong-revision'")
            connection.insertLegacy(blockedState("wrong-time"))
            connection.execSQL("UPDATE runs SET updated_at=updated_at+1 WHERE run_id='wrong-time'")
            connection.insertLegacy(completedState("wrong-end"))
            connection.execSQL("UPDATE runs SET ended_at=ended_at+1 WHERE run_id='wrong-end'")
            connection.insertLegacy(blockedState("future-checkpoint"), presentedRevision = healthy.revision + 1)
            connection.insertLegacy(completedState("active-terminal"), active = true)
            connection.insertLegacy(blockedState("bad-active"))
            connection.execSQL("UPDATE runs SET active=2 WHERE run_id='bad-active'")
            connection.insertLegacy(blockedState("negative-receipt"), receivedAt = -1)
        }
        migrationHelper.runMigrationsAndValidate(
            version = 5, migrations = listOf(OperationalDatabase.MIGRATION_4_5),
        ).use { connection ->
            assertEquals(1L, connection.count("runs"))
            assertEquals(1L, connection.count("run_revisions"))
            connection.assertValues(
                "runs", "run_id", "healthy",
                RunStateStorage.sessionValues(StoredRun(healthy, RECEIVED_AT, -1, true)),
            )
            connection.assertValues(
                "run_revisions", "run_id", "healthy", RunStateStorage.revisionValues(healthy, RECEIVED_AT),
            )
            assertFalse("runs_v4" in connection.tables())
        }
    }

    @Test
    fun migratesExactControlFieldsAndOrderWithoutRequiringLocalRun() = runBlocking {
        val controls = listOf(
            RunControl(REQUEST_IDS[0], ClientId("host"), "remote-only", RunControlKind.REFRESH, 1_000),
            RunControl(
                REQUEST_IDS[1], ClientId("host"), "remote-only", RunControlKind.WRITE_INPUT, 1_000,
                interactionGeneration = 0, inputText = "",
            ),
            RunControl(
                REQUEST_IDS[2], ClientId("host"), "remote-only", RunControlKind.WRITE_INPUT, 1_001,
                interactionGeneration = 9_000_000_000, inputText = "批准 ✅\nsecond line\n",
            ),
            RunControl(
                REQUEST_IDS[3], ClientId("host"), "remote-only", RunControlKind.SIGNAL, 1_002,
                signal = "RTMIN+1",
            ),
        )
        migrationHelper.createDatabase(4).use { connection ->
            controls.reversed().forEach { connection.insertLegacy(it) }
        }
        migrationHelper.runMigrationsAndValidate(
            version = 5, migrations = listOf(OperationalDatabase.MIGRATION_4_5),
        ).use { connection ->
            assertEquals(0L, connection.count("runs"))
            assertEquals(controls.size.toLong(), connection.count("run_controls"))
            controls.forEach { control ->
                connection.assertValues("run_controls", "request_id", control.requestId, RunControlStorage.values(control))
            }
            connection.prepare("SELECT request_id FROM run_controls ORDER BY requested_at,request_id").use { row ->
                controls.forEach { control ->
                    assertTrue(row.step())
                    assertEquals(control.requestId, row.getText(0))
                }
                assertFalse(row.step())
            }
            assertFalse("controls" in connection.tables())
            assertFalse("controls_v4" in connection.tables())
            assertFalse("payload" in connection.columns("run_controls"))
        }
    }

    @Test
    fun skipsMalformedAndMismatchedControlsWhileKeepingValidQueueEntry() = runBlocking {
        val valid = RunControl(REQUEST_IDS[0], ClientId("host"), "run", RunControlKind.REFRESH, 1_000)
        migrationHelper.createDatabase(4).use { connection ->
            connection.insertLegacy(valid)
            connection.insertLegacy(valid.copy(requestId = REQUEST_IDS[1]), payload = byteArrayOf(0))
            connection.insertLegacy(valid.copy(requestId = REQUEST_IDS[2]), requestId = REQUEST_IDS[3])
            connection.insertLegacy(valid.copy(requestId = REQUEST_IDS[4]), requestedAt = valid.requestedAt + 1)
        }
        migrationHelper.runMigrationsAndValidate(
            version = 5, migrations = listOf(OperationalDatabase.MIGRATION_4_5),
        ).use { connection ->
            assertEquals(1L, connection.count("run_controls"))
            connection.assertValues("run_controls", "request_id", valid.requestId, RunControlStorage.values(valid))
        }
    }

    private fun blockedState(id: String) = RunState(
        hostClientId = ClientId("host"),
        runId = id,
        revision = 7,
        phase = RunPhase.BLOCKED,
        updateReason = RunUpdateReason.BLOCKED,
        startedAt = 1_000,
        updatedAt = 2_000,
        argv = listOf("/usr/bin/tool", "--title", "测试 arguments", "", "quoted \"value\""),
        cwd = "/home/user/测试 workspace",
        usesPty = true,
        terminal = RunTerminalSnapshot("Compiling…\nContinue?\n", truncated = true, rawBytesSeen = 9_000_000_000),
        interactionGeneration = 5_000_000_000,
        blockedReason = RunBlockedReason.TERMINAL_INPUT,
        prompt = RunPromptKind.YES_NO,
        progress = RunProgress(current = 2, total = 5),
        llmSummary = RunLlmSummary("Needs approval", "The command is waiting.\nApprove to continue.", "Full explanation\n第二行"),
        responseToRequestId = REQUEST_IDS[0],
    )

    private fun completedState(id: String) = blockedState(id).copy(
        phase = RunPhase.COMPLETED,
        updateReason = RunUpdateReason.COMPLETED,
        endedAt = 1_900,
        durationMs = 900,
        blockedReason = null,
        prompt = null,
        progress = null,
        exitCode = -1,
    )

    private fun SQLiteConnection.insertLegacy(
        state: RunState,
        receivedAt: Long = RECEIVED_AT,
        presentedRevision: Long = -1,
        active: Boolean = state.phase == RunPhase.RUNNING || state.phase == RunPhase.BLOCKED,
        payload: ByteArray = ProtocolCodec.encodeToCbor(state),
    ) {
        prepare("INSERT INTO runs VALUES (?,?,?,?,?,?,?,?,?)").use { insert ->
            insert.bindText(1, state.hostClientId.value)
            insert.bindText(2, state.runId)
            insert.bindLong(3, state.revision)
            insert.bindLong(4, presentedRevision)
            insert.bindLong(5, if (active) 1 else 0)
            insert.bindLong(6, state.updatedAt)
            state.endedAt?.let { insert.bindLong(7, it) } ?: insert.bindNull(7)
            insert.bindLong(8, receivedAt)
            insert.bindBlob(9, payload)
            insert.step()
        }
    }

    private fun SQLiteConnection.insertLegacy(
        control: RunControl,
        requestId: String = control.requestId,
        requestedAt: Long = control.requestedAt,
        payload: ByteArray = ProtocolCodec.encodeToCbor(control),
    ) {
        prepare("INSERT INTO controls VALUES (?,?,?)").use { insert ->
            insert.bindText(1, requestId)
            insert.bindLong(2, requestedAt)
            insert.bindBlob(3, payload)
            insert.step()
        }
    }

    private fun SQLiteConnection.assertValues(table: String, key: String, id: String, expected: Map<String, Any?>) {
        prepare("SELECT ${expected.keys.joinToString(",")} FROM $table WHERE $key=?").use { row ->
            row.bindText(1, id)
            assertTrue("Missing $table row", row.step())
            expected.entries.forEachIndexed { index, (column, value) ->
                when (value) {
                    null -> assertTrue("$table.$column must be null", row.isNull(index))
                    is String -> assertEquals("$table.$column", value, row.getText(index))
                    is Number -> assertEquals("$table.$column", value.toLong(), row.getLong(index))
                    else -> error("Unexpected fixture value")
                }
            }
            assertFalse(row.step())
        }
    }

    private fun SQLiteConnection.count(table: String): Long = prepare("SELECT COUNT(*) FROM $table").use {
        check(it.step())
        it.getLong(0)
    }

    private fun SQLiteConnection.columns(table: String): List<String> = prepare("PRAGMA table_info($table)").use { row ->
        buildList { while (row.step()) add(row.getText(1)) }
    }

    private fun SQLiteConnection.tables(): List<String> = prepare("SELECT name FROM sqlite_master WHERE type='table'").use { row ->
        buildList { while (row.step()) add(row.getText(0)) }
    }

    companion object {
        private const val DATABASE_NAME = "run-storage-migration-test.db"
        private const val RECEIVED_AT = 9_000L
        private val REQUEST_IDS = (0..4).map { "10000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
    }
}
