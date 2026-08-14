package net.extrawdw.notisync.sshagent.endpoint

import java.nio.file.Path
import net.extrawdw.notisync.protocol.SshProcessContext
import net.extrawdw.notisync.protocol.SshProcessContextSource
import net.extrawdw.notisync.protocol.SshProcessIdentity
import org.newsclub.net.unix.AFUNIXSocket

class LocalCallerResolver {
    fun resolve(socket: AFUNIXSocket): SshProcessContext {
        // Windows AF_UNIX is supported as an explicit compatibility listener, but its provider
        // credentials are intentionally not treated as process provenance. Named pipes are the
        // only Windows endpoint that contributes verified caller process details.
        if (isWindows()) return unavailable()
        val pid = runCatching { socket.peerCredentials.pid }.getOrNull()?.takeIf { it > 0 }
            ?: return unavailable()
        return resolve(pid, SshProcessContextSource.PEER_CREDENTIALS)
    }

    fun resolve(pid: Long, source: SshProcessContextSource): SshProcessContext {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return unavailable()
        val leaf = identity(handle) ?: return unavailable()
        val parentHandle = handle.parent().orElse(null)
        val parent = parentHandle?.let(::identity)
        val ancestry = buildList {
            var current: ProcessHandle? = handle
            repeat(16) {
                val value = current ?: return@repeat
                identity(value)?.let(::add)
                current = value.parent().orElse(null)
            }
        }
        return SshProcessContext(source, leaf, parent, ancestry)
    }

    /** Re-resolve the accepted client and reject PID reuse before each sensitive operation. */
    fun refresh(original: SshProcessContext): SshProcessContext {
        val originalLeaf = original.leaf ?: return unavailable()
        val current = resolve(originalLeaf.pid, original.source)
        val currentLeaf = current.leaf ?: return unavailable()
        return current.takeIf {
            currentLeaf.pid == originalLeaf.pid &&
                currentLeaf.startEpochMillis == originalLeaf.startEpochMillis &&
                currentLeaf.executablePath.equals(
                    originalLeaf.executablePath,
                    ignoreCase = System.getProperty("os.name").contains("windows", ignoreCase = true),
                )
        } ?: unavailable()
    }

    private fun identity(handle: ProcessHandle): SshProcessIdentity? {
        val info = handle.info()
        val start = info.startInstant().orElse(null)?.toEpochMilli()?.takeIf { it > 0 } ?: return null
        val command = info.command().orElse(null)?.takeIf(String::isNotBlank) ?: return null
        val normalized = runCatching { Path.of(command).toAbsolutePath().normalize().toString() }.getOrNull()
            ?: return null
        return SshProcessIdentity(
            pid = handle.pid(),
            startEpochMillis = start,
            executablePath = normalized,
            displayName = runCatching { Path.of(normalized).fileName?.toString() }.getOrNull(),
        )
    }

    private fun unavailable() = SshProcessContext(SshProcessContextSource.UNAVAILABLE)
}
