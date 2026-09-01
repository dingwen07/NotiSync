package net.extrawdw.apps.notisync.sshkeyprovider

import net.extrawdw.notisync.protocol.DesktopProcessIdentity

/** Selects the user-meaningful caller from a leaf-first process lineage. */
internal fun List<DesktopProcessIdentity>.mainCallerLabel(): String? {
    return SshApplicationAnchorSelector.select(this).recommended?.displayName
        ?: firstOrNull()?.shortProcessName()
}

internal fun DesktopProcessIdentity.shortProcessName(): String =
    displayName?.takeIf(String::isNotBlank) ?: executableFileName() ?: "PID $pid"
