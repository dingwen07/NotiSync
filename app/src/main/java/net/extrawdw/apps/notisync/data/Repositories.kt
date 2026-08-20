package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** Independent app/UI switches, persisted in Preferences DataStore. Core and Operational authority stays in Room. */
class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope
) {
    private val batchLowKey = booleanPreferencesKey("batch_low_priority")
    private val advancedKey = booleanPreferencesKey("advanced_diagnostics")
    private val analyticsEnabledKey = booleanPreferencesKey("analytics_enabled")
    private val selfEpochActivatedKey = longPreferencesKey("self_epoch_activated_at")
    private val iosBridgeKey = booleanPreferencesKey("ancs_bridge_enabled")
    private val iosLocalKey = booleanPreferencesKey("ancs_local_display")
    private val iosMeshKey = booleanPreferencesKey("ancs_mesh_mirror")
    private val iosMediaKey = booleanPreferencesKey("ancs_media_mirror")
    private val onboardingDoneKey = booleanPreferencesKey("onboarding_completed")
    private val callRingerKey = booleanPreferencesKey("call_ringer_enabled")
    private val lockScreenPublicIdentityKey = booleanPreferencesKey("lock_screen_public_identity")

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

    suspend fun setBatchLowPriority(on: Boolean) = store.edit { it[batchLowKey] = on }
    suspend fun setAdvancedDiagnostics(on: Boolean) = store.edit { it[advancedKey] = on }
    suspend fun setAnalyticsEnabled(on: Boolean) = store.edit { it[analyticsEnabledKey] = on }

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

    private fun <T> kotlinx.coroutines.flow.Flow<T>.stateInEager(
        scope: CoroutineScope,
        initial: T
    ): StateFlow<T> =
        stateIn(scope, SharingStarted.Eagerly, initial)

    companion object {
        // Production broker (via Cloudflare). For a local server from the emulator, override in
        // Settings with http://10.0.2.2:8080. BrokerClient derives ws/wss for live delivery.
        const val DEFAULT_BROKER = "https://notisync-api-v2.extrawdw.net"
    }
}

/**
 * Per-app mirroring is opt-in: an allowlist of enabled packages (nothing is mirrored by default).
 * Also exposes the Room-backed last-seen projection so the picker can surface and sort by recently-active apps.
 */
interface AppSelectionRepository {
    val enabled: StateFlow<Set<String>>
    val lastSeen: StateFlow<Map<String, Long>>
    fun isEnabled(packageName: String): Boolean
    fun setEnabled(packageName: String, enabled: Boolean)
    fun setEnabled(packageNames: Collection<String>, enabled: Boolean)
    fun recordSeen(packageName: String, timeMillis: Long)
    suspend fun awaitHydrated()
}

/**
 * Per-app mirroring configuration beyond the on/off allowlist ([AppSelectionRepository]): whether to mirror an
 * app's ongoing notifications, update frequency, channel/group suppression, and receiver-side call ringing.
 * The Room facade keeps this source-side policy aggregate distinct from [NotificationFilterStore], which holds
 * INBOUND suppression requests received from peers.
 *
 * Reads are capture-hot-path safe ([configFor]/[isChannelSuppressed], and [recordSeenChannel] on every post).
 */
interface AppConfigRepository {
    val configs: StateFlow<Map<String, PerAppConfig>>
    val seenChannels: StateFlow<Map<String, List<SeenChannel>>>
    fun configFor(packageName: String): PerAppConfig
    fun seenChannelsFor(packageName: String): List<SeenChannel>
    fun setMirrorOngoing(packageName: String, enabled: Boolean)
    fun setUpdateIntervalSec(packageName: String, seconds: Int)
    fun setMirrorOngoingToIos(packageName: String, enabled: Boolean)
    fun setMirrorMediaPlaybackToIos(packageName: String, enabled: Boolean)
    fun setRingForCalls(packageName: String, enabled: Boolean)
    fun setChannelEnabled(packageName: String, channelId: String, enabled: Boolean)
    fun setGroupEnabled(packageName: String, groupId: String, enabled: Boolean)
    fun isChannelSuppressed(packageName: String, channelId: String?, groupId: String?): Boolean
    fun recordSeenChannel(packageName: String, channelId: String?, channelName: String?, groupId: String?, groupName: String?)
    fun removeSeenChannel(packageName: String, channelId: String)
    suspend fun awaitHydrated()
}
