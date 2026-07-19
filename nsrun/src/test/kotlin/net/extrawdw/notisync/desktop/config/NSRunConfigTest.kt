package net.extrawdw.notisync.desktop.config

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NSRunConfigTest {
    @Test
    fun `missing configuration uses safe defaults without writing a file`() {
        val path = Files.createTempDirectory("notisync-default-test").toRealPath().resolve("nsrun.conf")
        val config = NSRunConfigStore(path).load()
        assertEquals(30L, config.updateIntervalSeconds)
        assertEquals(300L, config.stuckAfterSeconds)
        assertFalse(Files.exists(path))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `symbolic link configuration is rejected`() {
        val root = Files.createTempDirectory("notisync-symlink-test").toRealPath()
        val target = root.resolve("target.json")
        Files.writeString(target, "{}")
        val link = root.resolve("nsrun.conf")
        Files.createSymbolicLink(link, target)
        NSRunConfigStore(link).load()
    }

    @Test
    fun `off stuck setting remains disabled`() {
        val root = Files.createTempDirectory("notisync-conf-off").toRealPath()
        val path = root.resolve("nsrun.conf")
        Files.writeString(path, "stuck-after-seconds off\n")
        assertEquals(null, NSRunConfigStore(path).load().stuckAfterSeconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown options are rejected`() {
        val root = Files.createTempDirectory("notisync-conf-unknown").toRealPath()
        val path = root.resolve("nsrun.conf")
        Files.writeString(path, "mystery-option yes\n")
        NSRunConfigStore(path).load()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate options are rejected`() {
        val root = Files.createTempDirectory("notisync-conf-duplicate").toRealPath()
        val path = root.resolve("nsrun.conf")
        Files.writeString(path, "pty auto\npty never\n")
        NSRunConfigStore(path).load()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `symbolic link ancestor is rejected`() {
        val root = Files.createTempDirectory("notisync-conf-ancestor").toRealPath()
        val real = Files.createDirectory(root.resolve("real"))
        val link = root.resolve("linked")
        Files.createSymbolicLink(link, real)
        NSRunConfigStore(link.resolve("nsrun.conf")).load()
    }

    @Test
    fun `runtime recovery archives malformed run config while strict load still rejects it`() {
        val root = Files.createTempDirectory("nsrun-conf-recovery").toRealPath()
        val path = root.resolve("nsrun.conf")
        Files.writeString(path, "unknown-option yes\n")
        val store = NSRunConfigStore(path)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { store.load() }

        val warnings = mutableListOf<String>()
        val recovered = store.loadRecovering(warnings::add)
        assertEquals(NSRunConfig(), recovered)
        assertEquals(NSRunConfig(), store.load())
        assertTrue(warnings.single().contains("using safe defaults"))
        assertTrue(Files.list(root).use { files ->
            files.anyMatch { it.fileName.toString().startsWith("nsrun.conf.corrupt-") }
        })
    }
}
