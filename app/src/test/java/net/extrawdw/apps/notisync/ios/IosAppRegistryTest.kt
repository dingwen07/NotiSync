package net.extrawdw.apps.notisync.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.testsupport.InMemoryOperationalApplicationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IosAppRegistryTest {
    private fun newRegistry(): IosAppRegistry {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        return IosAppRegistry(scope, InMemoryOperationalApplicationState())
    }

    @Test
    fun excludedBundleIdsRemainDiscoverableButCannotBeEnabled() = runBlocking {
        val registry = newRegistry()
        val excludedApp = "net.extrawdw.apps.NotiSync"
        val excludedService = "net.extrawdw.apps.NotiSync.NotificationService"
        val allowed = "com.apple.MobileSMS"

        registry.recordSeen(excludedApp, "NotiSync", 1L)
        registry.recordSeen(excludedService, "NotiSync Service", 2L)
        registry.recordSeen(allowed, "Messages", 3L)
        registry.setEnabled(excludedApp, true)
        registry.setEnabled(excludedService, true)
        registry.setEnabled(allowed, true)

        assertEquals(setOf(excludedApp, excludedService, allowed), registry.discovered.value.keys)
        assertFalse(registry.isEnabled(excludedApp))
        assertFalse(registry.isEnabled(excludedService))
        assertTrue(registry.isEnabled(allowed))
        assertEquals(setOf(allowed), registry.enabled.value)
    }

    @Test
    fun filterEnabledRemovesExcludedBundleIdsFromEnabledLists() {
        val allowed = "com.apple.MobileSMS"

        assertEquals(
            setOf(allowed),
            IosBundleIdExclusions.filterEnabled(
                setOf(
                    "net.extrawdw.apps.NotiSync",
                    "net.extrawdw.apps.NotiSync.NotificationService",
                    allowed,
                )
            )
        )
    }

    @Test
    fun forgetSeenRemovesDiscoveredAppWithoutChangingAllowlist() = runBlocking {
        val registry = newRegistry()
        val bundleId = "com.apple.MobileSMS"

        registry.recordSeen(bundleId, "Messages", 1L)
        registry.setEnabled(bundleId, true)

        registry.forgetSeen(bundleId)

        assertFalse(bundleId in registry.discovered.value)
        assertTrue(registry.isEnabled(bundleId))

        registry.recordSeen(bundleId, "Messages", 2L)

        assertEquals(IosApp(bundleId, "Messages", 2L), registry.discovered.value[bundleId])
    }
}
