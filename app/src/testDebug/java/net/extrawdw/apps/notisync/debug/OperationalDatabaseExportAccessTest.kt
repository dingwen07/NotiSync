package net.extrawdw.apps.notisync.debug

import org.junit.Assert.assertThrows
import org.junit.Test

class OperationalDatabaseExportAccessTest {
    @Test
    fun onlyShellOrRootCanExportDebugDatabase() {
        enforceDebugShellAccess(2000, true)
        enforceDebugShellAccess(0, true)
        listOf(1000, 10000, 10123, 102000, -1).forEach { uid ->
            assertThrows(SecurityException::class.java) { enforceDebugShellAccess(uid, true) }
        }
    }

    @Test
    fun nonDebuggableBuildAlwaysDeniesExport() {
        listOf(0, 2000, 10000).forEach { uid ->
            assertThrows(SecurityException::class.java) { enforceDebugShellAccess(uid, false) }
        }
    }
}
