package net.extrawdw.apps.notisync.data.storage.importer.legacy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.ProtocolCodec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable v51 Seal source fixtures.  No app-owned database or preference file is opened. */
@RunWith(AndroidJUnit4::class)
class SealV3AndEnrollmentReadersTest {
    private val roots = mutableListOf<File>()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun committedWalRowsAreVisiblePendingWorkIsCountedAndSourcePayloadsRemainUntouched() {
        val file = tempFile(LegacySourceId.OPENPGP_SIGNING.fileName)
        val writer = open(file)
        val privatePayload = "raw-commit-payload-never-selected".encodeToByteArray()
        val privateResponse = "provider-signature-response-never-selected".encodeToByteArray()
        try {
            writer.enableWriteAheadLogging()
            createSealSchema(writer)
            writer.execSQL("PRAGMA user_version = 3")
            insertSealRow(
                writer,
                requestId = "0123456789abcdef0123456789abcdef",
                state = "SENT",
                result = "APPROVED",
                payload = privatePayload,
                encodedResponse = privateResponse,
                commitDetails = validDisplay(),
            )
            insertSealRow(
                writer,
                requestId = "1123456789abcdef0123456789abcdef",
                state = "FAILED",
                result = "FAILED",
                payload = byteArrayOf(1, 2, 3),
                encodedResponse = byteArrayOf(4, 5, 6),
                commitDetails = byteArrayOf(7, 8, 9),
            )
            insertSealRow(
                writer,
                requestId = "2123456789abcdef0123456789abcdef",
                state = "PENDING_REVIEW",
                result = null,
                payload = privatePayload,
                encodedResponse = privateResponse,
                commitDetails = byteArrayOf(10, 11),
            )
            insertSealRow(
                writer,
                requestId = "3123456789abcdef0123456789abcdef",
                state = "SIGNED_PENDING_SEND",
                result = "APPROVED",
                payload = privatePayload,
                encodedResponse = privateResponse,
                commitDetails = byteArrayOf(12, 13),
            )

            val beforePayload = blob(writer, "0123456789abcdef0123456789abcdef", "payload")
            val beforeResponse = blob(writer, "0123456789abcdef0123456789abcdef", "encoded_response")
            val snapshot = LegacySealV3Reader().read(file)

            assertEquals(
                listOf("0123456789abcdef0123456789abcdef", "1123456789abcdef0123456789abcdef"),
                snapshot.terminalRows.map { it.requestId },
            )
            assertEquals(1L, snapshot.skippedActivePendingCount)
            assertEquals(1L, snapshot.skippedResponsePendingCount)
            assertEquals(1L, snapshot.malformedDisplayCount)
            assertEquals("89ABCDEF01234567", snapshot.terminalRows.first().primaryKeyId)
            assertEquals("/repo", snapshot.terminalRows.first().workingDirectory)
            assertEquals(40, snapshot.terminalRows.first().commit?.treeId?.length)
            assertArrayEquals(beforePayload, blob(writer, "0123456789abcdef0123456789abcdef", "payload"))
            assertArrayEquals(beforeResponse, blob(writer, "0123456789abcdef0123456789abcdef", "encoded_response"))
            assertEquals(4L, count(writer, "sign_requests"))
            assertEquals(3, pragma(writer, "user_version"))
            assertTrue(snapshot.terminalRows.none { row ->
                row.toString().contains("raw-commit-payload") ||
                    row.toString().contains("provider-signature-response")
            })
        } finally {
            writer.close()
        }
    }

    @Test
    fun sealDigestAndTerminalOrderingAreIndependentOfInsertOrder() {
        val first = tempFile(LegacySourceId.OPENPGP_SIGNING.fileName)
        val second = tempFile(LegacySourceId.OPENPGP_SIGNING.fileName)
        createSealFixture(first, listOf("1123456789abcdef0123456789abcdef", "0123456789abcdef0123456789abcdef"))
        createSealFixture(second, listOf("0123456789abcdef0123456789abcdef", "1123456789abcdef0123456789abcdef"))

        val left = LegacySealV3Reader().read(first)
        val right = LegacySealV3Reader().read(second)

        assertArrayEquals(left.digests.contentDigest, right.digests.contentDigest)
        assertArrayEquals(left.digests.logicalFingerprint, right.digests.logicalFingerprint)
        assertEquals(
            listOf("0123456789abcdef0123456789abcdef", "1123456789abcdef0123456789abcdef"),
            left.terminalRows.map { it.requestId },
        )
    }

