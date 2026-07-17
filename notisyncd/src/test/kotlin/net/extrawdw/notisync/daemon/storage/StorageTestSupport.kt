package net.extrawdw.notisync.daemon.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.After
import org.junit.Before

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
}
