package net.extrawdw.apps.notisync.appicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class ShippedIconsTest {

    // --- pure key resolution -------------------------------------------------

    @Test fun candidates_bundleIdThenAliasThenPackage_lowercasedAndDeduped() {
        // WhatsApp: the iOS bundle id, then its BundleIdMap Android-package alias (== the explicit package,
        // so it dedupes to a single extra candidate).
        assertEquals(
            listOf("net.whatsapp.whatsapp", "com.whatsapp"),
            ShippedIconKeys.candidates("net.whatsapp.WhatsApp", "com.whatsapp"),
        )
    }

    @Test fun candidates_appleFirstParty_hasNoAndroidAlias() {
        assertEquals(listOf("com.apple.preferences"), ShippedIconKeys.candidates("com.apple.Preferences", null))
    }

    @Test fun candidates_packageOnly_andBlanksIgnored() {
        assertEquals(listOf("com.foo.bar"), ShippedIconKeys.candidates(null, "com.foo.BAR"))
        assertEquals(emptyList<String>(), ShippedIconKeys.candidates("", "  "))
    }

    // --- loader I/O + memoization (via the injectable open seam) -------------

    private class FakeOpen(private val present: Set<String> = emptySet()) : (String) -> InputStream? {
        val calls = mutableListOf<String>()
        override fun invoke(path: String): InputStream? {
            calls += path
            return if (path in present) ByteArrayInputStream(byteArrayOf(1, 2, 3)) else null
        }
    }

    @Test fun bitmap_triesEachCandidatePath_underAppiconsDir() {
        val open = FakeOpen()
        val icons = ShippedIcons(open = open, decode = { null })
        assertNull(icons.bitmap("net.whatsapp.WhatsApp", "com.whatsapp"))
        assertEquals(
            listOf("appicons/net.whatsapp.whatsapp.webp", "appicons/com.whatsapp.webp"),
            open.calls,
        )
    }

    @Test fun load_memoizesMisses_soAssetsAreTouchedOncePerKey() {
        val open = FakeOpen()
        val icons = ShippedIcons(open = open, decode = { null })
        assertFalse(icons.covers("com.apple.Preferences"))
        assertFalse(icons.covers("com.apple.Preferences"))
        assertEquals(1, open.calls.size) // second lookup served from the in-memory negative cache
    }

    @Test fun iosFallback_isSeparateFromBundleCoverage() {
        val open = FakeOpen(present = setOf("appicons/GenericAppIcon_iOS.webp"))
        val icons = ShippedIcons(open = open, decode = { null })

        assertNull(icons.iosFallback())
        assertFalse(icons.covers("com.example.Missing"))
        assertEquals(
            listOf("appicons/GenericAppIcon_iOS.webp", "appicons/com.example.missing.webp"),
            open.calls,
        )
    }
}
