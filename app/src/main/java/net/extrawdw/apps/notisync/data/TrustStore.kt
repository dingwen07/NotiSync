package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.peer.ports.TrustPersistence
import net.extrawdw.notisync.protocol.crypto.IdentitySigner

/** Android DataStore adapter for the platform-neutral, signed peer trust store. */
class TrustStore(
    store: DataStore<Preferences>,
    scope: CoroutineScope,
    identity: IdentitySigner,
) : net.extrawdw.notisync.peer.trust.TrustStore(
    DataStoreTrustPersistence(store, scope),
    identity,
) {
    companion object {
        const val REVOKE_PURGE_DELAY_MS =
            net.extrawdw.notisync.peer.trust.TrustStore.REVOKE_PURGE_DELAY_MS
    }
}

private class DataStoreTrustPersistence(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : TrustPersistence {
    private val lock = Any()
    private val values = runBlocking {
        val preferences = store.data.first()
        TRUST_KEYS.associateWith { preferences[stringPreferencesKey(it)] }.toMutableMap()
    }

    override fun read(key: String): String? = synchronized(lock) { values[key] }

    override fun write(values: Map<String, String?>) {
        synchronized(lock) { this.values.putAll(values) }
        scope.launch {
            store.edit { preferences ->
                values.forEach { (key, value) ->
                    val preferenceKey = stringPreferencesKey(key)
                    if (value == null) preferences.remove(preferenceKey)
                    else preferences[preferenceKey] = value
                }
            }
        }
    }

    private companion object {
        val TRUST_KEYS = listOf(
            net.extrawdw.notisync.peer.trust.TrustStore.ENTRIES_KEY,
            net.extrawdw.notisync.peer.trust.TrustStore.CARDS_KEY,
            net.extrawdw.notisync.peer.trust.TrustStore.OVERLAYS_KEY,
            net.extrawdw.notisync.peer.trust.TrustStore.EPOCHS_KEY,
            net.extrawdw.notisync.peer.trust.TrustStore.SIGNATURE_KEY,
        )
    }
}
