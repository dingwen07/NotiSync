package net.extrawdw.notisync.desktop

import com.sun.jna.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopProcessExecutableResolverTest {
    @Test
    fun `macOS uses narrow process-path lookup without querying command arguments`() {
        var portableQueries = 0
        val resolver = DesktopProcessExecutableResolver(
            osName = "Mac OS X",
            portableCommand = {
                portableQueries++
                "/should/not/be/queried"
            },
            macProcessPath = { "/usr/bin/login" },
        )

        assertEquals("/usr/bin/login", resolver.resolve(42))
        assertEquals(0, portableQueries)
    }

    @Test
    fun `missing executable metadata remains a valid best-effort result`() {
        val resolver = DesktopProcessExecutableResolver(
            osName = "Mac OS X",
            macProcessPath = { null },
        )

        assertNull(resolver.resolve(42))
    }

    @Test
    fun `macOS native process-path lookup resolves an executable`() {
        assumeTrue(Platform.isMac())

        assertNotNull(DesktopProcessExecutableResolver().resolve(ProcessHandle.current().pid()))
    }
}
