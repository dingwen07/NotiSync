package net.extrawdw.apps.notisync.data.storage.operational

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.driver.SQLCipherConnection
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperationalDatabaseEncryptionTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var context: Context
    private val databaseFile get() = context.getDatabasePath(OperationalDatabase.DATABASE_NAME)

    @Before
    fun setUp() {
        root = File(base.cacheDir, "operational-encryption-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(root, name)
            override fun getNoBackupFilesDir(): File = File(root, "keys").also { it.mkdirs() }
        }
        System.loadLibrary("sqlcipher")
    }

    @After
    fun tearDown() {
        forgetCachedPassword()
        val identifier = MessageDigest.getInstance("SHA-256")
            .digest(databaseFile.canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry("notisync-operational-key-$identifier")
        }
        check(root.canonicalPath.startsWith(base.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    @Test
    fun plaintextConversionPreservesSchemaRowsBinaryDataAndCommittedWalThenReopens() {
        val fixture = File(root, "fixture.db")
        FrameworkSQLiteDatabase.openOrCreateDatabase(fixture, null).use { writer ->
            writer.execSQL("CREATE TABLE example(id INTEGER PRIMARY KEY, detail TEXT NOT NULL, material BLOB NOT NULL)")
            writer.version = 4
            writer.execSQL("INSERT INTO example VALUES(1, 'first private review', X'000102FEFF')")
            check(writer.enableWriteAheadLogging())
            writer.rawQuery("PRAGMA wal_autocheckpoint=0", emptyArray()).use { it.moveToFirst() }
            FrameworkSQLiteDatabase.openDatabase(fixture.path, null, FrameworkSQLiteDatabase.OPEN_READONLY).use { reader ->
                reader.execSQL("BEGIN")
                reader.rawQuery("SELECT * FROM example", emptyArray()).use { it.moveToFirst() }
                writer.execSQL("INSERT INTO example VALUES(2, 'committed WAL review', X'ABCDEF')")
                listOf("", "-wal").forEach { suffix ->
                    File(fixture.path + suffix).copyTo(File(databaseFile.path + suffix))
                }
                reader.execSQL("ROLLBACK")
            }
        }
        assertTrue(File(databaseFile.path + "-wal").length() > 0)
        // An interrupted export may leave an empty destination before SQLite wrote its first page.
        File(databaseFile.path + ".encrypting").writeBytes(byteArrayOf())
        OperationalDatabaseEncryption.open(context).use { encrypted ->
            assertEquals(4, encrypted.version)
            encrypted.rawQuery("SELECT id, detail, material FROM example ORDER BY id", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToNext())
                assertEquals("first private review", cursor.getString(1))
                assertArrayEquals(byteArrayOf(0, 1, 2, -2, -1), cursor.getBlob(2))
                assertTrue(cursor.moveToNext())
                assertEquals("committed WAL review", cursor.getString(1))
                assertFalse(cursor.moveToNext())
            }
        }
        assertFalse(File(databaseFile.path + ".encrypting").exists())
        assertFalse(databaseFile.readBytes().take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
        val plainOpen = runCatching {
            FrameworkSQLiteDatabase.openDatabase(
                databaseFile.path, null, FrameworkSQLiteDatabase.OPEN_READONLY,
                { /* An intentional wrong-engine open must preserve the encrypted database. */ },
            ).use { db -> db.rawQuery("SELECT * FROM example", emptyArray()).use { it.moveToFirst() } }
        }
        assertTrue(plainOpen.isFailure)
        forgetCachedPassword()
        OperationalDatabaseEncryption.driver(context).open(databaseFile.path).use { connection ->
            connection.prepare("SELECT COUNT(*) FROM example").use { statement ->
                assertTrue(statement.step())
                assertEquals(2L, statement.getLong(0))
            }
        }
    }

    @Test
    fun missingEnvelopeNeverCreatesReplacementKeyForExistingEncryptedDatabase() {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, ByteArray(32) { 7 }, null, null).use { db ->
            db.execSQL("CREATE TABLE valuable_data(value TEXT)")
            db.execSQL("INSERT INTO valuable_data VALUES('retain this')")
        }
        val original = databaseFile.readBytes()
        assertTrue(runCatching { OperationalDatabaseEncryption.open(context).close() }.isFailure)
        assertArrayEquals(original, databaseFile.readBytes())
        assertTrue(context.noBackupFilesDir.listFiles().orEmpty().none { it.extension == "bin" })
    }

    @Test
    fun newEncryptedDatabaseUsesSameKeyForRoomDriverAndFeatureHelper() {
        val room = OperationalDatabase.create(context)
        try {
            kotlinx.coroutines.runBlocking { room.metadata().schemaObjectCount() }
            val helper = object : OperationalSQLiteOpenHelper(context) {}
            helper.use {
                it.writableDatabase.execSQL("INSERT INTO mirror_message VALUES('sender', 'source', 'request', 42)")
            }
        } finally {
            room.close()
        }
        forgetCachedPassword()
        OperationalDatabaseEncryption.open(context).use { reopened ->
            reopened.rawQuery("SELECT message_id FROM mirror_message", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("request", cursor.getString(0))
            }
        }
    }

    @Test
    fun corruptWrappedKeyEnvelopeFailsWithoutChangingEncryptedData() {
        createEncryptedFixture()
        val original = databaseFile.readBytes()
        val envelope = context.noBackupFilesDir.listFiles().orEmpty().single { it.extension == "bin" }
        envelope.writeBytes(ByteArray(64))
        forgetCachedPassword()
        assertTrue(runCatching { OperationalDatabaseEncryption.open(context).close() }.isFailure)
        assertArrayEquals(original, databaseFile.readBytes())
        assertArrayEquals(ByteArray(64), envelope.readBytes())
    }

    @Test
    fun missingKeystoreWrappingKeyFailsWithoutReplacingEnvelopeOrDatabase() {
        createEncryptedFixture()
        val original = databaseFile.readBytes()
        val envelope = context.noBackupFilesDir.listFiles().orEmpty().single { it.extension == "bin" }
        val wrappedKey = envelope.readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(envelope.nameWithoutExtension) }
        forgetCachedPassword()
        assertTrue(runCatching { OperationalDatabaseEncryption.open(context).close() }.isFailure)
        assertArrayEquals(original, databaseFile.readBytes())
        assertArrayEquals(wrappedKey, envelope.readBytes())
    }

    @Test
    fun driverStatementCloseReleasesUpstreamCursorAndCanBeRepeated() {
        createEncryptedFixture()
        OperationalDatabaseEncryption.open(context).use { database ->
            // Inspect the real 4.19.0 cursor, not a mock of the workaround's implementation.
            val upstream = SQLCipherConnection(database).prepare("SELECT value FROM valuable_data")
            val statement = ClosingSqlCipherStatement(upstream)
            assertTrue(statement.step())
            val cursorField = upstream.javaClass.getDeclaredField("cursor").apply { isAccessible = true }
            val cursor = cursorField.get(upstream) as android.database.Cursor
            assertFalse(cursor.isClosed)
            statement.close()
            assertTrue(cursor.isClosed)
            statement.close()
        }
    }

    private fun createEncryptedFixture() {
        OperationalDatabaseEncryption.driver(context).open(databaseFile.path).use { connection ->
            connection.prepare("CREATE TABLE valuable_data(value TEXT)").use { it.step() }
            connection.prepare("INSERT INTO valuable_data VALUES('retain this')").use { it.step() }
        }
    }

    /** Simulate process restart without adding key-exposing test hooks to the production API. */
    private fun forgetCachedPassword() = synchronized(OperationalDatabaseEncryption) {
        val field = OperationalDatabaseEncryption::class.java.getDeclaredField("passwords").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val passwords = field.get(OperationalDatabaseEncryption) as MutableMap<String, ByteArray>
        passwords.remove(databaseFile.canonicalPath)?.fill(0)
        Unit
    }
}
