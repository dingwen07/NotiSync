package net.extrawdw.notisync.sshagent.endpoint

import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCallerResolverTest {
    private val resolver = LocalCallerResolver()

    @Test
    fun `current process context carries and refreshes the platform instance token`() {
        val original = resolver.resolve(
            ProcessHandle.current().pid(),
            DesktopProcessContextSource.CURRENT_PROCESS,
        )

        assertNull(original.processContext.validationError())
        assertNotNull(original.processContext.leaf)
        val refreshed = resolver.refresh(original)
        assertEquals(original.processContext.bootId, refreshed.bootId)
        assertEquals(original.processContext.leaf, refreshed.leaf)
    }

    @Test
    fun `refresh rejects a changed local process-instance token`() {
        val original = resolver.resolve(
            ProcessHandle.current().pid(),
            DesktopProcessContextSource.CURRENT_PROCESS,
        )
        val otherInstance = requireNotNull(original.leafInstance).copy(startToken = "reused-process")

        assertEquals(
            DesktopProcessContextSource.UNAVAILABLE,
            resolver.refresh(original.copy(leafInstance = otherInstance)).source,
        )
    }
}
