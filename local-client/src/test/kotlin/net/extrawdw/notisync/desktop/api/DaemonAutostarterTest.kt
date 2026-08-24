package net.extrawdw.notisync.desktop.api

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DaemonAutostarterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `Windows selects batch launcher instead of adjacent POSIX launcher`() {
        val bin = temporaryFolder.newFolder("bin").toPath()
        launcher(bin, "notisyncd")
        val batch = launcher(bin, "notisyncd.bat")

        assertEquals(batch, findLauncher(bin, "Windows 11"))
    }

    @Test
    fun `Windows selects cmd shim when no native or batch launcher exists`() {
        val bin = temporaryFolder.newFolder("bin").toPath()
        launcher(bin, "notisyncd")
        val command = launcher(bin, "notisyncd.cmd")

        assertEquals(command, findLauncher(bin, "Windows 11"))
    }

    @Test
    fun `Windows ignores POSIX-only distribution`() {
        val bin = temporaryFolder.newFolder("bin").toPath()
        launcher(bin, "notisyncd")

        assertNull(findLauncher(bin, "Windows 11"))
    }

    @Test
    fun `Linux and macOS retain extensionless launcher`() {
        val bin = temporaryFolder.newFolder("bin").toPath()
        val posix = launcher(bin, "notisyncd")
        launcher(bin, "notisyncd.bat")
        launcher(bin, "notisyncd.cmd")

        assertEquals(listOf("notisyncd"), launcherNames("Linux"))
        assertEquals(listOf("notisyncd"), launcherNames("Mac OS X"))
        assertEquals(posix, findLauncher(bin, "Linux"))
        assertEquals(posix, findLauncher(bin, "Mac OS X"))
    }

    private fun launcher(directory: Path, name: String): Path = Files.createFile(directory.resolve(name)).also {
        check(it.toFile().setExecutable(true)) { "could not mark test launcher executable: $it" }
    }
}
