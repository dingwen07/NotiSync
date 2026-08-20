package net.extrawdw.apps.notisync.data.storage.importer.legacy

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.io.File

/** Exact source column contract used before any source rows are exposed to a reader. */
internal data class LegacyColumnContract(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val primaryKeyOrdinal: Int,
    val defaultValue: String? = null,
)

internal data class LegacyIndexContract(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
)

internal data class LegacyTableContract(
    val name: String,
    val columns: List<LegacyColumnContract>,
    val indexes: List<LegacyIndexContract> = emptyList(),
)

/**
 * Open and consume one legacy database through a read-only, WAL-consistent transaction.
 *
 * This is intentionally a small raw SQLite adapter rather than SQLiteOpenHelper.  Opening a legacy
 * source with a helper could invoke onCreate/onUpgrade/onConfigure and therefore mutate a recovery
 * copy or silently accept an unknown source schema.  No Cursor or SQLiteDatabase escapes the lambda.
 */
internal fun <T> readLegacySqliteSnapshot(
    file: File,
    source: LegacySourceId,
    expectedTables: List<LegacyTableContract>,
    block: (LegacySqliteSource, SQLiteDatabase) -> T,
): T {
    if (file.name != source.fileName) throw LegacyImportException.filename(source)
    if (!file.exists()) throw LegacyImportException.missing(source)

    val database = try {
        SQLiteDatabase.openDatabase(
            file,
            SQLiteDatabase.OpenParams.Builder()
                .setOpenFlags(SQLiteDatabase.OPEN_READONLY)
                .build(),
        )
    } catch (failure: Throwable) {
        throw LegacyImportException.io(source, failure)
    }

    try {
        var transactionStarted = false
        var frameworkTransaction = false
        var rawTransactionCompleted = false
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                database.beginTransactionReadOnly()
                frameworkTransaction = true
            } else {
                // API 34 has no public read-only transaction helper. A deferred SQL transaction pins the same
                // WAL snapshot without requesting a write lock on the OPEN_READONLY connection.
                database.execSQL("BEGIN DEFERRED TRANSACTION")
            }
            transactionStarted = true
            val version = database.pragmaInt("user_version")
            if (version != source.userVersion) throw LegacyImportException.version(source, version)
            database.requireExactTables(source, expectedTables)
            database.requireQuickCheck(source)
            val result = block(
                LegacySqliteSource(
                    id = source,
                    fileName = file.name,
                    userVersion = version,
                ),
                database,
            )
            if (!frameworkTransaction) {
                database.execSQL("COMMIT")
                rawTransactionCompleted = true
            }
            return result
        } catch (failure: LegacyImportException) {
            throw failure
        } catch (failure: Throwable) {
            throw LegacyImportException.io(source, failure)
        } finally {
            if (transactionStarted) {
                if (frameworkTransaction) {
                    // No setTransactionSuccessful(): this is a read-only snapshot with nothing to commit.
                    database.endTransaction()
                } else if (!rawTransactionCompleted) {
                    runCatching { database.execSQL("ROLLBACK") }
                }
            }
        }
    } finally {
        database.close()
    }
}

internal fun SQLiteDatabase.pragmaInt(name: String): Int = rawQuery(
    "PRAGMA $name",
    emptyArray(),
).use { cursor ->
    if (!cursor.moveToFirst()) error("PRAGMA $name returned no row")
    cursor.getInt(0)
}

private fun SQLiteDatabase.requireQuickCheck(source: LegacySourceId) {
    rawQuery("PRAGMA quick_check", emptyArray()).use { cursor ->
        if (!cursor.moveToFirst()) throw LegacyImportException.quickCheck(source, "no result")
        val result = cursor.getString(0)
        if (result != "ok") throw LegacyImportException.quickCheck(source, result)
        // quick_check can return multiple diagnostic rows when corruption is present.  Requiring
        // the first row to be "ok" is insufficient, so consume and reject any later row too.
        while (cursor.moveToNext()) {
            throw LegacyImportException.quickCheck(source, cursor.getString(0))
        }
    }
}

