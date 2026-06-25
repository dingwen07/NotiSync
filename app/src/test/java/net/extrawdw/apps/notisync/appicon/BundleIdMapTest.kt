package net.extrawdw.apps.notisync.appicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BundleIdMapTest {

    @Test
    fun crossPlatformApp_mapsBundleIdToAndroidPackageAndName() {
        assertEquals("com.whatsapp", BundleIdMap.androidPackage("net.whatsapp.WhatsApp"))
        assertEquals("WhatsApp", BundleIdMap.displayName("net.whatsapp.WhatsApp"))
        assertEquals("org.telegram.messenger", BundleIdMap.androidPackage("ph.telegra.Telegraph"))
        assertEquals("com.google.android.gm", BundleIdMap.androidPackage("com.google.Gmail"))
        assertEquals("com.instagram.android", BundleIdMap.androidPackage("com.burbn.instagram"))
    }

    @Test
    fun appleFirstPartyApp_hasNameButNoAndroidPackage() {
        // No Android counterpart, but we still want a friendly name (and a future shipped icon).
        assertNull(BundleIdMap.androidPackage("com.apple.MobileSMS"))
        assertEquals("Messages", BundleIdMap.displayName("com.apple.MobileSMS"))
        assertEquals("Mail", BundleIdMap.displayName("com.apple.mobilemail"))
    }

    @Test
    fun unknownBundleId_resolvesToNull() {
        assertNull(BundleIdMap.androidPackage("com.unknown.vendor.app"))
        assertNull(BundleIdMap.displayName("com.unknown.vendor.app"))
        assertNull(BundleIdMap.lookup("com.unknown.vendor.app"))
    }

    @Test
    fun lookup_fallsBackToLowercase() {
        // Belt-and-suspenders: an unexpectedly-cased id still resolves via the lowercase fallback.
        assertEquals("com.whatsapp", BundleIdMap.androidPackage("net.whatsapp.whatsapp"))
    }
}
