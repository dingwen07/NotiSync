package net.extrawdw.apps.notisync.run

import org.junit.Assert.assertEquals
import org.junit.Test

class RunStorageUsageTest {
    @Test
    fun accountingUsesAllocatedDatabaseAndWalFiles() {
        val usage = RunStorageUsage(
            usedPageBytes = 80,
            mainFileBytes = 120,
            walFileBytes = 10,
            shmFileBytes = 5,
        )

        assertEquals(135L, usage.diskBytes)
        assertEquals(135L, usage.accountedBytes)
    }

    @Test
    fun accountingCannotUndercountUsedPages() {
        val usage = RunStorageUsage(
            usedPageBytes = 140,
            mainFileBytes = 100,
            walFileBytes = 20,
            shmFileBytes = 5,
        )

        assertEquals(125L, usage.diskBytes)
        assertEquals(165L, usage.accountedBytes)
    }
}
