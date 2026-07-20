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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import net.extrawdw.notisync.peer.transport.DeliveryMode
import net.extrawdw.notisync.protocol.ProtocolCodec

/** Global app + transport settings, persisted in Preferences DataStore. */
class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope
) {
    private val brokerUrlKey = stringPreferencesKey("broker_url")
    private val deviceNameKey = stringPreferencesKey("device_name")
    private val deviceNameUpdatedKey = longPreferencesKey("device_name_updated_at")
    private val selfProfileFingerprintKey = stringPreferencesKey("self_profile_fingerprint")
    private val selfProfileUpdatedKey = longPreferencesKey("self_profile_updated_at")
    private val batchLowKey = booleanPreferencesKey("batch_low_priority")
    private val advancedKey = booleanPreferencesKey("advanced_diagnostics")
    private val analyticsEnabledKey = booleanPreferencesKey("analytics_enabled")
    private val groupKey = stringPreferencesKey("group_id")
    private val epochKey = intPreferencesKey("route_epoch")
    private val fcmRouteRefKey = stringPreferencesKey("fcm_route_ref")
    private val lastSeenPostKey = longPreferencesKey("last_seen_post_time")
    private val selfEpochActivatedKey = longPreferencesKey("self_epoch_activated_at")
    private val iosBridgeKey = booleanPreferencesKey("ancs_bridge_enabled")
    private val iosLocalKey = booleanPreferencesKey("ancs_local_display")
    private val iosMeshKey = booleanPreferencesKey("ancs_mesh_mirror")
    private val iosMediaKey = booleanPreferencesKey("ancs_media_mirror")
    private val onboardingDoneKey = booleanPreferencesKey("onboarding_completed")
    private val callRingerKey = booleanPreferencesKey("call_ringer_enabled")
    private val lockScreenPublicIdentityKey = booleanPreferencesKey("lock_screen_public_identity")

    val brokerUrl: StateFlow<String> =
        store.data.map { it[brokerUrlKey] ?: DEFAULT_BROKER }.stateInEager(scope, DEFAULT_BROKER)
    val deviceName: StateFlow<String> =
        store.data.map { it[deviceNameKey] ?: android.os.Build.MODEL }
            .stateInEager(scope, android.os.Build.MODEL)

    /** Source-clock stamp of the last device-name change; 0 until the user first renames. Stamped into
     *  outgoing [ProfileUpdate]s so peers can apply last-writer-wins. */
    val deviceNameUpdatedAt: StateFlow<Long> =
        store.data.map { it[deviceNameUpdatedKey] ?: 0L }.stateInEager(scope, 0L)
    private val _selfProfileUpdatedAt = MutableStateFlow(
        runBlocking {
            store.data.first().let { it[selfProfileUpdatedKey] ?: it[deviceNameUpdatedKey] ?: 0L }
        }
    )
    /** Revision of the complete advertised profile (name, platform, and capabilities). */
    val selfProfileUpdatedAt: StateFlow<Long> = _selfProfileUpdatedAt
    val batchLowPriority: StateFlow<Boolean> =
        store.data.map { it[batchLowKey] ?: false }.stateInEager(scope, false)
    val advancedDiagnostics: StateFlow<Boolean> =
        store.data.map { it[advancedKey] ?: false }.stateInEager(scope, false)

    /** Opt-out analytics master switch covering Firebase Crashlytics + Performance collection. Default on
     *  (opt-out): both SDKs auto-collect unless disabled, so turning this off stops telemetry. Applied by
     *  the AppGraph collector wiring it into the Firebase SDKs. */
    val analyticsEnabled: StateFlow<Boolean> =
        store.data.map { it[analyticsEnabledKey] ?: true }.stateInEager(scope, true)

    /** The PERSISTED analytics switch, read directly from DataStore — use this (not [analyticsEnabled].value,
     *  which is still the `true` default during early startup) when applying the opt-out to the SDKs on
     *  process start, so an opted-out user isn't briefly re-enabled. */
    suspend fun analyticsEnabledNow(): Boolean = store.data.first()[analyticsEnabledKey] ?: true

    /** iOS bridge master switch: whether to advertise + connect to a paired iPhone. Default off (opt-in). */
    val iosBridgeEnabled: StateFlow<Boolean> =
        store.data.map { it[iosBridgeKey] ?: false }.stateInEager(scope, false)

    /** Show captured iPhone notifications on THIS phone (like a smartwatch). Default on once bridging. */
    val iosLocalDisplay: StateFlow<Boolean> =
        store.data.map { it[iosLocalKey] ?: true }.stateInEager(scope, true)

    /** Mirror captured iPhone notifications to the user's other mesh devices. Default on once bridging. */
    val iosMeshMirror: StateFlow<Boolean> =
        store.data.map { it[iosMeshKey] ?: true }.stateInEager(scope, true)

    /** Mirror the iPhone's now-playing media (AMS) as a media-controls card — here and, when
     *  [iosMeshMirror] is on, on the user's other Android devices. Default on once bridging. */
    val iosMediaMirror: StateFlow<Boolean> =
        store.data.map { it[iosMediaKey] ?: true }.stateInEager(scope, true)

    /** Master switch for the incoming-call ringer ([net.extrawdw.apps.notisync.notification.mirror.CallRinger]).
     *  Default off. When off, NO mirrored call rings on this device, regardless of the per-app
     *  [PerAppConfig.ringForCalls] toggle — calls still mirror and pop up, just silently. */
    val callRingerEnabled: StateFlow<Boolean> =
        store.data.map { it[callRingerKey] ?: false }.stateInEager(scope, false)

    suspend fun setCallRingerEnabled(on: Boolean) = store.edit { it[callRingerKey] = on }

    /** Show source app identity (app name + icon) in the lock-screen public version of mirrored
     *  notifications while keeping mirrored content private. Default on; users can disable it in Settings. */
    val lockScreenPublicIdentity: StateFlow<Boolean> =
        store.data.map { it[lockScreenPublicIdentityKey] ?: true }.stateInEager(scope, true)

    suspend fun setLockScreenPublicIdentity(on: Boolean) =
        store.edit { it[lockScreenPublicIdentityKey] = on }

    /** The PERSISTED switch, read directly from DataStore — use this (not [iosBridgeEnabled].value, which is
     *  still the default during early startup) when deciding whether to resume the bridge on a process start. */
    suspend fun iosBridgeEnabledNow(): Boolean = store.data.first()[iosBridgeKey] ?: false

    suspend fun setIosBridgeEnabled(on: Boolean) = store.edit { it[iosBridgeKey] = on }
    suspend fun setIosLocalDisplay(on: Boolean) = store.edit { it[iosLocalKey] = on }
    suspend fun setIosMeshMirror(on: Boolean) = store.edit { it[iosMeshKey] = on }
    suspend fun setIosMediaMirror(on: Boolean) = store.edit { it[iosMediaKey] = on }

    /** Whether first-launch onboarding was finished (every step completed or skipped). Direct read only —
     *  it gates what the launch frame shows, so an eager StateFlow's still-default value would flash
     *  onboarding at already-onboarded users while DataStore loads. */
    suspend fun onboardingCompletedNow(): Boolean = store.data.first()[onboardingDoneKey] ?: false
    suspend fun setOnboardingCompleted() = store.edit { it[onboardingDoneKey] = true }

    suspend fun setBrokerUrl(url: String) = store.edit { it[brokerUrlKey] = url }
    suspend fun setDeviceName(name: String) = store.edit {
        it[deviceNameKey] = name
        it[deviceNameUpdatedKey] = System.currentTimeMillis()
    }

    /**
     * Persist the complete self-profile fingerprint and monotonically advance its LWW revision when any
     * advertised field changes. Returns the new revision when callers must broadcast, or null when current.
     */
    suspend fun ensureSelfProfileRevision(fingerprint: String, now: Long = System.currentTimeMillis()): Long? {
        var changedAt: Long? = null
        store.edit { prefs ->
            if (prefs[selfProfileFingerprintKey] != fingerprint || prefs[selfProfileUpdatedKey] == null) {
                val previous = prefs[selfProfileUpdatedKey] ?: prefs[deviceNameUpdatedKey] ?: 0L
                val revision = maxOf(now, previous + 1L)
                prefs[selfProfileFingerprintKey] = fingerprint
                prefs[selfProfileUpdatedKey] = revision
                changedAt = revision
            }
        }
        changedAt?.let { _selfProfileUpdatedAt.value = it }
        return changedAt
    }

    suspend fun setBatchLowPriority(on: Boolean) = store.edit { it[batchLowKey] = on }
    suspend fun setAdvancedDiagnostics(on: Boolean) = store.edit { it[advancedKey] = on }
    suspend fun setAnalyticsEnabled(on: Boolean) = store.edit { it[analyticsEnabledKey] = on }

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

    private fun <T> kotlinx.coroutines.flow.Flow<T>.stateInEager(
        scope: CoroutineScope,
        initial: T
    ): StateFlow<T> =
        stateIn(scope, SharingStarted.Eagerly, initial)

    companion object {
        // Production broker (via Cloudflare). For a local server from the emulator, override in
        // Settings with ws://10.0.2.2:8080.
        const val DEFAULT_BROKER = "wss://notisync-api-v2.extrawdw.net"
    }
}

