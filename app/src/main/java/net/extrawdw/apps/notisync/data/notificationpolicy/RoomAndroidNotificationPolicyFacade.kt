package net.extrawdw.apps.notisync.data.notificationpolicy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.extrawdw.apps.notisync.data.AppConfigRepository
import net.extrawdw.apps.notisync.data.AppSelectionRepository
import net.extrawdw.apps.notisync.data.PerAppConfig
import net.extrawdw.apps.notisync.data.SeenChannel

/**
 * One behavior facade for the Android notification-policy aggregate. Selection, per-app configuration, and
 * observed channel metadata share one Room repository and one serialized writer; this avoids two projections
 * racing on the same app row while keeping the existing capture/UI ports intact.
 */
internal class RoomAndroidNotificationPolicyFacade(
    private val repository: AndroidNotificationPolicyRepository,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
) : AppSelectionRepository, AppConfigRepository {
    private val _enabled = MutableStateFlow<Set<String>>(emptySet())
    override val enabled: StateFlow<Set<String>> = _enabled

    private val _lastSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val lastSeen: StateFlow<Map<String, Long>> = _lastSeen

    private val _configs = MutableStateFlow<Map<String, PerAppConfig>>(emptyMap())
    override val configs: StateFlow<Map<String, PerAppConfig>> = _configs

    private val _seenChannels = MutableStateFlow<Map<String, List<SeenChannel>>>(emptyMap())
    override val seenChannels: StateFlow<Map<String, List<SeenChannel>>> = _seenChannels

    private val policyHydrated = CompletableDeferred<Unit>()
    private val configHydrated = CompletableDeferred<Unit>()
    private val channelsHydrated = CompletableDeferred<Unit>()
    private val writeMutex = Mutex()

    init {
        observePolicy()
        observeConfigs()
        observeSeenChannels()
    }

    override suspend fun awaitHydrated() {
        policyHydrated.await()
        configHydrated.await()
        channelsHydrated.await()
    }

    override fun isEnabled(packageName: String): Boolean = packageName in _enabled.value

    override fun setEnabled(packageName: String, enabled: Boolean) {
        requirePackageName(packageName)
        _enabled.update { current -> if (enabled) current + packageName else current - packageName }
        scope.launch {
            writeMutex.withLock {
                policyHydrated.await()
                mutateApp(packageName) { it.copy(enabled = enabled) }
            }
        }
    }

    override fun setEnabled(packageNames: Collection<String>, enabled: Boolean) {
        if (packageNames.isEmpty()) return
        packageNames.forEach(::requirePackageName)
        val distinctPackages = packageNames.distinct()
        _enabled.update { current -> if (enabled) current + distinctPackages else current - distinctPackages.toSet() }
        scope.launch {
            writeMutex.withLock {
                policyHydrated.await()
                distinctPackages.forEach { packageName ->
                    mutateApp(packageName) { it.copy(enabled = enabled) }
                }
            }
        }
    }

    override fun recordSeen(packageName: String, timeMillis: Long) {
        requirePackageName(packageName)
        require(timeMillis > 0) { "notification last-seen time must be positive" }
        // Recency only drives the current app picker; it is not a correctness boundary and the old store kept it
        // in memory. Avoid turning every notification post into a Room write (and avoid creating policy rows for
        // packages the user has not enabled).
        _lastSeen.update { current ->
            if (timeMillis > (current[packageName] ?: 0L)) current + (packageName to timeMillis) else current
        }
    }

    override fun configFor(packageName: String): PerAppConfig = _configs.value[packageName] ?: PerAppConfig()

    override fun seenChannelsFor(packageName: String): List<SeenChannel> =
        _seenChannels.value[packageName].orEmpty()

    override fun setMirrorOngoing(packageName: String, enabled: Boolean) =
        mutateConfig(packageName) { it.copy(mirrorOngoing = enabled) }

    override fun setUpdateIntervalSec(packageName: String, seconds: Int) {
        require(seconds >= -1) { "notification update interval must be -1 or non-negative" }
        mutateConfig(packageName) { it.copy(updateIntervalSec = seconds) }
    }

    override fun setMirrorOngoingToIos(packageName: String, enabled: Boolean) =
        mutateConfig(packageName) { it.copy(mirrorOngoingToIos = enabled) }

    override fun setMirrorMediaPlaybackToIos(packageName: String, enabled: Boolean) =
        mutateConfig(packageName) { it.copy(mirrorMediaPlaybackToIos = enabled) }

    override fun setRingForCalls(packageName: String, enabled: Boolean) =
        mutateConfig(packageName) { it.copy(ringForCalls = enabled) }

    override fun setChannelEnabled(packageName: String, channelId: String, enabled: Boolean) {
        requirePackageName(packageName)
        requireIdentifier(channelId, "notification channel id")
        scope.launch {
            writeMutex.withLock {
                policyHydrated.await()
                ensureApp(packageName)
                if (enabled) {
                    repository.removeSubscopePolicy(packageName, NotificationPolicyScope.CHANNEL, channelId)
                } else {
                    repository.replaceSubscopePolicy(
                        AndroidSubscopePolicy(
                            packageName = packageName,
                            scope = NotificationPolicyScope.CHANNEL,
                            scopeId = channelId,
                            enabled = false,
                            updatedAt = nextUpdatedAt(repository.findApp(packageName)?.updatedAt ?: now()),
                        ),
                    )
                }
            }
        }
    }

    override fun setGroupEnabled(packageName: String, groupId: String, enabled: Boolean) {
        requirePackageName(packageName)
        requireIdentifier(groupId, "notification group id")
        scope.launch {
            writeMutex.withLock {
                policyHydrated.await()
                ensureApp(packageName)
                if (enabled) {
                    repository.removeSubscopePolicy(packageName, NotificationPolicyScope.GROUP, groupId)
                } else {
                    repository.replaceSubscopePolicy(
                        AndroidSubscopePolicy(
                            packageName = packageName,
                            scope = NotificationPolicyScope.GROUP,
                            scopeId = groupId,
                            enabled = false,
                            updatedAt = nextUpdatedAt(repository.findApp(packageName)?.updatedAt ?: now()),
                        ),
                    )
                }
            }
        }
    }

    override fun isChannelSuppressed(packageName: String, channelId: String?, groupId: String?): Boolean {
        val cfg = _configs.value[packageName] ?: return false
        return (groupId != null && groupId in cfg.disabledGroupIds) ||
            (channelId != null && channelId in cfg.disabledChannelIds)
    }

    override fun recordSeenChannel(
        packageName: String,
        channelId: String?,
        channelName: String?,
        groupId: String?,
        groupName: String?,
    ) {
        val id = channelId?.takeIf { it.isNotBlank() } ?: return
        requirePackageName(packageName)
        scope.launch {
            writeMutex.withLock {
                policyHydrated.await()
                val projected = _seenChannels.value[packageName]?.firstOrNull { it.channelId == id }
                if (
                    projected != null &&
                    projected.channelName == (channelName ?: projected.channelName) &&
                    projected.groupId == groupId &&
                    projected.groupName == (groupName ?: projected.groupName)
                ) {
                    return@withLock
                }
                // Seen channels have a foreign key to the app policy. A capture can be the first row for a
                // package, so create the default policy before recording its channel metadata.
                ensureApp(packageName)
                val existingChannels = repository.observeSeenChannels(packageName).first()
                val existingGroups = repository.observeSeenGroups(packageName).first()
                val existingChannel = existingChannels.firstOrNull { it.channelId == id }
                val existingGroup = groupId?.let { group -> existingGroups.firstOrNull { it.groupId == group } }
                val effectiveChannelName = channelName ?: existingChannel?.channelName
                val effectiveGroupName = groupName ?: existingGroup?.groupName
                val channelMetadataChanged = existingChannel == null ||
                    existingChannel.channelName != effectiveChannelName ||
                    existingChannel.groupId != groupId
                val groupMetadataChanged = groupId != null &&
                    (existingGroup == null || existingGroup.groupName != effectiveGroupName)
                if (!channelMetadataChanged && !groupMetadataChanged) return@withLock
                val seenNow = now().coerceAtLeast(1L)
                val group = groupId?.let { group ->
                    AndroidObservedGroup(
                        packageName = packageName,
                        groupId = group,
                        groupName = effectiveGroupName,
                        firstSeenAt = minOf(existingGroup?.firstSeenAt ?: seenNow, seenNow),
                        lastSeenAt = maxOf(existingGroup?.lastSeenAt ?: 0L, seenNow),
                    )
                }
                repository.recordSeenChannel(
                    group = group,
                    channel = AndroidObservedChannel(
                        packageName = packageName,
                        channelId = id,
                        channelName = effectiveChannelName,
                        groupId = groupId,
                        firstSeenAt = minOf(existingChannel?.firstSeenAt ?: seenNow, seenNow),
                        lastSeenAt = maxOf(existingChannel?.lastSeenAt ?: 0L, seenNow),
                    ),
                )
            }
        }
    }

    override fun removeSeenChannel(packageName: String, channelId: String) {
        requirePackageName(packageName)
        requireIdentifier(channelId, "notification channel id")
        scope.launch {
            writeMutex.withLock {
                channelsHydrated.await()
                repository.forgetSeenChannel(packageName, channelId)
            }
        }
    }

    private fun mutateConfig(packageName: String, transform: (PerAppConfig) -> PerAppConfig) {
        requirePackageName(packageName)
        scope.launch {
            writeMutex.withLock {
                policyHydrated.await()
                val current = repository.findApp(packageName) ?: defaultApp(packageName)
                val config = current.toPerAppConfig(
                    repository.observeSubscopePolicies(packageName, NotificationPolicyScope.CHANNEL).first(),
                    repository.observeSubscopePolicies(packageName, NotificationPolicyScope.GROUP).first(),
                )
                val next = transform(config)
                repository.replaceApp(
                    current.copy(
                        mirrorOngoing = next.mirrorOngoing,
                        updateIntervalSeconds = next.updateIntervalSec,
                        mirrorOngoingToIos = next.mirrorOngoingToIos,
                        mirrorMediaPlaybackToIos = next.mirrorMediaPlaybackToIos,
                        ringForCalls = next.ringForCalls,
                        updatedAt = nextUpdatedAt(current.updatedAt),
                    ),
                )
            }
        }
    }

    private suspend fun mutateApp(packageName: String, transform: (AndroidAppPolicy) -> AndroidAppPolicy) {
        val current = repository.findApp(packageName) ?: defaultApp(packageName)
        repository.replaceApp(transform(current).copy(updatedAt = nextUpdatedAt(current.updatedAt)))
    }

    private suspend fun ensureApp(packageName: String): AndroidAppPolicy =
        repository.findApp(packageName) ?: defaultApp(packageName).also { repository.replaceApp(it) }

    private fun defaultApp(packageName: String): AndroidAppPolicy = AndroidAppPolicy(
        packageName = packageName,
        enabled = false,
        mirrorOngoing = false,
        updateIntervalSeconds = 0,
        mirrorOngoingToIos = false,
        mirrorMediaPlaybackToIos = false,
        ringForCalls = true,
        lastSeenAt = null,
        updatedAt = now().coerceAtLeast(1L),
    )

    private fun nextUpdatedAt(previous: Long): Long = maxOf(now(), previous + 1L, 1L)

    private fun observePolicy() {
        scope.launch {
            try {
                repository.observeApps().collect { policies ->
                    _enabled.value = policies.filter { it.enabled }.mapTo(linkedSetOf()) { it.packageName }
                    _lastSeen.value = policies.mapNotNull { policy ->
                        policy.lastSeenAt?.let { policy.packageName to it }
                    }.toMap()
                    policyHydrated.complete(Unit)
                }
            } catch (error: Throwable) {
                policyHydrated.completeExceptionally(error)
                if (error is CancellationException) throw error
                throw error
            }
        }
    }

    private fun observeConfigs() {
        scope.launch {
            try {
                repository.observeApps().flatMapLatest { policies ->
                    val flows: List<Flow<Pair<String, PerAppConfig>>> = policies.map { policy ->
                        combine(
                            repository.observeSubscopePolicies(policy.packageName, NotificationPolicyScope.CHANNEL),
                            repository.observeSubscopePolicies(policy.packageName, NotificationPolicyScope.GROUP),
                        ) { channels, groups -> policy.packageName to policy.toPerAppConfig(channels, groups) }
                    }
                    if (flows.isEmpty()) flowOf(emptyMap())
                    else combine(flows) { values -> values.map { it as Pair<String, PerAppConfig> }.toMap() }
                }.collect { configs ->
                    _configs.value = configs
                    configHydrated.complete(Unit)
                }
            } catch (error: Throwable) {
                configHydrated.completeExceptionally(error)
                if (error is CancellationException) throw error
                throw error
            }
        }
    }

    private fun observeSeenChannels() {
        scope.launch {
            try {
                repository.observeApps().flatMapLatest { policies ->
                    val flows: List<Flow<Pair<String, List<SeenChannel>>>> = policies.map { policy ->
                        combine(
                            repository.observeSeenChannels(policy.packageName),
                            repository.observeSeenGroups(policy.packageName),
                        ) { channels, groups ->
                            val groupById = groups.associateBy { it.groupId }
                            policy.packageName to channels.map { channel ->
                                SeenChannel(
                                    channelId = channel.channelId,
                                    channelName = channel.channelName,
                                    groupId = channel.groupId,
                                    groupName = channel.groupId?.let { groupById[it]?.groupName },
                                )
                            }
                        }
                    }
                    if (flows.isEmpty()) flowOf(emptyMap())
                    else combine(flows) { values -> values.map { it as Pair<String, List<SeenChannel>> }.toMap() }
                }.collect { channels ->
                    _seenChannels.value = channels
                    channelsHydrated.complete(Unit)
                }
            } catch (error: Throwable) {
                channelsHydrated.completeExceptionally(error)
                if (error is CancellationException) throw error
                throw error
            }
        }
    }
}