    @Test
    fun shippedSentCanceledPairNormalizesToCanonicalCancellation() {
        val file = tempFile(LegacySourceId.OPENPGP_SIGNING.fileName)
        val writer = open(file)
        try {
            createSealSchema(writer)
            writer.execSQL("PRAGMA user_version = 3")
            insertSealRow(
                writer,
                requestId = "0123456789abcdef0123456789abcdef",
                state = "SENT",
                result = "CANCELED",
                payload = byteArrayOf(1),
                encodedResponse = null,
                commitDetails = validDisplay(),
            )
        } finally {
            writer.close()
        }

        val row = LegacySealV3Reader().read(file).terminalRows.single()

        assertEquals(LegacySealRequestState.CANCELLED, row.state)
        assertEquals(LegacySealHistoryOutcome.CANCELED, row.outcome)
    }

    @Test
    fun exactVersionAndIndexContractRejectsUnexpectedSealSource() {
        val wrongVersion = tempFile(LegacySourceId.OPENPGP_SIGNING.fileName)
        val versionWriter = open(wrongVersion)
        try {
            createSealSchema(versionWriter)
            versionWriter.execSQL("PRAGMA user_version = 2")
        } finally {
            versionWriter.close()
        }
        val versionFailure = assertThrows(LegacyImportException::class.java) {
            LegacySealV3Reader().read(wrongVersion)
        }
        assertEquals(LegacyFailureKind.UNSUPPORTED_VERSION, versionFailure.kind)

        val wrongIndex = tempFile(LegacySourceId.OPENPGP_SIGNING.fileName)
        val indexWriter = open(wrongIndex)
        try {
            createSealSchema(indexWriter)
            indexWriter.execSQL("CREATE INDEX unexpected_idx ON sign_requests(request_id)")
            indexWriter.execSQL("PRAGMA user_version = 3")
        } finally {
            indexWriter.close()
        }
        val indexFailure = assertThrows(LegacyImportException::class.java) {
            LegacySealV3Reader().read(wrongIndex)
        }
        assertEquals(LegacyFailureKind.SCHEMA_MISMATCH, indexFailure.kind)
    }

    @Test
    fun sealFilenameIsPartOfTheSourceContract() {
        val file = tempFile("not-openpgp-signing.db")

        val failure = assertThrows(LegacyImportException::class.java) {
            LegacySealV3Reader().read(file)
        }

        assertEquals(LegacyFailureKind.FILENAME_MISMATCH, failure.kind)
    }

    @Test
    fun malformedTerminalIdentityIsRejectedWithSecretFreeDiagnostic() {
        val file = tempFile(LegacySourceId.OPENPGP_SIGNING.fileName)
        val writer = open(file)
        try {
            createSealSchema(writer)
            writer.execSQL("PRAGMA user_version = 3")
            insertSealRow(
                writer,
                requestId = "0123456789abcdef0123456789abcdef",
                state = "SENT",
                result = "APPROVED",
                requester = "private-requester",
                sender = "different-sender",
                payload = "secret-payload".encodeToByteArray(),
                encodedResponse = "secret-response".encodeToByteArray(),
                commitDetails = null,
            )
        } finally {
            writer.close()
        }

        val failure = assertThrows(LegacyImportException::class.java) {
            LegacySealV3Reader().read(file)
        }
        assertEquals(LegacyFailureKind.MALFORMED_ROW, failure.kind)
        assertFalse(failure.message.orEmpty().contains("private-requester"))
        assertFalse(failure.message.orEmpty().contains("secret-payload"))
        assertFalse(failure.message.orEmpty().contains("secret-response"))
    }

    @Test
    fun oldDisplaySnapshotIsBoundedAndKeepsCommitMessageLineBreaks() {
        val file = tempFile(LegacySourceId.OPENPGP_SIGNING.fileName)
        val writer = open(file)
        try {
            createSealSchema(writer)
            writer.execSQL("PRAGMA user_version = 3")
            insertSealRow(
                writer,
                requestId = "0123456789abcdef0123456789abcdef",
                state = "SENT",
                result = "APPROVED",
                payload = byteArrayOf(1),
                encodedResponse = null,
                commitDetails = validDisplay("line one\nline two\n" + "x".repeat(16 * 1_024)),
            )
        } finally {
            writer.close()
        }

        val commit = LegacySealV3Reader().read(file).terminalRows.single().commit

        assertTrue(commit?.truncated == true)
        assertEquals(16 * 1_024, commit?.message?.length)
        assertTrue(commit?.message?.startsWith("line one\nline two\n") == true)
    }

