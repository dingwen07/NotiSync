package net.extrawdw.apps.notisync.ios

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
import net.extrawdw.apps.notisync.data.storage.operational.OperationalApplicationState

/** A discovered iOS app: bundle id + best-known display name + last-seen time. */
@Serializable
data class IosApp(val bundleId: String, val displayName: String, val lastSeen: Long)

/** Bundle ids that should stay visible in the iPhone app list but must never be enabled for mirroring. */
internal object IosBundleIdExclusions {
    private val excluded = setOf(
        "net.extrawdw.apps.NotiSync",
        "net.extrawdw.apps.NotiSync.NotificationService",
    ).mapTo(mutableSetOf()) { normalize(it) }

    fun isExcluded(bundleId: String): Boolean = normalize(bundleId) in excluded

    fun filterEnabled(bundleIds: Set<String>): Set<String> =
        bundleIds.filterNotTo(LinkedHashSet()) { isExcluded(it) }

    private fun normalize(bundleId: String): String = bundleId.trim().lowercase()
}

/**
 * The iOS-side analogue of [net.extrawdw.apps.notisync.data.AppSelectionRepository]: per-bundle-id mirroring
 * is **opt-in** (an allowlist; nothing is captured by default), plus the set of apps discovered so far.
 *
 * iOS can't be enumerated, so [discovered] grows only as apps actually post notifications over ANCS — the
 * iOS tab lists those and lets the user enable the ones they want. Enabled bundle ids persist; the
 * discovered set persists so previously seen apps survive process death, and a forgotten app reappears the
 * next time ANCS reports it. Both states share one Operational Room row per bundle after cutover.
 */
class IosAppRegistry private constructor(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val operationalState: OperationalApplicationState?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {
    constructor(store: DataStore<Preferences>, scope: CoroutineScope) : this(store, scope, null, Unit)

    internal constructor(
        store: DataStore<Preferences>,
        scope: CoroutineScope,
        operationalState: OperationalApplicationState,
    ) : this(store, scope, operationalState, Unit)

    private val enabledKey = stringPreferencesKey("ancs_enabled_bundles_json")
    private val discoveredKey = stringPreferencesKey("ancs_discovered_apps_json")

    // Start empty and hydrate asynchronously: a blocking storage read in the constructor would stall the
    // main thread if another caller constructs this outside AppGraph's I/O init. The bridge takes seconds to connect,
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
                if (operationalState != null) {
                    val rows = runCatching { operationalState.iosApps() }.getOrNull()
                    if (rows != null) {
                        val loadedEnabled = IosBundleIdExclusions.filterEnabled(
                            rows.asSequence().filter { it.enabled }.map { it.bundleId }.toSet(),
                        )
                        val loadedDiscovered = rows.mapNotNull { row ->
                            val name = row.displayName ?: return@mapNotNull null
                            val lastSeen = row.lastSeenAt ?: return@mapNotNull null
                            row.bundleId to IosApp(row.bundleId, name, lastSeen)
                        }.toMap()
                        _enabled.update { inSession -> loadedEnabled + inSession }
                        _discovered.update { inSession -> loadedDiscovered + inSession }
                    }
                } else {
                    val prefs = runCatching { store.data.first() }.getOrNull()
                    if (prefs != null) {
                        prefs[enabledKey]?.let { json ->
                            runCatching { ProtocolCodec.decodeFromJson<Set<String>>(json) }.getOrNull()
                        }
                            ?.let { loaded ->
                                _enabled.update { inSession ->
                                    IosBundleIdExclusions.filterEnabled(loaded) + inSession
                                }
                            }
                        prefs[discoveredKey]?.let { json ->
                            runCatching { ProtocolCodec.decodeFromJson<Map<String, IosApp>>(json) }.getOrNull()
                        }
                            ?.let { loaded -> _discovered.update { inSession -> loaded + inSession } }
                    }
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
        return !IosBundleIdExclusions.isExcluded(bundleId) && bundleId in _enabled.value
    }

    fun setEnabled(bundleId: String, enabled: Boolean) {
        _enabled.update {
            if (IosBundleIdExclusions.isExcluded(bundleId)) {
                it - bundleId
            } else if (enabled) {
                it + bundleId
            } else {
                it - bundleId
            }
        }
        persistEnabled()
    }

    /** Bulk-set mirroring for [bundleIds] in a single persisted write; excluded ids are never enabled. */
    fun setEnabled(bundleIds: Collection<String>, enabled: Boolean) {
        if (bundleIds.isEmpty()) return
        _enabled.update { current ->
            if (enabled) current + bundleIds.filterNot { IosBundleIdExclusions.isExcluded(it) }
            else current - bundleIds
        }
        persistEnabled()
    }

    /** Record that [bundleId] (named [displayName]) just posted, so the tab can surface it for opt-in. */
    fun recordSeen(bundleId: String, displayName: String, timeMillis: Long) {
        val existing = _discovered.value[bundleId]
        if (existing != null && timeMillis <= existing.lastSeen && displayName == existing.displayName) return
        _discovered.update {
            it + (bundleId to IosApp(
                bundleId,
                displayName,
                maxOf(timeMillis, existing?.lastSeen ?: 0L)
            ))
        }
        // Persist only when the app set or its display name changes — NOT on every lastSeen bump, which would
        // re-serialize the whole (unbounded) map and disk-write on every incoming notification. lastSeen stays
        // live in memory for sort order and is flushed opportunistically with the next new-app / rename write.
        if (existing == null || existing.displayName != displayName) {
            scope.launch {
                if (operationalState != null) {
                    _discovered.value[bundleId]?.let { current ->
                        operationalState.recordIosApp(
                            current.bundleId,
                            current.displayName,
                            current.lastSeen,
                        )
                    }
                } else {
                    val json = ProtocolCodec.encodeToJson(_discovered.value)
                    store.edit { it[discoveredKey] = json }
                }
            }
        }
    }

    /** Forget a discovered app from the iOS tab's list only — it reappears the next time ANCS sees it. */
    fun forgetSeen(bundleId: String) {
        if (bundleId !in _discovered.value) return
        _discovered.update { it - bundleId }
        scope.launch {
            operationalState?.forgetIosApp(bundleId) ?: run {
                val json = ProtocolCodec.encodeToJson(_discovered.value)
                store.edit { it[discoveredKey] = json }
            }
        }
    }

    private fun persistEnabled() {
        scope.launch {
            val snapshot = IosBundleIdExclusions.filterEnabled(_enabled.value)
            operationalState?.replaceEnabledIosApps(snapshot) ?: run {
                val json = ProtocolCodec.encodeToJson(snapshot)
                store.edit { it[enabledKey] = json }
            }
        }
    }
}
