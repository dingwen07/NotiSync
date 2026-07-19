package net.extrawdw.notisync.daemon.logging

import java.time.Clock
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.concurrent.atomic.AtomicReference

enum class DaemonLogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    OFF;

    companion object {
        fun parse(value: String): DaemonLogLevel = when (value.uppercase()) {
            "WARNING" -> WARN
            else -> valueOf(value.uppercase())
        }
    }
}

/** Small daemon logger whose output remains useful when stdout and stderr share a rotated file. */
class DaemonLogger(
    level: String,
    private val output: Appendable = System.err,
    private val clock: Clock = Clock.systemUTC(),
    private val threadName: () -> String = { Thread.currentThread().name },
) {
    private val threshold = AtomicReference(DaemonLogLevel.parse(level))
    private val outputLock = Any()

    fun updateLevel(level: String) {
        threshold.set(DaemonLogLevel.parse(level))
    }

    fun trace(message: String) = write(DaemonLogLevel.TRACE, message)
    fun debug(message: String) = write(DaemonLogLevel.DEBUG, message)
    fun info(message: String) = write(DaemonLogLevel.INFO, message)
    fun warn(message: String) = write(DaemonLogLevel.WARN, message)
    fun error(message: String) = write(DaemonLogLevel.ERROR, message)

    private fun write(level: DaemonLogLevel, message: String) {
        if (level.ordinal < threshold.get().ordinal) return
        val timestamp = TIMESTAMP_FORMATTER.format(clock.instant())
        val currentThreadName = runCatching { threadName().trim() }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)
            ?: "unnamed"
        val prefix = "$timestamp ${level.name.padEnd(5)} [$currentThreadName] "
        val lines = message.lineSequence().toList().ifEmpty { listOf("") }
        // Logging must never take down the daemon because a redirected stream became unavailable.
        runCatching {
            synchronized(outputLock) {
                lines.forEach { line -> output.append(prefix).append(line).append('\n') }
            }
        }
    }

    private companion object {
        /** UTC ISO-8601 with an exact millisecond field, including for whole-second instants. */
        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendInstant(3)
            .toFormatter()
    }
}
