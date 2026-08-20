package net.extrawdw.apps.notisync.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterOrigin
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterRepository
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterReplaceResult
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterRuleSpec
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterSnapshot
import net.extrawdw.apps.notisync.data.incomingfilter.IncomingFilterUpdate
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.NotificationFilterRule
import net.extrawdw.notisync.protocol.OriginPlatform

/**
 * Notification-suppression filters RECEIVED from peers over DATA_SYNC ([net.extrawdw.notisync.protocol.DataSyncKind.FILTER]),
 * keyed by the requesting peer's client id. A peer — chiefly the iOS client, whose Notification Service
 * Extension cannot drop an APNs push locally — asks this device (a notification *source*) to stop delivering
 * matching captures to it. When a capture matches, the requester is dropped from the recipient list
 * ([recipientsToExclude]) so the notification never reaches that device.
 *
 * Each FILTER is a FULL snapshot: [apply] REPLACES the requester's prior filter (last-writer-wins on
 * [FilterSync.updatedAt]); an empty rule list clears it. The production implementation persists the aggregate
 * through Room; this device never *generates* filters (Android mirroring is customised with notification
 * channels), it only honors inbound ones.
 */
class NotificationFilterStore(
    private val repository: IncomingFilterRepository,
    private val scope: CoroutineScope,
) {
    private val _filters = MutableStateFlow<Map<String, FilterSync>>(emptyMap())
    private val hydrated = CompletableDeferred<Unit>()

    /** requesterClientId → the filter snapshot it asked this device to apply. */
    val filters: StateFlow<Map<String, FilterSync>> = _filters

    init {
        scope.launch {
            try {
                repository.observeAll().collect { snapshots ->
                    _filters.value = snapshots.associate { it.requesterClientId to it.toProtocol() }
                    hydrated.complete(Unit)
                }
            } catch (error: Throwable) {
                hydrated.completeExceptionally(error)
                throw error
            }
        }
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
        val before = repository.projection.filterFor(id)
        val result = runBlocking(Dispatchers.IO) {
            repository.replace(
                IncomingFilterUpdate(
                    requesterClientId = id,
                    updatedAt = filter.updatedAt,
                    receivedAt = System.currentTimeMillis().coerceAtLeast(1L),
                    rules = filter.rules.map { rule ->
                        IncomingFilterRuleSpec(
                            origin = when (rule.originPlatform) {
                                OriginPlatform.ANDROID_LOCAL -> IncomingFilterOrigin.ANDROID_LOCAL
                                OriginPlatform.IOS_ANCS -> IncomingFilterOrigin.IOS_ANCS
                            },
                            appId = rule.appId,
                            channelId = rule.channelId,
                        )
                    },
                ),
            )
        }
        return when (result) {
            IncomingFilterReplaceResult.INSERTED,
            IncomingFilterReplaceResult.REPLACED ->
                (before?.rules?.map { it.origin.toProtocolOrigin() to (it.appId to it.channelId) }?.toSet() ?: emptySet()) !=
                    filter.rules.map { it.originPlatform to (it.appId to it.channelId) }.toSet()
            IncomingFilterReplaceResult.UNCHANGED,
            IncomingFilterReplaceResult.STALE,
            IncomingFilterReplaceResult.CONFLICT -> false
        }
    }

    /** Forget a peer's filter (e.g. when it is permanently removed from the roster). No-op if absent. */
    fun remove(requesterId: ClientId) {
        val id = requesterId.value
        runBlocking(Dispatchers.IO) { repository.remove(id) }
    }

    /** The filter a [requesterId] currently asks us to apply — for the Devices "filters" sheet. */
    fun filterFor(requesterId: ClientId): FilterSync? = repository.projection.filterFor(requesterId.value)?.toProtocol()

    /**
     * The requester client ids whose stored filter matches [notif] — the devices to drop from this capture's
     * recipient list. Runs on the capture hot path, so it short-circuits on an empty store and is otherwise
     * linear in (stored peers × their rules), both tiny.
     */
    fun recipientsToExclude(notif: CapturedNotification): Set<ClientId> {
        val origin = when (notif.originPlatform) {
            OriginPlatform.ANDROID_LOCAL -> IncomingFilterOrigin.ANDROID_LOCAL
            OriginPlatform.IOS_ANCS -> IncomingFilterOrigin.IOS_ANCS
        }
        return repository.projection.recipientsToExclude(
            origin = origin,
            packageName = notif.packageName,
            iosBundleId = notif.iosBundleId,
            channelId = notif.channelId,
        ).mapTo(linkedSetOf()) { ClientId(it) }
    }

    /** Completes after the Room projection has loaded the first complete filter snapshot. */
    suspend fun awaitHydrated() = hydrated.await()

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

private fun IncomingFilterSnapshot.toProtocol(): FilterSync = FilterSync(
    rules = rules.map { rule ->
        NotificationFilterRule(
            originPlatform = when (rule.origin) {
                IncomingFilterOrigin.ANDROID_LOCAL -> OriginPlatform.ANDROID_LOCAL
                IncomingFilterOrigin.IOS_ANCS -> OriginPlatform.IOS_ANCS
            },
            appId = rule.appId,
            channelId = rule.channelId,
        )
    },
    updatedAt = updatedAt,
)

private fun IncomingFilterOrigin.toProtocolOrigin(): OriginPlatform = when (this) {
    IncomingFilterOrigin.ANDROID_LOCAL -> OriginPlatform.ANDROID_LOCAL
    IncomingFilterOrigin.IOS_ANCS -> OriginPlatform.IOS_ANCS
}
