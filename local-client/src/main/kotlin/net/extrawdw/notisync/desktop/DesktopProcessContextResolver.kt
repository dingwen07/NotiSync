package net.extrawdw.notisync.desktop

import java.nio.file.Path
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessContextLimits
import net.extrawdw.notisync.protocol.DesktopProcessContextSource
import net.extrawdw.notisync.protocol.DesktopProcessIdentity

data class DesktopProcessSnapshot(
    val context: DesktopProcessContext,
    /** Local-only PID-reuse token; never included in the transmitted context. */
    val leafInstance: ProcessInstanceIdentity?,
)

/** Shared, best-effort process inspection for desktop signing clients. Never collects arguments. */
class DesktopProcessContextResolver(
    private val processInstances: ProcessInstanceIdentityResolver = ProcessInstanceIdentityResolver(),
    private val processExecutables: DesktopProcessExecutableResolver = DesktopProcessExecutableResolver(),
    private val processNames: DesktopProcessNameResolver = DesktopProcessNameResolver(),
    private val processOwners: DesktopProcessOwnerResolver = DesktopProcessOwnerResolver(),
) {
    fun current(): DesktopProcessContext = resolve(
        ProcessHandle.current().pid(), DesktopProcessContextSource.CURRENT_PROCESS,
    ).context

    fun resolve(pid: Long, source: DesktopProcessContextSource): DesktopProcessSnapshot {
        val handle = runCatching { ProcessHandle.of(pid).orElse(null) }.getOrNull()
            ?: return unavailable()
        if (source == DesktopProcessContextSource.UNAVAILABLE) return unavailable()
        var bootId: String? = null
        var leafInstance: ProcessInstanceIdentity? = null
        val lineage = buildList<DesktopProcessIdentity> {
            var current: ProcessHandle? = handle
            repeat(DesktopProcessContextLimits.MAX_LINEAGE) {
                val value = current ?: return@buildList
                if (any { it.pid == value.pid() }) return@buildList
                val instance = processInstances.resolve(value.pid())
                if (isEmpty()) {
                    bootId = instance?.bootId
                    leafInstance = instance
                } else if (bootId != null && instance?.bootId != null && bootId != instance.bootId) {
                    return@buildList
                }
                // Restricted or oversized executable metadata must not hide the PID or its parents.
                add(identity(value.pid()))
                current = runCatching { value.parent().orElse(null) }.getOrNull()
            }
        }
        if (lineage.isEmpty()) return unavailable()
        return DesktopProcessSnapshot(DesktopProcessContext(source, lineage, bootId), leafInstance)
    }

    private fun identity(pid: Long): DesktopProcessIdentity {
        val path = processExecutables.resolve(pid)?.let { command ->
            runCatching { Path.of(command).toAbsolutePath().normalize().toString() }.getOrNull()
        }?.takeIf { DesktopProcessIdentity(pid, executablePath = it).validationError() == null }
        val name = path?.let { runCatching { Path.of(it).fileName?.toString() }.getOrNull() }
            ?: processNames.resolve(pid)
        val owner = processOwners.resolve(pid)
        return DesktopProcessIdentity(
            pid, path,
            name?.takeIf { DesktopProcessIdentity(pid, displayName = it).validationError() == null },
            username = owner?.username?.takeIf { DesktopProcessIdentity(pid, username = it).validationError() == null },
            uid = owner?.uid?.takeIf { DesktopProcessIdentity(pid, uid = it).validationError() == null },
            sid = owner?.sid?.takeIf { DesktopProcessIdentity(pid, sid = it).validationError() == null },
            windowsTokenInfo = owner?.windowsTokenInfo?.takeIf { it.validationError() == null },
        )
    }

    private fun unavailable() = DesktopProcessSnapshot(
        DesktopProcessContext(DesktopProcessContextSource.UNAVAILABLE), null,
    )
}
