package net.extrawdw.apps.notisync.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.extrawdw.apps.notisync.NotiSyncApp
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabaseEncryption

/** ADB-only plaintext snapshot. Neither this class nor its manifest entry is built into release. */
class OperationalDatabaseExportProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // Provider creation precedes Application.onCreate: never wait for storage here.
        OperationalDatabaseDebugExport.removeInterruptedExports(requireNotNull(context))
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val appContext = requireNotNull(context).applicationContext
        enforceDebugShellAccess(
            Binder.getCallingUid(),
            appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        if (mode != "r" || uri.scheme != "content" ||
            uri.authority != "${appContext.packageName}.debug.database" ||
            uri.encodedPath != "/operational" || uri.query != null || uri.fragment != null
        ) {
            throw FileNotFoundException("Only the read-only operational snapshot is available")
        }
        // Wait for migration, but allow diagnostics even if later graph initialization fails.
        runBlocking {
            withTimeout(60_000) { (appContext as NotiSyncApp).awaitStorageReady() }
        }
        return OperationalDatabaseDebugExport.openSnapshot(appContext)
    }

    override fun getType(uri: Uri): String = "application/vnd.sqlite3"
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor = throw UnsupportedOperationException("Use content read")
    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("Read-only snapshot")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only snapshot")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only snapshot")
}

internal fun enforceDebugShellAccess(callingUid: Int, debuggable: Boolean) {
    if (!debuggable || (callingUid != 2000 && callingUid != 0)) {
        throw SecurityException("Database export requires a debug build and adb shell")
    }
}

internal object OperationalDatabaseDebugExport {
    private const val DIRECTORY = "debug-database-export"

    @Synchronized
    fun removeInterruptedExports(context: Context) {
        File(context.noBackupFilesDir, DIRECTORY).listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("operational-")) file.delete()
        }
    }

    @Synchronized
    fun openSnapshot(context: Context): ParcelFileDescriptor {
        val directory = File(context.noBackupFilesDir, DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create snapshot directory" }
        val snapshot = File.createTempFile("operational-", ".sqlite", directory)
        try {
            OperationalDatabaseEncryption.open(context).use { database ->
                // Pin the primary connection throughout ATTACH and export. execSQL special-cases
                // ATTACH by disabling main WAL; a prepared statement avoids changing app journaling.
                database.beginTransactionNonExclusive()
                try {
                    database.compileStatement("ATTACH DATABASE ? AS debug_export KEY ''").use { attach ->
                        attach.bindString(1, snapshot.absolutePath)
                        attach.execute()
                    }
                    // Keep the snapshot self-contained, including committed source WAL contents.
                    database.rawQuery("PRAGMA debug_export.journal_mode", emptyArray<String>()).use {
                        check(it.moveToFirst() && it.getString(0).equals("delete", ignoreCase = true))
                    }
                    database.rawQuery("SELECT sqlcipher_export('debug_export')", emptyArray<String>()).use {
                        check(it.moveToFirst()) { "Snapshot export failed" }
                    }
                    database.execSQL("PRAGMA debug_export.user_version = ${database.version}")
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                // Closing this dedicated connection also detaches the completed snapshot.
            }
            val descriptor = ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY)
            // The shell receives an open descriptor; no named plaintext file remains after return.
            if (!snapshot.delete()) {
                descriptor.close()
                throw FileNotFoundException("Cannot remove temporary snapshot")
            }
            return descriptor
        } finally {
            snapshot.delete()
            listOf("-journal", "-wal", "-shm").forEach { suffix ->
                File(snapshot.path + suffix).delete()
            }
        }
    }
}
