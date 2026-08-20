package net.extrawdw.apps.notisync.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import net.extrawdw.apps.notisync.data.iosregistry.IosAppRegistryLimits
import net.extrawdw.apps.notisync.data.iosregistry.IosAppRegistryRepository

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
 * next time ANCS reports it.
 */
interface IosAppRegistry {
    val enabled: StateFlow<Set<String>>
    val discovered: StateFlow<Map<String, IosApp>>
    suspend fun isEnabled(bundleId: String): Boolean
    fun setEnabled(bundleId: String, enabled: Boolean)
    fun setEnabled(bundleIds: Collection<String>, enabled: Boolean)
    fun recordSeen(bundleId: String, displayName: String, timeMillis: Long)
    fun forgetSeen(bundleId: String)
    suspend fun awaitHydrated()
}

/** Room-backed adapter retaining the bridge/UI behavior port without a legacy DataStore business store. */
internal class RoomBackedIosAppRegistry(
    private val repository: IosAppRegistryRepository,
    private val scope: CoroutineScope,
) : IosAppRegistry {
    private val _enabled = MutableStateFlow<Set<String>>(emptySet())
    override val enabled: StateFlow<Set<String>> = _enabled

    private val _discovered = MutableStateFlow<Map<String, IosApp>>(emptyMap())
    override val discovered: StateFlow<Map<String, IosApp>> = _discovered

    private val enabledHydrated = CompletableDeferred<Unit>()
    private val discoveredHydrated = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                repository.observeAllowlisted().collect { rows ->
                    _enabled.value = IosBundleIdExclusions.filterEnabled(rows.mapTo(linkedSetOf()) { it.bundleId })
                    enabledHydrated.complete(Unit)
                }
            } catch (error: Throwable) {
                enabledHydrated.completeExceptionally(error)
                if (error is CancellationException) throw error
                throw error
            }
        }
        scope.launch {
            try {
                repository.observeSeen().collect { rows ->
                    _discovered.value = rows.associate { row ->
                        row.bundleId to IosApp(row.bundleId, row.displayName, row.lastSeenAt)
                    }
                    discoveredHydrated.complete(Unit)
                }
            } catch (error: Throwable) {
                discoveredHydrated.completeExceptionally(error)
                if (error is CancellationException) throw error
                throw error
            }
        }
    }

    override suspend fun awaitHydrated() {
        enabledHydrated.await()
        discoveredHydrated.await()
    }

    override suspend fun isEnabled(bundleId: String): Boolean {
        requireBundleId(bundleId)
        enabledHydrated.await()
        return !IosBundleIdExclusions.isExcluded(bundleId) && bundleId in _enabled.value
    }

    override fun setEnabled(bundleId: String, enabled: Boolean) {
        requireBundleId(bundleId)
        if (IosBundleIdExclusions.isExcluded(bundleId)) {
            _enabled.update { it - bundleId }
            return
        }
        _enabled.update { current -> if (enabled) current + bundleId else current - bundleId }
        scope.launch {
            enabledHydrated.await()
            repository.setEnabled(bundleId, enabled)
        }
    }

    override fun setEnabled(bundleIds: Collection<String>, enabled: Boolean) {
        if (bundleIds.isEmpty()) return
        bundleIds.forEach(::requireBundleId)
        val allowed = bundleIds.filterNot(IosBundleIdExclusions::isExcluded).distinct()
        _enabled.update { current -> if (enabled) current + allowed else current - bundleIds }
        scope.launch {
            enabledHydrated.await()
            allowed.forEach { repository.setEnabled(it, enabled) }
        }
    }

    override fun recordSeen(bundleId: String, displayName: String, timeMillis: Long) {
        requireBundleId(bundleId)
        requireDisplayName(displayName)
        require(timeMillis > 0) { "iOS app last-seen time must be positive" }
        val existing = _discovered.value[bundleId]
        if (existing != null && timeMillis <= existing.lastSeen && displayName == existing.displayName) return
        _discovered.update {
            it + (bundleId to IosApp(
                bundleId = bundleId,
                displayName = displayName,
                lastSeen = maxOf(timeMillis, existing?.lastSeen ?: 0L),
            ))
        }
        // Last-seen recency is useful to the current picker but not a correctness boundary. Keep it live in
        // memory and write only new apps/name changes, matching the old deliberate write-throttling behavior.
        if (existing == null || existing.displayName != displayName) {
            scope.launch {
                discoveredHydrated.await()
                repository.recordSeen(bundleId, displayName, timeMillis)
            }
        }
    }

    override fun forgetSeen(bundleId: String) {
        requireBundleId(bundleId)
        if (bundleId !in _discovered.value) return
        _discovered.update { it - bundleId }
        scope.launch {
            discoveredHydrated.await()
            repository.forgetSeen(bundleId)
        }
    }
}

private fun requireBundleId(bundleId: String) {
    require(
        bundleId.isNotBlank() && bundleId.length <= IosAppRegistryLimits.MAX_BUNDLE_ID_CHARS,
    ) { "iOS bundle id is invalid" }
    require(bundleId.none(Char::isISOControl)) { "iOS bundle id contains control characters" }
}

private fun requireDisplayName(displayName: String) {
    require(
        displayName.isNotBlank() && displayName.length <= IosAppRegistryLimits.MAX_DISPLAY_CHARS,
    ) { "iOS display name is invalid" }
    require(displayName.none(Char::isISOControl)) { "iOS display name contains control characters" }
}
