package net.extrawdw.notisync.daemon.storage

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.util.Comparator
import net.extrawdw.notisync.desktop.SecureFileSystem
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

abstract class StorageTestSupport {
    protected lateinit var temporaryDirectory: Path

    @Before
    fun createTemporaryDirectory() {
        // Resolve macOS' /var -> /private/var alias before constructing managed paths: the storage
        // boundary intentionally rejects every symbolic-link ancestor.
        val systemTemporaryDirectory = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
        temporaryDirectory = Files.createTempDirectory(systemTemporaryDirectory, "notisync-storage-test-")
    }

    @After
    fun deleteTemporaryDirectory() {
        if (!::temporaryDirectory.isInitialized || !Files.exists(temporaryDirectory)) return
        Files.walk(temporaryDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    protected fun assertPrivateFile(path: Path) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (posix != null) {
            assertEquals(SecureFileSystem.FILE_PERMISSIONS, posix.readAttributes().permissions())
        } else {
            assertTrue(
                Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null,
            )
        }
    }
}
