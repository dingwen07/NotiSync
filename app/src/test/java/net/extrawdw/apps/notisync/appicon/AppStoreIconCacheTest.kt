package net.extrawdw.apps.notisync.appicon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppStoreIconCacheTest {

    private fun tempDir() = Files.createTempDirectory("notisync-appicons").toFile()

    @Test
    fun writeReadRoundTrip_caseInsensitiveKey() {
        val c = AppStoreIconCache(tempDir(), now = { 1L })
        assertFalse(c.has("com.apple.MobileSMS"))
        c.write("com.apple.MobileSMS", byteArrayOf(1, 2, 3))
        assertTrue(c.has("com.apple.MobileSMS"))
        assertTrue(c.has("com.apple.mobilesms")) // normalized (lowercased) key
        assertArrayEquals(byteArrayOf(1, 2, 3), c.readBytes("com.apple.MOBILESMS"))
    }

    @Test
    fun negativeCache_marksAndExpiresAfterTtl() {
        var t = 0L
        val c = AppStoreIconCache(tempDir(), negativeTtlMs = 1000, now = { t })
        assertFalse(c.isRecentlyMissing("com.apple.Preferences"))
        c.markMissing("com.apple.Preferences")
        assertTrue(c.isRecentlyMissing("com.apple.Preferences"))
        t = 1001
        assertFalse(c.isRecentlyMissing("com.apple.Preferences")) // TTL elapsed -> retry allowed
    }

    @Test
    fun write_clearsNegativeMarker() {
        val c = AppStoreIconCache(tempDir(), now = { 5L })
        c.markMissing("x")
        assertTrue(c.isRecentlyMissing("x"))
        c.write("x", byteArrayOf(9))
        assertFalse(c.isRecentlyMissing("x"))
    }

    @Test
    fun lruEviction_dropsOldest_andReadProtectsRecent() {
        var t = 100L
        val c = AppStoreIconCache(tempDir(), maxBytes = 12, now = { t })
        c.write("a", ByteArray(6)); t = 200
        c.write("b", ByteArray(6)); t = 300 // total 12, exactly at budget
        c.readBytes("a"); t = 400           // touch a -> a becomes the most-recently-used
        c.write("c", ByteArray(6))          // total 18 > 12 -> evict the oldest (b)
        assertTrue(c.has("a"))
        assertFalse(c.has("b"))
        assertTrue(c.has("c"))
    }

    @Test
    fun negativeCache_persistsAcrossInstances() {
        val dir = tempDir()
        AppStoreIconCache(
            dir,
            negativeTtlMs = 10_000,
            now = { 1000L }).markMissing("com.example.absent")
        // A fresh instance over the same dir (an app restart) still sees the miss -> no redundant re-fetch.
        val restarted = AppStoreIconCache(dir, negativeTtlMs = 10_000, now = { 2000L })
        assertTrue(restarted.isRecentlyMissing("com.example.absent"))
    }

    @Test
    fun negativeCache_expiredEntriesDroppedOnLoad() {
        val dir = tempDir()
        AppStoreIconCache(
            dir,
            negativeTtlMs = 1000,
            now = { 1000L }).markMissing("com.example.absent")
        // Restart 4s later with a 1s TTL: the persisted miss is stale and must not suppress a retry.
        val restarted = AppStoreIconCache(dir, negativeTtlMs = 1000, now = { 5000L })
        assertFalse(restarted.isRecentlyMissing("com.example.absent"))
    }
}
