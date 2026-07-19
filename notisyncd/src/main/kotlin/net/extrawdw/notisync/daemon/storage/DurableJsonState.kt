package net.extrawdw.notisync.daemon.storage

import net.extrawdw.notisync.desktop.SecureFileSystem

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Small durable transactional state store for daemon queues and indexes.
 *
 * A single immutable value can contain the application registry, generic outbox, idempotency state,
 * and relay deduplication indexes; [update] serializes read-modify-write operations and commits by
 * atomic rename. The store intentionally does not silently replace corrupt state with defaults,
 * since doing so could replay or discard an accepted send.
 */
class DurableJsonState<T>(
    val path: Path,
    private val serializer: KSerializer<T>,
    private val defaultValue: () -> T,
    private val fileSystem: SecureFileSystem = SecureFileSystem(),
    private val json: Json = DEFAULT_JSON,
    private val maximumBytes: Long = SecureFileSystem.DEFAULT_MAXIMUM_READ_BYTES,
) {
    private val lock = ReentrantLock()

    fun exists(): Boolean = lock.withLock {
        fileSystem.rejectSymbolicLinkComponents(path)
        Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    }

    fun load(): T = lock.withLock { loadLocked() }

    fun initialize(): T = lock.withLock {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@withLock loadLocked()
        defaultValue().also(::saveLocked)
    }

    fun save(value: T) = lock.withLock { saveLocked(value) }

    /** Atomically commit the result and return the committed value. */
    fun update(transform: (T) -> T): T = lock.withLock {
        transform(loadLocked()).also(::saveLocked)
    }

    fun delete(): Boolean = lock.withLock { fileSystem.deletePrivateFileIfExists(path) }

    private fun loadLocked(): T {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return defaultValue()
        val encoded = fileSystem.readPrivateBytes(path, maximumBytes).decodeToString()
        return try {
            json.decodeFromString(serializer, encoded)
        } catch (error: SerializationException) {
            throw IllegalStateException("invalid durable JSON state in $path: ${error.message}", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("invalid durable JSON state in $path: ${error.message}", error)
        }
    }

    private fun saveLocked(value: T) {
        val encoded = json.encodeToString(serializer, value).encodeToByteArray()
        require(encoded.size.toLong() <= maximumBytes) {
            "encoded state for $path is ${encoded.size} bytes, limit is $maximumBytes"
        }
        fileSystem.atomicWrite(path, encoded)
    }

    companion object {
        val DEFAULT_JSON: Json = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    }
}
