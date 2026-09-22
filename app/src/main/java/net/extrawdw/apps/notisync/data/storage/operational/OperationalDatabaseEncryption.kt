package net.extrawdw.apps.notisync.data.storage.operational

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.sqlite.SQLiteDriver
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

/**
 * The operational file has one random SQLCipher key, wrapped by a non-exportable Android Keystore
 * key. Unwrapping happens once per process/database, never once per history row. Private signing
 * keys retain their independent custody and authentication requirements.
 */
internal object OperationalDatabaseEncryption {
    private const val KEY_PREFIX = "notisync-operational-key-"
    private val keyHeader = byteArrayOf(0x4e, 0x53, 0x44, 0x01)
    private val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val passwords = mutableMapOf<String, ByteArray>()
    private var libraryLoaded = false

    // Never allow a failed open (wrong key, damaged file, unavailable Keystore) to delete user data.
    internal val preserveOnCorruption = DatabaseErrorHandler { _, failure -> throw failure }

    fun driver(context: Context): SQLiteDriver =
        OperationalSqlCipherDriver(SQLCipherDriver(password(context), null, preserveOnCorruption))

    /** A separate keyed connection for migrations/diagnostics; callers must close it. */
    fun open(context: Context): SQLiteDatabase {
        val database = context.applicationContext.getDatabasePath(OperationalDatabase.DATABASE_NAME)
        check(database.isFile) { "Room must create the operational database before opening a raw connection" }
        return SQLiteDatabase.openDatabase(
            database.absolutePath,
            password(context),
            null,
            // SQLITE_OPEN_CREATE also permits ATTACH to create a migration/debug export target.
            // The explicit existence check above prevents accidental creation of the main database.
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING or
                SQLiteDatabase.CREATE_IF_NECESSARY,
            preserveOnCorruption,
            null,
        )
    }

    @Synchronized
    internal fun password(context: Context): ByteArray {
        loadLibrary()
        val appContext = context.applicationContext
        val database = appContext.getDatabasePath(OperationalDatabase.DATABASE_NAME)
        val identity = database.canonicalPath
        passwords[identity]?.let { return it.copyOf() }
        check(database.parentFile!!.let { it.isDirectory || it.mkdirs() })
        val identifier = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        val envelope = File(appContext.noBackupFilesDir, "$KEY_PREFIX$identifier.bin")
        val lock = File(appContext.noBackupFilesDir, "$KEY_PREFIX$identifier.lock")
        RandomAccessFile(lock, "rw").channel.use { channel ->
            channel.lock().use {
                val pending = File(database.path + ".encrypting")
                val secret = unwrapOrCreateKey(
                    envelope,
                    KEY_PREFIX + identifier,
                    encryptedFileExists = listOf(database, pending).any {
                        it.isFile && it.length() > 0 && !isPlaintext(it)
                    },
                )
                // SQLCipher's documented raw-key syntax avoids a password KDF on random key material.
                val password = ("x'" + secret.joinToString("") { "%02x".format(it) } + "'")
                    .toByteArray(Charsets.US_ASCII)
                secret.fill(0)
                try {
                    if (database.isFile && database.length() > 0 && isPlaintext(database)) {
                        encryptPlaintext(database, pending, password)
                    } else if (pending.exists()) {
                        // An atomic rename can only leave a pending file while the original still exists.
                        check(database.isFile) { "Operational encryption source is missing; preserving recovery data" }
                        verifyEncrypted(database, password)
                        deleteFamily(pending)
                    }
                    passwords[identity] = password
                    return password.copyOf()
                } catch (failure: Throwable) {
                    password.fill(0)
                    throw failure
                }
            }
        }
    }

    private fun loadLibrary() {
        if (!libraryLoaded) {
            System.loadLibrary("sqlcipher")
            libraryLoaded = true
        }
    }

