package net.extrawdw.apps.notisync.data.storage.migration

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
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
import net.extrawdw.apps.notisync.data.storage.RoomStorageSelection
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabaseFactory
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabase
import net.extrawdw.apps.notisync.data.storage.operational.LegacyDatabaseNames
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalApplicationState
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseFactory
import net.extrawdw.apps.notisync.ios.IosApp
import net.extrawdw.apps.notisync.ios.IosAppRegistry
import net.extrawdw.apps.notisync.run.RunControlOutbox
import net.extrawdw.apps.notisync.run.RunStore
import net.extrawdw.apps.notisync.seal.OpenPgpSignStore
import net.extrawdw.apps.notisync.seal.OpenPgpEnrollmentStore
import net.extrawdw.apps.notisync.screen.ScreenMirrorAuthorizationStore
import net.extrawdw.apps.notisync.screen.ScreenMirrorCodecPreferenceStore
import net.extrawdw.apps.notisync.sshagent.SshKeyProviderStore
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.NotificationFilterRule
import net.extrawdw.notisync.protocol.OriginPlatform
import net.extrawdw.notisync.protocol.ProtocolCodec
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
    fun firstInitCopiesKnownGoodSourcesAndSecondInitUsesRoomAuthority() = runBlocking {
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

        val migrator = RoomStorageMigration(context, preferences)
        val first = migrator.prepare()

        assertTrue(first.usesRoom)
        val migratedPreferences = preferences.data.first()
        assertEquals("Retained phone", migratedPreferences[stringPreferencesKey("device_name")])
        assertTrue(migratedPreferences[booleanPreferencesKey("known_good_to_room_v1_complete")] == true)
        assertTrue(migratedPreferences[stringPreferencesKey("enabled_packages_json")] != null)
        assertTrue(migratedPreferences[stringPreferencesKey("ancs_enabled_bundles_json")] != null)
        assertTrue(migratedPreferences[stringPreferencesKey("ancs_discovered_apps_json")] != null)
        assertEquals(
            "0123456789ABCDEF",
            migratedPreferences[stringPreferencesKey("openpgp_sign_primary_key_id")],
        )
        openCore().use { database ->
            assertEquals(1L, database.count("dedup"))
            assertTrue(database.tableExists("pending_ack"))
            assertFalse(database.tableExists("mirror_lifecycle"))
        }
        openOperational().use { database ->
            assertFalse(database.tableExists("dedup"))
            assertTrue(database.tableExists("mirror_lifecycle"))
            assertEquals(1L, database.count("mirror_msg"))
            assertEquals(1L, database.count("ssh_known_hosts"))
            assertEquals("known-host", database.knownHostName())
            assertEquals(1L, database.count("android_apps"))
            assertEquals(1L, database.count("incoming_notification_filters"))
            assertEquals(3L, database.count("ios_apps"))
            assertEquals(1L, database.count("screen_mirror_state"))
            assertEquals(1L, database.count("screen_codec_preferences"))
            assertEquals(1L, database.count("openpgp_enrollment"))
        }
        val roomContext = RoomSelectedContext(context)
        val applicationState = OperationalApplicationState(roomContext)
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        assertEquals(456L, SettingsRepository(preferences, repositoryScope, applicationState).lastSeenPostTime())
        assertTrue(SettingsRepository(preferences, repositoryScope, applicationState).screenMirroringEnabledNow())
        assertTrue(
            AppSelectionRepository(preferences, repositoryScope, applicationState)
                .isEnabled("com.example.enabled"),
        )
        assertEquals(
            androidConfig,
            AppConfigRepository(preferences, repositoryScope, applicationState)
                .configFor("com.example.enabled"),
        )
        assertEquals(
            incomingFilter,
            NotificationFilterStore(preferences, repositoryScope, applicationState)
                .filterFor(ClientId("filtering-peer")),
        )
        assertTrue(IosAppRegistry(preferences, repositoryScope, applicationState).isEnabled("com.example.ios"))
        assertTrue(
            ScreenMirrorAuthorizationStore(preferences, applicationState)
                .isAuthorized(ClientId("screen-peer")),
        )
        assertEquals(
            ScreenMirrorCodec.H265,
            ScreenMirrorCodecPreferenceStore(preferences, applicationState)
                .preferredCodec(ClientId("screen-peer")),
        )
        assertEquals(
            "0123456789ABCDEF",
            OpenPgpEnrollmentStore(preferences, repositoryScope, applicationState)
                .enrollment.value.primaryKeyId,
        )
        val messageStore = MessageStore(roomContext)
        val runStore = RunStore(roomContext)
        val controlOutbox = RunControlOutbox(roomContext)
        val signStore = OpenPgpSignStore(roomContext)
        val sshStore = SshKeyProviderStore(roomContext)
        try {
            assertTrue(messageStore.seen("migrated-message"))
            assertTrue("migrated-pending-ack" in messageStore.pendingAcks())
            messageStore.onDismissed(ClientId("source-client"), "source-key")
            assertTrue("mirror-message" in messageStore.pendingAcks())
            assertEquals("known-host", sshStore.knownHostHostname(ByteArray(32) { it.toByte() }))
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
        val second = migrator.prepare()

        assertTrue(second.usesRoom)
        openCore().use { database ->
            assertEquals(1L, database.count("dedup"))
            assertFalse(database.containsMessage("added-after-cutover"))
        }
        openOperational().use { database ->
            assertEquals(1L, database.count("android_apps"))
            assertTrue(database.androidAppEnabled("com.example.enabled"))
            assertFalse(database.androidAppEnabled("com.example.stale-legacy"))
        }
    }

    @Test
    fun incompatibleSourceLeavesFlagIncompleteAndFallsBackWithoutPartialTargets() = runBlocking {
        preferences.edit {
            it[stringPreferencesKey("enabled_packages_json")] =
                ProtocolCodec.encodeToJson(setOf("com.example.retained"))
        }
        SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(LegacyDatabaseNames.MESSAGE_LEDGER),
            null,
        ).also { database ->
            database.execSQL("CREATE TABLE dedup(message_id TEXT PRIMARY KEY)")
            database.close()
        }

        val prepared = RoomStorageMigration(context, preferences).prepare()

        assertFalse(prepared.usesRoom)
        assertTrue(prepared.migrationFailure != null)
        assertFalse(context.getDatabasePath(CoreDatabase.DATABASE_NAME).exists())
        assertFalse(context.getDatabasePath(OperationalDatabase.DATABASE_NAME).exists())
        assertEquals(
            setOf("com.example.retained"),
            ProtocolCodec.decodeFromJson<Set<String>>(
                checkNotNull(preferences.data.first()[stringPreferencesKey("enabled_packages_json")]),
            ),
        )
        assertFalse(
            preferences.data.first()[booleanPreferencesKey("known_good_to_room_v1_complete")] == true,
        )
    }

    private fun createKnownGoodSources() {
        MessageStore(context).also { store ->
            store.record("migrated-message")
            store.enqueueAck("migrated-pending-ack")
            store.recordMirror(ClientId("source-client"), "source-key", "mirror-message")
            store.close()
        }
        RunStore(context).close()
        RunControlOutbox(context).close()
        OpenPgpSignStore(context).close()
        SshKeyProviderStore(context).also { store ->
            store.writableDatabase.execSQL(
                "INSERT INTO ssh_known_hosts(host_key_sha256, hostname, first_approved_at, last_approved_at) " +
                    "VALUES (?, ?, 1, 1)",
                arrayOf(ByteArray(32) { it.toByte() }, "known-host"),
            )
            store.close()
        }
    }

    private fun openOperational(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath(OperationalDatabase.DATABASE_NAME).absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
    )

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

    private fun SQLiteDatabase.knownHostName(): String =
        rawQuery("SELECT hostname FROM ssh_known_hosts", emptyArray()).use { cursor ->
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

    private class IsolatedDatabaseContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getDatabasePath(name: String): File =
            File(root, "databases/$name").also { it.parentFile?.mkdirs() }

        override fun deleteDatabase(name: String): Boolean =
            SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }

    private class RoomSelectedContext(base: Context) :
        ContextWrapper(base),
        RoomStorageSelection {
        override fun getApplicationContext(): Context = this
        override val usesRoomStorage: Boolean = true
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }
}
