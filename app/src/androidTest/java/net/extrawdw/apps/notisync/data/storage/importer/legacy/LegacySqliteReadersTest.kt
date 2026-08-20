package net.extrawdw.apps.notisync.data.storage.importer.legacy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * These tests build disposable source copies.  They never open an app-owned legacy database and
 * deliberately keep the WAL writer open while a reader takes its snapshot.
 */
@RunWith(AndroidJUnit4::class)
class LegacySqliteReadersTest {
    private val roots = mutableListOf<File>()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun messageLedgerReadsCommittedWalRowsWithoutMutatingSourceAndSkipsPayloads() {
        val file = tempFile(LegacySourceId.MESSAGE_LEDGER.fileName)
        val writer = open(file)
        try {
            writer.enableWriteAheadLogging()
            createMessageLedgerSchema(writer)
            writer.execSQL("PRAGMA user_version = 2")
            writer.beginTransactionNonExclusive()
            writer.insertOrThrow(
                "dedup",
                null,
                ContentValues().apply {
                    put("message_id", "message-1")
                    put("handled_at", 10L)
                },
            )
            writer.insertOrThrow(
                "pending_ack",
                null,
                ContentValues().apply {
                    put("message_id", "ack-1")
                    put("queued_at", 11L)
                },
            )
            writer.insertOrThrow(
                "relay_inbox",
                null,
                ContentValues().apply {
                    put("message_id", "relay-1")
                    put("envelope", byteArrayOf(7, 8, 9))
                    put("accepted_at", 12L)
                    put("delivery_mode", "RELAY_DRAIN")
                    put("received_at", 13L)
                    put("early_ack", 0)
                },
            )
            writer.setTransactionSuccessful()
            writer.endTransaction()

            val beforeVersion = pragma(writer, "user_version")
            val beforeDedup = count(writer, "dedup")
            val snapshot = collectMessageLedger(file)

            assertEquals(1, snapshot.dedup.size)
            assertEquals("message-1", snapshot.dedup.single().messageId)
            assertEquals(1L, snapshot.evidence.skippedPendingAckCount)
            assertEquals(1L, snapshot.evidence.skippedRelayInboxCount)
            assertTrue(snapshot.metadata.none { row -> row.name == "envelope" })
            assertEquals(beforeVersion, pragma(writer, "user_version"))
            assertEquals(beforeDedup, count(writer, "dedup"))
        } finally {
            writer.close()
        }
    }

    @Test
    fun messageLedgerDigestAndOrderingDoNotDependOnInsertOrder() {
        val first = tempFile(LegacySourceId.MESSAGE_LEDGER.fileName)
        val second = tempFile(LegacySourceId.MESSAGE_LEDGER.fileName)
        createMessageLedger(first, listOf("b", "a"))
        createMessageLedger(second, listOf("a", "b"))

        val left = collectMessageLedger(first)
        val right = collectMessageLedger(second)
        assertArrayEquals(left.evidence.digests.contentDigest, right.evidence.digests.contentDigest)
        assertArrayEquals(left.evidence.digests.logicalFingerprint, right.evidence.digests.logicalFingerprint)
        assertEquals(listOf("a", "b"), left.dedup.map { it.messageId })
    }

    @Test
    fun unsupportedVersionAndExactSchemaAreRejected() {
        val wrongVersion = tempFile(LegacySourceId.MESSAGE_LEDGER.fileName)
        val versionWriter = open(wrongVersion)
        try {
            createMessageLedgerSchema(versionWriter)
            versionWriter.execSQL("PRAGMA user_version = 99")
        } finally {
            versionWriter.close()
        }
        val versionFailure = assertThrows(LegacyImportException::class.java) {
            LegacyMessageLedgerV2Reader().stream(wrongVersion)
        }
        assertEquals(LegacyFailureKind.UNSUPPORTED_VERSION, versionFailure.kind)

        val wrongSchema = tempFile(LegacySourceId.MESSAGE_LEDGER.fileName)
        val schemaWriter = open(wrongSchema)
        try {
            createMessageLedgerSchema(schemaWriter, extraDedupColumn = true)
            schemaWriter.execSQL("PRAGMA user_version = 2")
        } finally {
            schemaWriter.close()
        }
        val schemaFailure = assertThrows(LegacyImportException::class.java) {
            LegacyMessageLedgerV2Reader().stream(wrongSchema)
        }
        assertEquals(LegacyFailureKind.SCHEMA_MISMATCH, schemaFailure.kind)
    }

