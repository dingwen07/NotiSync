package net.extrawdw.apps.notisync.data.storage

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.core.CoreDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseFactory
import net.extrawdw.apps.notisync.data.storage.operational.OperationalSQLiteOpenHelper
import net.extrawdw.apps.notisync.data.storage.operational.RoomOperationalApplicationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomStorageInitializationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = instrumentation.targetContext
    private val root = File(base.cacheDir, "room-storage-init-${UUID.randomUUID()}")
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun getNoBackupFilesDir(): File = File(root, "keys").also { it.mkdirs() }
    }

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = context.getDatabasePath(OperationalDatabase.DATABASE_NAME),
        driver = AndroidSQLiteDriver(),
        databaseClass = OperationalDatabase::class,
    )

    @Before
    fun setUp() {
        check(root.mkdirs())
    }

    @After
    fun tearDown() {
        OperationalDatabaseFactory.close(context)
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            context.noBackupFilesDir.listFiles().orEmpty()
                .filter { it.extension == "bin" }
                .forEach { deleteEntry(it.nameWithoutExtension) }
        }
        check(root.canonicalPath.startsWith(base.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    @Test
    fun freshStorageCreatesCurrentSchemasAndRuntimeDefaults() = runBlocking {
        initializeRoomStorage(context)

        openCore().use { database ->
            assertEquals(CoreDatabase.VERSION, database.version)
            database.rawQuery("SELECT COUNT(*) FROM dedup", emptyArray()).use {
                assertTrue(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        }
        operationalHelper().use { helper ->
            val database = helper.readableDatabase
            assertEquals(OperationalDatabase.VERSION, database.version)
            database.rawQuery("SELECT COUNT(*) FROM hotspot_credentials", emptyArray()).use {
                assertTrue(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        }
        val state = RoomOperationalApplicationState(context)
        assertEquals(0L, state.lastSeenPostTime())
        assertTrue(state.androidApps().isEmpty())
        assertTrue(state.iosApps().isEmpty())
        assertEquals(null, state.openPgpEnrollment())
        assertFalse(state.screenMirroringEnabled.value)
        assertEquals("[]", state.screenMirrorState().authorizedPeerIdsJson)
        assertEquals("[]", state.screenMirrorState().virtualDisplayPeerIdsJson)
    }

    @Test
    fun repeatedInitializationPreservesExistingRoomData() = runBlocking {
        initializeRoomStorage(context)
        openCore().use { it.execSQL("INSERT INTO dedup VALUES('retained-message', 123)") }
        operationalHelper().use { helper ->
            helper.writableDatabase.execSQL(
                "INSERT INTO screen_mirror_state VALUES(1, 1, '[\"peer\"]', NULL, 0, NULL, NULL, '[\"peer\"]')",
            )
            helper.writableDatabase.execSQL(
                "INSERT INTO hotspot_credentials VALUES('peer', 'Test Wi-Fi', NULL, 0, 0, 123)",
            )
        }

        initializeRoomStorage(context)

        openCore().use { database ->
            database.rawQuery("SELECT message_id FROM dedup", emptyArray()).use {
                assertTrue(it.moveToFirst())
                assertEquals("retained-message", it.getString(0))
            }
        }
        operationalHelper().use { helper ->
            helper.readableDatabase.rawQuery("SELECT ssid FROM hotspot_credentials", emptyArray()).use {
                assertTrue(it.moveToFirst())
                assertEquals("Test Wi-Fi", it.getString(0))
            }
        }
        val state = RoomOperationalApplicationState(context).screenMirrorState()
        assertTrue(state.enabled)
        assertEquals("[\"peer\"]", state.authorizedPeerIdsJson)
        assertEquals("[\"peer\"]", state.virtualDisplayPeerIdsJson)
    }

    @Test
    fun originalRoomSchemaIsEncryptedAndUpgradedBeforeRuntimeStoresOpen() = runBlocking {
        migrationHelper.createDatabase(1).use { connection ->
            connection.execSQL("INSERT INTO mirror_msg VALUES('peer', 'notification', 'retained-message', 123)")
            connection.execSQL("INSERT INTO screen_mirror_state VALUES(1, 1, '[\"peer\"]', NULL, 0, NULL, NULL)")
        }

        initializeRoomStorage(context)

        operationalHelper().use { helper ->
            val database = helper.readableDatabase
            assertEquals(OperationalDatabase.VERSION, database.version)
            database.rawQuery("SELECT message_id FROM mirror_message", emptyArray()).use {
                assertTrue(it.moveToFirst())
                assertEquals("retained-message", it.getString(0))
            }
        }
        val state = RoomOperationalApplicationState(context).screenMirrorState()
        assertTrue(state.enabled)
        assertEquals("[\"peer\"]", state.authorizedPeerIdsJson)
        assertEquals("[]", state.virtualDisplayPeerIdsJson)
        val header = context.getDatabasePath(OperationalDatabase.DATABASE_NAME).inputStream().use { it.readNBytes(16) }
        assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))
    }

    private fun openCore(): FrameworkSQLiteDatabase = FrameworkSQLiteDatabase.openDatabase(
        context.getDatabasePath(CoreDatabase.DATABASE_NAME).path,
        null,
        FrameworkSQLiteDatabase.OPEN_READWRITE,
    )

    private fun operationalHelper(): OperationalSQLiteOpenHelper = object : OperationalSQLiteOpenHelper(context) {}
}
