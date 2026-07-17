package net.extrawdw.notisync.daemon.storage

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureFileSystemTest : StorageTestSupport() {
    private val fileSystem = SecureFileSystem()

    @Test
    fun `layout creates only private daemon directories`() {
        val layout = DaemonStorageLayout(temporaryDirectory.resolve("home/.notisync"))

        layout.prepare(fileSystem)

        listOf(
            layout.dataDirectory,
            layout.privateKeysDirectory,
            layout.stateDirectory,
            layout.runsDirectory,
        ).forEach { directory ->
            assertTrue(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            assertEquals(
                SecureFileSystem.DIRECTORY_PERMISSIONS,
                Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS),
            )
        }
        assertFalse(Files.exists(layout.pidFile, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `existing overly broad modes are repaired`() {
        val directory = temporaryDirectory.resolve("state")
        Files.createDirectory(directory)
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"))
        val file = directory.resolve("state.json")
        Files.writeString(file, "secret")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))

        fileSystem.ensurePrivateDirectory(directory)
        fileSystem.validatePrivateFile(file)

        assertEquals(
            SecureFileSystem.DIRECTORY_PERMISSIONS,
            Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS),
        )
        assertEquals(
            SecureFileSystem.FILE_PERMISSIONS,
            Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS),
        )
    }

    @Test
    fun `unix socket owner and mode validation works on desktop providers`() {
        // macOS caps sockaddr_un paths at 104 bytes; the JUnit temporary root can exceed that.
        val shortTemporaryRoot = Path.of("/private/tmp").takeIf {
            Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it)
        } ?: Path.of("/tmp")
        val directory = fileSystem.ensurePrivateDirectory(Files.createTempDirectory(shortTemporaryRoot, "nsfs-"))
        val socket = directory.resolve("S")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socket))

                fileSystem.validatePrivateNode(socket)

                assertEquals(
                    SecureFileSystem.FILE_PERMISSIONS,
                    Files.getPosixFilePermissions(socket, LinkOption.NOFOLLOW_LINKS),
                )
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
        assertEquals(
            SecureFileSystem.FILE_PERMISSIONS,
            Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS),
        )
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
    fun `run ids cannot escape the private runs directory`() {
        val layout = DaemonStorageLayout(temporaryDirectory.resolve("data")).prepare(fileSystem)

        assertThrows(IllegalArgumentException::class.java) {
            layout.runDirectory("../elsewhere", fileSystem)
        }
        val run = layout.runDirectory("1234-abc", fileSystem)
        assertEquals(layout.runsDirectory.resolve("1234-abc").toAbsolutePath(), run)
    }
}
