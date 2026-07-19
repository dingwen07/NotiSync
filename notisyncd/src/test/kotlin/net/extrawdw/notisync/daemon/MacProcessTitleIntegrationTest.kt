package net.extrawdw.notisync.daemon

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.TimeUnit
import net.extrawdw.notisync.cli.DaemonAdminClient
import net.extrawdw.notisync.daemon.storage.DaemonInstanceLock
import net.extrawdw.notisync.daemon.storage.DaemonPidRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MacProcessTitleIntegrationTest {
    @Test
    fun `generated JVM launcher detaches its daemon child`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("mac"))
        val installation = Path.of(requireNotNull(System.getProperty(INSTALLATION_PROPERTY)))
        val notisyncd = installation.resolve("libexec/notisyncd")
        val dataDirectory = Files.createTempDirectory(Path.of("/private/tmp"), "notisync-java-detach-")
        var daemonPid: Long? = null
        try {
            val started = command(notisyncd, dataDirectory, "start")
            assertEquals(started.output, 0, started.exitCode)
            val pidRecord = DaemonInstanceLock.readPidRecord(dataDirectory.resolve("notisyncd.pid"))
            assertNotNull("daemon did not write its PID record: ${started.output}", pidRecord)
            daemonPid = pidRecord!!.pid
            assertEquals(
                "JVM-launched daemon did not lead a detached session",
                daemonPid,
                posix.getsid(daemonPid.toInt()).toLong(),
            )

            val originalPid = daemonPid
            val restarted = command(notisyncd, dataDirectory, "restart")
            assertEquals(restarted.output, 0, restarted.exitCode)
            val restartedRecord = DaemonInstanceLock.readPidRecord(dataDirectory.resolve("notisyncd.pid"))
            assertNotNull("restarted daemon did not write its PID record: ${restarted.output}", restartedRecord)
            daemonPid = restartedRecord!!.pid
            assertTrue("restart reused the previous daemon process", daemonPid != originalPid)
            assertTrue("previous daemon remained alive after restart", awaitProcessExit(originalPid))
            assertEquals(
                "restarted daemon did not lead a detached session",
                daemonPid,
                posix.getsid(daemonPid.toInt()).toLong(),
            )

            val stopped = command(notisyncd, dataDirectory, "stop")
            assertEquals(stopped.output, 0, stopped.exitCode)
            assertTrue("JVM-launched daemon remained alive after stop", awaitProcessExit(daemonPid))
            daemonPid = null
        } finally {
            daemonPid?.let { pid ->
                runCatching { command(notisyncd, dataDirectory, "stop") }
                ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroy)
            }
            deleteTree(dataDirectory)
        }
    }

    @Test
    fun `foreground daemon remains attached to the caller session`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("mac"))
        val installation = Path.of(requireNotNull(System.getProperty(INSTALLATION_PROPERTY)))
        val notisyncd = installation.resolve("bin/notisyncd")
        val dataDirectory = Files.createTempDirectory(Path.of("/private/tmp"), "notisync-foreground-")
        var daemon: Process? = null
        try {
            val callerSession = posix.getsid(ProcessHandle.current().pid().toInt())
            daemon = process(notisyncd, dataDirectory, "foreground")
                .redirectErrorStream(true)
                .start()
            val pidRecord = awaitPidRecord(dataDirectory.resolve("notisyncd.pid"))
            assertNotNull("foreground daemon did not write its PID record", pidRecord)
            assertEquals(daemon.pid(), pidRecord!!.pid)
            assertEquals(
                "foreground daemon unexpectedly detached from its caller",
                callerSession.toLong(),
                posix.getsid(daemon.pid().toInt()).toLong(),
            )
            assertTrue("foreground daemon did not become ready", awaitDaemonReady(dataDirectory))

            val stopped = command(notisyncd, dataDirectory, "stop")
            assertEquals(stopped.output, 0, stopped.exitCode)
            assertTrue("foreground daemon remained alive after stop", daemon.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, daemon.exitValue())
            daemon = null
        } finally {
            daemon?.let { process ->
                if (process.isAlive) runCatching { command(notisyncd, dataDirectory, "stop") }
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            deleteTree(dataDirectory)
        }
    }

    @Test
    fun `installed launchers retain daemon and run names in macOS kernel process table`() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("mac"))
        val installation = Path.of(requireNotNull(System.getProperty(INSTALLATION_PROPERTY)))
        // Keep the UDS pathname well below Darwin's sockaddr_un limit.
        val temporaryRoot = Files.createTempDirectory(Path.of("/private/tmp"), "notisync-title-")
        val dataDirectory = Files.createDirectory(temporaryRoot.resolve("data"))
        val commandDirectory = Files.createDirectory(temporaryRoot.resolve("commands"))
        val installedNotisyncd = installation.resolve("bin/notisyncd")
        val installedNotisync = installation.resolve("bin/notisync")
        val installedNsrun = installation.resolve("bin/nsrun")
        assertTrue(Files.isExecutable(installedNotisyncd))
        assertTrue(Files.isExecutable(installedNotisync))
        assertTrue(Files.isExecutable(installedNsrun))
        // Match install-desktop.sh: commands on PATH are symlinks to physical, correctly named
        // launchers inside the distribution.
        val notisyncd = Files.createSymbolicLink(commandDirectory.resolve("notisyncd"), installedNotisyncd)
        val notisync = Files.createSymbolicLink(commandDirectory.resolve("notisync"), installedNotisync)
        val nsrun = Files.createSymbolicLink(commandDirectory.resolve("nsrun"), installedNsrun)
        var daemonPid: Long? = null
        var runProcess: Process? = null
        try {
            val unicodeArgument = "unknown-🚀"
            val rejected = command(notisyncd, dataDirectory, unicodeArgument)
            assertEquals(rejected.output, 1, rejected.exitCode)
            assertTrue(rejected.output, rejected.output.contains(unicodeArgument))

            val started = command(notisyncd, dataDirectory, "start")
            assertEquals(started.output, 0, started.exitCode)
            val pidRecord = DaemonInstanceLock.readPidRecord(dataDirectory.resolve("notisyncd.pid"))
            assertNotNull("daemon did not write its PID record: ${started.output}", pidRecord)
            daemonPid = pidRecord!!.pid
            assertEquals("notisyncd", awaitKernelName(daemonPid, "notisyncd"))
            assertEquals(
                "daemon did not lead a detached session",
                daemonPid,
                posix.getsid(daemonPid.toInt()).toLong(),
            )

            val originalPid = daemonPid
            val restarted = command(notisync, dataDirectory, "daemon", "restart")
            assertEquals(restarted.output, 0, restarted.exitCode)
            val restartedRecord = DaemonInstanceLock.readPidRecord(dataDirectory.resolve("notisyncd.pid"))
            assertNotNull("notisync daemon restart did not create a daemon: ${restarted.output}", restartedRecord)
            daemonPid = restartedRecord!!.pid
            assertTrue("notisync daemon restart reused the previous process", daemonPid != originalPid)
            assertTrue("previous daemon remained alive after notisync restart", awaitProcessExit(originalPid))
            assertEquals("notisyncd", awaitKernelName(daemonPid, "notisyncd"))

            runProcess = process(nsrun, dataDirectory, "--pty", "never", "--", "/bin/sleep", "2").start()
            assertEquals("nsrun", awaitKernelName(runProcess.pid(), "nsrun"))
            assertTrue("nsrun did not exit promptly", runProcess.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, runProcess.exitValue())

            val stopped = command(notisyncd, dataDirectory, "stop")
            assertEquals(stopped.output, 0, stopped.exitCode)
            assertTrue("daemon process remained alive after stop", awaitProcessExit(daemonPid))
            assertFalse(ProcessHandle.of(daemonPid).map(ProcessHandle::isAlive).orElse(false))
            daemonPid = null
        } finally {
            runProcess?.takeIf(Process::isAlive)?.let { process ->
                process.descendants().forEach(ProcessHandle::destroy)
                process.destroy()
                process.waitFor(2, TimeUnit.SECONDS)
                if (process.isAlive) process.destroyForcibly()
            }
            daemonPid?.let {
                runCatching { command(notisyncd, dataDirectory, "stop") }
                ProcessHandle.of(it).filter(ProcessHandle::isAlive).ifPresent { daemon ->
                    daemon.destroy()
                    if (!awaitProcessExit(it)) daemon.destroyForcibly()
                }
            }
            deleteTree(temporaryRoot)
        }
    }

    private fun awaitPidRecord(pidFile: Path): DaemonPidRecord? {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        var observed: DaemonPidRecord? = null
        while (System.nanoTime() < deadline) {
            observed = runCatching { DaemonInstanceLock.readPidRecord(pidFile) }.getOrNull()
            if (observed != null) return observed
            Thread.sleep(25)
        }
        return observed
    }

    private fun awaitDaemonReady(dataDirectory: Path): Boolean {
        val client = DaemonAdminClient(dataDirectory.resolve("S.notisyncd"))
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (System.nanoTime() < deadline) {
            if (runCatching { client.status() }.isSuccess) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun deleteTree(root: Path) {
        runCatching {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun command(executable: Path, dataDirectory: Path, vararg arguments: String): CommandResult {
        val process = process(executable, dataDirectory, *arguments)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            throw AssertionError("${executable.fileName} timed out")
        }
        return CommandResult(process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private fun process(executable: Path, dataDirectory: Path, vararg arguments: String): ProcessBuilder =
        ProcessBuilder(listOf(executable.toString()) + arguments).apply {
            environment()["JAVA_HOME"] = System.getProperty("java.home")
            environment()["NOTISYNC_DATA_DIR"] = dataDirectory.toString()
            environment()["JAVA_OPTS"] = "-Dnotisync.dataDir=$dataDirectory"
            redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
        }

    private fun awaitKernelName(pid: Long, expected: String): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        var observed = ""
        while (System.nanoTime() < deadline) {
            observed = ProcessBuilder("/bin/ps", "-p", pid.toString(), "-o", "ucomm=")
                .redirectErrorStream(true)
                .start()
                .let { process ->
                    process.waitFor(2, TimeUnit.SECONDS)
                    process.inputStream.bufferedReader().readText().trim()
                }
            if (observed == expected) return observed
            Thread.sleep(25)
        }
        return observed
    }

    private fun awaitProcessExit(pid: Long): Boolean {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false).not()) return true
            Thread.sleep(25)
        }
        return false
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private interface PosixLibC : Library {
        fun getsid(pid: Int): Int
    }

    private companion object {
        const val INSTALLATION_PROPERTY = "notisync.test.installation"
        private val posix: PosixLibC by lazy {
            Native.load(Platform.C_LIBRARY_NAME, PosixLibC::class.java)
        }
    }
}
