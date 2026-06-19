package net.extrawdw.apps.notisync.assets

import java.io.File

/**
 * Content-addressed local store of *decrypted* private-graphic bytes, keyed by the hex SHA-256 of the
 * plaintext (a safe filename). Shared by both roles of the same app: the provider caches what it
 * sends (so it can answer a repair request without re-rasterizing), and the consumer caches what it
 * fetches (so it can render and skip re-fetching).
 *
 * Bounded by [maxBytes] with least-recently-used eviction (read touches the file's mtime, so recently
 * rendered assets survive). Eviction runs after a write, on whatever background thread called it.
 */
class AssetCache(baseDir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val dir = File(baseDir, "blobs").apply { mkdirs() }
    private fun file(assetHash: String) = File(dir, assetHash)

    fun has(assetHash: String): Boolean = file(assetHash).exists()

    fun read(assetHash: String): ByteArray? = file(assetHash).takeIf { it.exists() }?.also {
        it.setLastModified(System.currentTimeMillis()) // mark as recently used for LRU
    }?.readBytes()

    /** Atomic write (temp + rename) so a concurrent reader never observes a partial file. */
    fun write(assetHash: String, bytes: ByteArray) {
        val target = file(assetHash)
        if (target.exists()) return // content-addressed: identical bytes already present
        val tmp = File(dir, "$assetHash.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
        evictIfNeeded()
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

    private companion object {
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
    }
}
