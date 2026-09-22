package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import net.extrawdw.apps.notisync.seal.GitCommitDetails
import net.extrawdw.apps.notisync.seal.GitTagDetails
import net.extrawdw.apps.notisync.seal.toCommitDetails
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OpenPgpObjectKind
import net.extrawdw.notisync.protocol.OpenPgpSignAction
import net.extrawdw.notisync.protocol.OpenPgpSignSync
import net.extrawdw.notisync.protocol.ProtocolCodec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OpenPgpRequestMigrationTest {
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
    fun migrationRecoversFullCommitFromPayloadAndKeepsResponseInTypedColumns() = runBlocking {
        val message = "Untruncated body\n\n" + "b".repeat(20_000) + "\n"
        val payload = (
            "tree ${"f".repeat(40)}\nparent ${"1".repeat(40)}\nparent ${"2".repeat(40)}\n" +
                "author Example <example@example.com> 1700000000 +0000\n" +
                "committer Example <example@example.com> 1700000000 +0000\n" +
                "x-extra first\nx-extra second\n\n$message"
            ).encodeToByteArray()
        val response = OpenPgpSignSync(
            action = OpenPgpSignAction.RESULT,
            requestId = REQUEST_ID,
            requesterClientId = ClientId("desktop"),
            issuedAt = 1_000,
            expiresAt = 100_000,
            primaryKeyId = "89ABCDEF01234567",
            payloadSha256 = MessageDigest.getInstance("SHA-256").digest(payload),
            objectKind = OpenPgpObjectKind.GIT_COMMIT,
            signatureArmor = ARMOR,
            actionAt = 1_200,
        )
        migrationHelper.createDatabase(4).use { connection ->
            connection.insertLegacy(
                REQUEST_ID, "GIT_COMMIT", payload,
                ProtocolCodec.encodeToCbor(LegacyCommit(
                    "f".repeat(40), listOf("1".repeat(40)), "old author", "old committer", "truncated",
                    emptyList(), payload.size, true,
                )),
                ProtocolCodec.encodeToCbor(response), response.payloadSha256,
            )
        }
        migrationHelper.runMigrationsAndValidate(
            version = 5, migrations = listOf(OperationalDatabase.MIGRATION_4_5),
        ).use { connection ->
            connection.prepare(
                "SELECT payload,response_action,response_signature_armor,response_action_at," +
                    "legacy_details_json,summary_title,summary_reference,summary_identity FROM seal_requests",
            ).use { row ->
                assertTrue(row.step())
                assertArrayEquals(payload, row.getBlob(0))
                assertEquals("RESULT", row.getText(1))
                assertEquals(ARMOR, row.getText(2))
                assertEquals(1_200, row.getLong(3))
                assertTrue(row.isNull(4))
                assertEquals("Untruncated body", row.getText(5))
                assertEquals("1".repeat(40), row.getText(6))
                assertEquals("Example <example@example.com> 1700000000 +0000", row.getText(7))
                val commit = row.getBlob(0).toCommitDetails()
                assertEquals(message, commit.message)
                assertEquals(listOf("1".repeat(40), "2".repeat(40)), commit.parentIds)
                assertEquals(listOf("first", "second"), commit.extraHeaders.map { it.value })
                assertFalse(commit.legacyTruncated)
            }
            val columns = connection.prepare("PRAGMA table_info(seal_requests)").use { row ->
                buildSet { while (row.step()) add(row.getText(1)) }
            }
            assertFalse("commit_details" in columns)
            assertFalse("encoded_response" in columns)
            connection.prepare(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN " +
                    "('sign_requests','sign_commit_details','sign_commit_parents','sign_commit_headers','sign_tag_details')",
            ).use { row ->
                assertTrue(row.step())
                assertEquals(0L, row.getLong(0))
            }
        }
    }

    @Test
    fun migrationPreservesLegacyTagAndCommitDetailsWhenRawPayloadWasAlreadyErased() = runBlocking {
        migrationHelper.createDatabase(4).use { connection ->
            connection.insertLegacy(
                REQUEST_ID, "GIT_COMMIT", null,
                ProtocolCodec.encodeToCbor(LegacyCommit(
                    "f".repeat(40), listOf("1".repeat(40), "2".repeat(40)), "author", "committer",
                    "Saved message", listOf(LegacyHeader("x-note", "kept")), 80_000, true,
                )),
            )
            connection.insertLegacy(
                "2".repeat(32), "GIT_TAG", null,
                ProtocolCodec.encodeToCbor(LegacyTag(
                    "a".repeat(40), "commit", "v1", "tagger", "Release note", 50_000, true,
                )),
            )
        }
        migrationHelper.runMigrationsAndValidate(
            version = 5, migrations = listOf(OperationalDatabase.MIGRATION_4_5),
        ).use { connection ->
            connection.prepare("SELECT payload FROM seal_requests").use { row ->
                while (row.step()) assertTrue(row.isNull(0))
            }
            connection.prepare("SELECT legacy_details_json FROM seal_requests WHERE object_kind='GIT_COMMIT'").use { row ->
                assertTrue(row.step())
                val commit = ProtocolCodec.decodeFromJson<GitCommitDetails>(row.getText(0))
                assertEquals("Saved message", commit.message)
                assertEquals(80_000, commit.payloadBytes)
                assertTrue(commit.legacyTruncated)
                assertEquals(listOf("1".repeat(40), "2".repeat(40)), commit.parentIds)
                assertEquals("kept", commit.extraHeaders.single().value)
            }
            connection.prepare("SELECT legacy_details_json FROM seal_requests WHERE object_kind='GIT_TAG'").use { row ->
                assertTrue(row.step())
                val tag = ProtocolCodec.decodeFromJson<GitTagDetails>(row.getText(0))
                assertEquals("v1", tag.tagName)
                assertEquals("Release note", tag.message)
                assertEquals(50_000, tag.payloadBytes)
                assertTrue(tag.legacyTruncated)
            }
        }
    }

    @Test
    fun corruptLegacyDetailsAndIncompleteActiveRequestsAreDroppedWithoutBlockingHealthyRecords() = runBlocking {
        val invalidDetails = byteArrayOf(0xFF.toByte())
        val healthyId = "2".repeat(32)
        val invalidPendingId = "3".repeat(32)
        val invalidUtf8Payload = (
            "tree ${"f".repeat(40)}\n" +
                "author Example <example@example.com> 1700000000 +0000\n" +
                "committer Example <example@example.com> 1700000000 +0000\n\n"
            ).encodeToByteArray() + byteArrayOf(0xFF.toByte())
        migrationHelper.createDatabase(4).use { connection ->
            connection.insertLegacy(REQUEST_ID, "GIT_COMMIT", null, invalidDetails)
            connection.insertLegacy(invalidPendingId, "GIT_COMMIT", null, invalidDetails)
            connection.execSQL("UPDATE sign_requests SET state='PENDING_REVIEW' WHERE request_id='$invalidPendingId'")
            connection.insertLegacy(
                "4".repeat(32), "GIT_COMMIT", invalidUtf8Payload, invalidDetails,
                digest = MessageDigest.getInstance("SHA-256").digest(invalidUtf8Payload),
            )
            connection.insertLegacy(
                healthyId, "GIT_TAG", null,
                ProtocolCodec.encodeToCbor(LegacyTag(
                    "a".repeat(40), "commit", "v1", "tagger", "Healthy release", 100, false,
                )),
            )
        }
        migrationHelper.runMigrationsAndValidate(
            version = 5, migrations = listOf(OperationalDatabase.MIGRATION_4_5),
        ).use { connection ->
            connection.prepare("SELECT request_id FROM seal_requests").use { row ->
                assertTrue(row.step())
                assertEquals(healthyId, row.getText(0))
                assertFalse(row.step())
            }
            connection.prepare(
                "SELECT COUNT(*) FROM seal_requests WHERE state IN ('PENDING_REVIEW', 'SIGNED_PENDING_SEND', 'REJECTED_PENDING_SEND')",
            ).use { row ->
                assertTrue(row.step())
                assertEquals(0L, row.getLong(0))
            }
            connection.prepare("SELECT legacy_details_json FROM seal_requests").use { row ->
                assertTrue(row.step())
                assertEquals("Healthy release", ProtocolCodec.decodeFromJson<GitTagDetails>(row.getText(0)).message)
            }
        }
    }

    private fun SQLiteConnection.insertLegacy(
        id: String,
        kind: String,
        payload: ByteArray?,
        details: ByteArray,
        response: ByteArray? = null,
        digest: ByteArray = ByteArray(32),
    ) {
        prepare(
            "INSERT INTO sign_requests VALUES (?, 'desktop', 'desktop', '89ABCDEF01234567', 1000, 100000, ?, ?, ?, " +
                "'${if (response == null) "SENT" else "SIGNED_PENDING_SEND"}', ?, 1200, ?, 'APPROVED', '/repo')",
        ).use { insert ->
            insert.bindText(1, id)
            insert.bindBlob(2, digest)
            insert.bindText(3, kind)
            if (payload == null) insert.bindNull(4) else insert.bindBlob(4, payload)
            if (response == null) insert.bindNull(5) else insert.bindBlob(5, response)
            insert.bindBlob(6, details)
            insert.step()
        }
    }

    private companion object {
        const val DATABASE_NAME = "seal-request-migration-test.db"
        val REQUEST_ID = "1".repeat(32)
        const val ARMOR = "-----BEGIN PGP SIGNATURE-----\nfixture\n-----END PGP SIGNATURE-----\n"
    }
}

@Serializable
private data class LegacyCommit(
    val treeId: String, val parentIds: List<String>, val author: String, val committer: String,
    val message: String, val extraHeaders: List<LegacyHeader>, val payloadBytes: Int, val truncated: Boolean,
)

@Serializable
private data class LegacyHeader(val name: String, val value: String)

@Serializable
private data class LegacyTag(
    val objectId: String, val objectType: String, val tagName: String, val tagger: String,
    val message: String, val payloadBytes: Int, val truncated: Boolean,
)
