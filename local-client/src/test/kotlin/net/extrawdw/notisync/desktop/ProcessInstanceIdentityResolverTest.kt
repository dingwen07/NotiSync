package net.extrawdw.notisync.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProcessInstanceIdentityResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `Linux identity ignores wall-clock boot-time corrections`() {
        val proc = temporaryFolder.newFolder("proc").toPath()
        writeBootId(proc, "01234567-89ab-cdef-0123-456789abcdef")
        writeProcessStat(proc, 42, "command with ) delimiter", "987654")
        Files.writeString(proc.resolve("stat"), "btime 1000\n")
        val resolver = ProcessInstanceIdentityResolver(osName = "Linux", procRoot = proc)

        val before = resolver.resolve(42)
        Files.writeString(proc.resolve("stat"), "btime 2054\n")
        val after = resolver.resolve(42)

        assertEquals(ProcessInstanceIdentity("01234567-89ab-cdef-0123-456789abcdef", "987654"), before)
        assertEquals(before, after)
    }

    @Test
    fun `Linux identity changes across process instances and boots`() {
        val proc = temporaryFolder.newFolder("proc").toPath()
        writeBootId(proc, "01234567-89ab-cdef-0123-456789abcdef")
        writeProcessStat(proc, 42, "ssh", "100")
        val resolver = ProcessInstanceIdentityResolver(osName = "Linux", procRoot = proc)
        val first = resolver.resolve(42)

        writeProcessStat(proc, 42, "ssh", "101")
        val reusedPid = resolver.resolve(42)
        writeBootId(proc, "fedcba98-7654-3210-fedc-ba9876543210")
        val rebooted = resolver.resolve(42)

        assertNotEquals(first, reusedPid)
        assertNotEquals(reusedPid, rebooted)
    }

    @Test
    fun `Linux identity fails closed when required proc fields are unavailable`() {
        val proc = temporaryFolder.newFolder("proc").toPath()
        writeBootId(proc, "01234567-89ab-cdef-0123-456789abcdef")
        Files.createDirectories(proc.resolve("42"))
        Files.writeString(proc.resolve("42/stat"), "malformed")

        assertNull(ProcessInstanceIdentityResolver(osName = "Linux", procRoot = proc).resolve(42))
    }

    @Test
    fun `portable identity uses the current process creation instant without a boot ID`() {
        val identity = ProcessInstanceIdentityResolver(osName = "Mac OS X")
            .resolve(ProcessHandle.current().pid())

        assertNotNull(identity)
        assertNull(identity?.bootId)
        assertNotNull(identity?.startToken?.let(java.time.Instant::parse))
    }

    private fun writeBootId(proc: Path, bootId: String) {
        val path = proc.resolve("sys/kernel/random/boot_id")
        Files.createDirectories(path.parent)
        Files.writeString(path, "$bootId\n")
    }

    private fun writeProcessStat(proc: Path, pid: Long, command: String, startTicks: String) {
        val fields = MutableList(20) { "0" }
        fields[0] = "S"
        fields[19] = startTicks
        val directory = proc.resolve(pid.toString())
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("stat"), "$pid ($command) ${fields.joinToString(" ")}\n")
    }
}
