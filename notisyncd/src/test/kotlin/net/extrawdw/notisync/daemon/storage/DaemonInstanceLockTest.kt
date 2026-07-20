package net.extrawdw.notisync.daemon.storage

import net.extrawdw.notisync.desktop.SecureFileSystem

import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonInstanceLockTest : StorageTestSupport() {
    private val fileSystem = SecureFileSystem()

    @Test
    fun `lock is exclusive and owns pid file lifecycle`() {
        val layout = DaemonStorageLayout(temporaryDirectory.resolve("data"))
        val first = DaemonInstanceLock.acquire(
            layout = layout,
            fileSystem = fileSystem,
            pid = 1001,
            processStartTime = "42",
            now = Instant.parse("2026-07-18T00:00:00Z"),
            registerShutdownHook = false,
        )
        try {
            assertTrue(first.isHeld)
            assertTrue(Files.exists(layout.pidFile, LinkOption.NOFOLLOW_LINKS))
            assertEquals(1001L, DaemonInstanceLock.readPidRecord(layout.pidFile, fileSystem)?.pid)
            assertEquals(
                SecureFileSystem.FILE_PERMISSIONS,
                Files.getPosixFilePermissions(layout.lockFile, LinkOption.NOFOLLOW_LINKS),
            )
            assertEquals(
                SecureFileSystem.FILE_PERMISSIONS,
                Files.getPosixFilePermissions(layout.pidFile, LinkOption.NOFOLLOW_LINKS),
            )

            val error = assertThrows(DaemonAlreadyRunningException::class.java) {
                DaemonInstanceLock.acquire(
                    layout = layout,
                    fileSystem = fileSystem,
                    registerShutdownHook = false,
                )
            }
            assertEquals(1001L, error.record?.pid)
        } finally {
            first.close()
        }

        assertFalse(first.isHeld)
        assertFalse(Files.exists(layout.pidFile, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `stale pid record is replaced once no process holds lock`() {
        val layout = DaemonStorageLayout(temporaryDirectory.resolve("data")).prepare(fileSystem)
        fileSystem.atomicWrite(
            layout.pidFile,
            """{"pid":7,"processStartTime":"old","daemonStartedAt":"2020-01-01T00:00:00Z","instanceId":"stale"}"""
                .encodeToByteArray(),
        )

        val lock = DaemonInstanceLock.acquire(
            layout = layout,
            fileSystem = fileSystem,
            pid = 2002,
            registerShutdownHook = false,
        )
        try {
            assertEquals(2002L, DaemonInstanceLock.readPidRecord(layout.pidFile, fileSystem)?.pid)
            assertNotEquals("stale", lock.record.instanceId)
        } finally {
            lock.close()
        }
    }

    @Test
    fun `close does not remove an externally replaced pid record`() {
        val layout = DaemonStorageLayout(temporaryDirectory.resolve("data"))
        val lock = DaemonInstanceLock.acquire(
            layout = layout,
            fileSystem = fileSystem,
            registerShutdownHook = false,
        )
        fileSystem.atomicWrite(
            layout.pidFile,
            """{"pid":9,"daemonStartedAt":"2026-07-18T00:00:00Z","instanceId":"replacement"}"""
                .encodeToByteArray(),
        )

        lock.close()

        assertTrue(Files.exists(layout.pidFile, LinkOption.NOFOLLOW_LINKS))
        assertEquals("replacement", DaemonInstanceLock.readPidRecord(layout.pidFile, fileSystem)?.instanceId)
    }
}
