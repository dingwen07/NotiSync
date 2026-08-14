package net.extrawdw.notisync.sshagent

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.DesktopProcessTitle
import net.extrawdw.notisync.desktop.SecureFileSystem
import net.extrawdw.notisync.desktop.api.DaemonAutostarter
import net.extrawdw.notisync.desktop.api.UnixDaemonClient
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.sshagent.bridge.ProviderRoster
import net.extrawdw.notisync.sshagent.cache.AgentDatabase
import net.extrawdw.notisync.sshagent.cache.AgentMetadataStore
import net.extrawdw.notisync.sshagent.cache.ProviderSnapshotStore
import net.extrawdw.notisync.sshagent.endpoint.agentEndpointAddresses
import net.extrawdw.notisync.sshagent.endpoint.isWindows

fun main(arguments: Array<String>) {
    exitProcess(NotisyncSshAgentCommand().run(arguments.toList()))
}

internal data class AgentInvocation(
    val command: String,
    val commandArguments: List<String>,
    val bindAddresses: List<String>,
)

internal fun parseAgentInvocation(arguments: List<String>): AgentInvocation {
    val bindAddresses = mutableListOf<String>()
    var index = 0
    while (index < arguments.size && arguments[index] == "-a") {
        require(index + 1 < arguments.size) { "-a requires a bind address" }
        bindAddresses += arguments[index + 1]
        index += 2
    }
    val command = arguments.getOrNull(index) ?: "foreground"
    require(!command.startsWith('-') || command == "--help" || command == "-h") { "unknown option: $command" }
    return AgentInvocation(command, arguments.drop(index + 1), bindAddresses)
}

