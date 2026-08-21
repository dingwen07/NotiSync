package net.extrawdw.apps.notisync.sshagent

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.testsupport.RoomStorageTestContext
import net.extrawdw.apps.notisync.testsupport.initializeOperationalTestDatabase
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SshKeyProviderDatabaseTest {
    private val context by lazy {
        RoomStorageTestContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "ssh-key-provider",
        )
    }
    private val databaseFile: File get() = context.getDatabasePath(DATABASE_NAME)
    private var store: SshKeyProviderStore? = null

    @Before
    fun clearDatabase() {
        context.deleteDatabase(DATABASE_NAME)
        initializeOperationalTestDatabase(context)
    }

    @After
    fun closeDatabase() {
        store?.close()
        store = null
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun newDatabaseUsesReleaseSchemaOne() {
        store = SshKeyProviderStore(context)
        val database = requireNotNull(store).readableDatabase
        assertEquals(1, database.version)
        val columns = database.rawQuery("PRAGMA table_info(provider_requests)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertTrue("history_cbor" in columns)
        assertTrue("history_nonce" in columns)
        assertTrue(database.hasTable("ssh_remembered_authorizations"))
        assertTrue(database.hasTable("ssh_known_hosts"))
        assertFalse(database.hasTable("remember_rules"))
        assertThrows(SQLiteConstraintException::class.java) {
            database.execSQL(
                """
                INSERT INTO provider_requests(
                  request_id, kind, requester_client_id, request_fingerprint,
                  request_cbor, request_nonce, history_cbor, history_nonce,
                  state, outcome, result_at, updated_at
                ) VALUES (?, 'IMPORT', 'requester', ?, ?, ?, ?, ?, 'SENT', 'IMPORTED', 1, 1)
                """.trimIndent(),
                arrayOf("terminal-request", byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4), byteArrayOf(5)),
            )
        }
    }

    @Test
    fun knownHostHostnameIsStoredAsAnUnvalidatedString() {
        store = SshKeyProviderStore(context)
        val hostKeySha256 = ByteArray(32) { it.toByte() }
        requireNotNull(store).writableDatabase.execSQL(
            "INSERT INTO ssh_known_hosts(host_key_sha256, hostname, first_approved_at, last_approved_at) " +
                "VALUES (?, NULL, 1, 1)",
            arrayOf(hostKeySha256),
        )
        val hostname = "not a DNS hostname / deliberately unvalidated\n"

        assertTrue(requireNotNull(store).updateKnownHostHostname(hostKeySha256, hostname))
        assertEquals(hostname, requireNotNull(store).knownHostHostname(hostKeySha256))
    }

    @Test
    fun knownHostEntryCanBeDeleted() {
        store = SshKeyProviderStore(context)
        val hostKeySha256 = ByteArray(32) { it.toByte() }
        requireNotNull(store).writableDatabase.execSQL(
            "INSERT INTO ssh_known_hosts(host_key_sha256, hostname, first_approved_at, last_approved_at) " +
                "VALUES (?, 'development host', 1, 1)",
            arrayOf(hostKeySha256),
        )

        assertTrue(requireNotNull(store).deleteKnownHost(hostKeySha256))
        assertFalse(requireNotNull(store).deleteKnownHost(hostKeySha256))
        assertEquals(null, requireNotNull(store).knownHostHostname(hostKeySha256))
    }

    @Test
    fun rememberedPeerAndHostAuthorizationsPersistButProcessScopeCannotReachDisk() {
        store = SshKeyProviderStore(context)
        val database = requireNotNull(store).writableDatabase
        database.execSQL(
            "INSERT INTO ssh_keys(provider_key_id, public_blob, public_hash, algorithm, display_name, origin, " +
                "approval_policy, created_at) VALUES ('key', ?, ?, 'SSH_ED25519', 'Test', 'GENERATED', " +
                "'ALLOW_REMEMBER', 1)",
            arrayOf(byteArrayOf(1), byteArrayOf(2)),
        )
        database.execSQL(
            "INSERT INTO ssh_remembered_authorizations(authorization_id, provider_key_id, requester_client_id, " +
                "authorization_generation, authorization_epoch, scope, host_key_sha256, created_at) " +
                "VALUES ('peer', 'key', 'requester', 'generation', 1, 'PEER', NULL, 1)",
        )
        database.execSQL(
            "INSERT INTO ssh_remembered_authorizations(authorization_id, provider_key_id, requester_client_id, " +
                "authorization_generation, authorization_epoch, scope, host_key_sha256, created_at) " +
                "VALUES ('host', 'key', 'requester', 'generation', 1, 'PEER_HOST_KEY', ?, 1)",
            arrayOf(ByteArray(32) { it.toByte() }),
        )
        database.execSQL(
            "INSERT INTO ssh_known_hosts(host_key_sha256, hostname, first_approved_at, last_approved_at) " +
                "VALUES (?, 'build host', 1, 1)",
            arrayOf(ByteArray(32) { it.toByte() }),
        )
        assertThrows(SQLiteConstraintException::class.java) {
            database.execSQL(
                "INSERT INTO ssh_remembered_authorizations(authorization_id, provider_key_id, requester_client_id, " +
                    "authorization_generation, authorization_epoch, scope, host_key_sha256, created_at) " +
                    "VALUES ('process', 'key', 'requester', 'generation', 1, 'APPLICATION_PROCESS', NULL, 1)",
            )
        }
        store?.close()
        store = SshKeyProviderStore(context)

        val persisted = requireNotNull(store).readableDatabase.rawQuery(
            "SELECT scope FROM ssh_remembered_authorizations ORDER BY scope",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        assertEquals(listOf("PEER", "PEER_HOST_KEY"), persisted)
        val authorizations = requireNotNull(store).rememberedAuthorizations()
        assertEquals(listOf("host", "peer"), authorizations.map { it.authorizationId }.sorted())
        assertEquals("build host", authorizations.single { it.authorizationId == "host" }.hostname)
        assertTrue(requireNotNull(store).deleteRememberedAuthorization("host"))
        assertFalse(requireNotNull(store).deleteRememberedAuthorization("host"))
        assertEquals(listOf("peer"), requireNotNull(store).rememberedAuthorizations().map { it.authorizationId })
    }

    @Test
    fun switchingToAlwaysAskPreservesRememberedAuthorizations() {
        store = SshKeyProviderStore(context)
        val database = requireNotNull(store).writableDatabase
        database.execSQL(
            "INSERT INTO ssh_keys(provider_key_id, public_blob, public_hash, algorithm, display_name, origin, " +
                "approval_policy, created_at) VALUES ('key', ?, ?, 'SSH_ED25519', 'Test', 'GENERATED', " +
                "'ALLOW_REMEMBER', 1)",
            arrayOf(byteArrayOf(1), byteArrayOf(2)),
        )
        database.execSQL(
            "INSERT INTO ssh_remembered_authorizations(authorization_id, provider_key_id, requester_client_id, " +
                "authorization_generation, authorization_epoch, scope, host_key_sha256, created_at) " +
                "VALUES ('peer', 'key', 'requester', 'generation', 1, 'PEER', NULL, 1)",
        )

        assertTrue(
            requireNotNull(store).updateKeyMetadata(
                "key",
                "Test",
                SshApprovalPolicy.ALWAYS_ASK,
            ),
        )
        assertEquals(listOf("peer"), requireNotNull(store).rememberedAuthorizations().map { it.authorizationId })

        assertTrue(
            requireNotNull(store).updateKeyMetadata(
                "key",
                "Test",
                SshApprovalPolicy.ALLOW_REMEMBER,
            ),
        )
        assertEquals(listOf("peer"), requireNotNull(store).rememberedAuthorizations().map { it.authorizationId })
    }

    @Test
    fun newerDatabaseFailsClosedWithoutDeletingOrRewritingIt() {
        createMarkerDatabase(version = 2)

        store = SshKeyProviderStore(context)
        assertThrows(IllegalStateException::class.java) { requireNotNull(store).readableDatabase }
        store?.close()
        store = null

        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            assertEquals(2, database.version)
            database.rawQuery("SELECT value FROM release_marker", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("preserve-me", cursor.getString(0))
            }
        }
    }

    @Test
    fun sameVersionWithWrongTablesFailsClosedWithoutResettingIt() {
        createMarkerDatabase(version = 1)

        store = SshKeyProviderStore(context)
        assertThrows(IllegalStateException::class.java) { requireNotNull(store).readableDatabase }
        store?.close()
        store = null

        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            assertEquals(1, database.version)
            database.rawQuery("SELECT value FROM release_marker", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("preserve-me", cursor.getString(0))
            }
        }
    }

    private fun createMarkerDatabase(version: Int) {
        context.deleteDatabase(DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            database.execSQL("CREATE TABLE release_marker(value TEXT NOT NULL)")
            database.execSQL("INSERT INTO release_marker(value) VALUES ('preserve-me')")
            database.version = version
        }
    }

    private fun SQLiteDatabase.hasTable(name: String): Boolean = rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    private companion object {
        const val DATABASE_NAME = OperationalDatabase.DATABASE_NAME
    }
}
