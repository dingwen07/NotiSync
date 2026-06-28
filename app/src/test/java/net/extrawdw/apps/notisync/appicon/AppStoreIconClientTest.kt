package net.extrawdw.apps.notisync.appicon

import org.junit.Assert.assertEquals
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
}