class NotisyncSshAgentCommand(
    private val paths: DesktopPaths = DesktopPaths.default(),
    private val output: Appendable = System.out,
    private val error: Appendable = System.err,
) {
    private val files = SecureFileSystem()

    fun run(arguments: List<String>): Int = try {
        val invocation = parseAgentInvocation(arguments)
        when (invocation.command) {
            "foreground", "run" -> foreground(invocation.bindAddresses, invocation.commandArguments)
            "start" -> start(invocation.bindAddresses, invocation.commandArguments)
            "stop" -> noArguments(invocation, ::stop)
            "status" -> noArguments(invocation, ::status)
            "env" -> noArguments(invocation) { environment(invocation.bindAddresses) }
            "doctor" -> noArguments(invocation) { doctor(invocation.bindAddresses) }
            "keys" -> noArguments(invocation, ::keys)
            "config" -> config(invocation.commandArguments)
            "help", "--help", "-h" -> noArguments(invocation, ::showHelp)
            else -> throw IllegalArgumentException("unknown command: ${invocation.command}")
        }
    } catch (running: AgentAlreadyRunningException) {
        error.appendLine(running.message ?: "SSH Agent is already running")
        2
    } catch (failure: Throwable) {
        error.appendLine("notisync-ssh-agent: ${diagnostic(failure.message)}")
        val command = runCatching { parseAgentInvocation(arguments).command }.getOrNull()
        if ((command ?: "foreground") in setOf("foreground", "run") && error === System.err) {
            failure.printStackTrace(System.err)
        }
        1
    }

    private fun foreground(bindAddresses: List<String>, arguments: List<String>): Int {
        require(arguments.isEmpty()) { "foreground does not accept positional arguments" }
        val resolved = resolveBindAddresses(bindAddresses)
        DesktopProcessTitle.set("ns-ssh-agent")
        AgentInstanceLock.acquire(paths, files, resolved, bindAddresses.isNotEmpty()).use { instance ->
            AgentRuntime(paths, output, bindAddresses, instance::markReady).run()
        }
        return 0
    }

    private fun start(bindAddresses: List<String>, arguments: List<String>): Int {
        require(arguments.isEmpty()) { "start does not accept positional arguments" }
        val resolved = resolveBindAddresses(bindAddresses)
        runningRecord()?.let { record ->
            output.appendLine("notisync-ssh-agent is already running")
            appendEndpoints(record.bindAddresses.ifEmpty { resolved })
            return 0
        }
        DaemonAutostarter(paths).connect()
        files.ensurePrivateDirectory(paths.logDirectory)
        files.ensurePrivateFile(paths.sshAgentLog)
        val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
        val command = buildList {
            add(java.toString())
            add("-Dnotisync.dataDir=${paths.dataDirectory.toAbsolutePath().normalize()}")
            add("-Dnotisync.logDir=${paths.logDirectory.toAbsolutePath().normalize()}")
            add("-cp")
            add(System.getProperty("java.class.path"))
            add("net.extrawdw.notisync.sshagent.NotisyncSshAgentMainKt")
            // Preserve an empty list so the child can perform AUTO bind-time fallback.
            (if (bindAddresses.isEmpty()) emptyList() else resolved).forEach {
                add("-a")
                add(it)
            }
            add("foreground")
        }
        val nullDevice = Path.of(if (isWindows()) "NUL" else "/dev/null").toFile()
        val process = ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(paths.sshAgentLog.toFile()))
            .redirectError(ProcessBuilder.Redirect.appendTo(paths.sshAgentLog.toFile()))
            .start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            runningRecord()?.takeIf { it.pid == process.pid() && it.ready }?.let {
                output.appendLine("notisync-ssh-agent started as PID ${it.pid}")
                appendEndpoints(it.bindAddresses.ifEmpty { resolved })
                return 0
            }
            if (!process.isAlive) {
                throw IllegalStateException(startupFailureMessage("SSH Agent exited during startup"))
            }
            Thread.sleep(50)
        }
        throw IllegalStateException(startupFailureMessage("SSH Agent did not become ready"))
    }

    private fun stop(): Int {
        val record = runningRecord()
        if (record == null) {
            output.appendLine("notisync-ssh-agent is not running")
            return 0
        }
        val process = ProcessHandle.of(record.pid).orElse(null)
            ?: throw IllegalStateException("SSH Agent PID disappeared")
        check(process.destroy()) { "could not request SSH Agent shutdown" }
        process.onExit().get(10, TimeUnit.SECONDS)
        output.appendLine("notisync-ssh-agent stopped")
        return 0
    }

    private fun status(): Int {
        val record = runningRecord()
        return if (record == null) {
            output.appendLine("notisync-ssh-agent is not running")
            1
        } else {
            output.appendLine("notisync-ssh-agent is running as PID ${record.pid}")
            appendEndpoints(record.bindAddresses.ifEmpty { resolveBindAddresses(emptyList()) })
            output.appendLine(
                "Endpoint selection: ${if (record.explicitBindAddresses) "explicit" else "configured"}",
            )
            0
        }
    }

    private fun environment(explicitAddresses: List<String>): Int {
        val endpoints = if (explicitAddresses.isNotEmpty()) {
            resolveBindAddresses(explicitAddresses)
        } else {
            runningRecord()?.bindAddresses?.takeIf { it.isNotEmpty() } ?: resolveBindAddresses(emptyList())
        }
        val endpoint = endpoints.first()
        if (isWindows()) {
            output.appendLine("\$env:SSH_AUTH_SOCK='$endpoint'")
            output.appendLine("OpenSSH config: IdentityAgent $endpoint")
        } else {
            output.appendLine("export SSH_AUTH_SOCK='${endpoint.replace("'", "'\\''")}'")
        }
        endpoints.drop(1).forEach { output.appendLine("Additional endpoint: $it") }
        return 0
    }

    private fun doctor(explicitAddresses: List<String>): Int {
        val config = AgentConfigStore(paths.sshAgentConfig).load()
        val endpoints = if (explicitAddresses.isNotEmpty()) {
            agentEndpointAddresses(paths, config, explicitAddresses)
        } else {
            runningRecord()?.bindAddresses?.takeIf { it.isNotEmpty() }
                ?: agentEndpointAddresses(paths, config)
        }
        val daemon = UnixDaemonClient(paths.socket).status()
        require(!daemon.clientId.isNullOrBlank()) { "notisyncd has no local identity" }
        files.ensurePrivateDirectory(paths.dataDirectory)
        output.appendLine("Configuration: OK")
        output.appendLine("notisyncd: ${daemon.connectionState} (${daemon.clientId})")
        output.appendLine("Endpoint mode: ${config.endpointMode}")
        appendEndpoints(endpoints)
        output.appendLine("Legacy RSA/SHA-1: ${if (config.allowLegacyRsaSha1) "enabled" else "disabled"}")
        return 0
    }

    private fun keys(): Int {
        if (!Files.exists(paths.sshAgentDatabase)) {
            output.appendLine("No cached SSH identities")
            return 0
        }
        val api = UnixDaemonClient(paths.socket)
        val requester = api.status().clientId?.let(::ClientId)
            ?: throw IllegalStateException("notisyncd has no local identity")
        AgentDatabase(paths.sshAgentDatabase).use { database ->
            val metadata = AgentMetadataStore(database).authorizationNamespace()
            val identities = ProviderSnapshotStore(database).aggregate(
                ProviderRoster(api).activeProviderIds(),
                requester,
                metadata.generation,
                metadata.epoch,
                System.currentTimeMillis(),
            )
            if (identities.isEmpty()) output.appendLine("No active cached SSH identities")
            identities.forEach { identity ->
                output.appendLine("${identity.fingerprint}  ${identity.comment}  (${identity.candidates.size} provider(s))")
            }
        }
        return 0
    }

    private fun config(arguments: List<String>): Int {
        val store = AgentConfigStore(paths.sshAgentConfig)
        return when (arguments.firstOrNull()) {
            "show" -> {
                require(arguments.size == 1) { "usage: notisync-ssh-agent config show" }
                output.append(store.encode(store.load()))
                0
            }
            "set-default-provider" -> {
                require(arguments.size == 2) {
                    "usage: notisync-ssh-agent config set-default-provider CLIENT_ID"
                }
                require(CLIENT_ID.matches(arguments[1])) { "invalid provider client id" }
                store.save(store.load().copy(defaultProviderClientId = arguments[1]))
                output.appendLine("Default SSH key provider set to ${arguments[1]}")
                0
            }
            "clear-default-provider" -> {
                require(arguments.size == 1) {
                    "usage: notisync-ssh-agent config clear-default-provider"
                }
                store.save(store.load().copy(defaultProviderClientId = null))
                output.appendLine("Default SSH key provider cleared")
                0
            }
            "set-endpoint" -> {
                require(arguments.size == 2) {
                    "usage: notisync-ssh-agent config set-endpoint auto | custom | openssh-compatible"
                }
                val mode = when (arguments[1].lowercase()) {
                    "auto" -> AgentEndpointMode.AUTO
                    "custom" -> AgentEndpointMode.CUSTOM
                    "openssh-compatible" -> AgentEndpointMode.OPENSSH_COMPATIBLE
                    else -> throw IllegalArgumentException("endpoint must be auto, custom, or openssh-compatible")
                }
                store.save(store.load().copy(endpointMode = mode))
                output.appendLine("SSH Agent endpoint mode set to ${mode.name.lowercase()}; restart the agent to apply")
                0
            }
            "set-legacy-rsa-sha1" -> {
                require(arguments.size == 2 && arguments[1] in setOf("enabled", "disabled")) {
                    "usage: notisync-ssh-agent config set-legacy-rsa-sha1 enabled | disabled"
                }
                store.save(store.load().copy(allowLegacyRsaSha1 = arguments[1] == "enabled"))
                output.appendLine("Legacy RSA/SHA-1 ${arguments[1]}; restart the agent to apply")
                0
            }
            else -> throw IllegalArgumentException(
                    "usage: notisync-ssh-agent config show | set-default-provider CLIENT_ID | " +
                    "clear-default-provider | set-endpoint MODE | set-legacy-rsa-sha1 enabled|disabled",
            )
        }
    }

    private fun showHelp(): Int {
        output.appendLine(
            """
            Usage:
              notisync-ssh-agent [-a ADDRESS]... foreground
              notisync-ssh-agent [-a ADDRESS]... start
              notisync-ssh-agent stop | status
              notisync-ssh-agent [-a ADDRESS]... env | doctor
              notisync-ssh-agent keys
              notisync-ssh-agent config show
              notisync-ssh-agent config set-default-provider CLIENT_ID
              notisync-ssh-agent config clear-default-provider
              notisync-ssh-agent config set-endpoint auto | custom | openssh-compatible
              notisync-ssh-agent config set-legacy-rsa-sha1 enabled | disabled

            -a overrides configured endpoints for this invocation and may be repeated.
            On Windows ADDRESS may be a local named pipe or an absolute AF_UNIX filesystem path.
            Repeat -a to listen on both endpoint types; only the named pipe is enabled by default.
            AUTO prefers \\.\pipe\openssh-ssh-agent and falls back to a stable private pipe if occupied.
            """.trimIndent(),
        )
        return 0
    }

    private fun runningRecord(): AgentPidRecord? {
        val record = AgentInstanceLock.read(paths, files) ?: return null
        val handle = ProcessHandle.of(record.pid).orElse(null) ?: return null
        val actualStart = handle.info().startInstant().orElse(null)
        val expectedStart = record.processStartTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return record.takeIf { handle.isAlive && (expectedStart == null || expectedStart == actualStart) }
    }

    private fun resolveBindAddresses(explicitAddresses: List<String>): List<String> =
        agentEndpointAddresses(paths, AgentConfigStore(paths.sshAgentConfig).load(), explicitAddresses)

    private fun appendEndpoints(endpoints: List<String>) {
        endpoints.forEach { output.appendLine("Endpoint: $it") }
    }

    private fun noArguments(invocation: AgentInvocation, block: () -> Int): Int {
        require(invocation.commandArguments.isEmpty()) { "${invocation.command} does not accept positional arguments" }
        return block()
    }

    private fun startupFailureMessage(prefix: String): String {
        val detail = runCatching {
            files.readPrivateBytes(paths.sshAgentLog, 1024 * 1024)
                .decodeToString()
                .lineSequence()
                .lastOrNull { it.startsWith("notisync-ssh-agent:") }
                ?.removePrefix("notisync-ssh-agent:")
                ?.trim()
        }.getOrNull()
        return if (detail.isNullOrBlank()) "$prefix; see ${paths.sshAgentLog}" else "$prefix: $detail"
    }

    private fun diagnostic(message: String?): String = message.orEmpty().ifBlank { "operation failed" }
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .filterNot(Char::isISOControl)
        .take(512)

    private companion object {
        val CLIENT_ID = Regex("[a-z2-7]{32}")
    }
}