/**
 * Per-app mirroring is opt-in: an allowlist of enabled packages (nothing is mirrored by default).
 * Also tracks which apps have posted notifications (in-memory) so the picker can surface and sort
 * by recently-active apps.
 */
class AppSelectionRepository(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope
) {
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

    /** Bulk-set mirroring for [packageNames] in a single persisted write (backs "turn on/off all"). */
    fun setEnabled(packageNames: Collection<String>, enabled: Boolean) {
        if (packageNames.isEmpty()) return
        _enabled.value =
            if (enabled) _enabled.value + packageNames else _enabled.value - packageNames
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

/**
 * Per-app mirroring configuration beyond the on/off allowlist ([AppSelectionRepository]): whether to mirror an
 * app's ongoing (media/transport/foreground-service) notifications, how frequently their updates may be
 * re-sent, and which of the app's notification channels / channel groups the user disabled. Two JSON blobs in
 * the shared "notisync" Preferences DataStore — pkg -> [PerAppConfig], and pkg -> observed [SeenChannel]s.
 *
 * All source-side and locally owned — distinct from [NotificationFilterStore], which holds INBOUND suppression
 * requests received from peers. Reads are capture-hot-path safe ([configFor]/[isChannelSuppressed], and
 * [recordSeenChannel] on every post); writes are fire-and-forget like [AppSelectionRepository].
 */
class AppConfigRepository(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    private val configKey = stringPreferencesKey("per_app_config_json")
    private val seenKey = stringPreferencesKey("per_app_seen_channels_json")

    private val _configs = MutableStateFlow(loadConfigs())
    /** pkg -> the user's per-app config; an absent pkg means all-defaults ([PerAppConfig]). */
    val configs: StateFlow<Map<String, PerAppConfig>> = _configs

    private val _seenChannels = MutableStateFlow(loadSeen())
    /** pkg -> channels observed from its captures (for the config sheet's channel list). */
    val seenChannels: StateFlow<Map<String, List<SeenChannel>>> = _seenChannels

    private fun loadConfigs(): Map<String, PerAppConfig> = runBlocking {
        store.data.first()[configKey]?.let {
            runCatching { ProtocolCodec.decodeFromJson<Map<String, PerAppConfig>>(it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    private fun loadSeen(): Map<String, List<SeenChannel>> = runBlocking {
        store.data.first()[seenKey]?.let {
            runCatching { ProtocolCodec.decodeFromJson<Map<String, List<SeenChannel>>>(it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    /** The stored config for [packageName], or all-defaults when none is set. */
    fun configFor(packageName: String): PerAppConfig = _configs.value[packageName] ?: PerAppConfig()

    /** Channels observed from [packageName]'s captures, for the config sheet. */
    fun seenChannelsFor(packageName: String): List<SeenChannel> = _seenChannels.value[packageName].orEmpty()

    fun setMirrorOngoing(packageName: String, enabled: Boolean) =
        mutate(packageName) { it.copy(mirrorOngoing = enabled) }

    fun setUpdateIntervalSec(packageName: String, seconds: Int) =
        mutate(packageName) { it.copy(updateIntervalSec = seconds) }

    /** Whether this app's non-media ongoing notifications are also mirrored to iOS devices (default off). */
    fun setMirrorOngoingToIos(packageName: String, enabled: Boolean) =
        mutate(packageName) { it.copy(mirrorOngoingToIos = enabled) }

    /** Whether this app's media playback notifications are also mirrored to iOS devices (default off). */
    fun setMirrorMediaPlaybackToIos(packageName: String, enabled: Boolean) =
        mutate(packageName) { it.copy(mirrorMediaPlaybackToIos = enabled) }

    /** Receiver-side: whether this app's mirrored incoming calls ring + vibrate on this device. */
    fun setRingForCalls(packageName: String, enabled: Boolean) =
        mutate(packageName) { it.copy(ringForCalls = enabled) }

    fun setChannelEnabled(packageName: String, channelId: String, enabled: Boolean) =
        mutate(packageName) {
            it.copy(
                disabledChannelIds =
                    if (enabled) it.disabledChannelIds - channelId else it.disabledChannelIds + channelId,
            )
        }

    fun setGroupEnabled(packageName: String, groupId: String, enabled: Boolean) =
        mutate(packageName) {
            it.copy(
                disabledGroupIds =
                    if (enabled) it.disabledGroupIds - groupId else it.disabledGroupIds + groupId,
            )
        }

    private fun mutate(packageName: String, transform: (PerAppConfig) -> PerAppConfig) {
        _configs.update { it + (packageName to transform(it[packageName] ?: PerAppConfig())) }
        persist(configKey, ProtocolCodec.encodeToJson(_configs.value))
    }

    /**
     * True when [channelId] or its [groupId] is disabled for [packageName] — the source-side capture gate.
     * Default all-ON: an app with no config (or an unknown channel) is never suppressed.
     */
    fun isChannelSuppressed(packageName: String, channelId: String?, groupId: String?): Boolean {
        val cfg = _configs.value[packageName] ?: return false
        if (groupId != null && groupId in cfg.disabledGroupIds) return true
        if (channelId != null && channelId in cfg.disabledChannelIds) return true
        return false
    }

    /**
     * Record a channel observed from [packageName]'s captures so the config sheet can list it. No-op without a
     * [channelId] (iOS/ANCS captures, or an unavailable Ranking). Persists only when the channel is new or its
     * name/group changed, so the capture hot path pays a write once per channel, not per post.
     */
    fun recordSeenChannel(
        packageName: String,
        channelId: String?,
        channelName: String?,
        groupId: String?,
        groupName: String?,
    ) {
        val id = channelId?.takeIf { it.isNotBlank() } ?: return
        val entry = SeenChannel(id, channelName, groupId, groupName)
        if (_seenChannels.value[packageName]?.firstOrNull { it.channelId == id } == entry) return
        _seenChannels.update { map ->
            val current = map[packageName].orEmpty()
            if (current.firstOrNull { it.channelId == id } == entry) map
            else map + (packageName to (current.filterNot { it.channelId == id } + entry))
        }
        persist(seenKey, ProtocolCodec.encodeToJson(_seenChannels.value))
    }

    /** Forget a seen channel from the config sheet's list ONLY — it reappears on the next capture in it. */
    fun removeSeenChannel(packageName: String, channelId: String) {
        if (_seenChannels.value[packageName]?.any { it.channelId == channelId } != true) return
        _seenChannels.update { map ->
            val current = map[packageName] ?: return@update map
            map + (packageName to current.filterNot { it.channelId == channelId })
        }
        persist(seenKey, ProtocolCodec.encodeToJson(_seenChannels.value))
    }

    private fun persist(key: Preferences.Key<String>, json: String) {
        scope.launch { store.edit { it[key] = json } }
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
        _events.value = (listOf(
            ActivityEvent(
                kind,
                title,
                detail,
                now,
                deliveryMode
            )
        ) + _events.value).take(MAX)
    }

    companion object {
        private const val MAX = 100
    }
}
