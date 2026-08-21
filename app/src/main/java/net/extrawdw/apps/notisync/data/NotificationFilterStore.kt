package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.OriginPlatform
import net.extrawdw.notisync.protocol.ProtocolCodec

/**
 * Notification-suppression filters RECEIVED from peers over DATA_SYNC ([net.extrawdw.notisync.protocol.DataSyncKind.FILTER]),
 * keyed by the requesting peer's client id. A peer — chiefly the iOS client, whose Notification Service
 * Extension cannot drop an APNs push locally — asks this device (a notification *source*) to stop delivering
 * matching captures to it. When a capture matches, the requester is dropped from the recipient list
 * ([recipientsToExclude]) so the notification never reaches that device.
 *
 * Each FILTER is a FULL snapshot: [apply] REPLACES the requester's prior filter (last-writer-wins on
 * [FilterSync.updatedAt]); an empty rule list clears it. Persisted as JSON in Preferences DataStore and
 * mirrored in [filters] for the Devices UI. This device never *generates* filters (Android mirroring is
 * customised with notification channels); it only honors inbound ones.
 */
class NotificationFilterStore(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    private val key = stringPreferencesKey("received_notification_filters_json")
    private val _filters = MutableStateFlow(load())

    /** requesterClientId → the filter snapshot it asked this device to apply. */
    val filters: StateFlow<Map<String, FilterSync>> = _filters

    private fun load(): Map<String, FilterSync> = runBlocking {
        store.data.first()[key]?.let {
            runCatching { ProtocolCodec.decodeFromJson<Map<String, FilterSync>>(it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    /**
     * Apply an inbound snapshot from [requesterId] (last-writer-wins on [FilterSync.updatedAt]): a strictly
     * older snapshot is dropped, anything else REPLACES what we hold. An empty rule list is stored verbatim —
     * it clears suppression for that requester (a delivery to it resumes).
     *
     * Returns true only when the effective rule SET changed (so a caller can log a user-visible activity row
     * for a real change but stay silent for the source's periodic, identical re-announce). Order-insensitive,
     * since the sender builds the list from unordered sets.
     */
    fun apply(requesterId: ClientId, filter: FilterSync): Boolean {
        val id = requesterId.value
        val before = _filters.value[id]
        if (before != null && filter.updatedAt < before.updatedAt) return false   // stale — ignore entirely
        // Atomic read-modify-write with the LWW check inside, since inbound handlers may run off any thread.
        _filters.update { current ->
            val existing = current[id]
            if (existing != null && filter.updatedAt < existing.updatedAt) current else current + (id to filter)
        }
        persist()
        return (before?.rules?.toSet() ?: emptySet()) != filter.rules.toSet()
    }

    /** Forget a peer's filter (e.g. when it is permanently removed from the roster). No-op if absent. */
    fun remove(requesterId: ClientId) {
        val id = requesterId.value
        if (id !in _filters.value) return
        _filters.update { it - id }
        persist()
    }

    /** The filter a [requesterId] currently asks us to apply — for the Devices "filters" sheet. */
    fun filterFor(requesterId: ClientId): FilterSync? = _filters.value[requesterId.value]

    /**
     * The requester client ids whose stored filter matches [notif] — the devices to drop from this capture's
     * recipient list. Runs on the capture hot path, so it short-circuits on an empty store and is otherwise
     * linear in (stored peers × their rules), both tiny.
     */
    fun recipientsToExclude(notif: CapturedNotification): Set<ClientId> {
        val current = _filters.value
        if (current.isEmpty()) return emptySet()
        val appId = appIdentifier(notif.packageName, notif.iosBundleId)
        val channelId = notif.channelId?.trim()?.takeIf { it.isNotEmpty() }
        return current.entries
            .filter { (_, filter) -> matches(filter, notif.originPlatform, appId, channelId) }
            .mapTo(mutableSetOf()) { (id, _) -> ClientId(id) }
    }

    private fun persist() {
        val json = ProtocolCodec.encodeToJson(_filters.value)
        scope.launch { store.edit { it[key] = json } }
    }

    companion object {
        /**
         * The app identifier a filter rule keys on: the iOS bundle id when present (an ANCS-bridged capture),
         * else the Android package name. Byte-for-byte the same choice as the iOS sender's
         * `NotificationFilterStore.appIdentifier`, so a rule built there matches a capture re-keyed here.
         */
        fun appIdentifier(packageName: String?, iosBundleId: String?): String? {
            iosBundleId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            return packageName?.trim()?.takeIf { it.isNotEmpty() }
        }

        /**
         * Does [filter] suppress a capture with this [originPlatform] / [appId] / [channelId]? A rule matches
         * only within its own origin; then a device-level rule (appId null) matches any app, an app-level rule
         * (channelId null) matches any channel of that app, and a channel-level rule matches exactly one
         * channel. IOS_ANCS rules never carry a channel (iOS has none) — they are device- or app-level.
         */
        fun matches(
            filter: FilterSync,
            originPlatform: OriginPlatform,
            appId: String?,
            channelId: String?,
        ): Boolean = filter.rules.any { rule ->
            rule.originPlatform == originPlatform && when {
                rule.appId == null -> true                 // device-level master switch for this origin
                rule.appId != appId -> false               // a different app
                rule.channelId == null -> true             // app-level: all channels of the app
                else -> rule.channelId == channelId        // channel-level: one channel
            }
        }
    }
}
