package net.extrawdw.apps.notisync.appicon

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppStoreIconProviderTest {

    private fun cache(now: () -> Long = { 0L }, ttl: Long = 10_000L) =
        AppStoreIconCache(Files.createTempDirectory("notisync-provider").toFile(), negativeTtlMs = ttl, now = now)

    @Test fun ensureCached_fetchesOnce_thenServesFromCache() = runBlocking {
        var calls = 0
        val cache = cache()
        val provider = AppStoreIconProvider(cache, fetch = { _, _ -> calls++; IconFetchResult.Found(byteArrayOf(1, 2, 3)) })

        assertTrue(provider.ensureCached("net.whatsapp.WhatsApp"))  // fetched -> newly available (re-render)
        assertFalse(provider.ensureCached("net.whatsapp.WhatsApp")) // already cached -> nothing new
        assertEquals(1, calls)
        assertTrue(cache.has("net.whatsapp.WhatsApp"))
    }

    @Test fun ensureCached_noStoreEntry_negativeCached_noRefetch() = runBlocking {
        var calls = 0
        val provider = AppStoreIconProvider(cache(now = { 0L }), fetch = { _, _ -> calls++; IconFetchResult.NotFound })

        assertFalse(provider.ensureCached("com.apple.Preferences"))
        assertFalse(provider.ensureCached("com.apple.Preferences")) // durable miss: negative cache suppresses the refetch
        assertEquals(1, calls)
    }

    @Test fun ensureCached_transientError_notCached_retries() = runBlocking {
        var calls = 0
        val provider = AppStoreIconProvider(cache(now = { 0L }), fetch = { _, _ -> calls++; IconFetchResult.TransientError })

        assertFalse(provider.ensureCached("com.example.app"))
        assertFalse(provider.ensureCached("com.example.app")) // transient failure must NOT be negative-cached -> refetch
        assertEquals(2, calls)
    }

    @Test fun ensureCached_blankBundleId_isNoOp() = runBlocking {
        var calls = 0
        val provider = AppStoreIconProvider(cache(), fetch = { _, _ -> calls++; IconFetchResult.Found(byteArrayOf(1)) })
        assertFalse(provider.ensureCached(""))
        assertEquals(0, calls)
    }
}
