package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import net.extrawdw.apps.notisync.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ProtocolCodec

/** Global app + transport settings, persisted in Preferences DataStore. */
class SettingsRepository(private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    private val brokerUrlKey = stringPreferencesKey("broker_url")
    private val deviceNameKey = stringPreferencesKey("device_name")
    private val deviceNameUpdatedKey = longPreferencesKey("device_name_updated_at")
    private val batchLowKey = booleanPreferencesKey("batch_low_priority")
    private val advancedKey = booleanPreferencesKey("advanced_diagnostics")
    private val groupKey = stringPreferencesKey("group_id")
    private val epochKey = intPreferencesKey("route_epoch")
    private val fcmRouteRefKey = stringPreferencesKey("fcm_route_ref")
    private val lastSeenPostKey = longPreferencesKey("last_seen_post_time")
    private val selfEpochActivatedKey = longPreferencesKey("self_epoch_activated_at")
    private val ancsBridgeKey = booleanPreferencesKey("ancs_bridge_enabled")
    private val ancsLocalKey = booleanPreferencesKey("ancs_local_display")
    private val ancsMeshKey = booleanPreferencesKey("ancs_mesh_mirror")

    val brokerUrl: StateFlow<String> =
        store.data.map { it[brokerUrlKey] ?: DEFAULT_BROKER }.stateInEager(scope, DEFAULT_BROKER)
    val deviceName: StateFlow<String> =
        store.data.map { it[deviceNameKey] ?: android.os.Build.MODEL }.stateInEager(scope, android.os.Build.MODEL)
    /** Source-clock stamp of the last device-name change; 0 until the user first renames. Stamped into
     *  outgoing [ProfileUpdate]s so peers can apply last-writer-wins. */
    val deviceNameUpdatedAt: StateFlow<Long> =
        store.data.map { it[deviceNameUpdatedKey] ?: 0L }.stateInEager(scope, 0L)
    val batchLowPriority: StateFlow<Boolean> =
        store.data.map { it[batchLowKey] ?: false }.stateInEager(scope, false)
    val advancedDiagnostics: StateFlow<Boolean> =
        store.data.map { it[advancedKey] ?: false }.stateInEager(scope, false)

    /** ANCS bridge master switch: whether to advertise + connect to a paired iPhone. Default off (opt-in). */
    val ancsBridgeEnabled: StateFlow<Boolean> =
        store.data.map { it[ancsBridgeKey] ?: false }.stateInEager(scope, false)
    /** Show captured iPhone notifications on THIS phone (like a smartwatch). Default on once bridging. */
    val ancsLocalDisplay: StateFlow<Boolean> =
        store.data.map { it[ancsLocalKey] ?: true }.stateInEager(scope, true)
    /** Mirror captured iPhone notifications to the user's other mesh devices. Default on once bridging. */
    val ancsMeshMirror: StateFlow<Boolean> =
        store.data.map { it[ancsMeshKey] ?: true }.stateInEager(scope, true)

    /** The PERSISTED switch, read directly from DataStore — use this (not [ancsBridgeEnabled].value, which is
     *  still the default during early startup) when deciding whether to resume the bridge on a process start. */
    suspend fun ancsBridgeEnabledNow(): Boolean = store.data.first()[ancsBridgeKey] ?: false

    suspend fun setAncsBridgeEnabled(on: Boolean) = store.edit { it[ancsBridgeKey] = on }
    suspend fun setAncsLocalDisplay(on: Boolean) = store.edit { it[ancsLocalKey] = on }
    suspend fun setAncsMeshMirror(on: Boolean) = store.edit { it[ancsMeshKey] = on }

    suspend fun setBrokerUrl(url: String) = store.edit { it[brokerUrlKey] = url }
    suspend fun setDeviceName(name: String) = store.edit {
        it[deviceNameKey] = name
        it[deviceNameUpdatedKey] = System.currentTimeMillis()
    }
    suspend fun setBatchLowPriority(on: Boolean) = store.edit { it[batchLowKey] = on }
    suspend fun setAdvancedDiagnostics(on: Boolean) = store.edit { it[advancedKey] = on }

    suspend fun groupId(): String? = store.data.first()[groupKey]
    suspend fun setGroupId(id: String) = store.edit { it[groupKey] = id }
    suspend fun epochForFcmRoute(routeRef: String): Int {
        var epoch = 1
        store.edit { prefs ->
            if (prefs[fcmRouteRefKey] == routeRef) {
                epoch = prefs[epochKey] ?: 1
            } else {
                epoch = (prefs[epochKey] ?: 0) + 1
                prefs[epochKey] = epoch
                prefs[fcmRouteRefKey] = routeRef
            }
        }
        return epoch
    }

    /** Wall-clock at which the current operational epoch became active (epoch 1 = first run with rotation
     *  enabled). EpochMaintenanceWorker compares `now −` this against the rotation interval to decide when to
     *  mint the next epoch; [setSelfEpochActivatedAt] restamps it on each activation. A local hygiene timer
     *  only — deliberately NOT in the signed TrustStore, since a wrong value can at worst rotate early/late,
     *  never forge an epoch (every key-epoch is still identity-signed and floor-checked). */
    suspend fun selfEpochActivatedAt(): Long = store.data.first()[selfEpochActivatedKey] ?: 0L
    suspend fun setSelfEpochActivatedAt(at: Long) = store.edit { it[selfEpochActivatedKey] = at }
    /** Seed the epoch-age clock on first run with rotation enabled WITHOUT resetting an existing stamp, so the
     *  first scheduled rotation fires one interval from now rather than immediately. */
    suspend fun seedSelfEpochActivatedAt(at: Long) = store.edit {
        if (it[selfEpochActivatedKey] == null) it[selfEpochActivatedKey] = at
    }

    /** High-water mark of post times we've mirrored — used to gate the listener backfill after a restart. */
    suspend fun lastSeenPostTime(): Long = store.data.first()[lastSeenPostKey] ?: 0L
    fun updateLastSeenPostTime(timeMillis: Long) {
        scope.launch {
            store.edit { prefs ->
                if (timeMillis > (prefs[lastSeenPostKey] ?: 0L)) prefs[lastSeenPostKey] = timeMillis
            }
        }
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.stateInEager(scope: CoroutineScope, initial: T): StateFlow<T> =
        stateIn(scope, SharingStarted.Eagerly, initial)

    companion object {
        // Production broker (via Cloudflare). For a local server from the emulator, override in
        // Settings with ws://10.0.2.2:8080.
        const val DEFAULT_BROKER = "wss://notisync-api.extrawdw.net"
    }
}

/**
 * Per-app mirroring is opt-in: an allowlist of enabled packages (nothing is mirrored by default).
 * Also tracks which apps have posted notifications (in-memory) so the picker can surface and sort
 * by recently-active apps.
 */
class AppSelectionRepository(private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    private val enabledKey = stringPreferencesKey("enabled_packages_json")
    private val _enabled = MutableStateFlow(load())
    val enabled: StateFlow<Set<String>> = _enabled

    private val _lastSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastSeen: StateFlow<Map<String, Long>> = _lastSeen

    private fun load(): Set<String> = runBlocking {
        store.data.first()[enabledKey]?.let {
            runCatching { ProtocolCodec.decodeFromJson<Set<String>>(it) }.getOrDefault(emptySet())
        } ?: emptySet()
    }

    fun isEnabled(packageName: String): Boolean = packageName in _enabled.value

    fun setEnabled(packageName: String, enabled: Boolean) {
        _enabled.value = if (enabled) _enabled.value + packageName else _enabled.value - packageName
        val json = ProtocolCodec.encodeToJson(_enabled.value)
        scope.launch { store.edit { it[enabledKey] = json } }
    }

    /** Record that [packageName] just posted a notification (drives recency sorting in the picker). */
    fun recordSeen(packageName: String, timeMillis: Long) {
        if (timeMillis > (_lastSeen.value[packageName] ?: 0L)) {
            _lastSeen.value = _lastSeen.value + (packageName to timeMillis)
        }
    }
}

/** Recent, privacy-safe activity for the Activity screen (in-memory, bounded). */
class ActivityLog {
    private val _events = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val events: StateFlow<List<ActivityEvent>> = _events

    fun add(
        kind: ActivityEvent.Kind,
        title: String,
        detail: String,
        now: Long,
        deliveryMode: DeliveryMode? = null,
    ) {
        _events.value = (listOf(ActivityEvent(kind, title, detail, now, deliveryMode)) + _events.value).take(MAX)
    }

    companion object {
        private const val MAX = 100
    }
}
