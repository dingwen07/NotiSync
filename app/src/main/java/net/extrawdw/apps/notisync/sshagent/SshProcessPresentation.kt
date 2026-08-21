package net.extrawdw.apps.notisync.sshagent

import net.extrawdw.notisync.protocol.DesktopProcessIdentity

/** Selects the user-meaningful caller from a leaf-first process lineage. */
internal fun List<DesktopProcessIdentity>.mainCallerLabel(): String? {
    val leaf = firstOrNull() ?: return null
    val caller = if (leaf.isTrivialProcess()) getOrNull(1) ?: leaf else leaf
    return caller.shortProcessName()
}

private fun DesktopProcessIdentity.isTrivialProcess(): Boolean =
    (executableFileName() ?: displayName)?.lowercase() in TRIVIAL_PROCESS_NAMES

internal fun DesktopProcessIdentity.shortProcessName(): String =
    displayName?.takeIf(String::isNotBlank) ?: executableFileName() ?: "PID $pid"

private fun DesktopProcessIdentity.executableFileName(): String? =
    executablePath
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.ifBlank { executablePath }

private val TRIVIAL_PROCESS_NAMES = setOf("ssh", "ssh.exe")
