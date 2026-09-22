package net.extrawdw.apps.notisync.data.storage.operational

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/** Keyed access for the existing feature stores; Room remains the sole schema owner. */
abstract class OperationalSQLiteOpenHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    OperationalDatabase.DATABASE_NAME,
    OperationalDatabaseEncryption.password(context),
    null,
    OperationalDatabase.VERSION,
    0,
    OperationalDatabaseEncryption.preserveOnCorruption,
    null,
    true,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase): Unit =
        error("Room must create and migrate the operational database before runtime stores open")

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int): Unit =
        error("Room must migrate the operational database before runtime stores open")
}

/** Equivalent to AndroidX's platform SQLite transaction extension, for the SQLCipher connection. */
internal inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    return try {
        val result = block()
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}
