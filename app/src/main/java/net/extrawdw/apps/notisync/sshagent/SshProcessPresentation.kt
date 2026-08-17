package net.extrawdw.apps.notisync.sshagent

import net.extrawdw.notisync.protocol.SshProcessIdentity

/** Selects the user-meaningful caller from a leaf-first process lineage. */
internal fun List<SshProcessIdentity>.mainCallerLabel(): String? {
    val leaf = firstOrNull() ?: return null
    val caller = if (leaf.isTrivialProcess()) getOrNull(1) ?: leaf else leaf
    return caller.shortProcessName()
}

private fun SshProcessIdentity.isTrivialProcess(): Boolean =
    executableFileName().lowercase() in TRIVIAL_PROCESS_NAMES

internal fun SshProcessIdentity.shortProcessName(): String =
    displayName?.takeIf(String::isNotBlank) ?: executableFileName()

private fun SshProcessIdentity.executableFileName(): String =
    executablePath.substringAfterLast('/').substringAfterLast('\\').ifBlank { executablePath }

private val TRIVIAL_PROCESS_NAMES = setOf("ssh", "ssh.exe")
