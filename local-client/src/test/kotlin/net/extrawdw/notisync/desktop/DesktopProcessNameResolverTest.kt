package net.extrawdw.notisync.desktop

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopProcessNameResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `Linux reads the process name independently of the executable path`() {
        val procRoot = temporaryFolder.newFolder("proc").toPath()
        val processDirectory = Files.createDirectory(procRoot.resolve("42"))
        Files.writeString(processDirectory.resolve("comm"), "system-service\n")

        assertEquals(
            "system-service",
            DesktopProcessNameResolver(osName = "Linux", procRoot = procRoot).resolve(42),
        )
    }

    @Test
    fun `missing or invalid process names remain unavailable`() {
        val procRoot = temporaryFolder.newFolder("proc").toPath()
        val processDirectory = Files.createDirectory(procRoot.resolve("42"))
        Files.writeString(processDirectory.resolve("comm"), "invalid\nname\n")
        val resolver = DesktopProcessNameResolver(osName = "Linux", procRoot = procRoot)

        assertNull(resolver.resolve(42))
        assertNull(resolver.resolve(43))
        assertNull(resolver.resolve(0))
    }

    @Test
    fun `non-Linux platforms do not inspect procfs`() {
        val procRoot = temporaryFolder.newFolder("proc").toPath()
        val processDirectory = Files.createDirectory(procRoot.resolve("42"))
        Files.writeString(processDirectory.resolve("comm"), "unexpected\n")

        assertNull(DesktopProcessNameResolver(osName = "Mac OS X", procRoot = procRoot).resolve(42))
    }
}
