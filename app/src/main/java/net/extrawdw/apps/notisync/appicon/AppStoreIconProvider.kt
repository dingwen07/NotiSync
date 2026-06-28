package net.extrawdw.apps.notisync.appicon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates App Store icon resolution over [AppStoreIconClient] (network) + [AppStoreIconCache] (disk),
 * with a small in-memory decoded-bitmap cache on top. The single type [IconResolver] and the renderer depend
 * on for the runtime icon long-tail.
 *
 * Two entry points, matching the synchronous/asynchronous split of the resolution chain:
 *  - [cached] — synchronous, cache-only (no network); feeds [IconResolver.colorIcon]'s immediate render;
 *  - [ensureCached] — suspending; fetches once if absent and returns whether a re-render would now show
 *    something new (the same `newlyAvailable` contract as
 *    [net.extrawdw.apps.notisync.domain.ResolveResult]).
 */
class AppStoreIconProvider(
    private val cache: AppStoreIconCache,
    /** Network fetch of a WebP for (bundleId, sizePx); typically [AppStoreIconClient.fetch]. Injected so the
     *  orchestration is unit-testable without a live API. */
    private val fetch: suspend (bundleId: String, sizePx: Int) -> IconFetchResult,
    private val sizePx: Int = DEFAULT_SIZE_PX,
    private val decode: (ByteArray) -> Bitmap? = { BitmapFactory.decodeByteArray(it, 0, it.size) },
) {
    private val mem = ConcurrentHashMap<String, Bitmap>()

    /** A previously-fetched icon for [bundleId] from memory/disk, or null. Never hits the network. */
    fun cached(bundleId: String): Bitmap? {
        if (bundleId.isBlank()) return null
        mem[bundleId]?.let { return it }
        val bytes = cache.readBytes(bundleId) ?: return null
        return decode(bytes)?.also { mem[bundleId] = it }
    }

    /**
     * Ensure [bundleId]'s icon is cached, fetching it once if needed. Returns true iff the icon is now
     * available and was *not* before this call — i.e. a re-render would surface it. Already-cached, a real
     * "no store entry", recently-missed, or a transient fetch failure all return false (no re-render).
     *
     * A genuine miss is negative-cached (suppressed for the TTL); a transient network/timeout failure is
     * NOT, so a momentary outage doesn't hide a real icon for the whole TTL.
     */
    suspend fun ensureCached(bundleId: String): Boolean {
        if (bundleId.isBlank()) return false
        // Disk checks/writes are blocking I/O — keep them off the (CPU-bound) caller dispatcher.
        if (withContext(Dispatchers.IO) { cache.has(bundleId) || cache.isRecentlyMissing(bundleId) }) return false
        val result = fetch(bundleId, sizePx) // network; ktor manages its own dispatcher
        return withContext(Dispatchers.IO) {
            when (result) {
                is IconFetchResult.Found -> {
                    cache.write(bundleId, result.bytes)
                    mem.remove(bundleId) // drop any stale decode so cached() re-decodes the fresh bytes
                    true
                }

                IconFetchResult.NotFound -> {
                    cache.markMissing(bundleId) // durable miss — suppress re-querying for the TTL
                    false
                }

                IconFetchResult.TransientError -> false // do NOT poison the cache; retry on the next pass
            }
        }
    }

    private companion object {
        // Notification large icons render ~48-64dp; 256px stays crisp to xxxhdpi at a few KB of WebP.
        const val DEFAULT_SIZE_PX = 256
    }
}
