package net.extrawdw.apps.notisync.ios

import org.junit.Assert.assertEquals
import org.junit.Test

class IosAppRegistryTest {
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
