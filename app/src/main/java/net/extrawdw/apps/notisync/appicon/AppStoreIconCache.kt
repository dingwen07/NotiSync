package net.extrawdw.apps.notisync.appicon

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * On-disk cache of App Store icon WebP bytes, keyed by a normalized iOS **bundle id** (not a content hash) —
 * deliberately **separate from [net.extrawdw.apps.notisync.assets.AssetCache]**: an App Store icon is public
 * artwork fetched per device, never delivered as an encrypted private asset. Bounded by [maxBytes] with LRU
 * eviction (read touches mtime), mirroring AssetCache.
 *
 * A negative cache ([markMissing]/[isRecentlyMissing]) suppresses re-querying bundle ids with no store entry
 * (e.g. a store-absent app not in the shipped pack) or a transient failure. It is **persisted** to a small
 * JSON beside the icon dir (with a TTL, expired entries dropped on load) so a store-absent app isn't
 * re-fetched over the network on every app restart.
 */
class AppStoreIconCache(
    baseDir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val negativeTtlMs: Long = DEFAULT_NEGATIVE_TTL_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val dir = File(baseDir, "icons").apply { mkdirs() }

    // Kept in baseDir, NOT in `dir`, so LRU eviction (which scans `dir`) never counts or deletes it.
    private val negFile = File(baseDir, "negatives.json")
    private val negatives =
        ConcurrentHashMap<String, Long>(loadNegatives()) // normalized bundle id -> last-miss epoch ms

    private fun key(bundleId: String) = bundleId.lowercase().replace(UNSAFE, "_")
    private fun file(bundleId: String) = File(dir, key(bundleId))

    fun has(bundleId: String): Boolean = file(bundleId).exists()

    fun readBytes(bundleId: String): ByteArray? = file(bundleId).takeIf { it.exists() }?.also {
        it.setLastModified(now()) // mark recently used for LRU
    }?.readBytes()

    /** Atomic write (temp + rename); clears any negative marker for this id. */
    fun write(bundleId: String, bytes: ByteArray) {
        val target = file(bundleId)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
        target.setLastModified(now()) // stamp from the injected clock so LRU recency is consistent with reads
        if (negatives.remove(key(bundleId)) != null) saveNegatives()
        evictIfNeeded()
    }

    fun markMissing(bundleId: String) {
        negatives[key(bundleId)] = now()
        saveNegatives()
    }

    fun isRecentlyMissing(bundleId: String): Boolean {
        val at = negatives[key(bundleId)] ?: return false
        if (now() - at < negativeTtlMs) return true
        negatives.remove(key(bundleId)) // expired — allow a retry
        saveNegatives()
        return false
    }

    private fun evictIfNeeded() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) { // oldest first
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun loadNegatives(): Map<String, Long> = runCatching {
        val cutoff = now() - negativeTtlMs
        Json.decodeFromString(NEG_SERIALIZER, negFile.readText())
            .filterValues { it >= cutoff } // drop expired
    }.getOrDefault(emptyMap())

    private fun saveNegatives() {
        runCatching {
            val tmp = File(negFile.parentFile, "${negFile.name}.tmp")
            tmp.writeText(Json.encodeToString(NEG_SERIALIZER, negatives.toMap()))
            tmp.renameTo(negFile)
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES =
            8L * 1024 * 1024 // tiny WebP icons; a few hundred fit comfortably
        const val DEFAULT_NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000
        val UNSAFE = Regex("[^a-z0-9._-]")
        val NEG_SERIALIZER = MapSerializer(String.serializer(), Long.serializer())
    }
}
