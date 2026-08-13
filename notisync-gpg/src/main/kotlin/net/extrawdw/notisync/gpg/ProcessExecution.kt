package net.extrawdw.notisync.gpg

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ProcessResult(val exitCode: Int, val output: ByteArray)

internal object ProcessExecution {
    private val readers = Executors.newCachedThreadPool { task ->
        Thread(task, "notisync-gpg-process-reader").apply { isDaemon = true }
    }

    fun capture(
        command: List<String>,
        stdin: ByteArray = ByteArray(0),
        maximumOutputBytes: Int = 2 * 1024 * 1024,
        timeout: Duration = Duration.ofSeconds(15),
    ): ProcessResult {
        require(command.isNotEmpty())
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = readers.submit(Callable {
            process.inputStream.use { input ->
                val collected = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(collected.size() + read <= maximumOutputBytes) { "process output exceeded safe bound" }
                    collected.write(buffer, 0, read)
                }
                collected.toByteArray()
            }
        })
        try {
            process.outputStream.use { stream ->
                if (stdin.isNotEmpty()) stream.write(stdin)
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("process timed out")
            }
            return ProcessResult(process.exitValue(), output.get(5, TimeUnit.SECONDS))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            output.cancel(true)
        }
    }

    fun delegate(executable: Path, arguments: List<String>, stdin: ByteArray? = null): Int {
        val builder = ProcessBuilder(listOf(executable.toString()) + arguments)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        if (stdin == null) builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
        val process = builder.start()
        if (stdin != null) process.outputStream.use { it.write(stdin) }
        return process.waitFor()
    }
}