    private fun unwrapOrCreateKey(file: File, alias: String, encryptedFileExists: Boolean): ByteArray {
        val atomic = AtomicFile(file)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aad = ("NotiSync operational database key v1:" + alias).toByteArray()
        if (file.exists() || File(file.path + ".bak").exists()) {
            val envelope = atomic.readFully()
            check(envelope.size == keyHeader.size + 12 + 32 + 16 &&
                envelope.copyOfRange(0, keyHeader.size).contentEquals(keyHeader)) {
                "Invalid operational database key envelope; preserving database"
            }
            val wrappingKey = keyStore.getKey(alias, null) as? SecretKey
                ?: error("Operational database wrapping key is unavailable; preserving database")
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, envelope.copyOfRange(4, 16)))
                updateAAD(aad)
                doFinal(envelope, 16, envelope.size - 16)
            }
        }
        check(!encryptedFileExists) {
            "Operational database encryption key is missing; preserving encrypted database"
        }
        val wrappingKey = (keyStore.getKey(alias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey)
            updateAAD(aad)
        }
        val output = atomic.startWrite()
        try {
            output.write(keyHeader + cipher.iv + cipher.doFinal(secret))
            output.fd.sync()
            atomic.finishWrite(output)
            syncDirectory(file.parentFile!!)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            secret.fill(0)
            throw failure
        }
        return secret
    }

    /**
     * Copy first, verify, then replace with one same-filesystem atomic rename. Before the rename a
     * crash leaves the complete plaintext source (including committed WAL); afterwards it leaves a
     * complete encrypted source that Room can migrate transactionally. No plaintext backup survives.
     */
    private fun encryptPlaintext(database: File, pending: File, password: ByteArray) {
        deleteFamily(pending)
        val version: Int
        val counts: Map<String, Long>
        SQLiteDatabase.openDatabase(
            database.absolutePath, byteArrayOf(), null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            preserveOnCorruption, null,
        ).use { source ->
            source.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Operational WAL checkpoint is busy" }
            }
            source.rawQuery("PRAGMA journal_mode=DELETE", emptyArray()).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("delete", true))
            }
            integrityCheck(source)
            version = source.version
            counts = tableCounts(source)
            source.execSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?",
                arrayOf(pending.absolutePath, password.toString(Charsets.US_ASCII)),
            )
            try {
                source.beginTransaction()
                try {
                    source.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray()).use { it.moveToFirst() }
                    source.execSQL("PRAGMA encrypted.user_version=$version")
                    source.setTransactionSuccessful()
                } finally {
                    source.endTransaction()
                }
            } finally {
                source.execSQL("DETACH DATABASE encrypted")
            }
        }
        verifyEncrypted(pending, password, version, counts)
        RandomAccessFile(pending, "rw").use { it.fd.sync() }
        check(listOf("-wal", "-journal").none { File(database.path + it).let { f -> f.exists() && f.length() > 0 } }) {
            "Operational source still has a journal; preserving original"
        }
        Os.rename(pending.absolutePath, database.absolutePath)
        syncDirectory(database.parentFile!!)
    }

    private fun syncDirectory(file: File) {
        val directory = Os.open(file.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(directory)
        } finally {
            Os.close(directory)
        }
    }

    private fun verifyEncrypted(
        file: File,
        password: ByteArray,
        version: Int? = null,
        counts: Map<String, Long>? = null,
    ) {
        check(!isPlaintext(file)) { "Operational encryption produced a plaintext database" }
        SQLiteDatabase.openDatabase(
            file.absolutePath, password, null, SQLiteDatabase.OPEN_READWRITE,
            preserveOnCorruption, null,
        ).use { encrypted ->
            integrityCheck(encrypted)
            encrypted.rawQuery("PRAGMA cipher_integrity_check", emptyArray()).use { cursor ->
                check(!cursor.moveToFirst()) { "Operational encryption authentication check failed" }
            }
            check(version == null || encrypted.version == version) { "Operational schema version was not preserved" }
            check(counts == null || tableCounts(encrypted) == counts) { "Operational rows were not preserved" }
        }
    }

    private fun integrityCheck(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                "Operational database integrity check failed"
            }
        }
    }

    private fun tableCounts(database: SQLiteDatabase): Map<String, Long> {
        val tables = database.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", emptyArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            val identifier = "\"" + table.replace("\"", "\"\"") + "\""
            database.rawQuery("SELECT COUNT(*) FROM $identifier", emptyArray()).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
        }
    }

    private fun isPlaintext(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(sqliteHeader.size)
        input.read(header) == header.size && header.contentEquals(sqliteHeader)
    }

    private fun deleteFamily(file: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val member = File(file.path + suffix)
            check(!member.exists() || member.delete()) { "Could not remove incomplete operational encryption copy" }
        }
    }
}
