package net.extrawdw.apps.notisync.appicon

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStoreIconClientTest {

    @Test
    fun lookupUrl_scopesToSoftwareAndBundleId() {
        val u = AppStoreIconClient.lookupUrl("com.apple.MobileSMS", "us")
        assertTrue(u.startsWith("https://itunes.apple.com/lookup?"))
        assertTrue(u.contains("bundleId=com.apple.MobileSMS"))
        assertTrue(u.contains("country=us"))
        assertTrue(u.contains("entity=software"))
    }

    @Test
    fun toWebpUrl_rewritesOnlyTrailingTransformSuffix() {
        // The source filename also ends in `.png` mid-path; only the final `/512x512bb.jpg` segment is rewritten.
        val art =
            "https://is1-ssl.mzstatic.com/image/thumb/Purple/v4/ab/cd/messages-0-sRGB-85-220.png/512x512bb.jpg"
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Purple/v4/ab/cd/messages-0-sRGB-85-220.png/256x256bb.webp",
            AppStoreIconClient.toWebpUrl(art, 256),
        )
    }

    @Test
    fun toWebpUrl_passesThroughNonTemplatedUrl() {
        val u = "https://example.com/icon.png"
        assertEquals(u, AppStoreIconClient.toWebpUrl(u, 256))
    }

    @Test
    fun parseArtworkUrl_prefersHighestRes_thenFallsBack() {
        val all =
            """{"resultCount":1,"results":[{"artworkUrl60":"a60","artworkUrl100":"a100","artworkUrl512":"a512"}]}"""
        assertEquals("a512", AppStoreIconClient.parseArtworkUrl(all))
        val small =
            """{"resultCount":1,"results":[{"artworkUrl60":"a60","artworkUrl100":"a100"}]}"""
        assertEquals("a100", AppStoreIconClient.parseArtworkUrl(small))
    }

    @Test
    fun parseArtworkUrl_noStoreEntryOrGarbage_returnsNull() {
        assertNull(AppStoreIconClient.parseArtworkUrl("""{"resultCount":0,"results":[]}"""))
        assertNull(AppStoreIconClient.parseArtworkUrl("not json at all"))
    }
    @Test
    fun numericIdUsesRequestedStorefrontThenFallsBackAndFetchesMacArtwork() = runBlocking {
        val urls = mutableListOf<String>()
        val image = byteArrayOf(1, 2, 3)
        val client = AppStoreIconClient(download = { url, maxBytes ->
            urls += url
            when (url) {
                "https://itunes.apple.com/lookup?id=497799835&country=gb" -> "{\"results\":[]}".encodeToByteArray()
                "https://itunes.apple.com/lookup?id=497799835&country=us" -> {
                    assertEquals(1024 * 1024, maxBytes)
                    """{"results":[{"trackId":497799835,"wrapperType":"software","kind":"mac-software","artworkUrl512":"https://example.com/512x512bb.png"}]}""".encodeToByteArray()
                }
                "https://example.com/512x512bb.webp" -> {
                    assertEquals(MAX_ICON_SOURCE_BYTES, maxBytes)
                    image
                }
                else -> error("Unexpected request: $url")
            }
        })
        val result = client.fetchAppId("497799835", 512, "gb") as IconFetchResult.Found
        assertArrayEquals(image, result.bytes)
        assertEquals(3, urls.size)
    }

    @Test
    fun lookupRequiresTheRequestedSoftwareIdentity() {
        val body = """{"results":[{"trackId":1,"wrapperType":"track","artworkUrl512":"music"},{"trackId":2,"wrapperType":"software","artworkUrl512":"app"}]}"""
        assertNull(AppStoreIconClient.parseArtworkUrl(body, "1"))
        assertNull(AppStoreIconClient.parseArtworkUrl(body, "3"))
        assertEquals("app", AppStoreIconClient.parseArtworkUrl(body, "2"))
    }

    @Test
    fun existingBundleLookupStillFallsBackAndDistinguishesErrorsFromMisses() = runBlocking {
        val urls = mutableListOf<String>()
        val missing = AppStoreIconClient(download = { url, _ ->
            urls += url
            "{\"results\":[]}".encodeToByteArray()
        })
        assertSame(IconFetchResult.NotFound, missing.fetch("com.example.app", 256))
        assertEquals(listOf(
            AppStoreIconClient.lookupUrl("com.example.app", "us"),
            AppStoreIconClient.lookupUrl("com.example.app", "cn"),
        ), urls)
        val offline = AppStoreIconClient(download = { url, _ ->
            if (url.contains("country=us")) throw IOException("test")
            "{\"results\":[]}".encodeToByteArray()
        })
        assertSame(IconFetchResult.TransientError, offline.fetch("com.example.app", 256))
    }

    @Test
    fun lookupCancellationStopsBeforeTheNextStorefront() = runBlocking {
        var requests = 0
        val client = AppStoreIconClient(download = { _, _ -> requests++; throw CancellationException() })
        try {
            client.fetchAppId("497799835", 512)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(1, requests)
    }
}
