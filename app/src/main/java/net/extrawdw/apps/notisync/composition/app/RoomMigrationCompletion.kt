package net.extrawdw.apps.notisync.composition.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.roomMigrationState by preferencesDataStore(name = "room_migration_state")
private val ROOM_MIGRATION_COMPLETE = booleanPreferencesKey("migration_complete")

/** Process-independent app-init switch; the retained v51 preferences remain a read-only migration source. */
internal class RoomMigrationCompletion(context: Context) {
    private val dataStore = context.applicationContext.roomMigrationState

    suspend fun isComplete(): Boolean = dataStore.data.first()[ROOM_MIGRATION_COMPLETE] == true

    suspend fun markComplete() {
        dataStore.edit { preferences -> preferences[ROOM_MIGRATION_COMPLETE] = true }
    }
}
