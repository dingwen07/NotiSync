package net.extrawdw.apps.notisync.ancs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IosAppRegistryTest {
    private fun newRegistry(): IosAppRegistry {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val file = File.createTempFile("ios-app-registry-${System.nanoTime()}", ".preferences_pb")
            .also { it.delete() }
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        return IosAppRegistry(ds, scope)
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
}
