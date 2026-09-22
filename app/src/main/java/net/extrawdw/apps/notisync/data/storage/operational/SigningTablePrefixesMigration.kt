package net.extrawdw.apps.notisync.data.storage.operational

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Give every signing-domain table its SSH or Seal prefix without rewriting its stored rows. */
internal fun migrateSigningTablePrefixes4To5(connection: SQLiteConnection) {
    connection.execSQL("ALTER TABLE provider_state RENAME TO ssh_provider_state")
    connection.execSQL("ALTER TABLE authorization_floors RENAME TO ssh_authorization_floors")
    connection.execSQL("ALTER TABLE openpgp_enrollment RENAME TO seal_enrollment")
}
