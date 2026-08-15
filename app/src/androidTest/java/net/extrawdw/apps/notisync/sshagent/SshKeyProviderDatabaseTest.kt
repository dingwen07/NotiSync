package net.extrawdw.apps.notisync.sshagent

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SshKeyProviderDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseFile: File get() = context.getDatabasePath(DATABASE_NAME)
    private var store: SshKeyProviderStore? = null

    @Before
    fun clearDatabase() {
        context.deleteDatabase(DATABASE_NAME)
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
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
            database.execSQL("CREATE TABLE release_marker(value TEXT NOT NULL)")
            database.execSQL("INSERT INTO release_marker(value) VALUES ('preserve-me')")
            database.version = version
        }
    }

    private companion object {
        const val DATABASE_NAME = "ssh-key-provider.sqlite3"
    }
}
