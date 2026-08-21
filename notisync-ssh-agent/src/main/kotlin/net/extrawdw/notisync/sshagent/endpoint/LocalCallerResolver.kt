package net.extrawdw.notisync.sshagent.endpoint

import java.nio.file.Path
import net.extrawdw.notisync.desktop.ProcessInstanceIdentity
import net.extrawdw.notisync.desktop.ProcessInstanceIdentityResolver
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextLimits
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.DesktopProcessIdentity
import org.newsclub.net.unix.AFUNIXSocket

data class LocalCallerSnapshot(
    val processContext: DesktopProcessContext,
    internal val leafInstance: ProcessInstanceIdentity?,
)

class LocalCallerResolver(
    private val processInstances: ProcessInstanceIdentityResolver = ProcessInstanceIdentityResolver(),
) {
    fun resolve(socket: AFUNIXSocket): LocalCallerSnapshot {
        // Windows AF_UNIX is supported as an explicit compatibility listener, but its provider
        // credentials are intentionally not treated as process provenance. Named pipes are the
        // only Windows endpoint that contributes verified caller process details.
        if (isWindows()) return unavailable()
        val pid = runCatching { socket.peerCredentials.pid }.getOrNull()?.takeIf { it > 0 }
            ?: return unavailable()
        return resolve(pid, DesktopProcessContextSource.PEER_CREDENTIALS)
    }

    fun resolve(pid: Long, source: DesktopProcessContextSource): LocalCallerSnapshot {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return unavailable()
        var bootId: String? = null
        var leafInstance: ProcessInstanceIdentity? = null
        val processLineage = buildList {
            var current: ProcessHandle? = handle
            repeat(DesktopProcessContextLimits.MAX_LINEAGE) {
                val value = current ?: return@buildList
                // A gap would make the next entry look like a direct parent when it is not.
                val resolved = identity(value) ?: return@buildList
                if (isEmpty()) {
                    bootId = resolved.instance.bootId
                    leafInstance = resolved.instance
                } else if (bootId != resolved.instance.bootId) {
                    return@buildList
                }
                add(resolved.identity)
                current = value.parent().orElse(null)
            }
        }
        if (processLineage.isEmpty()) return unavailable()
        return LocalCallerSnapshot(
            DesktopProcessContext(source, processLineage, bootId),
            leafInstance,
        )
    }

    /** Re-resolve the accepted client and reject PID reuse before each sensitive operation. */
    fun refresh(original: LocalCallerSnapshot): DesktopProcessContext {
        val originalLeaf = original.processContext.leaf ?: return unavailableContext()
        val originalInstance = original.leafInstance ?: return unavailableContext()
        val current = resolve(originalLeaf.pid, original.processContext.source)
        val currentLeaf = current.processContext.leaf ?: return unavailableContext()
        return current.processContext.takeIf {
            current.leafInstance == originalInstance &&
                currentLeaf.pid == originalLeaf.pid &&
                currentLeaf.executablePath.equals(
                    originalLeaf.executablePath,
                    ignoreCase = System.getProperty("os.name").contains("windows", ignoreCase = true),
                )
        } ?: unavailableContext()
    }

    private fun identity(handle: ProcessHandle): ResolvedIdentity? {
        val info = handle.info()
        val instance = processInstances.resolve(handle.pid()) ?: return null
        val command = info.command().orElse(null)?.takeIf(String::isNotBlank) ?: return null
        val normalized = runCatching { Path.of(command).toAbsolutePath().normalize().toString() }.getOrNull()
            ?: return null
        return ResolvedIdentity(
            instance,
            DesktopProcessIdentity(
                pid = handle.pid(),
                executablePath = normalized,
                displayName = runCatching { Path.of(normalized).fileName?.toString() }.getOrNull(),
            ),
        )
    }

    private data class ResolvedIdentity(
        val instance: ProcessInstanceIdentity,
        val identity: DesktopProcessIdentity,
    )

    private fun unavailable() = LocalCallerSnapshot(
        unavailableContext(),
        null,
    )

    private fun unavailableContext() = DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE)
}
