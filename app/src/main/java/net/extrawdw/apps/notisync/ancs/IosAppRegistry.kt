package net.extrawdw.apps.notisync.ancs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.ProtocolCodec

/** A discovered iOS app: bundle id + best-known display name + last-seen time. */
@Serializable
data class IosApp(val bundleId: String, val displayName: String, val lastSeen: Long)

/**
 * The iOS-side analogue of [net.extrawdw.apps.notisync.data.AppSelectionRepository]: per-bundle-id mirroring
 * is **opt-in** (an allowlist; nothing is captured by default), plus the set of apps discovered so far.
 *
 * iOS can't be enumerated, so [discovered] grows only as apps actually post notifications over ANCS — the
 * iOS tab lists those and lets the user enable the ones they want. Enabled bundle ids persist; the
 * discovered set is in-memory (rebuilt each session as notifications arrive).
 */
class IosAppRegistry(private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    private val enabledKey = stringPreferencesKey("ancs_enabled_bundles_json")
    private val discoveredKey = stringPreferencesKey("ancs_discovered_apps_json")
    // Start empty and hydrate asynchronously: a blocking DataStore read in the constructor would stall the
    // main thread (this is built in AppGraph.init on every cold start). The bridge takes seconds to connect,
    // so the load lands well before the first notification; the UI just fills in once it arrives.
    private val _enabled = MutableStateFlow<Set<String>>(emptySet())
    val enabled: StateFlow<Set<String>> = _enabled

    // Persisted so the iOS-apps list survives process death (the bridge often outlives a UI process).
    private val _discovered = MutableStateFlow<Map<String, IosApp>>(emptyMap())
    val discovered: StateFlow<Map<String, IosApp>> = _discovered

    // Completed once the persisted allowlist has loaded; [isEnabled] awaits it so a notification arriving in
    // the startup window is never dropped as "not enabled" before the user's opt-ins are available.
    private val hydrated = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                val prefs = runCatching { store.data.first() }.getOrNull()
                if (prefs != null) {
                    prefs[enabledKey]?.let { json ->
                        runCatching { ProtocolCodec.decodeFromJson<Set<String>>(json) }.getOrNull()
                    }?.let { loaded -> _enabled.update { inSession -> loaded + inSession } } // keep any enable that raced in
                    prefs[discoveredKey]?.let { json ->
                        runCatching { ProtocolCodec.decodeFromJson<Map<String, IosApp>>(json) }.getOrNull()
                    }?.let { loaded -> _discovered.update { inSession -> loaded + inSession } } // a recordSeen during load wins
                }
            } finally {
                hydrated.complete(Unit) // always release awaiters, even on a failed/cancelled load
            }
        }
    }

    /** Suspends until the persisted allowlist has loaded, then answers — so an iOS notification arriving in
     *  the startup window isn't silently dropped as "not enabled" before the user's opt-ins are available. */
    suspend fun isEnabled(bundleId: String): Boolean {
        hydrated.await()
        return bundleId in _enabled.value
    }

    fun setEnabled(bundleId: String, enabled: Boolean) {
        _enabled.update { if (enabled) it + bundleId else it - bundleId }
        val json = ProtocolCodec.encodeToJson(_enabled.value)
        scope.launch { store.edit { it[enabledKey] = json } }
    }

    /** Record that [bundleId] (named [displayName]) just posted, so the tab can surface it for opt-in. */
    fun recordSeen(bundleId: String, displayName: String, timeMillis: Long) {
        val existing = _discovered.value[bundleId]
        if (existing != null && timeMillis <= existing.lastSeen && displayName == existing.displayName) return
        _discovered.update { it + (bundleId to IosApp(bundleId, displayName, maxOf(timeMillis, existing?.lastSeen ?: 0L))) }
        // Persist only when the app set or its display name changes — NOT on every lastSeen bump, which would
        // re-serialize the whole (unbounded) map and disk-write on every incoming notification. lastSeen stays
        // live in memory for sort order and is flushed opportunistically with the next new-app / rename write.
        if (existing == null || existing.displayName != displayName) {
            val json = ProtocolCodec.encodeToJson(_discovered.value)
            scope.launch { store.edit { it[discoveredKey] = json } }
        }
    }
}
