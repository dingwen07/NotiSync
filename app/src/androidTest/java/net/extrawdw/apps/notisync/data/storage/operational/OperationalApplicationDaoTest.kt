package net.extrawdw.apps.notisync.data.storage.operational

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.testsupport.RoomStorageTestContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OperationalApplicationDaoTest {
    private val context = RoomStorageTestContext(InstrumentationRegistry.getInstrumentation().targetContext, "app-preferences")
    private lateinit var database: OperationalDatabase
    private val dao get() = database.applicationState()

    @Before
    fun open() = runBlocking {
        context.deleteDatabase(OperationalDatabase.DATABASE_NAME)
        database = OperationalDatabase.create(context)
        database.metadata().schemaObjectCount()
        Unit
    }

    @After
    fun close() {
        database.close()
        context.deleteDatabase(OperationalDatabase.DATABASE_NAME)
    }

    @Test
    fun rowAndBulkTogglesPreserveUnrelatedColumnsAndApps() = runBlocking {
        dao.setAndroidAppConfig("selected", "config")
        dao.setAndroidSeenChannels("selected", "channels")
        dao.setAndroidAppsEnabled(mapOf("selected" to true, "untouched" to true))
        dao.setAndroidAppEnabled("selected", false)
        assertEquals(AndroidAppEntity("selected", false, "config", "channels"), dao.androidApps().single { it.packageName == "selected" })
        assertTrue(dao.androidApps().single { it.packageName == "untouched" }.enabled)

        dao.recordIosApp("selected", "App", 123L)
        dao.setIosAppsEnabled(mapOf("selected" to true, "untouched" to true))
        dao.setIosAppEnabled("selected", false)
        assertEquals(IosAppEntity("selected", false, "App", 123L), dao.iosApps().single { it.bundleId == "selected" })
        assertTrue(dao.iosApps().single { it.bundleId == "untouched" }.enabled)
    }

    @Test
    fun codecUpdatesAndPruningPreserveRetainedRows() = runBlocking {
        dao.setScreenCodecPreference("first", "h264")
        dao.setScreenCodecPreference("second", "av1")
        dao.setScreenCodecPreference("first", "h265")
        dao.retainScreenCodecPreferences(setOf("first", "second"))
        assertEquals(setOf(ScreenCodecPreferenceEntity("first", "h265"), ScreenCodecPreferenceEntity("second", "av1")), dao.screenCodecPreferences().toSet())

        dao.deleteScreenCodecPreference("first")
        assertEquals(listOf(ScreenCodecPreferenceEntity("second", "av1")), dao.screenCodecPreferences())
        dao.retainScreenCodecPreferences(emptySet())
        assertTrue(dao.screenCodecPreferences().isEmpty())
    }

    @Test
    fun bulkSelectionRollsBackIfAnyRowFails() = runBlocking {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(OperationalDatabase.DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { raw ->
            raw.execSQL(
                "CREATE TRIGGER reject_test_app BEFORE INSERT ON android_apps " +
                    "WHEN NEW.package_name = 'rejected' BEGIN SELECT RAISE(ABORT, 'test failure'); END",
            )
        }
        val failure = runCatching { dao.setAndroidAppsEnabled(linkedMapOf("first" to true, "rejected" to true)) }
        assertTrue(failure.isFailure)
        assertTrue(dao.androidApps().isEmpty())
    }
}
