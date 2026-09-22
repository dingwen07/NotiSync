package net.extrawdw.apps.notisync.data.storage.operational

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Process-scoped approvals have no durable identity and must remain in the volatile store. */
internal fun installSshAuthorizationGuards(connection: SQLiteConnection) {
    listOf("INSERT", "UPDATE").forEach { operation ->
        connection.execSQL("""
            CREATE TRIGGER IF NOT EXISTS ssh_authorization_disk_scope_${operation.lowercase()}
            BEFORE $operation ON ssh_remembered_authorizations
            WHEN NEW.scope NOT IN ('PEER', 'PEER_HOST_KEY')
            BEGIN
                SELECT RAISE(ABORT, 'SSH process authorizations must remain in memory');
            END
        """.trimIndent())
    }
}

/** Discard preexisting invalid rows before installing the invariant for future writes. */
internal fun migrateSshAuthorizationScopes4To5(connection: SQLiteConnection) {
    connection.execSQL("DELETE FROM ssh_remembered_authorizations WHERE scope NOT IN ('PEER', 'PEER_HOST_KEY')")
    installSshAuthorizationGuards(connection)
}
