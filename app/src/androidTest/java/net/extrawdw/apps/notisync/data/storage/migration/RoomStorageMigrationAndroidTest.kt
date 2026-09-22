package net.extrawdw.apps.notisync.data.storage.migration

import android.content.Context
import android.content.ContextWrapper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.data.MessageStore
import net.extrawdw.apps.notisync.data.AppConfigRepository
import net.extrawdw.apps.notisync.data.AppSelectionRepository
import net.extrawdw.apps.notisync.data.NotificationFilterStore
import net.extrawdw.apps.notisync.data.PerAppConfig
import net.extrawdw.apps.notisync.data.SeenChannel
import net.extrawdw.apps.notisync.data.SettingsRepository
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabaseFactory
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseEncryption
import net.extrawdw.apps.notisync.data.storage.operational.RoomOperationalApplicationState
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseFactory
import net.extrawdw.apps.notisync.ios.IosApp
import net.extrawdw.apps.notisync.ios.IosAppRegistry
import net.extrawdw.apps.notisync.run.RunControlOutbox
import net.extrawdw.apps.notisync.run.RunStore
import net.extrawdw.apps.notisync.run.RunKey
import net.extrawdw.apps.notisync.run.StoredRun
import net.extrawdw.apps.notisync.run.StoredRunRevision
import net.extrawdw.apps.notisync.seal.OpenPgpSignStore
import net.extrawdw.apps.notisync.seal.OpenPgpEnrollmentStore
import net.extrawdw.apps.notisync.screen.ScreenMirrorAuthorizationStore
import net.extrawdw.apps.notisync.screen.ScreenMirrorCodecPreferenceStore
import net.extrawdw.apps.notisync.sshkeyprovider.SshKeyProviderStore
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.NotificationFilterRule
import net.extrawdw.notisync.protocol.OriginPlatform
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomStorageMigrationAndroidTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var preferences: InMemoryPreferencesDataStore

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        root = File(base.cacheDir, "room-storage-migration-${UUID.randomUUID()}")
        check(root.mkdirs())
        context = IsolatedDatabaseContext(base, root)
        preferences = InMemoryPreferencesDataStore()
    }

    @After
    fun tearDown() {
        CoreDatabaseFactory.close(context)
        OperationalDatabaseFactory.close(context)
        check(root.canonicalPath.startsWith(base.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    @Test
    fun firstInitCopiesPlayV51SourcesAndSecondInitUsesRoomAuthority() = runBlocking {
        val androidConfig = PerAppConfig(mirrorOngoing = true, updateIntervalSec = 15)
        val seenChannel = SeenChannel("messages", "Messages", "social", "Social")
        val incomingFilter = FilterSync(
            rules = listOf(NotificationFilterRule(OriginPlatform.ANDROID_LOCAL, "com.example.enabled")),
            updatedAt = 900L,
        )
        preferences.edit {
            it[stringPreferencesKey("device_name")] = "Retained phone"
            it[stringPreferencesKey("enabled_packages_json")] =
                ProtocolCodec.encodeToJson(setOf("com.example.enabled"))
            it[stringPreferencesKey("per_app_config_json")] =
                ProtocolCodec.encodeToJson(mapOf("com.example.enabled" to androidConfig))
            it[stringPreferencesKey("per_app_seen_channels_json")] =
                ProtocolCodec.encodeToJson(mapOf("com.example.enabled" to listOf(seenChannel)))
            it[longPreferencesKey("last_seen_post_time")] = 456L
            it[stringPreferencesKey("received_notification_filters_json")] =
                ProtocolCodec.encodeToJson(mapOf("filtering-peer" to incomingFilter))
            it[stringPreferencesKey("ancs_enabled_bundles_json")] =
                ProtocolCodec.encodeToJson(setOf("com.example.ios", "com.example.enabled-only"))
            it[stringPreferencesKey("ancs_discovered_apps_json")] = ProtocolCodec.encodeToJson(
                mapOf(
                    "com.example.ios" to IosApp("com.example.ios", "Example iOS", 789L),
                    "com.example.discovered-only" to
                        IosApp("com.example.discovered-only", "Discovered", 790L),
                ),
            )
            it[booleanPreferencesKey("screen_mirroring_enabled")] = true
            it[stringPreferencesKey("screen_mirror_authorized_peer_ids")] =
                ProtocolCodec.encodeToJson(setOf("screen-peer"))
            it[stringPreferencesKey("screen_mirror_codec_preferences_v1")] =
                ProtocolCodec.encodeToJson(mapOf("screen-peer" to "h265"))
            it[booleanPreferencesKey("openpgp_sign_enabled")] = true
            it[stringPreferencesKey("openpgp_sign_provider")] = "openkeychain"
            it[stringPreferencesKey("openpgp_sign_provider_reference")] = "provider-reference"
            it[stringPreferencesKey("openpgp_sign_primary_key_id")] = "0123456789ABCDEF"
            it[stringPreferencesKey("openpgp_sign_display_identity")] = "Signer <signer@example.com>"
            it[longPreferencesKey("openpgp_sign_enrolled_at")] = 1_234L
        }
        createKnownGoodSources()

        var migrationRequiredCount = 0
        val migrator = RoomStorageMigration(context, preferences)
        migrator.prepare { migrationRequiredCount += 1 }

        val migratedPreferences = preferences.data.first()
        assertEquals(1, migrationRequiredCount)
        assertEquals("Retained phone", migratedPreferences[stringPreferencesKey("device_name")])
        assertTrue(migratedPreferences[booleanPreferencesKey("known_good_to_room_v1_complete")] == true)
        assertEquals(null, migratedPreferences[stringPreferencesKey("enabled_packages_json")])
        assertEquals(null, migratedPreferences[stringPreferencesKey("ancs_enabled_bundles_json")])
        assertEquals(null, migratedPreferences[stringPreferencesKey("ancs_discovered_apps_json")])
        assertEquals(null, migratedPreferences[stringPreferencesKey("openpgp_sign_primary_key_id")])
        assertTrue(legacyMessageFamily().isEmpty())
        openCore().use { database ->
            assertEquals(2L, database.count("dedup"))
            assertTrue(database.tableExists("pending_ack"))
            assertFalse(database.tableExists("mirror_lifecycle"))
        }
        openOperational().use { database ->
            assertFalse(database.tableExists("dedup"))
            assertTrue(database.tableExists("mirror_lifecycle"))
            assertEquals(1L, database.count("mirror_message"))
            assertFalse(database.tableExists("mirror_msg"))
            assertEquals(0L, database.count("ssh_known_hosts"))
            assertEquals(1L, database.count("ssh_provider_state"))
            database.rawQuery(
                "SELECT inventory_generation FROM ssh_provider_state WHERE singleton=1",
                emptyArray(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(Regex("[0-9a-f]{32}").matches(cursor.getString(0)))
            }
            assertEquals(1L, database.count("android_apps"))
            assertEquals(1L, database.count("incoming_notification_filters"))
            assertEquals(3L, database.count("ios_apps"))
            assertEquals(1L, database.count("screen_mirror_state"))
            assertEquals(1L, database.count("screen_codec_preferences"))
            assertEquals(1L, database.count("seal_enrollment"))
            assertEquals(1L, database.count("runs"))
            assertEquals(1L, database.count("run_revisions"))
            assertEquals(1L, database.count("run_controls"))
            assertFalse(database.tableExists("controls"))
        }
        val applicationState = RoomOperationalApplicationState(context)
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        assertEquals(456L, SettingsRepository(preferences, repositoryScope, applicationState).lastSeenPostTime())
        assertTrue(SettingsRepository(preferences, repositoryScope, applicationState).screenMirroringEnabledNow())
        assertTrue(
            AppSelectionRepository(repositoryScope, applicationState)
                .isEnabled("com.example.enabled"),
        )
        assertEquals(
            androidConfig,
            AppConfigRepository(repositoryScope, applicationState)
                .configFor("com.example.enabled"),
        )
        assertEquals(
            incomingFilter,
            NotificationFilterStore(repositoryScope, applicationState)
                .filterFor(ClientId("filtering-peer")),
        )
        assertTrue(IosAppRegistry(repositoryScope, applicationState).isEnabled("com.example.ios"))
        assertTrue(
            ScreenMirrorAuthorizationStore(applicationState)
                .isAuthorized(ClientId("screen-peer")),
        )
        assertEquals(
            ScreenMirrorCodec.H265,
            ScreenMirrorCodecPreferenceStore(applicationState)
                .preferredCodec(ClientId("screen-peer")),
        )
        assertEquals(
            "0123456789ABCDEF",
            OpenPgpEnrollmentStore(applicationState)
                .enrollment.value.primaryKeyId,
        )
        val messageStore = MessageStore(context)
        val runStore = RunStore(context, now = { 3_000 })
        val controlOutbox = RunControlOutbox(context)
        val signStore = OpenPgpSignStore(context)
        val sshStore = SshKeyProviderStore(context)
        try {
            val expectedRun = StoredRun(legacyRunState(), receivedAt = 3_000, presentedRevision = 2, active = false)
            val runKey = RunKey(expectedRun.state.hostClientId.value, expectedRun.state.runId)
            assertTrue("Presented completed Runs belong to paged history, not the live cache", runStore.runs.value.isEmpty())
            assertEquals(expectedRun, runStore.find(runKey))
            assertEquals(listOf(expectedRun), runStore.historyPage().items)
            assertEquals(listOf(StoredRunRevision(expectedRun.state, expectedRun.receivedAt)), runStore.revisions(runKey))
            assertEquals(listOf(legacyRunControl()), controlOutbox.pending())
            assertTrue(messageStore.seen("migrated-message"))
            assertTrue("migrated-pending-ack" in messageStore.pendingAcks())
            messageStore.onDismissed(ClientId("source-client"), "source-key")
            assertTrue("mirror-message" in messageStore.pendingAcks())
            assertEquals(null, sshStore.knownHostHostname(ByteArray(32) { it.toByte() }))
            listOf(
                messageStore.readableDatabase,
                runStore.readableDatabase,
                controlOutbox.readableDatabase,
                signStore.readableDatabase,
                sshStore.readableDatabase,
            ).forEach { database -> assertEquals("wal", database.journalMode()) }
        } finally {
            sshStore.close()
            signStore.close()
            controlOutbox.close()
            runStore.close()
            messageStore.close()
        }

        MessageStore(context).also { store ->
            store.record("added-after-cutover")
            store.close()
        }
        preferences.edit {
            it[stringPreferencesKey("enabled_packages_json")] =
                ProtocolCodec.encodeToJson(setOf("com.example.stale-legacy"))
        }
        migrator.prepare { migrationRequiredCount += 1 }
        assertEquals(1, migrationRequiredCount)

        openCore().use { database ->
            assertEquals(3L, database.count("dedup"))
            assertTrue(database.containsMessage("added-after-cutover"))
        }
        openOperational().use { database ->
            assertEquals(1L, database.count("android_apps"))
            assertTrue(database.androidAppEnabled("com.example.enabled"))
            assertFalse(database.androidAppEnabled("com.example.stale-legacy"))
        }
    }

    @Test
    fun corruptWholeSourceAbortsWithoutDiscardingRecoverableInputs() = runBlocking {
        val enabledPackagesJson = """["com.example.retained",7,""]"""
        preferences.edit {
            it[stringPreferencesKey("enabled_packages_json")] = enabledPackagesJson
            it[stringPreferencesKey("screen_mirror_codec_preferences_v1")] =
                """{"screen-peer":"h264","unsupported":"vp9","wrong-type":7}"""
            it[booleanPreferencesKey("openpgp_sign_enabled")] = true
            it[stringPreferencesKey("openpgp_sign_provider")] = "openkeychain"
        }
        SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(LegacyDatabaseNames.MESSAGE_LEDGER),
            null,
        ).also { database ->
            database.execSQL("CREATE TABLE dedup(message_id TEXT PRIMARY KEY)")
            database.execSQL("INSERT INTO dedup(message_id) VALUES('missing-handled-at')")
            database.execSQL("CREATE TABLE pending_ack(message_id TEXT, queued_at INTEGER)")
            database.execSQL("INSERT INTO pending_ack VALUES('valid-ack', 1)")
            database.execSQL("INSERT INTO pending_ack VALUES('invalid-ack', NULL)")
            database.close()
        }
        context.getDatabasePath(LegacyDatabaseNames.RUNS).writeBytes("not a sqlite database".toByteArray())

        val before = legacyMessageFamily().associate { it.name to it.sha256() }
        assertTrue(runCatching { RoomStorageMigration(context, preferences).prepare() }.isFailure)
        assertFalse(context.getDatabasePath(CoreDatabase.DATABASE_NAME).exists())
        assertFalse(context.getDatabasePath(OperationalDatabase.DATABASE_NAME).exists())
        assertEquals(before, legacyMessageFamily().associate { it.name to it.sha256() })
        assertTrue(context.getDatabasePath(LegacyDatabaseNames.RUNS).exists())
        assertEquals(
            enabledPackagesJson,
            preferences.data.first()[stringPreferencesKey("enabled_packages_json")],
        )
        assertTrue(
            preferences.data.first()[booleanPreferencesKey("known_good_to_room_v1_complete")] != true,
        )
    }

    @Test
    fun unknownLegacyTablesAreOmittedWhileValidImportsProceed() = runBlocking {
        createKnownGoodSources()
        createDatabase(LegacyDatabaseNames.RUNS) { database ->
            database.execSQL("CREATE TABLE future_feature(data TEXT NOT NULL)")
            database.execSQL("INSERT INTO future_feature VALUES('retain this unrecognized row')")
        }
        RoomStorageMigration(context, preferences).prepare()
        assertTrue(preferences.data.first()[booleanPreferencesKey("known_good_to_room_v1_complete")] == true)
        openOperational().use { database ->
            assertFalse(database.tableExists("future_feature"))
            assertEquals(1L, database.count("mirror_message"))
            assertFalse(database.tableExists("mirror_msg"))
        }
        assertFalse(context.getDatabasePath(LegacyDatabaseNames.RUNS).exists())
        assertFalse(context.getDatabasePath(LegacyDatabaseNames.MESSAGE_LEDGER).exists())
    }

    @Test
    fun inconsistentRowsAndMalformedPreferencesDoNotBlockValidLegacyImports() = runBlocking {
        preferences.edit {
            it[stringPreferencesKey("enabled_packages_json")] = """["com.example.retained",7,""]"""
            it[stringPreferencesKey("screen_mirror_codec_preferences_v1")] =
                """{"screen-peer":"h264","unsupported":"vp9","wrong-type":7}"""
            it[stringPreferencesKey("per_app_config_json")] = "not valid JSON"
            it[booleanPreferencesKey("openpgp_sign_enabled")] = true
            it[stringPreferencesKey("openpgp_sign_provider")] = "openkeychain"
        }
        createDatabase(LegacyDatabaseNames.MESSAGE_LEDGER) { database ->
            database.execSQL("CREATE TABLE dedup(message_id TEXT PRIMARY KEY)")
            database.execSQL("INSERT INTO dedup VALUES('missing-handled-at')")
            database.execSQL("CREATE TABLE pending_ack(message_id TEXT, queued_at INTEGER)")
            database.execSQL("INSERT INTO pending_ack VALUES('valid-ack',1)")
            database.execSQL("INSERT INTO pending_ack VALUES('invalid-ack',NULL)")
        }
        RoomStorageMigration(context, preferences).prepare()
        assertTrue(preferences.data.first()[booleanPreferencesKey("known_good_to_room_v1_complete")] == true)
        openCore().use { database ->
            assertEquals(0L, database.count("dedup"))
            assertEquals(1L, database.count("pending_ack"))
        }
        openOperational().use { database ->
            assertTrue(database.androidAppEnabled("com.example.retained"))
            assertEquals(1L, database.count("android_apps"))
            assertEquals(1L, database.count("screen_codec_preferences"))
            assertEquals(0, database.openPgpEnrollmentEnabled())
            assertFalse(database.tableExists("migration_quarantine"))
        }
        assertFalse(context.getDatabasePath(LegacyDatabaseNames.MESSAGE_LEDGER).exists())
    }

    private fun createKnownGoodSources() {
        createDatabase(LegacyDatabaseNames.MESSAGE_LEDGER) { database ->
            database.execSQL("CREATE TABLE dedup(message_id TEXT PRIMARY KEY, handled_at INTEGER NOT NULL)")
            database.execSQL("CREATE TABLE pending_ack(message_id TEXT PRIMARY KEY, queued_at INTEGER NOT NULL)")
            database.execSQL(
                "CREATE TABLE mirror_msg(source_client TEXT NOT NULL, source_key TEXT NOT NULL, " +
                    "message_id TEXT NOT NULL, recorded_at INTEGER NOT NULL, " +
                    "PRIMARY KEY(source_client, source_key))",
            )
            database.execSQL(
                "CREATE TABLE relay_inbox(message_id TEXT PRIMARY KEY, envelope BLOB NOT NULL, " +
                    "accepted_at INTEGER NOT NULL, delivery_mode TEXT NOT NULL, received_at INTEGER NOT NULL, " +
                    "early_ack INTEGER NOT NULL)",
            )
            database.execSQL("CREATE TABLE message_meta(name TEXT PRIMARY KEY, long_value INTEGER NOT NULL)")
            database.execSQL(
                "CREATE TABLE mirror_lifecycle(source_client TEXT NOT NULL, source_key TEXT NOT NULL, " +
                    "post_time INTEGER, dismissed_at INTEGER, updated_at INTEGER NOT NULL, " +
                    "PRIMARY KEY(source_client, source_key))",
            )
            database.execSQL("INSERT INTO dedup VALUES('migrated-message', 1)")
            database.execSQL("INSERT INTO pending_ack VALUES('migrated-pending-ack', 1)")
            database.execSQL(
                "INSERT INTO mirror_msg VALUES('source-client', 'source-key', 'mirror-message', 1)",
            )
        }
        appendWalOnlyMessage()
        createDatabase(LegacyDatabaseNames.RUNS) { database ->
            database.execSQL(
                "CREATE TABLE runs(host_client TEXT NOT NULL, run_id TEXT NOT NULL, revision INTEGER NOT NULL, " +
                    "presented_revision INTEGER NOT NULL, active INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
                    "ended_at INTEGER, received_at INTEGER NOT NULL, payload BLOB NOT NULL, " +
                "PRIMARY KEY(host_client, run_id))",
            )
            database.execSQL(
                "INSERT INTO runs VALUES('run-host', 'legacy-run', 2, 2, 0, 2000, 2000, 3000, ?)",
                arrayOf(ProtocolCodec.encodeToCbor(legacyRunState())),
            )
        }
        createDatabase(LegacyDatabaseNames.RUN_CONTROL_OUTBOX) { database ->
            database.execSQL(
                "CREATE TABLE controls(request_id TEXT PRIMARY KEY, requested_at INTEGER NOT NULL, " +
                    "payload BLOB NOT NULL)",
            )
            database.execSQL(
                "INSERT INTO controls VALUES(?, 1000, ?)",
                arrayOf(legacyRunControl().requestId, ProtocolCodec.encodeToCbor(legacyRunControl())),
            )
        }
        createDatabase(LegacyDatabaseNames.OPENPGP_SIGNING) { database ->
            database.execSQL(
                "CREATE TABLE sign_requests(request_id TEXT PRIMARY KEY, requester_client_id TEXT NOT NULL, " +
                    "sender_client_id TEXT NOT NULL, primary_key_id TEXT NOT NULL, issued_at INTEGER NOT NULL, " +
                    "expires_at INTEGER NOT NULL, payload_sha256 BLOB NOT NULL, object_kind TEXT NOT NULL, " +
                    "payload BLOB, state TEXT NOT NULL, encoded_response BLOB, updated_at INTEGER NOT NULL, " +
                    "commit_details BLOB, result TEXT, working_directory TEXT)",
            )
        }
    }

    private fun appendWalOnlyMessage() {
        val source = context.getDatabasePath(LegacyDatabaseNames.MESSAGE_LEDGER)
        val captureRoot = File(root, "message-wal-capture").also { check(it.mkdirs()) }
        val writer = SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        check(writer.enableWriteAheadLogging())
        writer.rawQuery("PRAGMA wal_autocheckpoint=0", emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
        }
        val reader = SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            reader.execSQL("BEGIN DEFERRED TRANSACTION")
            reader.rawQuery("SELECT COUNT(*) FROM dedup", emptyArray()).use { cursor ->
                check(cursor.moveToFirst())
            }
            writer.execSQL("INSERT INTO dedup VALUES('wal-only-message', 2)")
            listOf("", "-wal", "-shm").forEach { suffix ->
                val input = File(source.absolutePath + suffix)
                check(input.isFile)
                input.copyTo(File(captureRoot, source.name + suffix))
            }
        } finally {
            runCatching { reader.execSQL("ROLLBACK") }
            reader.close()
            writer.close()
        }
        listOf("", "-wal", "-shm").forEach { suffix ->
            val target = File(source.absolutePath + suffix)
            check(target.delete() || !target.exists())
            File(captureRoot, source.name + suffix).copyTo(target)
        }
        check(captureRoot.deleteRecursively())
    }

    private fun legacyRunState() = RunState(
        hostClientId = ClientId("run-host"), runId = "legacy-run", revision = 2,
        phase = RunPhase.COMPLETED, updateReason = RunUpdateReason.COMPLETED,
        startedAt = 1_000, updatedAt = 2_000, endedAt = 2_000, exitCode = 0,
        argv = listOf("make", "test"), cwd = "/work", usesPty = false,
        terminal = RunTerminalSnapshot("Tests passed\n", truncated = false, rawBytesSeen = 13),
    )

    private fun legacyRunControl() = RunControl(
        requestId = "00000000-0000-4000-8000-000000000007",
        hostClientId = ClientId("run-host"), runId = "legacy-run",
        kind = RunControlKind.REFRESH, requestedAt = 1_000,
    )

    private fun legacyMessageFamily(): List<File> {
        val source = context.getDatabasePath(LegacyDatabaseNames.MESSAGE_LEDGER)
        return listOf("", "-wal", "-shm", "-journal").map { suffix -> File(source.absolutePath + suffix) }
            .filter(File::isFile)
    }

    private fun File.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(readBytes()).joinToString("") { "%02x".format(it) }

    private inline fun createDatabase(name: String, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use(block)
    }

    private fun openOperational(): SQLiteDatabase = OperationalDatabaseEncryption.open(context)

    private fun openCore(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath(CoreDatabase.DATABASE_NAME).absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
    )

    private fun SQLiteDatabase.count(table: String): Long =
        rawQuery("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SQLiteDatabase.tableExists(table: String): Boolean =
        rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun SQLiteDatabase.journalMode(): String =
        rawQuery("PRAGMA journal_mode", emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SQLiteDatabase.containsMessage(messageId: String): Boolean =
        rawQuery("SELECT 1 FROM dedup WHERE message_id=?", arrayOf(messageId)).use { it.moveToFirst() }

    private fun SQLiteDatabase.androidAppEnabled(packageName: String): Boolean =
        rawQuery(
            "SELECT enabled FROM android_apps WHERE package_name=?",
            arrayOf(packageName),
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }

    private fun SQLiteDatabase.openPgpEnrollmentEnabled(): Int =
        rawQuery("SELECT enabled FROM seal_enrollment WHERE singleton_id=1", emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private class IsolatedDatabaseContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getDatabasePath(name: String): File =
            File(root, "databases/$name").also { it.parentFile?.mkdirs() }

        override fun deleteDatabase(name: String): Boolean =
            SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }
}
