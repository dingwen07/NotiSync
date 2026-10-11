package net.extrawdw.apps.notisync.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

internal val Context.notiSyncDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "notisync")
