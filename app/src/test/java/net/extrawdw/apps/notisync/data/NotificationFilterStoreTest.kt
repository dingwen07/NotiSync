package net.extrawdw.apps.notisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.NotificationFilterRule
import net.extrawdw.notisync.protocol.OriginPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // ---- DataStore-backed apply/exclude/clear behavior (drives the activity-log "changed" gate) ----

    private fun newStore(): NotificationFilterStore {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val file = File.createTempFile("filters-${System.nanoTime()}", ".preferences_pb").also { it.delete() }
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        return NotificationFilterStore(ds, scope)
    }

    private fun androidNotif(pkg: String, channel: String? = null) = CapturedNotification(
        sourceClientId = ClientId("src"), sourceKey = "k", packageName = pkg, appLabel = pkg,
        postTime = 1L, channelId = channel,
    )

    @Test
    fun apply_reportsChangeOnlyWhenRuleSetChanges() = runBlocking {
        val store = newStore()
        val req = ClientId("iphone-c")
        val v1 = FilterSync(listOf(NotificationFilterRule(OriginPlatform.ANDROID_LOCAL, appId = "com.a")), 100L)
        assertTrue("first non-empty filter is a change", store.apply(req, v1))
        // A periodic re-announce (same rules, newer ts) is NOT a change → must not log an activity row.
        assertFalse("idempotent re-announce is not a change", store.apply(req, FilterSync(v1.rules, 200L)))
        // Same rules, reordered — still not a change (the sender builds the list from unordered sets).
        assertFalse("reordered rules are not a change", store.apply(req, FilterSync(v1.rules.reversed(), 300L)))
        // A strictly older snapshot is ignored entirely (last-writer-wins) and is not a change.
        assertFalse("stale snapshot ignored",
            store.apply(req, FilterSync(listOf(NotificationFilterRule(OriginPlatform.IOS_ANCS)), 50L)))
        // A genuinely different rule set IS a change.
        assertTrue(store.apply(req, FilterSync(
            listOf(
                NotificationFilterRule(OriginPlatform.ANDROID_LOCAL, appId = "com.a"),
                NotificationFilterRule(OriginPlatform.ANDROID_LOCAL, appId = "com.b"),
            ), 400L)))
    }

    @Test
    fun recipientsToExclude_clearAndRemove() = runBlocking {
        val store = newStore()
        val req = ClientId("iphone-c")
        store.apply(req, FilterSync(listOf(NotificationFilterRule(OriginPlatform.ANDROID_LOCAL, appId = "com.a")), 100L))
        assertEquals(setOf(req), store.recipientsToExclude(androidNotif("com.a")))
        assertEquals(emptySet<ClientId>(), store.recipientsToExclude(androidNotif("com.b")))
        // An empty snapshot clears suppression (a real change → would log "cleared"), and delivery resumes.
        assertTrue(store.apply(req, FilterSync(emptyList(), 200L)))
        assertEquals(emptySet<ClientId>(), store.recipientsToExclude(androidNotif("com.a")))
        // remove() forgets the peer's filter entirely.
        store.apply(req, FilterSync(listOf(NotificationFilterRule(OriginPlatform.ANDROID_LOCAL)), 300L))
        assertEquals(setOf(req), store.recipientsToExclude(androidNotif("com.x")))
        store.remove(req)
        assertEquals(emptySet<ClientId>(), store.recipientsToExclude(androidNotif("com.x")))
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
