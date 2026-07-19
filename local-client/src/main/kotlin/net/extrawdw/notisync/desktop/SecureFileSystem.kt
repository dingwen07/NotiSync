package net.extrawdw.notisync.desktop

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.ArrayDeque

/**
 * Filesystem boundary for daemon secrets and durable state.
 *
 * The daemon deliberately does not use [Files.createDirectories]: that operation follows symbolic
 * links in intermediate components. Every existing component is inspected with `NOFOLLOW_LINKS`,
 * and every newly-created component is created one at a time with private permissions. This cannot
 * provide the full `openat(2)` race guarantees of a native implementation, but final files are also
 * opened with `NOFOLLOW_LINKS` and atomic replacement never writes through an existing link.
 */
class SecureFileSystem(
    private val expectedOwnerName: String? = System.getProperty("user.name")?.takeIf(String::isNotBlank),
) {
    fun ensurePrivateDirectory(path: Path): Path {
        val absolute = normalized(path)
        rejectSymbolicLinkComponents(absolute)

        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            createMissingDirectories(absolute)
        }

        require(Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            "$absolute is not a directory"
        }
        verifyOwner(absolute)
        setAndVerifyPermissions(absolute, DIRECTORY_PERMISSIONS)
        rejectSymbolicLinkComponents(absolute)
        return absolute
    }

    fun ensurePrivateFile(path: Path): Path {
        val absolute = normalized(path)
        val parent = requireNotNull(absolute.parent) { "$absolute has no parent" }
        ensurePrivateDirectory(parent)
        rejectSymbolicLinkComponents(absolute)

        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(absolute, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
            } catch (_: FileAlreadyExistsException) {
                // A concurrent creator is acceptable only after all checks below pass.
            }
        }
        validatePrivateFile(absolute)
        return absolute
    }

    fun validatePrivateFile(path: Path) {
        val absolute = normalized(path)
        rejectSymbolicLinkComponents(absolute)
        require(Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) { "$absolute does not exist" }
        require(Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            "$absolute is not a regular file"
        }
        verifyOwner(absolute)
        setAndVerifyPermissions(absolute, FILE_PERMISSIONS)
    }

    /** Validate an existing socket or other non-directory node and force mode 0600. */
    fun validatePrivateNode(path: Path) {
        val absolute = normalized(path)
        rejectSymbolicLinkComponents(absolute)
        require(Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) { "$absolute does not exist" }
        require(!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            "$absolute is unexpectedly a directory"
        }
        // macOS's owner/PosixFileAttributeView providers reject Unix-domain sockets even though
        // the lower-level unix attributes and chmod operation support them. The enclosing 0700
        // directory is already name-owner verified; require the node's numeric uid to match it.
        val parent = requireNotNull(absolute.parent) { "$absolute has no parent" }
        verifyOwner(parent)
        val nodeUid = (Files.getAttribute(absolute, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
        val parentUid = (Files.getAttribute(parent, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
        require(nodeUid == parentUid) { "$absolute is owned by uid $nodeUid, expected $parentUid" }
        Files.setPosixFilePermissions(absolute, FILE_PERMISSIONS)
        val mode = (Files.getAttribute(absolute, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Number).toInt()
        require(mode and POSIX_MODE_MASK == FILE_MODE) {
            "$absolute has mode ${(mode and POSIX_MODE_MASK).toString(8)}, expected 600"
        }
    }

    fun readPrivateBytes(path: Path, maximumBytes: Long = DEFAULT_MAXIMUM_READ_BYTES): ByteArray {
        require(maximumBytes >= 0) { "maximumBytes must not be negative" }
        val absolute = normalized(path)
        validatePrivateFile(absolute)
        val size = Files.size(absolute)
        require(size <= maximumBytes) { "$absolute is $size bytes, limit is $maximumBytes" }
        require(size <= Int.MAX_VALUE) { "$absolute is too large to read into memory" }
        return FileChannel.open(absolute, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val result = ByteArray(size.toInt())
            val buffer = ByteBuffer.wrap(result)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw IOException("unexpected end of file reading $absolute")
            }
            result
        }
    }

    /**
     * Durably replaces [path] from a mode-0600 sibling temporary file.
     *
     * Lack of atomic-move support is a hard error. Falling back to a non-atomic replacement would
     * permit a crash to destroy the only copy of trust, outbox, or deduplication state.
     */
    fun atomicWrite(path: Path, bytes: ByteArray) {
        val absolute = normalized(path)
        val parent = ensurePrivateDirectory(requireNotNull(absolute.parent) { "$absolute has no parent" })
        rejectSymbolicLinkComponents(absolute)
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) validatePrivateFile(absolute)

        val temporary = Files.createTempFile(
            parent,
            ".${absolute.fileName}.",
            ".tmp",
            PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS),
        )
        try {
            verifyOwner(temporary)
            setAndVerifyPermissions(temporary, FILE_PERMISSIONS)
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }

            // Recheck immediately before replacement. A link is never accepted as a managed target.
            rejectSymbolicLinkComponents(absolute)
            try {
                Files.move(
                    temporary,
                    absolute,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: AtomicMoveNotSupportedException) {
                throw IOException("atomic replacement is not supported for $absolute", error)
            }
            validatePrivateFile(absolute)
            forceDirectory(parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun deletePrivateFileIfExists(path: Path): Boolean {
        val absolute = normalized(path)
        rejectSymbolicLinkComponents(absolute)
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return false
        validatePrivateFile(absolute)
        val deleted = Files.deleteIfExists(absolute)
        if (deleted) absolute.parent?.let(::forceDirectory)
        return deleted
    }

    /** Reject a symbolic link at [path] or at any existing ancestor. */
    fun rejectSymbolicLinkComponents(path: Path) {
        val absolute = normalized(path)
        var current = absolute.root ?: error("$absolute is not absolute")
        require(!Files.isSymbolicLink(current)) { "refusing symbolic link: $current" }
        for (component in absolute) {
            current = current.resolve(component)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current)) { "refusing symbolic link: $current" }
            }
        }
    }

    internal fun normalized(path: Path): Path = path.toAbsolutePath().normalize()

    internal fun verifyOwner(path: Path) {
        val expected = expectedOwnerName ?: return
        val owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)
        require(ownerMatches(owner, expected)) {
            "$path is owned by ${owner.name}, expected $expected"
        }
    }

    internal fun setAndVerifyPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        val view = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: throw IOException("$path is not on a POSIX filesystem")
        view.setPermissions(permissions)
        val actual = view.readAttributes().permissions()
        require(actual == permissions) {
            "$path has permissions ${PosixFilePermissions.toString(actual)}, expected " +
                PosixFilePermissions.toString(permissions)
        }
    }

    private fun createMissingDirectories(path: Path) {
        val missing = ArrayDeque<Path>()
        var cursor: Path? = path
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(cursor)
            cursor = cursor.parent
        }
        requireNotNull(cursor) { "cannot find an existing ancestor for $path" }
        rejectSymbolicLinkComponents(cursor)

        for (directory in missing) {
            try {
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
            } catch (_: FileAlreadyExistsException) {
                // A racing creator is checked just like a pre-existing component.
            }
            rejectSymbolicLinkComponents(directory)
            require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                "$directory is not a directory"
            }
            verifyOwner(directory)
            setAndVerifyPermissions(directory, DIRECTORY_PERMISSIONS)
        }
    }

    private fun forceDirectory(path: Path) {
        // Directory fsync is supported on the target Linux/macOS filesystems. Some custom providers
        // reject opening a directory, so the data file's force(true) remains the durability floor.
        runCatching {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        }
    }

    private fun ownerMatches(owner: UserPrincipal, expected: String): Boolean {
        val actual = owner.name
        if (actual == expected) return true
        // File providers may qualify the principal as DOMAIN\\name or domain/name.
        return actual.substringAfterLast('\\').substringAfterLast('/') == expected
    }

    companion object {
        val DIRECTORY_PERMISSIONS: Set<PosixFilePermission> =
            PosixFilePermissions.fromString("rwx------")
        val FILE_PERMISSIONS: Set<PosixFilePermission> =
            PosixFilePermissions.fromString("rw-------")
        const val DEFAULT_MAXIMUM_READ_BYTES: Long = 64L * 1024 * 1024
        private const val POSIX_MODE_MASK = 0x1FF
        private const val FILE_MODE = 0x180
    }
}