private fun SQLiteDatabase.requireExactTables(
    source: LegacySourceId,
    expectedTables: List<LegacyTableContract>,
) {
    val expectedByName = expectedTables.associateBy { it.name }
    val actualNames = rawQuery(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        emptyArray(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    // Android's SQLiteOpenHelper may add android_metadata.  It is framework bookkeeping, not a
    // source-owned table, and is accepted only with its exact one-column shape below.
    val allowedNames = expectedByName.keys + ANDROID_METADATA.name
    val unexpected = actualNames.filterNot { it in allowedNames }
    val missing = expectedByName.keys.filterNot { it in actualNames }
    if (unexpected.isNotEmpty() || missing.isNotEmpty()) {
        throw LegacyImportException.schema(
            source,
            "table set differs (missing=${missing.sorted()}, unexpected=${unexpected.sorted()})",
        )
    }

    expectedTables.forEach { contract ->
        val actualColumns = tableInfo(contract.name)
        if (actualColumns != contract.columns) {
            throw LegacyImportException.schema(source, "columns differ for ${contract.name}")
        }
        if (contract.indexes.isNotEmpty()) {
            requireExactIndexes(source, contract)
        }
    }

    if (ANDROID_METADATA.name in actualNames && tableInfo(ANDROID_METADATA.name) != ANDROID_METADATA.columns) {
        throw LegacyImportException.schema(source, "columns differ for ${ANDROID_METADATA.name}")
    }
}

private fun SQLiteDatabase.requireExactIndexes(
    source: LegacySourceId,
    table: LegacyTableContract,
) {
    val expectedByName = table.indexes.associateBy { it.name }
    val actualHeaders = rawQuery("PRAGMA index_list(${table.name})", emptyArray()).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                // SQLite creates an implementation-defined autoindex for a PRIMARY KEY. It is
                // checked indirectly by the exact primary-key column contract, not by its name.
                if (!name.startsWith("sqlite_autoindex_")) {
                    add(
                        LegacyIndexContract(
                            name = name,
                            unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) != 0,
                            columns = emptyList(),
                        ),
                    )
                }
            }
        }
    }
    val actual = actualHeaders.map { it.copy(columns = indexColumns(it.name)) }
    val actualByName = actual.associateBy { it.name }
    if (actualByName.keys != expectedByName.keys) {
        throw LegacyImportException.schema(source, "indexes differ for ${table.name}")
    }
    expectedByName.forEach { (name, expected) ->
        if (actualByName[name] != expected) {
            throw LegacyImportException.schema(source, "index definition differs for ${table.name}")
        }
    }
}

private fun SQLiteDatabase.indexColumns(indexName: String): List<String> = rawQuery(
    "PRAGMA index_info('${indexName.replace("'", "''")}')",
    emptyArray(),
).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
    }
}

private fun SQLiteDatabase.tableInfo(tableName: String): List<LegacyColumnContract> = rawQuery(
    "PRAGMA table_info($tableName)",
    emptyArray(),
).use { cursor ->
    buildList {
        while (cursor.moveToNext()) {
            add(
                LegacyColumnContract(
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")).uppercase(),
                    notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0,
                    primaryKeyOrdinal = cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                    defaultValue = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("dflt_value")),
                ),
            )
        }
    }
}

private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

/** BLOB values are copied before the Cursor is closed, so no Android storage object leaks out. */
internal fun Cursor.copyBlob(index: Int): ByteArray = getBlob(index).copyOf()

internal fun Cursor.optionalLong(index: Int): Long? = if (isNull(index)) null else getLong(index)

private val ANDROID_METADATA = LegacyTableContract(
    name = "android_metadata",
    columns = listOf(
        LegacyColumnContract(
            name = "locale",
            type = "TEXT",
            notNull = false,
            primaryKeyOrdinal = 0,
        ),
    ),
)
