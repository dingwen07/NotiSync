package net.extrawdw.apps.notisync.data.storage.operational

import androidx.room3.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.*
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProcessContextMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = context.getDatabasePath(DATABASE_NAME),
        driver = SQLCipherDriver("process-context-test".encodeToByteArray(), null, null).also {
            System.loadLibrary("sqlcipher")
        },
        databaseClass = OperationalDatabase::class,
    )

    @After
    fun deleteDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun versionFiveContextsBecomeSharedJsonWithoutChangingRequestsOrResponses() = runBlocking {
        val contexts = listOf(
            DesktopProcessContext(
                DesktopProcessContextSource.PEER_CREDENTIALS,
                listOf(
                    DesktopProcessIdentity(42, "/usr/bin/ssh", "ssh", "alice", 501),
                    DesktopProcessIdentity(41, "/bin/bash", "bash", "root", 0),
                ),
                "12345678-abcd-1234-abcd-123456789abc",
            ),
            DesktopProcessContext(
                DesktopProcessContextSource.NAMED_PIPE_CLIENT_PID,
                listOf(DesktopProcessIdentity(
                    42, "C:\\Windows\\System32\\OpenSSH\\ssh.exe", "ssh", "DOMAIN\\alice",
                    sid = "S-1-5-21-100-200-300-1001",
                    windowsTokenInfo = DesktopWindowsTokenInfo(
                        true, DesktopWindowsElevationType.FULL, 12288, false,
                    ),
                )),
            ),
            DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE),
            null,
        )
        migrationHelper.createDatabase(5).use { connection ->
            contexts.forEachIndexed { index, process ->
                connection.prepare(
                    "INSERT INTO ssh_requests(request_id,kind,requester_client_id,request_fingerprint,state," +
                        "updated_at,request_complete,requested_at,expires_at,encrypted_import,payload_size," +
                        "process_lineage_json,process_source,process_boot_id,sign_data,response_signature_blob) " +
                        "VALUES (?,'SIGN','desktop',X'0102',?,1500,?,1000,2000,0,3,?,?,?,X'030405',X'0607')",
                ).use { insert ->
                    insert.bindText(1, index.toString())
                    insert.bindText(2, if (index == 0) "PENDING_REVIEW" else "SENT")
                    insert.bindLong(3, if (process == null) 0 else 1)
                    insert.bindText(4, ProtocolCodec.encodeToJson(
                        process?.processLineage ?: listOf(DesktopProcessIdentity(42, "/usr/bin/ssh")),
                    ))
                    if (process == null) insert.bindNull(5) else insert.bindText(5, process.source.name)
                    process?.bootId?.let { insert.bindText(6, it) } ?: insert.bindNull(6)
                    insert.step()
                }
            }
        }
        migrationHelper.runMigrationsAndValidate(
            version = 6, migrations = listOf(OperationalDatabase.MIGRATION_5_6),
        ).use { connection ->
            connection.prepare(
                "SELECT process_context_json,state,sign_data,response_signature_blob FROM ssh_requests ORDER BY request_id",
            ).use { row ->
                contexts.forEachIndexed { index, expected ->
                    assertTrue(row.step())
                    if (expected == null) assertTrue(row.isNull(0)) else {
                        assertEquals(expected, ProtocolCodec.decodeFromJson<DesktopProcessContext>(row.getText(0)))
                    }
                    assertEquals(if (index == 0) "PENDING_REVIEW" else "SENT", row.getText(1))
                    assertArrayEquals(byteArrayOf(3, 4, 5), row.getBlob(2))
                    assertArrayEquals(byteArrayOf(6, 7), row.getBlob(3))
                }
                assertFalse(row.step())
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "process-context-migration-test.db"
    }
}
