package net.extrawdw.notisync.sshagent

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
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.SecureFileSystem

@Serializable
data class AgentPidRecord(
    val pid: Long,
    val processStartTime: String? = null,
    val startedAt: String,
    val instanceId: String,
    val bindAddresses: List<String> = emptyList(),
    val explicitBindAddresses: Boolean = false,
    val ready: Boolean = false,
)

class AgentAlreadyRunningException(val record: AgentPidRecord?) : IllegalStateException(
    record?.let { "notisync-ssh-agent is already running as PID ${it.pid}" }
        ?: "notisync-ssh-agent is already running",
)

class AgentInstanceLock private constructor(
    private val paths: DesktopPaths,
    private val files: SecureFileSystem,
    private val channel: FileChannel,
    private val lock: FileLock,
    val record: AgentPidRecord,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    @Synchronized
    fun markReady(bindAddresses: List<String> = record.bindAddresses) {
        check(!closed.get()) { "SSH Agent instance lock is closed" }
        require(bindAddresses.isNotEmpty()) { "ready SSH Agent must have at least one bind address" }
        files.atomicWrite(
            paths.sshAgentPid,
            JSON.encodeToString(record.copy(bindAddresses = bindAddresses, ready = true)).encodeToByteArray(),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            val current = read(paths, files)
            if (current?.instanceId == record.instanceId) files.deletePrivateFileIfExists(paths.sshAgentPid)
        } finally {
            runCatching { lock.release() }
            runCatching { channel.close() }
        }
    }

    companion object {
        private val JSON = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

        fun acquire(
            paths: DesktopPaths = DesktopPaths.default(),
            files: SecureFileSystem = SecureFileSystem(),
            bindAddresses: List<String> = emptyList(),
            explicitBindAddresses: Boolean = false,
        ): AgentInstanceLock {
            files.ensurePrivateDirectory(paths.dataDirectory)
            val lockPath = files.ensurePrivateFile(paths.sshAgentLock)
            val channel = FileChannel.open(
                lockPath,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            val acquired = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (acquired == null) {
                channel.close()
                throw AgentAlreadyRunningException(read(paths, files))
            }
            val record = AgentPidRecord(
                ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant().orElse(null)?.toString(),
                Instant.now().toString(),
                UUID.randomUUID().toString(),
                bindAddresses,
                explicitBindAddresses,
            )
            try {
                files.atomicWrite(paths.sshAgentPid, JSON.encodeToString(record).encodeToByteArray())
                return AgentInstanceLock(paths, files, channel, acquired, record)
            } catch (failure: Throwable) {
                runCatching { acquired.release() }
                runCatching { channel.close() }
                throw failure
            }
        }

        fun read(
            paths: DesktopPaths = DesktopPaths.default(),
            files: SecureFileSystem = SecureFileSystem(),
        ): AgentPidRecord? {
            files.rejectSymbolicLinkComponents(paths.sshAgentPid)
            if (!Files.exists(paths.sshAgentPid, LinkOption.NOFOLLOW_LINKS)) return null
            return runCatching {
                JSON.decodeFromString<AgentPidRecord>(files.readPrivateBytes(paths.sshAgentPid).decodeToString())
            }.getOrNull()
        }
    }
}