private fun AndroidAppPolicy.toPerAppConfig(
    channelPolicies: List<AndroidSubscopePolicy> = emptyList(),
    groupPolicies: List<AndroidSubscopePolicy> = emptyList(),
): PerAppConfig = PerAppConfig(
    mirrorOngoing = mirrorOngoing,
    updateIntervalSec = updateIntervalSeconds,
    mirrorOngoingToIos = mirrorOngoingToIos,
    mirrorMediaPlaybackToIos = mirrorMediaPlaybackToIos,
    disabledChannelIds = channelPolicies.filterNot { it.enabled }.mapTo(linkedSetOf()) { it.scopeId },
    disabledGroupIds = groupPolicies.filterNot { it.enabled }.mapTo(linkedSetOf()) { it.scopeId },
    ringForCalls = ringForCalls,
)

private fun requirePackageName(packageName: String) {
    require(packageName.isNotBlank() && packageName.length <= AndroidNotificationPolicyLimits.MAX_PACKAGE_CHARS) {
        "package name is invalid"
    }
    require(packageName.none(Char::isISOControl)) { "package name contains control characters" }
}

private fun requireIdentifier(value: String, name: String) {
    require(value.isNotBlank() && value.length <= AndroidNotificationPolicyLimits.MAX_IDENTIFIER_CHARS) {
        "$name is invalid"
    }
    require(value.none(Char::isISOControl)) { "$name contains control characters" }
}
