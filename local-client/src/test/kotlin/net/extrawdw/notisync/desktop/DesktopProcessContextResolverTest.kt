package net.extrawdw.notisync.desktop

import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopProcessContextResolverTest {
    @Test
    fun currentLineageCarriesOwnersAndRetainsPidWhenMetadataCannotBeRepresented() {
        val resolver = DesktopProcessContextResolver(
            processExecutables = DesktopProcessExecutableResolver(osName = "Test", portableCommand = { "/" + "x".repeat(2000) }),
            processNames = DesktopProcessNameResolver(osName = "Test"),
            processOwners = DesktopProcessOwnerResolver(osName = "Windows", windowsOwner = {
                DesktopProcessOwner(username = "bad\nname", sid = "S-1-5-18")
            }),
        )
        val context = resolver.current()
        assertEquals(DesktopProcessContextSource.CURRENT_PROCESS, context.source)
        assertEquals(ProcessHandle.current().pid(), context.leaf?.pid)
        assertNull(context.validationError())
        assertNull(context.leaf?.executablePath)
        assertNull(context.leaf?.username)
        assertEquals("S-1-5-18", context.leaf?.sid)
        assertNotNull(context.leaf)
    }
}
