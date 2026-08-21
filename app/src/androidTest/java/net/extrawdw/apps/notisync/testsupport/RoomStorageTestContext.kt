package net.extrawdw.apps.notisync.testsupport

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase

/** Gives raw-store instrumentation tests an isolated Room-owned Operational database. */
internal class RoomStorageTestContext(
    base: Context,
    private val namespace: String,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getDatabasePath(name: String): File =
        baseContext.getDatabasePath("room-test-$namespace-$name")

    override fun deleteDatabase(name: String): Boolean =
        SQLiteDatabase.deleteDatabase(getDatabasePath(name))
}

internal fun initializeOperationalTestDatabase(context: Context) {
    val database = OperationalDatabase.create(context)
    try {
        runBlocking { database.metadata().schemaObjectCount() }
    } finally {
        database.close()
    }
}
