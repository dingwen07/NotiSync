package net.extrawdw.apps.notisync.data

import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.NotificationFilterRule
import net.extrawdw.notisync.protocol.OriginPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the source-side filter matching contract — the half that must agree, byte for byte, with
 * the iOS sender's `NotificationFilterStore` (which builds the rules) and its NSE filter (which keys the
 * same way locally). Exercises the pure companion functions directly; no DataStore needed.
 */
class NotificationFilterStoreTest {
    private fun filter(vararg rules: NotificationFilterRule) = FilterSync(rules.toList(), updatedAt = 1L)

    @Test
    fun appIdentifier_prefersIosBundleIdThenPackage() {
        // An ANCS capture carries both: the iOS bundle id wins (matches the iOS sender's choice).
        assertEquals("net.whatsapp.WhatsApp",
            NotificationFilterStore.appIdentifier("com.whatsapp", "net.whatsapp.WhatsApp"))
        // A plain Android capture has no bundle id → the package name.
        assertEquals("com.whatsapp", NotificationFilterStore.appIdentifier("com.whatsapp", null))
        // Blank fields are ignored.
        assertEquals("com.x", NotificationFilterStore.appIdentifier("com.x", "  "))
        assertEquals(null, NotificationFilterStore.appIdentifier(" ", null))
    }

    @Test
    fun androidDeviceLevelRule_suppressesEveryAndroidLocalCapture() {
        val f = filter(NotificationFilterRule(OriginPlatform.ANDROID_LOCAL))
        assertTrue(NotificationFilterStore.matches(f, OriginPlatform.ANDROID_LOCAL, "com.a", "ch"))
        assertTrue(NotificationFilterStore.matches(f, OriginPlatform.ANDROID_LOCAL, "com.b", null))
        // ...but never a bridged-iPhone capture (different origin).
        assertFalse(NotificationFilterStore.matches(f, OriginPlatform.IOS_ANCS, "com.a", null))
    }

    @Test
    fun androidAppLevelRule_suppressesThatAppAnyChannelOnly() {
        val f = filter(NotificationFilterRule(OriginPlatform.ANDROID_LOCAL, appId = "com.whatsapp"))
        assertTrue(NotificationFilterStore.matches(f, OriginPlatform.ANDROID_LOCAL, "com.whatsapp", "calls"))
        assertTrue(NotificationFilterStore.matches(f, OriginPlatform.ANDROID_LOCAL, "com.whatsapp", null))
        assertFalse(NotificationFilterStore.matches(f, OriginPlatform.ANDROID_LOCAL, "com.slack", "dms"))
    }

    @Test
    fun androidChannelLevelRule_suppressesOnlyThatChannel() {
        val f = filter(NotificationFilterRule(OriginPlatform.ANDROID_LOCAL, appId = "com.slack", channelId = "dms"))
        assertTrue(NotificationFilterStore.matches(f, OriginPlatform.ANDROID_LOCAL, "com.slack", "dms"))
        assertFalse(NotificationFilterStore.matches(f, OriginPlatform.ANDROID_LOCAL, "com.slack", "mentions"))
        assertFalse(NotificationFilterStore.matches(f, OriginPlatform.ANDROID_LOCAL, "com.slack", null))
        assertFalse(NotificationFilterStore.matches(f, OriginPlatform.ANDROID_LOCAL, "com.whatsapp", "dms"))
    }

    @Test
    fun iosRules_areDeviceOrAppLevel_andOriginScoped() {
        val device = filter(NotificationFilterRule(OriginPlatform.IOS_ANCS))
        assertTrue(NotificationFilterStore.matches(device, OriginPlatform.IOS_ANCS, "net.whatsapp.WhatsApp", null))
        assertFalse("an iOS device-level rule must not touch Android-local captures",
            NotificationFilterStore.matches(device, OriginPlatform.ANDROID_LOCAL, "com.whatsapp", null))

        val app = filter(NotificationFilterRule(OriginPlatform.IOS_ANCS, appId = "net.whatsapp.WhatsApp"))
        assertTrue(NotificationFilterStore.matches(app, OriginPlatform.IOS_ANCS, "net.whatsapp.WhatsApp", null))
        assertFalse(NotificationFilterStore.matches(app, OriginPlatform.IOS_ANCS, "com.apple.MobileSMS", null))
    }


    @Test
    fun multipleRules_matchIfAnyMatches_andEmptyFilterMatchesNothing() {
        val f = filter(
            NotificationFilterRule(OriginPlatform.ANDROID_LOCAL, appId = "com.a"),
            NotificationFilterRule(OriginPlatform.IOS_ANCS),
        )
        assertTrue(NotificationFilterStore.matches(f, OriginPlatform.ANDROID_LOCAL, "com.a", null))
        assertTrue(NotificationFilterStore.matches(f, OriginPlatform.IOS_ANCS, "anything", null))
        assertFalse(NotificationFilterStore.matches(f, OriginPlatform.ANDROID_LOCAL, "com.b", null))
        assertFalse(NotificationFilterStore.matches(filter(), OriginPlatform.ANDROID_LOCAL, "com.a", "c"))
    }
}