    @Test
    fun terminalHistoryCountAboveShippedLimitBlocksBeforeDisplaySelection() {
        val file = tempFile(LegacySourceId.OPENPGP_SIGNING.fileName)
        val writer = open(file)
        try {
            createSealSchema(writer)
            writer.execSQL("PRAGMA user_version = 3")
            writer.beginTransaction()
            try {
                repeat(501) { ordinal ->
                    insertSealRow(
                        writer,
                        requestId = ordinal.toString(16).padStart(32, '0'),
                        state = "SENT",
                        result = "APPROVED",
                        payload = byteArrayOf(1),
                        encodedResponse = null,
                        // Deliberately malformed. The row-count gate must win before any display decode.
                        commitDetails = byteArrayOf(0x7f),
                    )
                }
                writer.setTransactionSuccessful()
            } finally {
                writer.endTransaction()
            }
        } finally {
            writer.close()
        }

        val failure = assertThrows(LegacyImportException::class.java) {
            LegacySealV3Reader().read(file)
        }

        assertEquals(LegacyFailureKind.SCHEMA_MISMATCH, failure.kind)
        assertTrue(failure.message.orEmpty().contains("terminal Seal history exceeds"))
    }

    private fun createSealFixture(file: File, requestIds: List<String>) {
        val writer = open(file)
        try {
            createSealSchema(writer)
            writer.execSQL("PRAGMA user_version = 3")
            requestIds.forEach { id ->
                insertSealRow(
                    writer,
                    requestId = id,
                    state = "SENT",
                    result = "APPROVED",
                    payload = byteArrayOf(1, 2, 3),
                    encodedResponse = null,
                    commitDetails = validDisplay(),
                )
            }
        } finally {
            writer.close()
        }
    }

    private fun createSealSchema(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE sign_requests(" +
                "request_id TEXT PRIMARY KEY," +
                "requester_client_id TEXT NOT NULL," +
                "sender_client_id TEXT NOT NULL," +
                "primary_key_id TEXT NOT NULL," +
                "issued_at INTEGER NOT NULL," +
                "expires_at INTEGER NOT NULL," +
                "payload_sha256 BLOB NOT NULL," +
                "object_kind TEXT NOT NULL," +
                "payload BLOB," +
                "state TEXT NOT NULL," +
                "encoded_response BLOB," +
                "updated_at INTEGER NOT NULL," +
                "commit_details BLOB," +
                "result TEXT," +
                "working_directory TEXT)",
        )
        database.execSQL("CREATE INDEX sign_requests_state_idx ON sign_requests(state, updated_at)")
        database.execSQL("CREATE INDEX sign_requests_sender_idx ON sign_requests(sender_client_id, state)")
    }

    private fun insertSealRow(
        database: SQLiteDatabase,
        requestId: String,
        state: String,
        result: String?,
        payload: ByteArray,
        encodedResponse: ByteArray?,
        commitDetails: ByteArray?,
        requester: String = "desktop",
        sender: String = requester,
    ) {
        database.insertOrThrow(
            "sign_requests",
            null,
            ContentValues().apply {
                put("request_id", requestId)
                put("requester_client_id", requester)
                put("sender_client_id", sender)
                put("primary_key_id", "89ABCDEF01234567")
                put("issued_at", 1_000L)
                put("expires_at", 2_000L)
                put("payload_sha256", ByteArray(32) { it.toByte() })
                put("object_kind", OpenPgpObjectKind.GIT_COMMIT.name)
                put("payload", payload)
                put("state", state)
                if (encodedResponse == null) putNull("encoded_response") else put("encoded_response", encodedResponse)
                put("updated_at", 1_100L)
                if (commitDetails == null) putNull("commit_details") else put("commit_details", commitDetails)
                if (result == null) putNull("result") else put("result", result)
                put("working_directory", "/repo")
            },
        )
    }

    private fun validDisplay(message: String = "Signed commit"): ByteArray = ProtocolCodec.encodeToCbor(
        LegacyGitCommitDisplaySnapshotV51(
            treeId = "0123456789abcdef0123456789abcdef01234567",
            parentIds = listOf("1123456789abcdef0123456789abcdef01234567"),
            author = "Alice <alice@example.test>",
            committer = "Alice <alice@example.test>",
            message = message,
            extraHeaders = emptyList(),
            payloadBytes = 128,
        ),
    )

    private fun open(file: File): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null)

    private fun tempFile(name: String): File {
        val root = File(context.cacheDir, "legacy-seal-${UUID.randomUUID()}").also { it.mkdirs() }
        roots += root
        return File(root, name)
    }

    private fun blob(database: SQLiteDatabase, requestId: String, column: String): ByteArray = database.rawQuery(
        "SELECT $column FROM sign_requests WHERE request_id = ?",
        arrayOf(requestId),
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getBlob(0).copyOf()
    }

    private fun count(database: SQLiteDatabase, table: String): Long = database.rawQuery(
        "SELECT COUNT(*) FROM $table",
        emptyArray(),
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun pragma(database: SQLiteDatabase, name: String): Int = database.rawQuery(
        "PRAGMA $name",
        emptyArray(),
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }
}