    @Test
    fun filenameIsPartOfTheSourceContract() {
        val file = tempFile("not-the-legacy-name.db")
        val failure = assertThrows(LegacyImportException::class.java) {
            LegacyMessageLedgerV2Reader().stream(file)
        }
        assertEquals(LegacyFailureKind.FILENAME_MISMATCH, failure.kind)
    }

    @Test
    fun malformedRunAndProjectionMismatchAreClassifiedWithoutImport() {
        val malformed = tempFile(LegacySourceId.RUNS.fileName)
        createRuns(malformed, payload = byteArrayOf(1, 2, 3))
        val malformedFailure = assertThrows(LegacyImportException::class.java) {
            LegacyRunsV2Reader().stream(
                malformed,
                LegacyRunsV2Reader.MAX_LEGACY_RUN_PAYLOAD_BYTES,
            ) { }
        }
        assertEquals(LegacyFailureKind.MALFORMED_ROW, malformedFailure.kind)

        val state = validRunState()
        val mismatch = tempFile(LegacySourceId.RUNS.fileName)
        createRuns(
            mismatch,
            payload = ProtocolCodec.encodeToCbor(state),
            hostClient = "different-host",
        )
        val mismatchFailure = assertThrows(LegacyImportException::class.java) {
            LegacyRunsV2Reader().stream(
                mismatch,
                LegacyRunsV2Reader.MAX_LEGACY_RUN_PAYLOAD_BYTES,
            ) { }
        }
        assertEquals(LegacyFailureKind.MALFORMED_ROW, mismatchFailure.kind)
    }

    @Test
    fun messageLedgerRejectsOversizedTextBeforeAnyRetainedRowReachesTheSink() {
        val file = tempFile(LegacySourceId.MESSAGE_LEDGER.fileName)
        val writer = open(file)
        try {
            createMessageLedgerSchema(writer)
            writer.execSQL("PRAGMA user_version = 2")
            writer.insertOrThrow(
                "dedup",
                null,
                ContentValues().apply {
                    put("message_id", "x".repeat(LegacyMessageLedgerV2Reader.MAX_LEGACY_TEXT_BYTES.toInt() + 1))
                    put("handled_at", 1L)
                },
            )
        } finally {
            writer.close()
        }
        var sinkCalls = 0
        val failure = assertThrows(LegacyImportException::class.java) {
            LegacyMessageLedgerV2Reader().stream(
                file,
                sink = object : LegacyMessageLedgerRowSink by LegacyMessageLedgerRowSink.DISCARD {
                    override fun acceptDedup(row: LegacyDedupRow) {
                        sinkCalls++
                    }
                },
            )
        }

        assertEquals(LegacyFailureKind.MALFORMED_ROW, failure.kind)
        assertEquals(0, sinkCalls)
    }

    private fun createMessageLedger(file: File, ids: List<String>) {
        val writer = open(file)
        try {
            createMessageLedgerSchema(writer)
            writer.execSQL("PRAGMA user_version = 2")
            ids.forEach { id ->
                writer.insertOrThrow(
                    "dedup",
                    null,
                    ContentValues().apply {
                        put("message_id", id)
                        put("handled_at", if (id == "a") 1L else 2L)
                    },
                )
            }
        } finally {
            writer.close()
        }
    }

