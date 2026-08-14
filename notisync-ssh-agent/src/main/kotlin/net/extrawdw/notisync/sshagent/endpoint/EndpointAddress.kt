package net.extrawdw.notisync.sshagent.endpoint

import java.security.MessageDigest
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.sshagent.AgentConfig
import net.extrawdw.notisync.sshagent.AgentEndpointMode

fun agentEndpointAddress(paths: DesktopPaths, config: AgentConfig): String =
    agentEndpointAddresses(paths, config).first()

fun agentEndpointAddresses(
    paths: DesktopPaths,
    config: AgentConfig,
    explicitAddresses: List<String> = emptyList(),
    windows: Boolean = isWindows(),
): List<String> {
    val requested = explicitAddresses.ifEmpty {
        if (windows) {
            listOf(windowsDefaultAddress(paths, config))
        } else {
            listOf(paths.sshAgentSocket.toAbsolutePath().normalize().toString())
        }
    }
    require(requested.isNotEmpty()) { "at least one SSH Agent bind address is required" }
    require(requested.size <= MAX_AGENT_ENDPOINTS) { "at most $MAX_AGENT_ENDPOINTS SSH Agent endpoints are allowed" }
    val normalized = requested.map { normalizeAgentEndpointAddress(it, windows) }
    val distinct = if (windows) normalized.distinctBy(String::lowercase) else normalized.distinct()
    require(distinct.size == normalized.size) { "SSH Agent bind addresses must be unique" }
    return normalized
}

private fun windowsDefaultAddress(paths: DesktopPaths, config: AgentConfig): String = when (config.endpointMode) {
    AgentEndpointMode.AUTO, AgentEndpointMode.OPENSSH_COMPATIBLE -> WINDOWS_OPENSSH_PIPE
    AgentEndpointMode.CUSTOM -> windowsCustomAgentAddress(paths)
}

internal fun windowsCustomAgentAddress(paths: DesktopPaths): String {
    val identity = System.getProperty("user.name").orEmpty() + "\u0000" +
        paths.dataDirectory.toAbsolutePath().normalize()
    val suffix = MessageDigest.getInstance("SHA-256").digest(identity.encodeToByteArray())
        .take(12).joinToString("") { "%02x".format(it) }
    return "\\\\.\\pipe\\notisync-ssh-agent-$suffix"
}

internal fun normalizeAgentEndpointAddress(address: String, windows: Boolean = isWindows()): String {
    require(address.isNotBlank() && '\u0000' !in address) { "SSH Agent bind address must not be blank" }
    if (windows && address.startsWith(WINDOWS_PIPE_PREFIX, ignoreCase = true)) {
        val name = address.substring(WINDOWS_PIPE_PREFIX.length)
        require(WINDOWS_PIPE_NAME.matches(name)) { "invalid Windows SSH Agent pipe name" }
        return WINDOWS_PIPE_PREFIX + name
    }
    val path = java.nio.file.Path.of(address)
    require(path.isAbsolute) { "SSH Agent socket path must be absolute" }
    val normalized = path.normalize().toString()
    require(normalized.encodeToByteArray().size <= MAX_UNIX_SOCKET_PATH_BYTES) {
        "SSH Agent socket path is too long"
    }
    require(java.nio.file.Path.of(normalized).parent != null) { "SSH Agent socket path must have a parent directory" }
    return normalized
}

internal fun isWindowsNamedPipeAddress(address: String): Boolean =
    address.startsWith(WINDOWS_PIPE_PREFIX, ignoreCase = true)

internal const val WINDOWS_OPENSSH_PIPE = "\\\\.\\pipe\\openssh-ssh-agent"
private const val WINDOWS_PIPE_PREFIX = "\\\\.\\pipe\\"
private const val MAX_AGENT_ENDPOINTS = 4
private const val MAX_UNIX_SOCKET_PATH_BYTES = 100
private val WINDOWS_PIPE_NAME = Regex("[A-Za-z0-9._-]{1,128}")

fun isWindows(): Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)
