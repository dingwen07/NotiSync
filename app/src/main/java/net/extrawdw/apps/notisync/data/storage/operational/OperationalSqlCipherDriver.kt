package net.extrawdw.apps.notisync.data.storage.operational

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement

/** Keep the upstream driver/pool behavior, while releasing each prepared statement's cursor. */
internal class OperationalSqlCipherDriver(private val delegate: SQLiteDriver) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement =
                ClosingSqlCipherStatement(connection.prepare(sql))
        }
    }
}

/**
 * SQLCipher 4.19.0's SQLCipherStatement.close() only sets a flag; reset() closes its Cursor.
 * Remove this workaround when an upstream upgrade fixes cursor disposal. Verified published source:
 * https://repo.maven.apache.org/maven2/net/zetetic/sqlcipher-android/4.19.0/sqlcipher-android-4.19.0-sources.jar
 */
internal class ClosingSqlCipherStatement(private val delegate: SQLiteStatement) : SQLiteStatement by delegate {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        try {
            delegate.reset()
        } finally {
            delegate.close()
        }
    }
}
