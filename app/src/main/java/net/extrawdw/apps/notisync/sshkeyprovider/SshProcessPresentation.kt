package net.extrawdw.apps.notisync.sshkeyprovider

import net.extrawdw.notisync.protocol.DesktopProcessIdentity

/** Selects the user-meaningful caller from a leaf-first process lineage. */
internal fun List<DesktopProcessIdentity>.mainCallerLabel(
    registry: KnownDesktopApplicationRegistry = BUILT_IN_DESKTOP_APPLICATIONS,
): String? {
    return DesktopApplicationAnchorSelector.select(this, registry).recommended?.displayName
        ?: firstOrNull()?.shortProcessName()
}

internal fun DesktopProcessIdentity.shortProcessName(): String =
    displayName?.takeIf(String::isNotBlank) ?: executableFileName() ?: "PID $pid"
