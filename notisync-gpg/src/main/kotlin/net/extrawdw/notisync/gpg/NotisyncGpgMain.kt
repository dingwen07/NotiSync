package net.extrawdw.notisync.gpg

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.system.exitProcess
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.api.UnixDaemonClient
import net.extrawdw.notisync.protocol.GitCommitPayloadParser

fun main(arguments: Array<String>) {
    exitProcess(NotisyncGpgCommand().run(arguments.toList()))
}

class NotisyncGpgCommand(
    private val paths: DesktopPaths = DesktopPaths.default(),
    private val stdout: java.io.PrintStream = System.out,
    private val stderr: java.io.PrintStream = System.err,
) {
    fun run(arguments: List<String>): Int = try {
        when (arguments.firstOrNull()) {
            "config" -> manageConfig(arguments.drop(1))
            "doctor" -> doctor(arguments.drop(1))
            "--help", "-h" -> showHelp()
            else -> invokeGpg(arguments)
        }
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        stderr.println("notisync-gpg: signing interrupted")
        GENERAL_FAILURE
    } catch (error: Exception) {
        stderr.println("notisync-gpg: ${boundedDiagnostic(error.message)}")
        GENERAL_FAILURE
    }

    private fun manageConfig(arguments: List<String>): Int {
        val store = NotisyncGpgConfigStore(paths.notisyncGpgConfig)
        return when (arguments.firstOrNull()) {
            "show" -> {
                require(arguments.size == 1) { "usage: notisync-gpg config show" }
                val config = store.load()
                stdout.print(store.encode(config))
                SUCCESS
            }
            "set-real-gpg" -> {
                require(arguments.size == 2) {
                    "usage: notisync-gpg config set-real-gpg ABSOLUTE_PATH"
                }
                val supplied = Path.of(arguments[1])
                require(supplied.isAbsolute) { "real GPG path must be absolute" }
                val path = supplied.toRealPath().normalize()
                val current = if (Files.exists(store.path, LinkOption.NOFOLLOW_LINKS)) {
                    store.load()
                } else null
                store.save(
                    (current ?: NotisyncGpgConfig(path)).copy(realGpgPath = path)
                )
                stdout.println("Stored real GPG path in ${store.path}")
                SUCCESS
            }
            else -> throw IllegalArgumentException(
                "usage: notisync-gpg config show | config set-real-gpg ABSOLUTE_PATH"
            )
        }
    }

    private fun doctor(arguments: List<String>): Int {
        require(arguments.isEmpty()) { "usage: notisync-gpg doctor" }
        val config = NotisyncGpgConfigStore(paths.notisyncGpgConfig).load()
        val version = ProcessExecution.capture(
            listOf(config.realGpgPath.toString(), "--version"),
            maximumOutputBytes = 64 * 1024,
        )
        require(version.exitCode == 0) { "real GPG failed its version check" }
        val daemon = UnixDaemonClient(paths.socket).status()
        require(!daemon.clientId.isNullOrBlank()) { "notisyncd has no local identity" }
        stdout.println("Configuration: OK")
        stdout.println("Real GPG: ${config.realGpgPath}")
        stdout.println("notisyncd: ${daemon.connectionState} (${daemon.clientId})")
        stdout.println("Fallback: fail closed")
        return SUCCESS
    }

    private fun showHelp(): Int {
        stdout.println(
            """
            Usage:
              notisync-gpg config show
              notisync-gpg config set-real-gpg ABSOLUTE_PATH
              notisync-gpg doctor

            Configure this executable as Git's gpg.program after storing the absolute real-GPG path.
            Other invocations are either handled as Git commit signing or delegated unchanged to real GPG.
            """.trimIndent()
        )
        return SUCCESS
    }

    private fun invokeGpg(arguments: List<String>): Int {
        val config = NotisyncGpgConfigStore(paths.notisyncGpgConfig).load()
        return when (val invocation = GitSigningInvocationParser.parse(arguments)) {
            GitSigningInvocation.Delegate -> ProcessExecution.delegate(config.realGpgPath, arguments)
            is GitSigningInvocation.Remote -> {
                val certificate = GpgKeyResolver(config.realGpgPath).resolve(invocation.selector)
                val payload = readBounded(System.`in`, config.maximumPayloadBytes)
                if (runCatching { GitCommitPayloadParser.parse(payload) }.isFailure) {
                    return ProcessExecution.delegate(config.realGpgPath, arguments, payload)
                }
                when (val outcome = RemoteSigningClient(paths, config).sign(payload, certificate)) {
                    is RemoteSigningOutcome.Rejected -> throw IllegalStateException(
                        "signing request rejected (${outcome.reason})"
                    )
                    is RemoteSigningOutcome.Signed -> {
                        stdout.write(outcome.signature.armor.encodeToByteArray())
                        stdout.flush()
                        stderr.println(outcome.signature.sigCreatedStatus)
                        stderr.flush()
                        SUCCESS
                    }
                }
            }
        }
    }

    private fun readBounded(input: java.io.InputStream, maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size() + read <= maximumBytes) { "Git commit payload exceeds configured bound" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun boundedDiagnostic(message: String?): String {
        val neutral = message.orEmpty().ifBlank { "operation failed" }
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .filterNot(Char::isISOControl)
        return neutral.take(MAX_DIAGNOSTIC_CHARS)
    }

    private companion object {
        const val SUCCESS = 0
        const val GENERAL_FAILURE = 2
        const val MAX_DIAGNOSTIC_CHARS = 512
    }
}
