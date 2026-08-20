package net.extrawdw.apps.notisync.data.storage.importer.coordinator

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyRunsV2Reader
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportFailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportCommand
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportFailure
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalRebuildIdentity
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RUN_TERMINAL_MAX_UTF8_BYTES
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyStagingSourceAdaptersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val roots = mutableListOf<File>()

    @After
    fun tearDown() {
        roots.forEach(File::deleteRecursively)
    }

    @Test
    fun stagingCleanupCanNeverTargetAReadOnlyLegacySourceOrItsSidecars() {
        val fixture = newFixture()
        val runsSource = File(fixture.noBackupDirectory, LegacyRunsStagingSourceAdapter.STAGING_FILE_NAME)
        val ledgerSource = File(
            fixture.noBackupDirectory,
            LegacyMessageLedgerSourceAdapter.STAGING_FILE_NAME + "-wal",
        )

        assertTrue(
            runCatching {
                LegacyRunsStagingSourceAdapter(runsSource, fixture.noBackupDirectory)
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                LegacyMessageLedgerSourceAdapter(ledgerSource, fixture.noBackupDirectory)
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun runsStageSeesWalRowsAndReturnsDeterministicByteLimitedPages() = runBlocking {
        val fixture = newFixture()
        val source = File(fixture.sourceDirectory, "runs.db")
        val writer = SQLiteDatabase.openOrCreateDatabase(source, null)
        val payloads = mutableListOf<ByteArray>()
        try {
            writer.enableWriteAheadLogging()
            createRunsSchema(writer)
            writer.execSQL("PRAGMA user_version = 2")
            writer.beginTransactionNonExclusive()
            try {
                repeat(20) { ordinal ->
                    val state = largeRunState(ordinal)
                    val payload = ProtocolCodec.encodeToCbor(state)
                    payloads += payload
                    insertRun(writer, state, payload, receivedAt = 10_000L + ordinal)
                }
                writer.setTransactionSuccessful()
            } finally {
                writer.endTransaction()
            }
            assertTrue(File(source.path + "-wal").length() > 0)

            val adapter = LegacyRunsStagingSourceAdapter(
                source,
                fixture.noBackupDirectory,
                usableSpace = { Long.MAX_VALUE },
            )
            val snapshot = adapter.load(IDENTITY)
            val first = snapshot.commands(0, snapshot.source.batchSize)
            val firstBytes = first.sumOf { command ->
                (command as OperationalImportCommand.RunState).withBorrowedPayload { payload, _ -> payload.size.toLong() }
            }

            assertEquals(20L, snapshot.commandCount)
            assertTrue(first.isNotEmpty())
            assertTrue(first.size < 20)
            assertTrue(firstBytes <= LegacyRunsStagingSourceAdapter.MAX_PAGE_PAYLOAD_BYTES)

            var ordinal = 0L
            var pageCount = 0
            while (ordinal < snapshot.commandCount) {
                val page = snapshot.commands(ordinal, snapshot.source.batchSize)
                assertTrue(page.isNotEmpty())
                ordinal += page.size
                pageCount++
            }
            assertEquals(20L, ordinal)
            assertTrue(pageCount > 1)
            assertEquals(20L, count(writer, "runs"))

            val stage = File(fixture.noBackupDirectory, LegacyRunsStagingSourceAdapter.STAGING_FILE_NAME)
            assertTrue(stage.isFile)
            val restarted = LegacyRunsStagingSourceAdapter(
                source,
                fixture.noBackupDirectory,
                usableSpace = { Long.MAX_VALUE },
            ).load(IDENTITY)
            assertEquals(20L, restarted.commandCount)

            adapter.cleanupAfterAttempt()
            assertFalse(stage.exists())
        } finally {
            writer.close()
            payloads.forEach { it.fill(0) }
        }
    }

    @Test
    fun runsStageDiscardsPayloadDigestProjectionAndOrdinalCorruption() = runBlocking {
        val fixture = newFixture()
        val source = File(fixture.sourceDirectory, "runs.db")
        val writer = SQLiteDatabase.openOrCreateDatabase(source, null)
        val state = smallRunState(0)
        val sourcePayload = ProtocolCodec.encodeToCbor(state)
        try {
            createRunsSchema(writer)
            writer.execSQL("PRAGMA user_version = 2")
            insertRun(writer, state, sourcePayload, receivedAt = 10)
        } finally {
            writer.close()
        }
        val adapter = LegacyRunsStagingSourceAdapter(
            source,
            fixture.noBackupDirectory,
            usableSpace = { Long.MAX_VALUE },
        )
        adapter.load(IDENTITY)
        val stage = File(fixture.noBackupDirectory, LegacyRunsStagingSourceAdapter.STAGING_FILE_NAME)

        mutate(stage) { database ->
            val changed = byteArrayOf(1, 2, 3, 4)
            database.execSQL(
                "UPDATE stage_runs SET payload = ?, payload_digest = ? WHERE ordinal = 0",
                arrayOf(changed, MessageDigest.getInstance("SHA-256").digest(changed)),
            )
        }
        assertArrayEquals(sourcePayload, firstRunPayload(adapter.load(IDENTITY)))

        mutate(stage) { database -> database.execSQL("UPDATE stage_runs SET revision = 999 WHERE ordinal = 0") }
        assertEquals(1L, firstRun(adapter.load(IDENTITY)).revision)

        mutate(stage) { database -> database.execSQL("UPDATE stage_runs SET ordinal = 9 WHERE ordinal = 0") }
        assertEquals(1L, adapter.load(IDENTITY).commandCount)
    }

    @Test
    fun runsStageReportsLowSpaceBeforeSourcePayloadMaterialization() = runBlocking {
        val fixture = newFixture()
        val source = File(fixture.sourceDirectory, "runs.db")
        SQLiteDatabase.openOrCreateDatabase(source, null).use { writer ->
            createRunsSchema(writer)
            writer.execSQL("PRAGMA user_version = 2")
            val state = smallRunState(0)
            insertRun(writer, state, ProtocolCodec.encodeToCbor(state), receivedAt = 10)
        }
        val adapter = LegacyRunsStagingSourceAdapter(
            source,
            fixture.noBackupDirectory,
            usableSpace = { 0 },
        )

        val failure = expectImportFailure { adapter.load(IDENTITY) }

        assertEquals(ImportFailureDisposition.RETRYABLE, failure.disposition)
        assertEquals("run_stage_space_insufficient", failure.errorCode)
    }

    @Test
    fun runsReaderCancellationStopsInsidePinnedStream() {
        val fixture = newFixture()
        val source = File(fixture.sourceDirectory, "runs.db")
        SQLiteDatabase.openOrCreateDatabase(source, null).use { writer ->
            createRunsSchema(writer)
            writer.execSQL("PRAGMA user_version = 2")
            repeat(3) { ordinal ->
                val state = smallRunState(ordinal)
                insertRun(writer, state, ProtocolCodec.encodeToCbor(state), receivedAt = 10L + ordinal)
            }
        }
        var checks = 0

        val failure = try {
            LegacyRunsV2Reader().stream(
                source,
                LegacyRunsV2Reader.MAX_LEGACY_RUN_PAYLOAD_BYTES,
                checkCancelled = {
                    checks++
                    if (checks == 2) throw CancellationException("fixture cancellation")
                },
            ) { }
            fail("expected cancellation")
            null
        } catch (failure: net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyImportException) {
            failure
        }

        assertTrue(failure?.cause is CancellationException)
    }

    @Test
    fun messageLedgerStageStreamsWalRowsAndNeverStagesSkippedPayloads() = runBlocking {
        val fixture = newFixture()
        val source = File(fixture.sourceDirectory, "message_ledger.db")
        val writer = SQLiteDatabase.openOrCreateDatabase(source, null)
        try {
            writer.enableWriteAheadLogging()
            createMessageLedgerSchema(writer)
            writer.execSQL("PRAGMA user_version = 2")
            writer.beginTransactionNonExclusive()
            try {
                repeat(300) { ordinal ->
                    writer.insertOrThrow(
                        "dedup",
                        null,
                        ContentValues().apply {
                            put("message_id", "message-${ordinal.toString().padStart(4, '0')}")
                            put("handled_at", ordinal + 1L)
                        },
                    )
                }
                writer.insertOrThrow(
                    "mirror_msg",
                    null,
                    ContentValues().apply {
                        put("source_client", "legacy-source")
                        put("source_key", "deprecated-index")
                        put("message_id", "legacy-mirror-message")
                        put("recorded_at", 1L)
                    },
                )
                writer.insertOrThrow(
                    "mirror_lifecycle",
                    null,
                    ContentValues().apply {
                        put("source_client", "legacy-source")
                        put("source_key", "retained-lifecycle")
                        put("post_time", 2L)
                        put("dismissed_at", 3L)
                        put("updated_at", 3L)
                    },
                )
                writer.insertOrThrow(
                    "message_meta",
                    null,
                    ContentValues().apply {
                        put("name", "last_deferred_at")
                        put("long_value", 4L)
                    },
                )
                writer.insertOrThrow(
                    "pending_ack",
                    null,
                    ContentValues().apply {
                        put("message_id", "pending-ack")
                        put("queued_at", 1L)
                    },
                )
                writer.insertOrThrow(
                    "relay_inbox",
                    null,
                    ContentValues().apply {
                        put("message_id", "pending-relay")
                        put("envelope", ByteArray(2 * 1024 * 1024) { 7 })
                        put("accepted_at", 1L)
                        put("delivery_mode", "RELAY_DRAIN")
                        put("received_at", 2L)
                        put("early_ack", 0)
                    },
                )
                writer.setTransactionSuccessful()
            } finally {
                writer.endTransaction()
            }
            assertTrue(File(source.path + "-wal").length() > 0)

            val adapter = LegacyMessageLedgerSourceAdapter(
                source,
                fixture.noBackupDirectory,
                usableSpace = { Long.MAX_VALUE },
            )
            val snapshot = adapter.load(IDENTITY)

            assertEquals(301L, snapshot.commandCount)
            assertEquals(4L, snapshot.skippedRowCount)
            assertEquals(128, snapshot.commands(0, 128).size)
            assertTrue(snapshot.commands(300, 1).single() is OperationalImportCommand.MirrorLifecycle)
            assertEquals(300L, count(writer, "dedup"))
            val stage = File(fixture.noBackupDirectory, LegacyMessageLedgerSourceAdapter.STAGING_FILE_NAME)
            SQLiteDatabase.openDatabase(stage.path, null, SQLiteDatabase.OPEN_READONLY).use { staged ->
                val names = staged.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                    null,
                ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                assertFalse(names.any { it.contains("relay") || it.contains("ack") })
            }
        } finally {
            writer.close()
        }
    }

    @Test
    fun messageLedgerStageCorruptionIsDiscardedAndRebuiltFromSource() = runBlocking {
        val fixture = newFixture()
        val source = File(fixture.sourceDirectory, "message_ledger.db")
        SQLiteDatabase.openOrCreateDatabase(source, null).use { writer ->
            createMessageLedgerSchema(writer)
            writer.execSQL("PRAGMA user_version = 2")
            writer.insertOrThrow(
                "dedup",
                null,
                ContentValues().apply {
                    put("message_id", "source-message")
                    put("handled_at", 1L)
                },
            )
        }
        val adapter = LegacyMessageLedgerSourceAdapter(
            source,
            fixture.noBackupDirectory,
            usableSpace = { Long.MAX_VALUE },
        )
        adapter.load(IDENTITY)
        val stage = File(fixture.noBackupDirectory, LegacyMessageLedgerSourceAdapter.STAGING_FILE_NAME)
        mutate(stage) { database ->
            database.execSQL("UPDATE stage_dedup SET message_id = 'corrupt-message' WHERE ordinal = 0")
        }

        val rebuilt = adapter.load(IDENTITY)
        val command = rebuilt.commands(0, 1).single() as OperationalImportCommand.HandledMessageIdOnly

        assertEquals("source-message", command.messageId)
    }

    private fun newFixture(): Fixture {
        val root = File(context.noBackupFilesDir, "legacy-stage-test-${UUID.randomUUID()}").also { it.mkdirs() }
        roots += root
        return Fixture(
            sourceDirectory = File(root, "source").also { it.mkdirs() },
            noBackupDirectory = File(root, "stage").also { it.mkdirs() },
        )
    }

    private fun createRunsSchema(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE runs(host_client TEXT NOT NULL, run_id TEXT NOT NULL, revision INTEGER NOT NULL, " +
                "presented_revision INTEGER NOT NULL, active INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
                "ended_at INTEGER, received_at INTEGER NOT NULL, payload BLOB NOT NULL, " +
                "PRIMARY KEY(host_client, run_id))",
        )
        database.execSQL("CREATE INDEX runs_order_idx ON runs(active DESC, updated_at DESC)")
        database.execSQL("CREATE INDEX runs_retention_idx ON runs(active, received_at)")
    }

    private fun insertRun(database: SQLiteDatabase, state: RunState, payload: ByteArray, receivedAt: Long) {
        database.insertOrThrow(
            "runs",
            null,
            ContentValues().apply {
                put("host_client", state.hostClientId.value)
                put("run_id", state.runId)
                put("revision", state.revision)
                put("presented_revision", -1L)
                put("active", if (state.phase in setOf(RunPhase.RUNNING, RunPhase.BLOCKED)) 1 else 0)
                put("updated_at", state.updatedAt)
                state.endedAt?.let { put("ended_at", it) } ?: putNull("ended_at")
                put("received_at", receivedAt)
                put("payload", payload)
            },
        )
    }

    private fun largeRunState(ordinal: Int): RunState = RunState(
        hostClientId = ClientId("host"),
        runId = "run-${ordinal.toString().padStart(3, '0')}",
        revision = 1,
        phase = RunPhase.RUNNING,
        updateReason = RunUpdateReason.INITIAL,
        startedAt = 1,
        updatedAt = 2,
        argv = List(4) { "a".repeat(16 * 1024) },
        cwd = "/" + "c".repeat(16 * 1024 - 1),
        usesPty = true,
        terminal = RunTerminalSnapshot(
            "t".repeat(RUN_TERMINAL_MAX_UTF8_BYTES),
            truncated = true,
            rawBytesSeen = RUN_TERMINAL_MAX_UTF8_BYTES.toLong(),
        ),
    )

    private fun smallRunState(ordinal: Int): RunState = RunState(
        hostClientId = ClientId("host"),
        runId = "run-${ordinal.toString().padStart(3, '0')}",
        revision = 1,
        phase = RunPhase.RUNNING,
        updateReason = RunUpdateReason.INITIAL,
        startedAt = 1,
        updatedAt = 2,
        argv = listOf("echo", ordinal.toString()),
        cwd = "/tmp",
        usesPty = false,
        terminal = RunTerminalSnapshot("", truncated = false, rawBytesSeen = 0),
    )

    private fun createMessageLedgerSchema(database: SQLiteDatabase) {
        database.execSQL("CREATE TABLE dedup(message_id TEXT PRIMARY KEY, handled_at INTEGER NOT NULL)")
        database.execSQL("CREATE TABLE pending_ack(message_id TEXT PRIMARY KEY, queued_at INTEGER NOT NULL)")
        database.execSQL(
            "CREATE TABLE mirror_msg(source_client TEXT NOT NULL, source_key TEXT NOT NULL, " +
                "message_id TEXT NOT NULL, recorded_at INTEGER NOT NULL, PRIMARY KEY(source_client, source_key))",
        )
        database.execSQL(
            "CREATE TABLE relay_inbox(message_id TEXT PRIMARY KEY, envelope BLOB NOT NULL, accepted_at INTEGER NOT NULL, " +
                "delivery_mode TEXT NOT NULL, received_at INTEGER NOT NULL, early_ack INTEGER NOT NULL)",
        )
        database.execSQL("CREATE TABLE message_meta(name TEXT PRIMARY KEY, long_value INTEGER NOT NULL)")
        database.execSQL(
            "CREATE TABLE mirror_lifecycle(source_client TEXT NOT NULL, source_key TEXT NOT NULL, post_time INTEGER, " +
                "dismissed_at INTEGER, updated_at INTEGER NOT NULL, PRIMARY KEY(source_client, source_key))",
        )
        database.execSQL("CREATE INDEX relay_inbox_accepted_idx ON relay_inbox(accepted_at, received_at)")
    }

    private suspend fun firstRun(
        snapshot: net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSnapshot,
    ) =
        snapshot.commands(0, 1).single() as OperationalImportCommand.RunState

    private suspend fun firstRunPayload(
        snapshot: net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportSnapshot,
    ): ByteArray = firstRun(snapshot).withBorrowedPayload { payload, _ -> payload.copyOf() }

    private fun mutate(file: File, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { database -> block(database) }
    }

    private suspend fun expectImportFailure(block: suspend () -> Unit): OperationalImportFailure = try {
        block()
        fail("expected OperationalImportFailure")
        error("unreachable")
    } catch (failure: OperationalImportFailure) {
        failure
    }

    private fun count(database: SQLiteDatabase, table: String): Long = database.rawQuery(
        "SELECT COUNT(*) FROM $table",
        null,
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private data class Fixture(
        val sourceDirectory: File,
        val noBackupDirectory: File,
    )

    private companion object {
        val IDENTITY = OperationalRebuildIdentity(1, "staging-test", 1)
    }
}
