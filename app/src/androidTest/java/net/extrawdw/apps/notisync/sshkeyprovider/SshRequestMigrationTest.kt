package net.extrawdw.apps.notisync.sshkeyprovider

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.File
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import net.extrawdw.apps.notisync.data.storage.operational.migrateSshRequests4To5
import net.extrawdw.apps.notisync.data.storage.operational.migrateSshAuthorizationScopes4To5
import net.extrawdw.notisync.protocol.*
import org.junit.Assert.*
import org.junit.Test

class SshRequestMigrationTest {
    @Test
    fun authorizationMigrationDropsNondurableScopesAndGuardsFutureWrites() = withLegacyDatabase { connection ->
        connection.execSQL("CREATE TABLE ssh_remembered_authorizations(authorization_id TEXT PRIMARY KEY, scope TEXT NOT NULL)")
        connection.execSQL("INSERT INTO ssh_remembered_authorizations VALUES ('peer', 'PEER'), " +
            "('host', 'PEER_HOST_KEY'), ('process', 'APPLICATION_PROCESS'), ('invalid', 'UNKNOWN')")
        migrateSshAuthorizationScopes4To5(connection)
        assertEquals(2L, count(connection, "ssh_remembered_authorizations"))
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            connection.execSQL("INSERT INTO ssh_remembered_authorizations VALUES ('new-process', 'APPLICATION_PROCESS')")
        }
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            connection.execSQL("UPDATE ssh_remembered_authorizations SET scope='APPLICATION_PROCESS' WHERE authorization_id='peer'")
        }
        connection.prepare("SELECT scope FROM ssh_remembered_authorizations ORDER BY scope").use {
            assertTrue(it.step()); assertEquals("PEER", it.getText(0))
            assertTrue(it.step()); assertEquals("PEER_HOST_KEY", it.getText(0))
        }
    }

    @Test
    fun realLegacyKeystoreEncryptionMigratesPendingRequestAndOutbox() = withLegacyDatabase { connection ->
        val alias = "notisync_ssh_audit_wrapping_v1"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // Never replace or delete the application's existing legacy key when running instrumentation.
        val key = keyStore.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
        val encrypt: (String, String, ByteArray) -> Pair<ByteArray, ByteArray> = { requestId, purpose, plaintext ->
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD("notisync:ssh-provider-audit:v1:$purpose:$requestId".encodeToByteArray())
                doFinal(plaintext) to iv
            }
        }
        insertLegacy(connection, "pending", SshProviderRequestState.PENDING_REVIEW, importRequest("pending"), encrypt)
        insertLegacy(connection, "outbox", SshProviderRequestState.RESPONSE_PENDING_SEND, null, encrypt)
        insertLegacy(connection, "corrupt", SshProviderRequestState.PENDING_REVIEW, importRequest("corrupt")) { id, purpose, bytes ->
            val (ciphertext, nonce) = encrypt(id, purpose, bytes)
            if (purpose == "history") ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
            ciphertext to nonce
        }
        insertLegacy(connection, "bad-nonce", SshProviderRequestState.PENDING_REVIEW, importRequest("bad-nonce"), encrypt)
        connection.execSQL("UPDATE provider_requests SET history_nonce=X'00' WHERE request_id='bad-nonce'")
        insertLegacy(connection, "short-ciphertext", SshProviderRequestState.PENDING_REVIEW, importRequest("short-ciphertext"), encrypt)
        connection.execSQL("UPDATE provider_requests SET history_cbor=X'01' WHERE request_id='short-ciphertext'")

        migrateSshRequests4To5(connection)

        assertEquals(2L, count(connection, "ssh_requests"))
        connection.prepare("SELECT import_file_bytes, suggested_name FROM ssh_requests WHERE request_id='pending'").use {
            assertTrue(it.step())
            assertArrayEquals(byteArrayOf(1, 2, 3), it.getBlob(0))
            assertEquals("Imported key", it.getText(1))
        }
        connection.prepare("SELECT response_kind, response_provider_client_id, result_at FROM ssh_requests WHERE request_id='outbox'").use {
            assertTrue(it.step())
            assertEquals(SshImportResultKind.USER_DECLINED.name, it.getText(0))
            assertEquals("provider", it.getText(1))
            assertEquals(1_500L, it.getLong(2))
        }
    }

    @Test
    fun migrationPreservesAllStatesHistoricalContextAndOtherTables() = withLegacyDatabase { connection ->
        connection.execSQL("CREATE TABLE ssh_keys(provider_key_id TEXT PRIMARY KEY, public_blob BLOB)")
        connection.execSQL("INSERT INTO ssh_keys VALUES ('retained-key', X'0102')")
        connection.execSQL("CREATE TABLE ssh_remembered_authorizations(authorization_id TEXT PRIMARY KEY)")
        connection.execSQL("INSERT INTO ssh_remembered_authorizations VALUES ('retained-grant')")
        SshProviderRequestState.entries.forEachIndexed { index, state ->
            insertLegacy(connection, index.toString(), state, if (state == SshProviderRequestState.PENDING_REVIEW) importRequest(index.toString()) else null)
        }

        migrateSshRequests4To5(connection) { _, _, ciphertext, _ -> ciphertext.copyOf() }

        connection.prepare("SELECT request_id, state, requested_at, destination_host, import_file_bytes, request_complete " +
            "FROM ssh_requests ORDER BY request_id").use { row ->
            SshProviderRequestState.entries.forEachIndexed { index, state ->
                assertTrue(row.step())
                assertEquals(index.toString(), row.getText(0))
                assertEquals(state.name, row.getText(1))
                assertEquals(1_000L, row.getLong(2))
                assertEquals("preserved.example", row.getText(3))
                if (state == SshProviderRequestState.PENDING_REVIEW) {
                    assertArrayEquals(byteArrayOf(1, 2, 3), row.getBlob(4))
                    assertEquals(1L, row.getLong(5))
                } else {
                    assertTrue(row.isNull(4))
                    assertEquals(0L, row.getLong(5))
                }
            }
            assertFalse(row.step())
        }
        assertEquals(1L, count(connection, "ssh_keys"))
        assertEquals(1L, count(connection, "ssh_remembered_authorizations"))
        assertEquals(SshProviderRequestState.entries.size.toLong(), lineageCount(connection))
        connection.prepare("SELECT name FROM sqlite_master WHERE name='provider_requests_legacy'").use {
            assertFalse(it.step())
        }
        connection.prepare("SELECT name FROM sqlite_master WHERE name LIKE 'provider_request_%'").use {
            assertFalse(it.step())
        }
    }

    @Test
    fun migratedTerminalImportNeverRetainsUnexpectedSurvivingPrivateSource() = withLegacyDatabase { connection ->
        insertLegacy(connection, "terminal", SshProviderRequestState.SENT, importRequest("terminal"))
        migrateSshRequests4To5(connection) { _, _, bytes, _ -> bytes.copyOf() }
        connection.prepare("SELECT import_file_bytes, import_agent_identity, request_complete, suggested_name FROM ssh_requests").use {
            assertTrue(it.step())
            assertTrue(it.isNull(0))
            assertTrue(it.isNull(1))
            assertEquals(0L, it.getLong(2))
            assertEquals("Imported key", it.getText(3))
        }
    }

    @Test
    fun failedRecordAuthenticationDropsOnlyInvalidRowAndMigratesValidRows() = withLegacyDatabase { connection ->
        insertLegacy(connection, "bad", SshProviderRequestState.RESPONSE_PENDING_SEND, importRequest("bad"))
        insertLegacy(connection, "valid", SshProviderRequestState.PENDING_REVIEW, importRequest("valid"))
        migrateSshRequests4To5(connection) { id, _, bytes, _ ->
            if (id == "bad") throw AEADBadTagException("Corrupted record") else bytes.copyOf()
        }
        assertEquals(1L, count(connection, "ssh_requests"))
        connection.prepare("SELECT request_id FROM ssh_requests").use {
            assertTrue(it.step())
            assertEquals("valid", it.getText(0))
        }
        assertEquals(1L, lineageCount(connection))
    }

    @Test
    fun keyLookupInitializationAndProviderFailuresDropAffectedRowsAndContinue() {
        val failures = listOf(
            UnrecoverableKeyException("Keystore lookup failed"),
            IllegalStateException("Legacy SSH review encryption key is unavailable"),
            KeyPermanentlyInvalidatedException("Keystore cipher initialization failed"),
            ProviderException("Keystore service is temporarily unavailable"),
        )
        for (failure in failures) withLegacyDatabase { connection ->
            insertLegacy(connection, "first", SshProviderRequestState.PENDING_REVIEW, importRequest("first"))
            insertLegacy(connection, "second", SshProviderRequestState.PENDING_REVIEW, importRequest("second"))
            insertLegacy(connection, "third", SshProviderRequestState.PENDING_REVIEW, importRequest("third"))
            migrateSshRequests4To5(connection) { id, _, bytes, _ ->
                if (id == "second") throw failure else bytes.copyOf()
            }
            assertEquals(2L, count(connection, "ssh_requests"))
            connection.prepare("SELECT request_id,import_file_bytes FROM ssh_requests ORDER BY request_id").use {
                for (id in listOf("first", "third")) {
                    assertTrue(it.step())
                    assertEquals(id, it.getText(0))
                    assertArrayEquals(byteArrayOf(1, 2, 3), it.getBlob(1))
                }
                assertFalse(it.step())
            }
            connection.prepare("SELECT name FROM sqlite_master WHERE name IN ('provider_requests', 'provider_requests_legacy')").use {
                assertFalse(it.step())
            }
        }
    }

    @Test
    fun missingLegacyKeyCanDropEveryUnreadableRowAndFinishMigration() = withLegacyDatabase { connection ->
        insertLegacy(connection, "pending", SshProviderRequestState.PENDING_REVIEW, importRequest("pending"))
        insertLegacy(connection, "outbox", SshProviderRequestState.RESPONSE_PENDING_SEND, null)
        migrateSshRequests4To5(connection) { _, _, _, _ ->
            error("Legacy SSH review encryption key is unavailable")
        }
        assertEquals(0L, count(connection, "ssh_requests"))
        connection.prepare("SELECT name FROM sqlite_master WHERE name IN ('provider_requests', 'provider_requests_legacy')").use {
            assertFalse(it.step())
        }
    }

    @Test
    fun malformedDecodedDataIsDroppedWithoutHidingHealthyRecords() = withLegacyDatabase { connection ->
        insertLegacy(connection, "bad", SshProviderRequestState.PENDING_REVIEW, importRequest("bad"))
        insertLegacy(connection, "valid", SshProviderRequestState.PENDING_REVIEW, importRequest("valid"))
        migrateSshRequests4To5(connection) { id, _, bytes, _ ->
            if (id == "bad") byteArrayOf(0) else bytes.copyOf()
        }
        assertEquals(1L, count(connection, "ssh_requests"))
        connection.prepare("SELECT request_id FROM ssh_requests").use {
            assertTrue(it.step())
            assertEquals("valid", it.getText(0))
        }
    }

    @Test
    fun mismatchedDecodedIdentityIsDroppedWithoutBlockingValidRequests() = withLegacyDatabase { connection ->
        insertLegacy(connection, "expected", SshProviderRequestState.PENDING_REVIEW, importRequest("different"))
        insertLegacy(connection, "valid", SshProviderRequestState.PENDING_REVIEW, importRequest("valid"))
        migrateSshRequests4To5(connection) { _, _, bytes, _ -> bytes.copyOf() }
        assertEquals(1L, count(connection, "ssh_requests"))
        connection.prepare("SELECT request_id FROM ssh_requests WHERE request_id='expected'").use {
            assertFalse(it.step())
        }
        assertEquals(1L, lineageCount(connection))
    }

    private fun withLegacyDatabase(block: (SQLiteConnection) -> Unit) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "ssh-migration-" + System.nanoTime() + ".db")
        try {
            AndroidSQLiteDriver().open(file.path).use { connection ->
                connection.execSQL("""
                    CREATE TABLE provider_requests(
                        request_id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, requester_client_id TEXT NOT NULL,
                        request_fingerprint BLOB NOT NULL, request_cbor BLOB, request_nonce BLOB,
                        history_cbor BLOB NOT NULL, history_nonce BLOB NOT NULL, state TEXT NOT NULL,
                        outcome TEXT, result_at INTEGER, response_cbor BLOB, response_nonce BLOB, updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
                block(connection)
            }
        } finally {
            android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
        }
    }

    private fun insertLegacy(
        connection: SQLiteConnection,
        id: String,
        state: SshProviderRequestState,
        request: SshImportRequest?,
        encrypt: (String, String, ByteArray) -> Pair<ByteArray, ByteArray> = { _, _, bytes -> bytes to ByteArray(12) },
    ) {
        val history = SshRequestHistorySnapshot(
            1_000, 2_000, keyName = "Imported key", suggestedName = "Imported key",
            importSourceType = SshImportSourceType.PRIVATE_KEY_FILE,
            processLineage = listOf(DesktopProcessIdentity(123, "/bin/ssh", "ssh")),
            destinationHost = "preserved.example", payloadSize = 3,
        )
        val encryptedRequest = request?.let { encrypt(id, "request", ProtocolCodec.encodeToCbor(it)) }
        val encryptedHistory = encrypt(id, "history", ProtocolCodec.encodeToCbor(history))
        val encryptedResponse = if (state == SshProviderRequestState.RESPONSE_PENDING_SEND) encrypt(id, "response",
            ProtocolCodec.encodeToCbor(SshImportResult(id, ClientId("desktop"), ClientId("provider"),
                1_500, SshImportResultKind.USER_DECLINED))) else null
        connection.prepare("INSERT INTO provider_requests(request_id, kind, requester_client_id, request_fingerprint, " +
            "request_cbor, request_nonce, history_cbor, history_nonce, state, updated_at, response_cbor, response_nonce, result_at) " +
            "VALUES (?, 'IMPORT', 'desktop', X'01', ?, ?, ?, ?, ?, 1000, ?, ?, ?)").use {
            it.bindText(1, id)
            if (encryptedRequest == null) { it.bindNull(2); it.bindNull(3) } else {
                it.bindBlob(2, encryptedRequest.first); it.bindBlob(3, encryptedRequest.second)
            }
            it.bindBlob(4, encryptedHistory.first)
            it.bindBlob(5, encryptedHistory.second)
            it.bindText(6, state.name)
            if (encryptedResponse == null) { it.bindNull(7); it.bindNull(8); it.bindNull(9) } else {
                it.bindBlob(7, encryptedResponse.first); it.bindBlob(8, encryptedResponse.second); it.bindLong(9, 1_500)
            }
            it.step()
        }
    }

    private fun importRequest(id: String) = SshImportRequest(
        id, ClientId("desktop"), 1_000, 2_000, SshImportSourceType.PRIVATE_KEY_FILE,
        fileBytes = byteArrayOf(1, 2, 3), suggestedName = "Imported key",
    )

    private fun lineageCount(connection: SQLiteConnection): Long = connection.prepare("SELECT process_lineage_json FROM ssh_requests").use { row ->
        var count = 0L
        while (row.step()) {
            val lineage = ProtocolCodec.decodeFromJson<List<DesktopProcessIdentity>>(row.getText(0))
            assertEquals(listOf(DesktopProcessIdentity(123, "/bin/ssh", "ssh")), lineage)
            count++
        }
        count
    }

    private fun count(connection: SQLiteConnection, table: String) = connection.prepare("SELECT COUNT(*) FROM $table").use {
        check(it.step())
        it.getLong(0)
    }
}
