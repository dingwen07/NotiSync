package net.extrawdw.apps.notisync.assets

import java.io.File

/**
 * Content-addressed local store of *decrypted* private-graphic bytes, keyed by the hex SHA-256 of the
 * plaintext (a safe filename). Shared by both roles of the same app: the provider caches what it
 * sends (so it can answer a repair request without re-rasterizing), and the consumer caches what it
 * fetches (so it can render and skip re-fetching).
 */
class AssetCache(baseDir: File) {
    private val dir = File(baseDir, "blobs").apply { mkdirs() }
    private fun file(assetHash: String) = File(dir, assetHash)

    fun has(assetHash: String): Boolean = file(assetHash).exists()

    fun read(assetHash: String): ByteArray? = file(assetHash).takeIf { it.exists() }?.readBytes()

    /** Atomic write (temp + rename) so a concurrent reader never observes a partial file. */
    fun write(assetHash: String, bytes: ByteArray) {
        val target = file(assetHash)
        if (target.exists()) return // content-addressed: identical bytes already present
        val tmp = File(dir, "$assetHash.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
    }
}
