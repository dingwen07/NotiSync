package net.extrawdw.notisync.daemon.storage

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import net.extrawdw.notisync.desktop.SecureFileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureFileSystemTest : StorageTestSupport() {
    private val fileSystem = SecureFileSystem()

    @Test
    fun `only the daemon log uses the separate log root`() {
        val data = temporaryDirectory.resolve("home/.notisync")
        val logs = temporaryDirectory.resolve("home/logs/NotiSync")
        val layout = DaemonStorageLayout(data, logs)

        assertEquals(logs.resolve("notisyncd.log"), layout.daemonLogFile)
        listOf(
            layout.socketFile,
            layout.lockFile,
            layout.pidFile,
            layout.daemonConfigFile,
            layout.privateKeysDirectory,
            layout.stateDirectory,
        ).forEach { path -> assertTrue(path.startsWith(data)) }
    }

    @Test
    fun `layout creates only private daemon directories`() {
        val layout = DaemonStorageLayout(
            temporaryDirectory.resolve("home/.notisync"),
            temporaryDirectory.resolve("home/logs/NotiSync"),
        )

        layout.prepare(fileSystem)

        listOf(
            layout.dataDirectory,
            layout.logDirectory,
            layout.privateKeysDirectory,
            layout.stateDirectory,
        ).forEach { directory ->
            assertTrue(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            assertPrivateSecurity(directory, SecureFileSystem.DIRECTORY_PERMISSIONS)
        }
        assertFalse(Files.exists(layout.pidFile, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `existing overly broad modes are repaired`() {
        val directory = temporaryDirectory.resolve("state")
        Files.createDirectory(directory)
        if (Files.getFileAttributeView(directory, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        val file = directory.resolve("state.json")
        Files.writeString(file, "secret")
        if (Files.getFileAttributeView(file, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        }

        fileSystem.ensurePrivateDirectory(directory)
        fileSystem.validatePrivateFile(file)

        assertPrivateSecurity(directory, SecureFileSystem.DIRECTORY_PERMISSIONS)
        assertPrivateSecurity(file, SecureFileSystem.FILE_PERMISSIONS)
    }

    @Test
    fun `unix socket owner and mode validation works on desktop providers`() {
        // macOS caps sockaddr_un paths at 104 bytes; the JUnit temporary root can exceed that.
        val shortTemporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
        val directory = fileSystem.ensurePrivateDirectory(Files.createTempDirectory(shortTemporaryRoot, "nsfs-"))
        val socket = directory.resolve("S")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socket))

                fileSystem.validatePrivateNode(socket)
                assertTrue(fileSystem.isSocketNode(socket))

                assertPrivateSecurity(socket, SecureFileSystem.FILE_PERMISSIONS)
            }
        } finally {
            Files.deleteIfExists(socket)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `direct symbolic link is rejected without changing its target`() {
        val target = temporaryDirectory.resolve("target")
        Files.createDirectory(target)
        val link = temporaryDirectory.resolve("link")
        Files.createSymbolicLink(link, target)

        val error = assertThrows(IllegalArgumentException::class.java) {
            fileSystem.ensurePrivateDirectory(link)
        }

        assertTrue(error.message!!.contains("symbolic link"))
        assertTrue(Files.isDirectory(target))
    }

    @Test
    fun `symbolic link in an ancestor is rejected`() {
        val target = temporaryDirectory.resolve("real-home")
        Files.createDirectory(target)
        val link = temporaryDirectory.resolve("linked-home")
        Files.createSymbolicLink(link, target)

        val error = assertThrows(IllegalArgumentException::class.java) {
            DaemonStorageLayout(link.resolve(".notisync")).prepare(fileSystem)
        }

        assertTrue(error.message!!.contains(link.toString()))
        assertFalse(Files.exists(target.resolve(".notisync"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `owner mismatch is rejected`() {
        val path = temporaryDirectory.resolve("owned-by-test-user")
        Files.createDirectory(path)

        val error = assertThrows(IllegalArgumentException::class.java) {
            SecureFileSystem(expectedOwnerName = "not-the-test-user").ensurePrivateDirectory(path)
        }

        assertTrue(error.message!!.contains("owned by"))
    }

    @Test
    fun `atomic write replaces contents and leaves no temporary file`() {
        val directory = fileSystem.ensurePrivateDirectory(temporaryDirectory.resolve("private"))
        val path = directory.resolve("state.json")
        fileSystem.atomicWrite(path, "first".encodeToByteArray())
        fileSystem.atomicWrite(path, "second".encodeToByteArray())

        assertArrayEquals("second".encodeToByteArray(), fileSystem.readPrivateBytes(path))
        assertPrivateSecurity(path, SecureFileSystem.FILE_PERMISSIONS)
        Files.list(directory).use { files ->
            assertEquals(listOf(path), files.toList())
        }
    }

    @Test
    fun `atomic write refuses a symbolic-link target`() {
        val directory = fileSystem.ensurePrivateDirectory(temporaryDirectory.resolve("private"))
        val outside = temporaryDirectory.resolve("outside")
        Files.writeString(outside, "unchanged")
        val path = directory.resolve("state.json")
        Files.createSymbolicLink(path, outside)

        assertThrows(IllegalArgumentException::class.java) {
            fileSystem.atomicWrite(path, "replacement".encodeToByteArray())
        }
        assertEquals("unchanged", Files.readString(outside))
    }

    @Test
    fun `private directory validation accepts an owner-only directory without mutation`() {
        val directory = fileSystem.ensurePrivateDirectory(temporaryDirectory.resolve("private-existing"))

        assertEquals(directory.toAbsolutePath().normalize(), fileSystem.validatePrivateDirectory(directory))
        assertPrivateSecurity(directory, SecureFileSystem.DIRECTORY_PERMISSIONS)
    }

    private fun assertPrivateSecurity(path: Path, expected: Set<java.nio.file.attribute.PosixFilePermission>) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (posix != null) {
            assertEquals(expected, posix.readAttributes().permissions())
        } else {
            assertTrue(
                Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null,
            )
        }
    }

}