    private fun collectMessageLedger(file: File): CollectedMessageLedger {
        val dedup = mutableListOf<LegacyDedupRow>()
        val mirrorMessages = mutableListOf<LegacyMirrorMessageRow>()
        val mirrorLifecycles = mutableListOf<LegacyMirrorLifecycleRow>()
        val metadata = mutableListOf<LegacyMessageMetaRow>()
        val evidence = LegacyMessageLedgerV2Reader().stream(
            file,
            sink = object : LegacyMessageLedgerRowSink {
                override fun acceptDedup(row: LegacyDedupRow) {
                    dedup += row
                }

                override fun acceptMirrorMessage(row: LegacyMirrorMessageRow) {
                    mirrorMessages += row
                }

                override fun acceptMirrorLifecycle(row: LegacyMirrorLifecycleRow) {
                    mirrorLifecycles += row
                }

                override fun acceptMetadata(row: LegacyMessageMetaRow) {
                    metadata += row
                }
            },
        )
        return CollectedMessageLedger(dedup, mirrorMessages, mirrorLifecycles, metadata, evidence)
    }

    private data class CollectedMessageLedger(
        val dedup: List<LegacyDedupRow>,
        val mirrorMessages: List<LegacyMirrorMessageRow>,
        val mirrorLifecycles: List<LegacyMirrorLifecycleRow>,
        val metadata: List<LegacyMessageMetaRow>,
        val evidence: LegacyMessageLedgerStreamEvidence,
    )

    private fun createMessageLedgerSchema(database: SQLiteDatabase, extraDedupColumn: Boolean = false) {
        database.execSQL(
            if (extraDedupColumn) {
                "CREATE TABLE dedup(message_id TEXT PRIMARY KEY, handled_at INTEGER NOT NULL, extra TEXT)"
            } else {
                "CREATE TABLE dedup(message_id TEXT PRIMARY KEY, handled_at INTEGER NOT NULL)"
            },
        )
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

    private fun createRuns(file: File, payload: ByteArray, hostClient: String = "host") {
        val writer = open(file)
        try {
            writer.execSQL(
                "CREATE TABLE runs(host_client TEXT NOT NULL, run_id TEXT NOT NULL, revision INTEGER NOT NULL, " +
                    "presented_revision INTEGER NOT NULL, active INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
                    "ended_at INTEGER, received_at INTEGER NOT NULL, payload BLOB NOT NULL, " +
                    "PRIMARY KEY(host_client, run_id))",
            )
            writer.execSQL("CREATE INDEX runs_order_idx ON runs(active DESC, updated_at DESC)")
            writer.execSQL("CREATE INDEX runs_retention_idx ON runs(active, received_at)")
            writer.execSQL("PRAGMA user_version = 2")
            writer.insertOrThrow(
                "runs",
                null,
                ContentValues().apply {
                    put("host_client", hostClient)
                    put("run_id", "run-1")
                    put("revision", 1L)
                    put("presented_revision", -1L)
                    put("active", 1)
                    put("updated_at", 2L)
                    putNull("ended_at")
                    put("received_at", 3L)
                    put("payload", payload)
                },
            )
        } finally {
            writer.close()
        }
    }

    private fun validRunState() = RunState(
        hostClientId = ClientId("host"),
        runId = "run-1",
        revision = 1,
        phase = RunPhase.RUNNING,
        updateReason = RunUpdateReason.INITIAL,
        startedAt = 1,
        updatedAt = 2,
        argv = listOf("echo", "ok"),
        cwd = "/tmp",
        usesPty = false,
        terminal = RunTerminalSnapshot(text = "", truncated = false, rawBytesSeen = 0),
    )

    private fun open(file: File): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)

    private fun tempFile(name: String): File {
        val root = File(context.cacheDir, "legacy-import-${UUID.randomUUID()}").also { it.mkdirs() }
        roots += root
        return File(root, name)
    }

    private fun count(database: SQLiteDatabase, table: String): Long = database.rawQuery(
        "SELECT COUNT(*) FROM $table",
        emptyArray(),
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getLong(0)
    }

    private fun pragma(database: SQLiteDatabase, name: String): Int = database.rawQuery(
        "PRAGMA $name",
        emptyArray(),
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }
}
