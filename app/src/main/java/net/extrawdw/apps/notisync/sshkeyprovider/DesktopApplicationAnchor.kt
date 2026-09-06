package net.extrawdw.apps.notisync.sshkeyprovider

import java.util.Locale
import net.extrawdw.notisync.protocol.DesktopProcessContext
import net.extrawdw.notisync.protocol.DesktopProcessIdentity

/** Stable, requester-reported application identity used only as a narrowing policy predicate. */
internal class DesktopApplicationIdentity(val executablePath: String) {
    private val comparisonPath = executablePath.desktopApplicationPathComparisonKey()

    init {
        require(executablePath.isAbsoluteDesktopPath()) { "application executable path must be absolute" }
    }

    fun matches(other: DesktopApplicationIdentity): Boolean = comparisonPath == other.comparisonPath
}

internal data class DesktopApplicationAnchor(
    val process: DesktopProcessIdentity,
    val identity: DesktopApplicationIdentity,
    val knownDesktopApplication: KnownDesktopApplication?,
    val lineageIndex: Int,
) {
    val applicationId: String get() = knownDesktopApplication?.id ?: UNKNOWN_DESKTOP_APPLICATION_ID
    val displayName: String get() = knownDesktopApplication?.displayName ?: process.shortProcessName()
    val priority: Int get() = knownDesktopApplication?.priority ?: DesktopApplicationProcessRole.UNKNOWN.defaultPriority
}

internal data class DesktopApplicationAnchorSelection(
    val recommended: DesktopApplicationAnchor?,
    val candidates: List<DesktopApplicationAnchor>,
) {
    fun contains(identity: DesktopApplicationIdentity): Boolean = candidates.any { it.identity.matches(identity) }
}

/**
 * Selects full-path anchors from a leaf-first requester-reported lineage using the supplied registry snapshot.
 * Per-entry traversal is evaluated before requiring a path; numeric priority only ranks candidates.
 * The selected executable path remains a reported narrowing predicate, not a verified application principal.
 */
internal object DesktopApplicationAnchorSelector {
    fun select(
        context: DesktopProcessContext,
        registry: KnownDesktopApplicationRegistry = BUILT_IN_DESKTOP_APPLICATIONS,
    ): DesktopApplicationAnchorSelection = select(context.processLineage, registry)

    fun select(
        lineage: List<DesktopProcessIdentity>,
        registry: KnownDesktopApplicationRegistry = BUILT_IN_DESKTOP_APPLICATIONS,
    ): DesktopApplicationAnchorSelection {
        val candidates = buildList {
            for ((lineageIndex, process) in lineage.withIndex()) {
                val application = registry.find(process)
                when (application?.traversal ?: DesktopApplicationLineageTraversal.CANDIDATE) {
                    DesktopApplicationLineageTraversal.STOP -> break
                    DesktopApplicationLineageTraversal.SKIP -> continue
                    DesktopApplicationLineageTraversal.CANDIDATE -> Unit
                }
                val path = process.executablePath ?: continue
                val identity = runCatching { DesktopApplicationIdentity(path) }.getOrNull() ?: continue
                add(DesktopApplicationAnchor(process, identity, application, lineageIndex))
            }
        }.sortedWith(
            compareByDescending<DesktopApplicationAnchor> { it.priority }
                .thenBy(DesktopApplicationAnchor::lineageIndex),
        )
        return DesktopApplicationAnchorSelection(candidates.firstOrNull(), candidates)
    }
}

internal fun DesktopProcessIdentity.executableFileName(): String? =
    executablePath
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.ifBlank { executablePath }

internal fun String.desktopApplicationPathComparisonKey(): String = if (isWindowsDesktopPath()) {
    replace('/', '\\').lowercase(Locale.ROOT)
} else {
    this
}

internal fun String.isAbsoluteDesktopPath(): Boolean =
    startsWith('/') || startsWith("\\\\") ||
        (length >= 3 && this[0].isLetter() && this[1] == ':' && (this[2] == '\\' || this[2] == '/'))

private fun String.isWindowsDesktopPath(): Boolean =
    startsWith("\\\\") ||
        (length >= 3 && this[0].isLetter() && this[1] == ':' && (this[2] == '\\' || this[2] == '/'))

private const val UNKNOWN_DESKTOP_APPLICATION_ID = "unknown"
