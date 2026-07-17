package net.extrawdw.notisync.daemon.storage

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DaemonPidRecord(
    val pid: Long,
    val processStartTime: String? = null,
    val daemonStartedAt: String,
    val instanceId: String,
)

class DaemonAlreadyRunningException(
    val record: DaemonPidRecord?,
) : IllegalStateException(
    record?.let { "notisyncd is already running as PID ${it.pid}" }
        ?: "notisyncd is already running",
)

/** Owns the advisory single-instance lock and the matching private PID record. */
class DaemonInstanceLock private constructor(
    private val layout: DaemonStorageLayout,
    private val fileSystem: SecureFileSystem,
    private val channel: FileChannel,
    private val fileLock: FileLock,
    val record: DaemonPidRecord,
    registerShutdownHook: Boolean,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val shutdownHook: Thread? = if (registerShutdownHook) {
        Thread({ close() }, "notisyncd-instance-lock-cleanup").also(Runtime.getRuntime()::addShutdownHook)
    } else {
        null
    }

    val isHeld: Boolean get() = !closed.get() && fileLock.isValid

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            // Do not unlink a record that was externally replaced. The advisory lock remains the
            // source of truth, but retaining an unexpected record is better forensic behavior.
            val current = readPidRecord(layout.pidFile, fileSystem)
            if (current?.instanceId == record.instanceId) {
                fileSystem.deletePrivateFileIfExists(layout.pidFile)
            }
        } finally {
            runCatching { fileLock.release() }
            runCatching { channel.close() }
            shutdownHook?.let { hook ->
                if (Thread.currentThread() !== hook) runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            }
        }
    }

    companion object {
        private val PID_JSON = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

        fun acquire(
            layout: DaemonStorageLayout = DaemonStorageLayout.default(),
            fileSystem: SecureFileSystem = SecureFileSystem(),
            pid: Long = ProcessHandle.current().pid(),
            processStartTime: String? = ProcessHandle.current().info().startInstant().orElse(null)?.toString(),
            now: Instant = Instant.now(),
            registerShutdownHook: Boolean = true,
        ): DaemonInstanceLock {
            layout.prepare(fileSystem)
            val lockPath = fileSystem.ensurePrivateFile(layout.lockFile)
            val channel = FileChannel.open(
                lockPath,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                throw DaemonAlreadyRunningException(readPidRecord(layout.pidFile, fileSystem))
            }

            val record = DaemonPidRecord(
                pid = pid,
                processStartTime = processStartTime,
                daemonStartedAt = now.toString(),
                instanceId = UUID.randomUUID().toString(),
            )
            try {
                fileSystem.atomicWrite(layout.pidFile, PID_JSON.encodeToString(record).encodeToByteArray())
                return DaemonInstanceLock(
                    layout,
                    fileSystem,
                    channel,
                    lock,
                    record,
                    registerShutdownHook,
                )
            } catch (error: Throwable) {
                runCatching { lock.release() }
                runCatching { channel.close() }
                throw error
            }
        }

        fun readPidRecord(
            path: java.nio.file.Path,
            fileSystem: SecureFileSystem = SecureFileSystem(),
        ): DaemonPidRecord? {
            fileSystem.rejectSymbolicLinkComponents(path)
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
            return runCatching {
                PID_JSON.decodeFromString<DaemonPidRecord>(fileSystem.readPrivateBytes(path).decodeToString())
            }.getOrNull()
        }
    }
}
