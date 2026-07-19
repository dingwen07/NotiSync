package net.extrawdw.notisync.run

import java.time.Duration
import net.extrawdw.notisync.desktop.config.LlmConfig
import net.extrawdw.notisync.desktop.config.NSRunConfig
import net.extrawdw.notisync.desktop.config.NSRunConfigStore
import net.extrawdw.notisync.desktop.config.PtyMode

data class RunOptions(
    val command: List<String>,
    val llmEnabled: Boolean,
    val updateInterval: Duration,
    val stuckAfter: Duration?,
    val ptyMode: PtyMode,
    val config: NSRunConfig,
)

sealed interface CliInvocation {
    data class Run(val options: RunOptions) : CliInvocation
    data class Config(val arguments: List<String>) : CliInvocation
    data object Help : CliInvocation
}

object NSRunCli {
    fun parse(arguments: Array<String>, config: NSRunConfig): CliInvocation {
        if (arguments.isEmpty()) throw CliUsageException("missing command")
        if (arguments[0] == "config") return CliInvocation.Config(arguments.drop(1))
        if (arguments[0] == "--help" || arguments[0] == "-h") return CliInvocation.Help

        var llm = false
        var update = Duration.ofSeconds(config.updateIntervalSeconds)
        var stuck = config.stuckAfterSeconds?.let(Duration::ofSeconds)
        var pty = config.pty
        var index = 0
        var optionsEnded = false
        while (index < arguments.size && !optionsEnded) {
            when (val argument = arguments[index]) {
                "--" -> {
                    optionsEnded = true
                    index++
                }
                "--llm" -> {
                    llm = true
                    index++
                }
                "--update-interval" -> {
                    update = parseDuration(arguments.valueAfter(index, argument), allowOff = false)!!
                    index += 2
                }
                "--stuck-after" -> {
                    stuck = parseDuration(arguments.valueAfter(index, argument), allowOff = true)
                    index += 2
                }
                "--pty" -> {
                    pty = parsePty(arguments.valueAfter(index, argument))
                    index += 2
                }
                else -> {
                    if (argument.startsWith('-')) throw CliUsageException("unknown option: $argument")
                    optionsEnded = true
                }
            }
        }
        val command = arguments.drop(index)
        if (command.isEmpty()) throw CliUsageException("missing command")
        if (update < Duration.ofSeconds(5) || update > Duration.ofDays(1)) {
            throw CliUsageException("--update-interval must be between 5s and 1d")
        }
        if (stuck != null && (stuck < Duration.ofSeconds(10) || stuck > Duration.ofDays(7))) {
            throw CliUsageException("--stuck-after must be off or between 10s and 7d")
        }
        if (llm && config.llm == null) {
            throw CliUsageException("--llm requires LLM settings in nsrun.conf (use `nsrun config set llm ...`)")
        }
        return CliInvocation.Run(
            RunOptions(command, llm, update, stuck, pty, config),
        )
    }

    fun usage(): String = """
        Usage: nsrun [options] [--] command [args...]

        Options:
          --llm                       Generate content with configured OpenAI-compatible API
          --update-interval DURATION  Periodic update interval (default: 30s)
          --stuck-after DURATION|off  Silence-only stuck threshold (default: 5m)
          --pty auto|always|never     PTY selection (default: auto)

        Config:
          nsrun config get
          nsrun config set updateInterval DURATION
          nsrun config set stuckAfter DURATION|off
          nsrun config set pty auto|always|never
          nsrun config set logRetentionDays DAYS
          nsrun config set logMaxBytes BYTES
          nsrun config set llm BASE_URL MODEL API_KEY
          nsrun config set llm.clear
    """.trimIndent()
}

class NSRunConfigCommand(private val store: NSRunConfigStore) {
    fun execute(arguments: List<String>, output: Appendable) {
        when (arguments.firstOrNull()) {
            "get" -> output.append(store.render())
            "set" -> set(arguments.drop(1), output)
            else -> throw CliUsageException("config requires get or set")
        }
    }

    private fun set(arguments: List<String>, output: Appendable) {
        val key = arguments.firstOrNull() ?: throw CliUsageException("config set requires a key")
        val old = store.load()
        val updated = when (key) {
            "updateInterval" -> old.copy(
                updateIntervalSeconds = parseDuration(arguments.singleValue(key), false)!!.seconds,
            )
            "stuckAfter" -> old.copy(
                stuckAfterSeconds = parseDuration(arguments.singleValue(key), true)?.seconds,
            )
            "pty" -> old.copy(pty = parsePty(arguments.singleValue(key)))
            "logRetentionDays" -> old.copy(logRetentionDays = arguments.singleValue(key).toIntOrNull()
                ?: throw CliUsageException("logRetentionDays must be an integer"))
            "logMaxBytes" -> old.copy(logMaxBytes = parseByteCount(arguments.singleValue(key)))
            "llm" -> {
                if (arguments.size != 4) throw CliUsageException("config set llm requires BASE_URL MODEL API_KEY")
                old.copy(llm = LlmConfig(arguments[1], arguments[2], arguments[3]))
            }
            "llm.clear" -> {
                if (arguments.size != 1) throw CliUsageException("llm.clear takes no value")
                old.copy(llm = null)
            }
            else -> throw CliUsageException("unknown nsrun config key: $key")
        }.validate()
        store.save(updated)
        output.appendLine("updated ${store.path}")
    }
}

class CliUsageException(message: String) : IllegalArgumentException(message)

internal fun parseDuration(text: String, allowOff: Boolean): Duration? {
    if (allowOff && text.equals("off", ignoreCase = true)) return null
    val match = Regex("^(\\d+)(s|m|h|d)$", RegexOption.IGNORE_CASE).matchEntire(text)
        ?: throw CliUsageException("invalid duration: $text (expected 30s, 5m, 1h, or off)")
    val amount = match.groupValues[1].toLong()
    return when (match.groupValues[2].lowercase()) {
        "s" -> Duration.ofSeconds(amount)
        "m" -> Duration.ofMinutes(amount)
        "h" -> Duration.ofHours(amount)
        "d" -> Duration.ofDays(amount)
        else -> error("unreachable")
    }
}

private fun parsePty(value: String): PtyMode = when (value.lowercase()) {
    "auto" -> PtyMode.AUTO
    "always" -> PtyMode.ALWAYS
    "never" -> PtyMode.NEVER
    else -> throw CliUsageException("invalid PTY mode: $value")
}

private fun parseByteCount(value: String): Long {
    val match = Regex("^(\\d+)(B|KiB|MiB|GiB)?$", RegexOption.IGNORE_CASE).matchEntire(value)
        ?: throw CliUsageException("invalid byte count: $value")
    val multiplier = when (match.groupValues[2].lowercase()) {
        "", "b" -> 1L
        "kib" -> 1024L
        "mib" -> 1024L * 1024
        "gib" -> 1024L * 1024 * 1024
        else -> error("unreachable")
    }
    return Math.multiplyExact(match.groupValues[1].toLong(), multiplier)
}

private fun Array<String>.valueAfter(index: Int, option: String): String =
    getOrNull(index + 1) ?: throw CliUsageException("$option requires a value")

private fun List<String>.singleValue(key: String): String {
    if (size != 2) throw CliUsageException("config set $key requires one value")
    return this[1]
}
