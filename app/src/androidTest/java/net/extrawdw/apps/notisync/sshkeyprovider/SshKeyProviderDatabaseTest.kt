package net.extrawdw.apps.notisync.sshkeyprovider

import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseEncryption
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import net.extrawdw.apps.notisync.data.HistoryDirection
import java.io.File
import java.security.MessageDigest
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseFactory
import net.extrawdw.apps.notisync.testsupport.RoomStorageTestContext
import net.extrawdw.apps.notisync.testsupport.initializeOperationalTestDatabase
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.SshApprovalPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import net.extrawdw.notisync.protocol.SshImportRequest
import net.extrawdw.notisync.protocol.SshImportSourceType
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull

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
        OperationalDatabaseFactory.close(context)
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun expiryWithoutFurtherTrafficUpdatesHistoryAndSurvivesReopenWithoutDeletingResponses() {
        store = SshKeyProviderStore(context)
        val provider = ClientId("provider")
        val pending = SshImportRequest(
            requestId = "1".repeat(32),
            requesterClientId = ClientId("requester"),
            requestedAt = 1_000,
            expiresAt = 2_000,
            sourceType = SshImportSourceType.PRIVATE_KEY_FILE,
            fileBytes = byteArrayOf(1, 2, 3),
        )
        val rejected = pending.copy(requestId = "2".repeat(32))
        val activeStore = requireNotNull(store)
        assertEquals(SshProviderAcceptResult.STORED, activeStore.acceptImport(pending, 1_100))
        assertEquals(SshProviderAcceptResult.STORED, activeStore.acceptImport(rejected, 1_100))
        assertTrue(activeStore.reject(rejected.requestId, provider, 1_200))
        val versionBeforeExpiry = activeStore.changeVersion.value

        assertTrue(activeStore.expireDue(pending.expiresAt).isEmpty())
        assertEquals(listOf(pending.requestId), activeStore.expireDue(pending.expiresAt + 1))
        assertTrue(activeStore.changeVersion.value > versionBeforeExpiry)
        val snapshot = activeStore.managementSnapshot(provider, pending.expiresAt + 1).snapshot
        assertFalse(snapshot.requests.any { it.requestId == pending.requestId })
        assertEquals(SshProviderRequestState.EXPIRED, activeStore.historyPage().items.first {
            it.requestId == pending.requestId
        }.state)
        val expired = activeStore.find(pending.requestId)
        assertNull(expired?.importRequest)
        assertEquals(SshProviderRequestOutcome.EXPIRED, expired?.outcome)
        assertEquals(2_000L, expired?.history?.expiresAt)
        assertTrue(activeStore.expireDue(pending.expiresAt + 2).isEmpty())

        activeStore.close()
        store = SshKeyProviderStore(context)
        assertEquals(SshProviderRequestState.EXPIRED, requireNotNull(store).find(pending.requestId)?.state)
        assertEquals(SshProviderRequestState.RESPONSE_PENDING_SEND, requireNotNull(store).find(rejected.requestId)?.state)
        assertNotNull(requireNotNull(store).find(rejected.requestId)?.encodedResponse)
    }

    @Test
    fun historyPaginationDoesNotDeleteRecordsAndCompletedImportsKeepResponsesWithoutPrivateSources() {
        store = SshKeyProviderStore(context)
        val activeStore = requireNotNull(store)
        val provider = ClientId("provider")
        repeat(505) { index ->
            val request = SshImportRequest(
                requestId = index.toString(16).padStart(32, '0'), requesterClientId = ClientId("desktop"),
                requestedAt = 1_000, expiresAt = 2_000, sourceType = SshImportSourceType.PRIVATE_KEY_FILE,
                fileBytes = byteArrayOf(1, 2, 3), suggestedName = "Import $index",
            )
            assertEquals(SshProviderAcceptResult.STORED, activeStore.acceptImport(request, 1_000))
            assertTrue(activeStore.reject(request.requestId, provider, 1_100))
            assertTrue(activeStore.markSent(request.requestId, 1_200L + index))
        }
        val firstPage = activeStore.historyPage()
        assertEquals(50, firstPage.items.size)
        var page = firstPage
        val retainedIds = page.items.map { it.requestId }.toMutableList()
        while (page.nextCursor != null) {
            page = activeStore.historyPage(cursor = page.nextCursor)
            assertTrue(page.items.size <= 50)
            retainedIds += page.items.map { it.requestId }
        }
        assertEquals(505, retainedIds.size)
        assertEquals(505, retainedIds.toSet().size)
        assertEquals(5, page.items.size)
        val earliest = requireNotNull(activeStore.find("0".repeat(32)))
        assertEquals("Import 0", earliest.history.suggestedName)
        assertEquals(SshProviderRequestState.SENT, earliest.state)
        assertNotNull(earliest.encodedResponse)
        assertNull(earliest.importRequest)
        activeStore.readableDatabase.rawQuery(
            "SELECT COUNT(*), COUNT(import_file_bytes), COUNT(import_agent_identity) FROM ssh_requests", null,
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(505, it.getInt(0))
            assertEquals(0, it.getInt(1))
            assertEquals(0, it.getInt(2))
        }
    }

    @Test
    fun historyPagesUseStableTieBreakersAndDoNotHideLiveRequestsOrReadBlobs() {
        store = SshKeyProviderStore(context)
        val activeStore = requireNotNull(store)
        val provider = ClientId("provider")
        fun request(index: Int) = SshImportRequest(
            requestId = index.toString(16).padStart(32, '0'), requesterClientId = ClientId("desktop"),
            requestedAt = 1_000, expiresAt = 20_000, sourceType = SshImportSourceType.PRIVATE_KEY_FILE,
            fileBytes = byteArrayOf(1, 2, 3), suggestedName = "Import $index",
        )
        repeat(105) { index ->
            val request = request(index)
            assertEquals(SshProviderAcceptResult.STORED, activeStore.acceptImport(request, 1_000))
            assertTrue(activeStore.reject(request.requestId, provider, 1_100))
            assertTrue(activeStore.markSent(request.requestId, 1_200))
        }
        val pending = request(200)
        val outbox = request(201)
        assertEquals(SshProviderAcceptResult.STORED, activeStore.acceptImport(pending, 1_300))
        assertEquals(SshProviderAcceptResult.STORED, activeStore.acceptImport(outbox, 1_300))
        assertTrue(activeStore.reject(outbox.requestId, provider, 1_400))
        val liveIds = setOf(pending.requestId, outbox.requestId)
        assertEquals(liveIds, activeStore.activeRequests().map { it.requestId }.toSet())
        assertEquals(liveIds, activeStore.managementSnapshot(provider, 1_500).snapshot.requests.map {
            it.requestId
        }.toSet())

        val first = activeStore.historyPage()
        assertEquals((104 downTo 55).map { request(it).requestId }, first.items.map { it.requestId })
        assertTrue(first.items.all {
            it.requestFingerprint.isEmpty() && it.history.publicKeyBlob == null &&
                it.signRequest == null && it.importRequest == null && it.encodedResponse == null
        })

        // Moving the front of the result set must not shift subsequent pages, unlike OFFSET paging.
        val newTerminal = request(202)
        activeStore.acceptImport(newTerminal, 1_500)
        activeStore.reject(newTerminal.requestId, provider, 1_600)
        activeStore.markSent(newTerminal.requestId, 1_700)
        activeStore.writableDatabase.delete("ssh_requests", "request_id=?", arrayOf(request(104).requestId))
        // Even an oversized unused BLOB must stay out of every list cursor window.
        activeStore.writableDatabase.execSQL(
            "UPDATE ssh_requests SET response_signature_blob=zeroblob(4000000) WHERE request_id=?",
            arrayOf(request(54).requestId),
        )
        val second = activeStore.historyPage(cursor = first.nextCursor)
        val third = activeStore.historyPage(cursor = second.nextCursor)
        assertEquals((54 downTo 5).map { request(it).requestId }, second.items.map { it.requestId })
        assertEquals((4 downTo 0).map { request(it).requestId }, third.items.map { it.requestId })
        assertNull(third.nextCursor)
        // An anchored refresh reads a bounded window on both sides without returning to page one.
        val pivot = second.items[25]
        val anchor = SshHistoryCursor(pivot.updatedAt, pivot.requestId)
        val before = activeStore.historyPage(25, anchor, HistoryDirection.NEWER)
        val fromAnchor = activeStore.historyPage(25, anchor, HistoryDirection.OLDER, includeCursor = true)
        assertEquals(second.items.map { it.requestId }, (before.items + fromAnchor.items).map { it.requestId })
        assertNotNull(before.nextCursor)
        assertEquals(pivot.requestId, activeStore.historyPage(
            1, anchor, HistoryDirection.NEWER, includeCursor = true,
        ).items.single().requestId)
        assertTrue(activeStore.historyPage(
            50, SshHistoryCursor(1_700, newTerminal.requestId), HistoryDirection.NEWER,
        ).items.isEmpty())
        assertEquals("Import 0", activeStore.find(request(0).requestId)?.history?.suggestedName)
        assertEquals(newTerminal.requestId, activeStore.historyPage().items.first().requestId)
        for ((position, arguments) in listOf(
            "" to arrayOf("51"),
            " AND (updated_at, request_id) < (?, ?)" to arrayOf("1200", request(55).requestId, "51"),
        )) {
            activeStore.readableDatabase.rawQuery(
                "EXPLAIN QUERY PLAN SELECT request_id,state,updated_at,key_name FROM ssh_requests " +
                    "WHERE state NOT IN ('PENDING_REVIEW', 'RESPONSE_PENDING_SEND')$position " +
                    "ORDER BY updated_at DESC, request_id DESC LIMIT ?",
                arguments,
            ).use { cursor ->
                val plan = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
                }.joinToString("\n")
                assertTrue(plan, plan.contains("ssh_requests_history_idx"))
                assertFalse(plan, plan.contains("TEMP B-TREE"))
                if (position.isNotEmpty()) assertTrue(plan, plan.contains("SEARCH ssh_requests"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { activeStore.historyPage(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { activeStore.historyPage(limit = 501) }
    }

    @Test
    fun newDatabaseUsesCurrentReleaseSchema() {
        store = SshKeyProviderStore(context)
        val database = requireNotNull(store).readableDatabase
        assertEquals(OperationalDatabase.VERSION, database.version)
        val columns = database.rawQuery("PRAGMA table_info(ssh_requests)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertFalse("history_cbor" in columns)
        assertFalse("history_nonce" in columns)
        assertTrue("sign_data" in columns)
        assertTrue("expires_at" in columns)
        assertTrue(database.hasTable("ssh_remembered_authorizations"))
        assertTrue(database.hasTable("ssh_known_hosts"))
        assertTrue(database.hasTable("ssh_webauthn_credentials"))
        assertFalse(database.hasTable("remember_rules"))
        assertThrows(SQLiteConstraintException::class.java) {
            database.execSQL("INSERT INTO ssh_requests(request_id, kind) VALUES ('invalid', 'IMPORT')")
        }
    }

    @Test
    fun openingStoreRepairsLegacyUuidInventoryGeneration() {
        OperationalDatabaseEncryption.open(context).use { database ->
            database.execSQL(
                "INSERT INTO ssh_provider_state(singleton, inventory_generation, revision) VALUES(1, ?, 28)",
                arrayOf("b59ebab4-5fc0-40d0-b1de-27e20f21cf80"),
            )
        }

        store = SshKeyProviderStore(context)
        val snapshot = requireNotNull(store).snapshot(ClientId("provider"), null, 1_000)

        assertEquals("b59ebab45fc040d0b1de27e20f21cf80", snapshot.inventoryGeneration)
        assertEquals(28L, snapshot.revision)
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
    fun blankKnownHostHostnameIsStoredAsUnset() {
        store = SshKeyProviderStore(context)
        val hostKeySha256 = ByteArray(32) { it.toByte() }
        requireNotNull(store).writableDatabase.execSQL(
            "INSERT INTO ssh_known_hosts(host_key_sha256, hostname, first_approved_at, last_approved_at) " +
                "VALUES (?, 'old name', 1, 1)",
            arrayOf(hostKeySha256),
        )

        assertTrue(requireNotNull(store).updateKnownHostHostname(hostKeySha256, "  "))
        assertEquals(null, requireNotNull(store).knownHostHostname(hostKeySha256))
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
    fun binaryHashLookupsAndMutationsMatchOnlyExactBytes() {
        store = SshKeyProviderStore(context)
        val activeStore = requireNotNull(store)
        val first = ByteArray(32) { if (it % 2 == 0) 0 else 0xff.toByte() }
        val second = first.copyOf().apply { this[lastIndex] = 0x7f }
        val missing = first.copyOf().apply { this[0] = 1 }
        listOf(first to "first", second to "second").forEach { (hash, name) ->
            activeStore.writableDatabase.execSQL(
                "INSERT INTO ssh_known_hosts(host_key_sha256, hostname, first_approved_at, last_approved_at) " +
                    "VALUES (?, ?, 1, 1)", arrayOf(hash, name),
            )
        }
        assertEquals("first", activeStore.knownHostHostname(first))
        assertEquals("second", activeStore.knownHostHostname(second))
        assertNull(activeStore.knownHostHostname(missing))
        assertTrue(activeStore.updateKnownHostHostname(first, "changed"))
        assertEquals("second", activeStore.knownHostHostname(second))
        assertFalse(activeStore.updateKnownHostHostname(missing, "absent"))
        assertTrue(activeStore.deleteKnownHost(first))
        assertFalse(activeStore.deleteKnownHost(first))
        assertEquals("second", activeStore.knownHostHostname(second))

        val publicBlob = byteArrayOf(0, 0xff.toByte(), 1, 0x80.toByte())
        activeStore.writableDatabase.execSQL(
            "INSERT INTO ssh_keys(provider_key_id, public_blob, public_hash, algorithm, display_name, origin, " +
                "approval_policy, created_at) VALUES ('binary-key', ?, ?, 'SSH_ED25519', 'Binary key', " +
                "'GENERATED', 'ALWAYS_ASK', 1)",
            arrayOf(publicBlob, MessageDigest.getInstance("SHA-256").digest(publicBlob)),
        )
        assertEquals("Binary key", activeStore.keyDisplayName(publicBlob))
        assertTrue(activeStore.owns(publicBlob, 1_000))
        assertNull(activeStore.keyDisplayName(publicBlob + 1.toByte()))
    }

    @Test
    fun binaryHashPredicatesUseExistingIndexesForReadsUpdatesAndDeletes() {
        store = SshKeyProviderStore(context)
        val database = requireNotNull(store).readableDatabase
        val hash = ByteArray(32) { it.toByte() }
        val queries = listOf(
            "SELECT hostname FROM ssh_known_hosts WHERE host_key_sha256=?" to arrayOf<Any>(hash),
            "UPDATE ssh_known_hosts SET hostname=? WHERE host_key_sha256=?" to arrayOf<Any>("name", hash),
            "DELETE FROM ssh_known_hosts WHERE host_key_sha256=?" to arrayOf<Any>(hash),
            "UPDATE ssh_known_hosts SET last_approved_at=? WHERE host_key_sha256=? AND last_approved_at<?" to
                arrayOf<Any>(2L, hash, 2L),
            "SELECT display_name FROM ssh_keys WHERE public_hash=?" to arrayOf<Any>(hash),
            "SELECT k.provider_key_id FROM ssh_keys k JOIN ssh_operational_keys o " +
                "ON o.provider_key_id=k.provider_key_id WHERE k.public_hash=?" to arrayOf<Any>(hash),
            "SELECT k.provider_key_id FROM ssh_keys k JOIN ssh_webauthn_credentials w " +
                "ON w.provider_key_id=k.provider_key_id WHERE k.public_hash=?" to arrayOf<Any>(hash),
        )
        queries.forEach { (sql, arguments) ->
            val plan = database.rawQuery("EXPLAIN QUERY PLAN $sql", *arguments).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("; ")
            }
            assertTrue("Expected indexed binary lookup for $sql: $plan",
                plan.contains("SEARCH") && plan.contains("USING") && plan.contains("INDEX"))
            assertFalse("Unexpected table scan for $sql: $plan", plan.contains("SCAN "))
        }
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
        assertThrows(SQLiteConstraintException::class.java) {
            database.execSQL("UPDATE ssh_remembered_authorizations SET scope='APPLICATION_PROCESS' WHERE authorization_id='peer'")
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
        createMarkerDatabase(version = OperationalDatabase.VERSION + 1)

        store = SshKeyProviderStore(context)
        assertThrows(IllegalStateException::class.java) { requireNotNull(store).readableDatabase }
        store?.close()
        store = null

        OperationalDatabaseEncryption.open(context).use { database ->
            assertEquals(OperationalDatabase.VERSION + 1, database.version)
            database.rawQuery("SELECT value FROM release_marker", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("preserve-me", cursor.getString(0))
            }
        }
    }

    @Test
    fun sameVersionWithWrongTablesFailsClosedWithoutResettingIt() {
        createMarkerDatabase(version = OperationalDatabase.VERSION)

        store = SshKeyProviderStore(context)
        assertThrows(IllegalStateException::class.java) { requireNotNull(store).readableDatabase }
        store?.close()
        store = null

        OperationalDatabaseEncryption.open(context).use { database ->
            assertEquals(OperationalDatabase.VERSION, database.version)
            database.rawQuery("SELECT value FROM release_marker", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("preserve-me", cursor.getString(0))
            }
        }
    }

    @Test
    fun foreignKeyViolationFailsBeforeInventoryRepair() {
        OperationalDatabaseEncryption.open(context).use { database ->
            database.execSQL("PRAGMA foreign_keys=OFF")
            database.execSQL(
                "INSERT INTO ssh_remembered_authorizations(authorization_id, provider_key_id, requester_client_id, " +
                    "authorization_generation, authorization_epoch, scope, host_key_sha256, created_at) " +
                    "VALUES ('orphan', 'missing-key', 'requester', 'generation', 1, 'PEER', NULL, 1)",
            )
        }

        store = SshKeyProviderStore(context)
        val failure = assertThrows(IllegalStateException::class.java) { requireNotNull(store).readableDatabase }
        assertTrue(failure.message.orEmpty().contains("foreign-key violations"))
        OperationalDatabaseEncryption.open(context).use { database ->
            database.rawQuery("SELECT COUNT(*) FROM ssh_provider_state", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun roomRejectsWrongSchemaIdentityBeforeInventoryRepair() {
        OperationalDatabaseEncryption.open(context).use { database ->
            database.execSQL("UPDATE room_master_table SET identity_hash='wrong-schema' WHERE id=42")
        }

        store = SshKeyProviderStore(context)
        assertThrows(IllegalStateException::class.java) { requireNotNull(store).readableDatabase }
        OperationalDatabaseEncryption.open(context).use { database ->
            database.rawQuery("SELECT COUNT(*) FROM ssh_provider_state", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private fun createMarkerDatabase(version: Int) {
        context.deleteDatabase(DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()
        OperationalDatabaseEncryption.driver(context).open(databaseFile.path).use { connection ->
            connection.execSQL("CREATE TABLE release_marker(value TEXT NOT NULL)")
            connection.execSQL("INSERT INTO release_marker(value) VALUES ('preserve-me')")
            connection.execSQL("PRAGMA user_version=$version")
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

