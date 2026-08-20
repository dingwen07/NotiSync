package net.extrawdw.apps.notisync.composition.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageContainerBoundaryTest {
    @Test
    fun processRegistryPublishesExactlyOneCompleteInstanceAndClearsOnlyItsOwner() {
        val registry = ProcessSingletonRegistry<Any>()
        var constructions = 0
        val first = registry.getOrCreate { Any().also { constructions += 1 } }
        val second = registry.getOrCreate { Any().also { constructions += 1 } }

        assertSame(first, second)
        assertEquals(1, constructions)
        assertFalse(registry.clearIfSame(Any()))
        assertSame(first, registry.peek())
        assertTrue(registry.clearIfSame(first))
        assertNull(registry.peek())
    }
}
