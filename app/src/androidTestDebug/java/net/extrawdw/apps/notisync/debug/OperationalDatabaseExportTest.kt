package net.extrawdw.apps.notisync.debug

import android.content.ComponentName
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseEncryption
import net.extrawdw.apps.notisync.testsupport.RoomStorageTestContext
import net.extrawdw.apps.notisync.testsupport.initializeOperationalTestDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalDatabaseExportTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = RoomStorageTestContext(base, "debug-export")
    private val exported = File(base.cacheDir, "operational-export-test.sqlite")

    @After
    fun cleanup() {
        context.deleteDatabase(OperationalDatabase.DATABASE_NAME)
        SQLiteDatabase.deleteDatabase(exported)
    }

    @Test
    fun snapshotContainsCommittedWalDataAndLeavesNoNamedPlaintextCopy() = runBlocking {
        context.deleteDatabase(OperationalDatabase.DATABASE_NAME)
        initializeOperationalTestDatabase(context)
        OperationalDatabaseEncryption.open(context).use { writer ->
            writer.rawQuery("PRAGMA wal_autocheckpoint = 0", emptyArray<String>()).use { it.moveToFirst() }
            writer.execSQL("INSERT INTO notification_capture_state VALUES(1, 987654321)")
            writer.execSQL(
                "INSERT INTO desktop_applications VALUES('export-test', 'Export test', 1, 'STOP', '[]', '[]', ?)",
                arrayOf(byteArrayOf(0, 1, 127, -1)),
            )
            ParcelFileDescriptor.AutoCloseInputStream(
                OperationalDatabaseDebugExport.openSnapshot(context),
            ).use { input -> exported.outputStream().use { input.copyTo(it) } }
            assertTrue(File(context.noBackupFilesDir, "debug-database-export").listFiles().orEmpty().isEmpty())
            writer.rawQuery("PRAGMA main.journal_mode", emptyArray<String>()).use {
                assertTrue(it.moveToFirst())
                assertEquals("wal", it.getString(0))
            }
            SQLiteDatabase.openDatabase(exported.path, null, SQLiteDatabase.OPEN_READONLY).use { snapshot ->
                assertEquals(OperationalDatabase.VERSION, snapshot.version)
                snapshot.rawQuery("SELECT last_seen_post_time FROM notification_capture_state", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(987654321L, it.getLong(0))
                }
                snapshot.rawQuery("SELECT icon_data FROM desktop_applications WHERE id='export-test'", null).use {
                    assertTrue(it.moveToFirst())
                    assertArrayEquals(byteArrayOf(0, 1, 127, -1), it.getBlob(0))
                }
                snapshot.rawQuery("PRAGMA integrity_check", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
            }
        }
    }

    @Test
    fun providerRequiresDumpPermissionAndStillRejectsApplicationUid() {
        val provider = base.packageManager.getProviderInfo(
            ComponentName(base, OperationalDatabaseExportProvider::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertEquals("android.permission.DUMP", provider.readPermission)
        assertEquals("android.permission.DUMP", provider.writePermission)
        assertFalse(provider.grantUriPermissions)
        assertThrows(SecurityException::class.java) {
            base.contentResolver.openFileDescriptor(
                Uri.parse("content://${base.packageName}.debug.database/operational"), "r",
            )?.close()
        }
    }
}
