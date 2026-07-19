package net.extrawdw.notisync.run.process

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

data class TerminalSize(val columns: Int, val rows: Int)

interface TerminalController : AutoCloseable {
    fun enterRawMode()
    fun size(): TerminalSize?
    fun observeOutput(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) = Unit
    override fun close()
}

class SttyTerminalController(
    private val tty: Path = Path.of("/dev/tty"),
    private val stty: (List<String>) -> String = { arguments -> executeStty(tty, arguments) },
    private val terminalWriter: (ByteArray) -> Unit = { bytes ->
        Files.newOutputStream(tty, StandardOpenOption.WRITE).use { output ->
            output.write(bytes)
            output.flush()
        }
    },
) : TerminalController {
    private var saved: String? = null
    private val active = AtomicBoolean()
    private val termiosRestorePending = AtomicBoolean()
    private val presentation = TerminalPresentationTracker()

    @Synchronized
    override fun enterRawMode() {
        if (active.get() || termiosRestorePending.get()) return
        if (!Files.isReadable(tty)) return
        val original = runStty("-g").trim().takeIf(String::isNotBlank) ?: return
        saved = original
        termiosRestorePending.set(true)
        runStty("raw", "-echo")
        active.set(true)
    }

    override fun size(): TerminalSize? {
        if (!Files.isReadable(tty)) return null
        val parts = runCatching { runStty("size").trim().split(Regex("\\s+")) }.getOrNull()
            ?: return null
        if (parts.size != 2) return null
        val rows = parts[0].toIntOrNull() ?: return null
        val columns = parts[1].toIntOrNull() ?: return null
        return TerminalSize(columns, rows)
    }

    @Synchronized
    override fun observeOutput(bytes: ByteArray, offset: Int, length: Int) {
        presentation.accept(bytes, offset, length)
    }

    @Synchronized
    override fun close() {
        active.set(false)
        if (termiosRestorePending.compareAndSet(true, false)) {
            val restored = saved?.let { state -> runCatching { runStty(state) }.isSuccess } == true
            if (!restored) runCatching { runStty("sane") }
            saved = null
        }
        // A killed full-screen program may omit its teardown. Emit only inverses for modes that the
        // child was observed enabling and did not later disable; a healthy exit emits no bytes.
        presentation.recovery().takeIf(ByteArray::isNotEmpty)?.let { recovery ->
            runCatching { terminalWriter(recovery) }
        }
    }

    private fun runStty(vararg arguments: String): String = stty(arguments.toList())

    private companion object {
        fun executeStty(tty: Path, arguments: List<String>): String {
            val isMac = System.getProperty("os.name").contains("mac", ignoreCase = true)
            val command = mutableListOf("/bin/stty")
            if (isMac) command += listOf("-f", tty.toString()) else command += listOf("-F", tty.toString())
            command += arguments
            val process = ProcessBuilder(command)
                .redirectInput(tty.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { "stty failed" }
            return output
        }
    }
}
