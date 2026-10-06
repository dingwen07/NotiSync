package net.extrawdw.notisync.sshagent.endpoint

import net.extrawdw.notisync.desktop.DesktopProcessContextResolver
import net.extrawdw.notisync.desktop.DesktopProcessExecutableResolver
import net.extrawdw.notisync.desktop.DesktopProcessNameResolver
import net.extrawdw.notisync.desktop.ProcessInstanceIdentity
import net.extrawdw.notisync.desktop.ProcessInstanceIdentityResolver
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import org.newsclub.net.unix.AFUNIXSocket

data class LocalCallerSnapshot(
    val processContext: DesktopProcessContext,
    internal val leafInstance: ProcessInstanceIdentity?,
)

class LocalCallerResolver(
    processInstances: ProcessInstanceIdentityResolver = ProcessInstanceIdentityResolver(),
    processExecutables: DesktopProcessExecutableResolver = DesktopProcessExecutableResolver(),
    processNames: DesktopProcessNameResolver = DesktopProcessNameResolver(),
) {
    private val processContextResolver = DesktopProcessContextResolver(processInstances, processExecutables, processNames)

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
        val snapshot = processContextResolver.resolve(pid, source)
        return LocalCallerSnapshot(snapshot.context, snapshot.leafInstance)
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

    private fun unavailable() = LocalCallerSnapshot(
        unavailableContext(),
        null,
    )

    private fun unavailableContext() = DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE)
}
