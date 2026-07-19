package net.extrawdw.notisync.run.process

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import net.extrawdw.notisync.desktop.config.PtyMode

interface ManagedChild : AutoCloseable {
    val pid: Long
    val input: OutputStream
    val mergedOutput: InputStream
    val usesPty: Boolean
    fun waitFor(): Int
    fun isAlive(): Boolean
    fun resize(columns: Int, rows: Int)
    fun signal(signal: ChildSignal)
    /** Returns false when this child implementation cannot deliver an otherwise valid arbitrary signal. */
    fun signal(signal: String): Boolean = when (signal.uppercase().removePrefix("SIG")) {
        ChildSignal.INTERRUPT.unixName -> true.also { signal(ChildSignal.INTERRUPT) }
        ChildSignal.TERMINATE.unixName -> true.also { signal(ChildSignal.TERMINATE) }
        else -> false
    }
    override fun close()
}

enum class ChildSignal(val unixName: String) { INTERRUPT("INT"), TERMINATE("TERM") }

fun interface ChildProcessLauncher {
    fun launch(
        command: List<String>,
        workingDirectory: Path,
        environment: Map<String, String>,
        mode: PtyMode,
    ): ManagedChild
}

class ChildLauncher(
    private val terminalAttached: () -> Boolean = { System.console() != null },
    private val ptyFactory: PtyFactory = ReflectivePtyFactory,
    private val inspector: ProcessInspector = ProcessInspectors.system(),
) : ChildProcessLauncher {
    override fun launch(
        command: List<String>,
        workingDirectory: Path,
        environment: Map<String, String>,
        mode: PtyMode,
    ): ManagedChild {
        require(command.isNotEmpty())
        val usePty = mode == PtyMode.ALWAYS || (mode == PtyMode.AUTO && terminalAttached())
        if (usePty) {
            try {
                val ptyEnvironment = environment.toMutableMap().apply { putIfAbsent("TERM", "xterm-256color") }
                return ProcessManagedChild(
                    ptyFactory.start(command, workingDirectory, ptyEnvironment),
                    usesPty = true,
                    inspector = inspector,
                )
            } catch (error: Throwable) {
                if (mode == PtyMode.ALWAYS) throw IllegalStateException("unable to start PTY", error)
            }
        }
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(environment)
            }
            .start()
        return ProcessManagedChild(process, usesPty = false, inspector = inspector)
    }
}

fun interface PtyFactory {
    fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): Process
}

/** Reflection keeps this code source-compatible across PTY4J minor builder API changes. */
object ReflectivePtyFactory : PtyFactory {
    override fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): Process {
        val type = Class.forName("com.pty4j.PtyProcessBuilder")
        var builder = type.getConstructor(Array<String>::class.java).newInstance(command.toTypedArray())
        fun invoke(name: String, parameter: Class<*>, value: Any) {
            builder = type.getMethod(name, parameter).invoke(builder, value)
        }
        invoke("setDirectory", String::class.java, workingDirectory.toAbsolutePath().toString())
        invoke("setEnvironment", Map::class.java, environment)
        invoke("setRedirectErrorStream", Boolean::class.javaPrimitiveType!!, true)
        return type.getMethod("start").invoke(builder) as Process
    }
}

private class ProcessManagedChild(
    private val process: Process,
    override val usesPty: Boolean,
    private val inspector: ProcessInspector,
) : ManagedChild {
    override val pid: Long get() = process.pid()
    override val input: OutputStream get() = process.outputStream
    override val mergedOutput: InputStream get() = process.inputStream

    override fun waitFor(): Int = process.waitFor()
    override fun isAlive(): Boolean = process.isAlive

    override fun resize(columns: Int, rows: Int) {
        if (!usesPty) return
        runCatching {
            val winSize = Class.forName("com.pty4j.WinSize")
                .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(columns, rows)
            process.javaClass.getMethod("setWinSize", winSize.javaClass).invoke(process, winSize)
        }
    }

    override fun signal(signal: ChildSignal) {
        signal(signal.unixName)
    }

    override fun signal(signal: String): Boolean {
        val signalName = signal.uppercase().removePrefix("SIG")
        if (!signalName.isSafeSignal()) return false
        val snapshot = inspector.snapshot(pid)
        val processGroup = snapshot?.foregroundProcessGroupId
            ?.takeIf { it > 0 }
            ?: snapshot?.processGroupId?.takeIf { it > 0 }
        val ownProcessGroup = inspector.currentProcessGroupId()
        // A non-PTY child normally inherits nsrun's process group. Negative kill in that case would
        // signal nsrun itself, so address only the child tree until it enters a distinct group.
        val delivered = if (processGroup != null && processGroup != ownProcessGroup) {
            val stopped = inspector.foregroundProcessGroup(pid).any {
                it.processGroupId == processGroup && it.state == 'T'
            }
            sendUnixGroupSignal(processGroup, signalName).also { sent ->
                // INT/TERM remains pending for a stopped foreground job. Resume only a group that
                // was actually stopped: SIGCONT is observable by handlers on a running process.
                if (sent && stopped && signalName in RESUME_AFTER_SIGNALS) {
                    sendUnixGroupSignal(processGroup, "CONT")
                }
            }
        } else {
            sendChildTreeSignal(signalName)
        }
        if (delivered) return true
        return when (signalName) {
            "TERM" -> {
                process.descendants().toList().asReversed().forEach { it.destroy() }
                process.destroy()
                true
            }
            "KILL" -> {
                process.descendants().toList().asReversed().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                true
            }
            else -> false
        }
    }

    private fun sendUnixGroupSignal(processGroup: Long, signalName: String): Boolean = runCatching {
        val kill = Path.of("/bin/kill")
        if (!java.nio.file.Files.isExecutable(kill)) return@runCatching false
        ProcessBuilder(kill.toString(), "-$signalName", "-$processGroup")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    }.getOrDefault(false)

    private fun sendChildTreeSignal(signalName: String): Boolean {
        val targets = process.descendants().map(ProcessHandle::pid).toList().asReversed() + process.pid()
        val stopped = targets.any { inspector.snapshot(it)?.state == 'T' }
        var delivered = false
        for (target in targets) delivered = sendUnixPidSignal(target, signalName) || delivered
        if (delivered && stopped && signalName in RESUME_AFTER_SIGNALS) {
            targets.forEach { sendUnixPidSignal(it, "CONT") }
        }
        return delivered
    }

    private fun sendUnixPidSignal(pid: Long, signalName: String): Boolean = runCatching {
        val kill = Path.of("/bin/kill")
        if (!java.nio.file.Files.isExecutable(kill)) return@runCatching false
        ProcessBuilder(kill.toString(), "-$signalName", pid.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    }.getOrDefault(false)

    override fun close() {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun String.isSafeSignal(): Boolean = when {
        isEmpty() || length > 64 -> false
        all(Char::isDigit) -> true
        else -> first().isLetter() && all { it.isLetterOrDigit() || it == '_' || it == '+' || it == '-' }
    }

    private companion object {
        val RESUME_AFTER_SIGNALS = setOf("INT", "TERM", "KILL")
    }
}
