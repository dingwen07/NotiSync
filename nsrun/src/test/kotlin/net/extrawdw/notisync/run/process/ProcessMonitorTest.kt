package net.extrawdw.notisync.run.process

import java.nio.file.Files
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import net.extrawdw.notisync.run.output.PromptKind

class ProcessMonitorTest {
    @Test
    fun `linux stat parser uses starttime and process groups`() {
        val proc = Files.createTempDirectory("proc-fixture")
        val pid = 123L
        val directory = Files.createDirectory(proc.resolve(pid.toString()))
        Files.writeString(
            directory.resolve("stat"),
            "123 (command with spaces) S 1 123 123 0 123 0 0 0 0 0 10 4 0 0 20 0 1 0 999 0 0",
        )
        Files.writeString(directory.resolve("wchan"), "n_tty_read")
        Files.writeString(directory.resolve("syscall"), "0 0x0 0x7fff 0x100 0 0 0 0 0")
        val snapshot = LinuxProcessInspector(proc).snapshot(pid)
        assertNotNull(snapshot)
        assertEquals("999", snapshot?.startIdentity)
        assertEquals(123L, snapshot?.processGroupId)
        assertEquals(123L, snapshot?.foregroundProcessGroupId)
        assertEquals(14L, snapshot?.cpuTicks)
        assertTrue(snapshot?.waitingForStdin == true)
    }

    @Test
    fun `terminal wait requires three samples and emits one resume edge`() {
        var snapshot = ProcessSnapshot(2, "start", 'S', 2, 2, 1, "n_tty_read")
        val monitor = ProcessMonitor(ProcessInspector { snapshot }, 2, Duration.ofMinutes(5), { 0 })
        assertEquals(null, monitor.sample(1, PromptKind.TEXT))
        assertEquals(null, monitor.sample(2, PromptKind.TEXT))
        assertTrue(requireNotNull(monitor.sample(3, PromptKind.TEXT)).blocked)
        snapshot = snapshot.copy(waitChannel = "poll_schedule_timeout", cpuTicks = 2)
        assertFalse(requireNotNull(monitor.sample(4)).blocked)
        assertEquals(null, monitor.sample(5))
    }

    @Test
    fun `foreground descendant terminal wait is detected`() {
        val root = ProcessSnapshot(10, "root", 'S', 10, 20, 2, "wait")
        val editor = ProcessSnapshot(11, "editor", 'S', 20, 20, 3, "n_tty_read")
        val inspector = object : ProcessInspector {
            override fun snapshot(pid: Long): ProcessSnapshot = root
            override fun foregroundProcessGroup(pid: Long): List<ProcessSnapshot> = listOf(editor)
        }
        val monitor = ProcessMonitor(inspector, 10, Duration.ofMinutes(5), { 0 })
        monitor.sample(1)
        monitor.sample(2)
        assertEquals(BlockedReason.TERMINAL_INPUT, monitor.sample(3)?.reason)
    }

    @Test
    fun `stdin read syscall detects terminal input even with generic wait channel`() {
        val proc = Files.createTempDirectory("proc-syscall-fixture")
        val pid = 321L
        val directory = Files.createDirectory(proc.resolve(pid.toString()))
        Files.writeString(
            directory.resolve("stat"),
            "321 (reader) S 1 321 321 0 321 0 0 0 0 0 0 0 0 0 20 0 1 0 1001 0 0",
        )
        Files.writeString(directory.resolve("wchan"), "wait_woken")
        // arm64 __NR_read with fd 0; parsing also accepts x86_64/i386 read/readv numbers.
        Files.writeString(directory.resolve("syscall"), "63 0 4096 128 0 0 0 0 0")
        val monitor = ProcessMonitor(LinuxProcessInspector(proc), pid, Duration.ofMinutes(5), { 0 })

        assertEquals(null, monitor.sample(1))
        assertEquals(null, monitor.sample(2))
        assertEquals(BlockedReason.TERMINAL_INPUT, monitor.sample(3)?.reason)
    }
}
