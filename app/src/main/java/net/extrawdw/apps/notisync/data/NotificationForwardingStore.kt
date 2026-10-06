package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.OriginPlatform

/** Local sending choices, independent of the suppression filters received from peers. */
data class NotificationForwardingPreferences(
    val localDisabledPeerIds: Set<String> = emptySet(),
    val iphoneDisabledPeerIds: Set<String> = emptySet(),
) {
    fun disabledPeerIds(origin: OriginPlatform): Set<String> = when (origin) {
        OriginPlatform.ANDROID_LOCAL -> localDisabledPeerIds
        OriginPlatform.IOS_ANCS -> iphoneDisabledPeerIds
    }

    fun isEnabled(peerId: ClientId, origin: OriginPlatform): Boolean =
        peerId.value !in disabledPeerIds(origin)
}

/** Stores only opt-outs, so both origins default ON for existing and newly paired devices. */
class NotificationForwardingStore(private val store: DataStore<Preferences>) {
    private val mutex = Mutex()
    // Load before the engine starts sending: a saved opt-out must also apply during cold start.
    private val _preferences = MutableStateFlow(runBlocking { decode(store.data.first()) })
    val preferences = _preferences.asStateFlow()

    fun recipientsToExclude(origin: OriginPlatform): Set<ClientId> =
        _preferences.value.disabledPeerIds(origin).mapTo(mutableSetOf(), ::ClientId)

    suspend fun setEnabled(peerId: ClientId, origin: OriginPlatform, enabled: Boolean) = mutex.withLock {
        val key = when (origin) {
            OriginPlatform.ANDROID_LOCAL -> LOCAL_DISABLED_KEY
            OriginPlatform.IOS_ANCS -> IPHONE_DISABLED_KEY
        }
        val persisted = store.edit { current ->
            val disabled = current[key].orEmpty()
            current[key] = if (enabled) disabled - peerId.value else disabled + peerId.value
        }
        _preferences.value = decode(persisted)
    }

    /** Keep choices through revocation/restoration; discard them only when the roster entry is gone. */
    suspend fun retainPeers(peerIds: Set<String>) = mutex.withLock {
        val current = _preferences.value
        if ((current.localDisabledPeerIds + current.iphoneDisabledPeerIds).all { it in peerIds }) {
            return@withLock
        }
        val persisted = store.edit { stored ->
            stored[LOCAL_DISABLED_KEY] = stored[LOCAL_DISABLED_KEY].orEmpty().intersect(peerIds)
            stored[IPHONE_DISABLED_KEY] = stored[IPHONE_DISABLED_KEY].orEmpty().intersect(peerIds)
        }
        _preferences.value = decode(persisted)
    }

    private fun decode(stored: Preferences) = NotificationForwardingPreferences(
        localDisabledPeerIds = stored[LOCAL_DISABLED_KEY].orEmpty(),
        iphoneDisabledPeerIds = stored[IPHONE_DISABLED_KEY].orEmpty(),
    )

    private companion object {
        val LOCAL_DISABLED_KEY = stringSetPreferencesKey("notification_forwarding_local_disabled_peers")
        val IPHONE_DISABLED_KEY = stringSetPreferencesKey("notification_forwarding_iphone_disabled_peers")
    }
}
