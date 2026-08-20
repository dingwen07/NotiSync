package net.extrawdw.notisync.sshagent.endpoint

import java.nio.file.Path
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextLimits
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import org.newsclub.net.unix.AFUNIXSocket

class LocalCallerResolver {
    fun resolve(socket: AFUNIXSocket): DesktopProcessContext {
        // Windows AF_UNIX is supported as an explicit compatibility listener, but its provider
        // credentials are intentionally not treated as process provenance. Named pipes are the
        // only Windows endpoint that contributes verified caller process details.
        if (isWindows()) return unavailable()
        val pid = runCatching { socket.peerCredentials.pid }.getOrNull()?.takeIf { it > 0 }
            ?: return unavailable()
        return resolve(pid, DesktopProcessContextSource.PEER_CREDENTIALS)
    }

    fun resolve(pid: Long, source: DesktopProcessContextSource): DesktopProcessContext {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return unavailable()
        val processLineage = buildList {
            var current: ProcessHandle? = handle
            repeat(DesktopProcessContextLimits.MAX_LINEAGE) {
                val value = current ?: return@buildList
                // A gap would make the next entry look like a direct parent when it is not.
                add(identity(value) ?: return@buildList)
                current = value.parent().orElse(null)
            }
        }
        return if (processLineage.isEmpty()) unavailable() else DesktopProcessContext(source, processLineage)
    }

    /** Re-resolve the accepted client and reject PID reuse before each sensitive operation. */
    fun refresh(original: DesktopProcessContext): DesktopProcessContext {
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

    private fun identity(handle: ProcessHandle): DesktopProcessIdentity? {
        val info = handle.info()
        val start = info.startInstant().orElse(null)?.toEpochMilli()?.takeIf { it > 0 } ?: return null
        val command = info.command().orElse(null)?.takeIf(String::isNotBlank) ?: return null
        val normalized = runCatching { Path.of(command).toAbsolutePath().normalize().toString() }.getOrNull()
            ?: return null
        return DesktopProcessIdentity(
            pid = handle.pid(),
            startEpochMillis = start,
            executablePath = normalized,
            displayName = runCatching { Path.of(normalized).fileName?.toString() }.getOrNull(),
        )
    }

    private fun unavailable() = DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE)
}
