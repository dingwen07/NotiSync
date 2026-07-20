package net.extrawdw.notisync.desktop.config

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.PrivateFiles

@Serializable
enum class PtyMode { AUTO, ALWAYS, NEVER }

@Serializable
data class LlmConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val timeoutSeconds: Int = 8,
    val treeDepth: Int = 2,
    val treeEntries: Int = 200,
    val treeBytes: Int = 32 * 1024,
    val outputBytes: Int = 16 * 1024,
) {
    fun validate(): LlmConfig = apply {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "llm-base-url must use http:// or https://"
        }
        require(model.isNotBlank()) { "llm-model must not be blank" }
        require(apiKey.isNotBlank()) { "llm-api-key must not be blank" }
        require(timeoutSeconds in 1..120) { "llm-timeout-seconds must be between 1 and 120" }
        require(treeDepth in 0..2) { "llm-tree-depth must be between 0 and 2" }
        require(treeEntries in 0..200) { "llm-tree-entries must be between 0 and 200" }
        require(treeBytes in 0..32 * 1024) { "llm-tree-bytes must be at most 32768" }
        require(outputBytes in 0..16 * 1024) { "llm-output-bytes must be at most 16384" }
    }
}

@Serializable
data class NSRunConfig(
    val updateIntervalSeconds: Long = 30,
    /** Null disables silence-only stuck detection; direct terminal waits are still detected. */
    val stuckAfterSeconds: Long? = 5 * 60,
    val pty: PtyMode = PtyMode.AUTO,
    val logRetentionDays: Int = 30,
    val logMaxBytes: Long = 100L * 1024 * 1024,
    val llm: LlmConfig? = null,
) {
    fun validate(): NSRunConfig = apply {
        require(updateIntervalSeconds in 5..86_400) { "update-interval-seconds must be between 5 and 86400" }
        require(stuckAfterSeconds == null || stuckAfterSeconds in 10..604_800) {
            "stuck-after-seconds must be off or between 10 and 604800"
        }
        require(logRetentionDays in 0..3650) { "log-retention-days must be between 0 and 3650" }
        require(logMaxBytes in 0..10L * 1024 * 1024 * 1024) { "log-max-bytes is out of range" }
        llm?.validate()
    }
}

class NSRunConfigStore(
    val path: Path = DesktopPaths.default().nsrunConfig,
) {
    fun load(): NSRunConfig = readConfig(path, ::NSRunConfig, NSRunConfigCodec::decode).validate()

    fun save(value: NSRunConfig) {
        PrivateFiles.atomicWrite(path, NSRunConfigCodec.encode(value.validate()).encodeToByteArray())
    }

    fun render(value: NSRunConfig = load()): String = NSRunConfigCodec.encode(value.validate())

    /**
     * Runtime-only recovery path. [load] remains strict for config management and tests, while an
     * unrelated malformed Run configuration must not prevent the requested child from starting.
     */
    fun loadRecovering(onRecovery: (String) -> Unit = {}): NSRunConfig = try {
        load()
    } catch (original: Exception) {
        recoverMalformed(path, ::NSRunConfig, ::save, original, onRecovery)
    }
}

private fun <T> recoverMalformed(
    path: Path,
    defaults: () -> T,
    save: (T) -> Unit,
    original: Exception,
    onRecovery: (String) -> Unit,
): T {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return defaults()

    // A symlink, wrong owner, or non-regular node is never merely malformed configuration.
    PrivateFiles.validatePrivateFile(path)
    val backup = path.resolveSibling(
        "${path.fileName}.corrupt-${Instant.now().toEpochMilli()}-${UUID.randomUUID()}",
    )
    Files.move(path, backup, StandardCopyOption.ATOMIC_MOVE)
    PrivateFiles.validatePrivateFile(backup)
    val recovered = defaults()
    save(recovered)
    onRecovery("Invalid ${path.fileName} was moved to $backup; using safe defaults (${original.message})")
    return recovered
}

private fun <T> readConfig(path: Path, default: () -> T, decode: (String, Path) -> T): T {
    PrivateFiles.ensureDirectory(requireNotNull(path.parent))
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return default()
    PrivateFiles.validatePrivateFile(path)
    return try {
        decode(Files.readString(path), path)
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw IllegalArgumentException("invalid configuration in $path: ${error.message}", error)
    }
}

internal object NSRunConfigCodec {
    private val options = setOf(
        "update-interval-seconds",
        "stuck-after-seconds",
        "pty",
        "log-retention-days",
        "log-max-bytes",
        "llm-base-url",
        "llm-model",
        "llm-api-key",
        "llm-timeout-seconds",
        "llm-tree-depth",
        "llm-tree-entries",
        "llm-tree-bytes",
        "llm-output-bytes",
    )
    private val llmOptions = options.filterTo(linkedSetOf()) { it.startsWith("llm-") }

    fun decode(text: String, path: Path): NSRunConfig {
        val values = LineConfig.parse(text, path, options)
        val defaults = NSRunConfig()
        val presentLlm = values.keys.intersect(llmOptions)
        val llm = if (presentLlm.isEmpty()) null else {
            val required = setOf("llm-base-url", "llm-model", "llm-api-key")
            val missing = required - values.keys
            require(missing.isEmpty()) {
                "$path: incomplete LLM configuration; missing ${missing.sorted().joinToString()}"
            }
            LlmConfig(
                baseUrl = requireNotNull(values.string("llm-base-url")),
                model = requireNotNull(values.string("llm-model")),
                apiKey = requireNotNull(values.string("llm-api-key")),
                timeoutSeconds = values.int("llm-timeout-seconds") ?: 8,
                treeDepth = values.int("llm-tree-depth") ?: 2,
                treeEntries = values.int("llm-tree-entries") ?: 200,
                treeBytes = values.int("llm-tree-bytes") ?: 32 * 1024,
                outputBytes = values.int("llm-output-bytes") ?: 16 * 1024,
            ).validate()
        }
        return NSRunConfig(
            updateIntervalSeconds = values.long("update-interval-seconds") ?: defaults.updateIntervalSeconds,
            stuckAfterSeconds = when (val entry = values.entry("stuck-after-seconds")) {
                null -> defaults.stuckAfterSeconds
                else -> if (entry.value.equals("off", true)) null else entry.long("stuck-after-seconds")
            },
            pty = values.entry("pty")?.let { entry ->
                runCatching { PtyMode.valueOf(entry.value.uppercase()) }.getOrElse {
                    throw entry.error("pty must be auto, always, or never")
                }
            } ?: defaults.pty,
            logRetentionDays = values.int("log-retention-days") ?: defaults.logRetentionDays,
            logMaxBytes = values.long("log-max-bytes") ?: defaults.logMaxBytes,
            llm = llm,
        ).validate()
    }

    fun encode(value: NSRunConfig): String = buildString {
        appendLine("# NotiSync Run configuration")
        appendLine("# This file is never read or exposed by notisyncd.")
        option("update-interval-seconds", value.updateIntervalSeconds.toString(), false)
        option("stuck-after-seconds", value.stuckAfterSeconds?.toString() ?: "off", false)
        option("pty", value.pty.name.lowercase(), false)
        option("log-retention-days", value.logRetentionDays.toString(), false)
        option("log-max-bytes", value.logMaxBytes.toString(), false)
        value.llm?.let { llm ->
            appendLine()
            appendLine("# OpenAI-compatible content generation (used only with nsrun --llm)")
            option("llm-base-url", llm.baseUrl)
            option("llm-model", llm.model)
            option("llm-api-key", llm.apiKey)
            option("llm-timeout-seconds", llm.timeoutSeconds.toString(), false)
            option("llm-tree-depth", llm.treeDepth.toString(), false)
            option("llm-tree-entries", llm.treeEntries.toString(), false)
            option("llm-tree-bytes", llm.treeBytes.toString(), false)
            option("llm-output-bytes", llm.outputBytes.toString(), false)
        }
    }
}

private data class ConfigEntry(val value: String, val path: Path, val line: Int) {
    fun error(message: String) = IllegalArgumentException("$path:$line: $message")
    fun long(option: String): Long = value.toLongOrNull() ?: throw error("$option requires an integer")
}

private class ConfigValues(private val values: Map<String, ConfigEntry>) {
    val keys: Set<String> get() = values.keys
    fun entry(option: String): ConfigEntry? = values[option]
    fun string(option: String): String? = values[option]?.value
    fun long(option: String): Long? = values[option]?.long(option)
    fun int(option: String): Int? = values[option]?.let { entry ->
        entry.value.toIntOrNull() ?: throw entry.error("$option requires a 32-bit integer")
    }
}

private object LineConfig {
    fun parse(text: String, path: Path, allowedOptions: Set<String>): ConfigValues {
        val values = linkedMapOf<String, ConfigEntry>()
        text.lineSequence().forEachIndexed { index, raw ->
            val lineNumber = index + 1
            val line = raw.trimStart()
            if (line.isBlank() || line.startsWith('#')) return@forEachIndexed
            val optionEnd = line.indexOfFirst(Char::isWhitespace).takeIf { it >= 0 } ?: line.length
            val option = line.substring(0, optionEnd)
            require(OPTION.matches(option)) { "$path:$lineNumber: invalid option name '$option'" }
            require(option in allowedOptions) { "$path:$lineNumber: unknown option '$option'" }
            require(option !in values) {
                "$path:$lineNumber: duplicate option '$option' (first set on line ${values[option]?.line})"
            }
            val remainder = line.substring(optionEnd).trimStart()
            require(remainder.isNotEmpty() && !remainder.startsWith('#')) {
                "$path:$lineNumber: option '$option' requires a value"
            }
            values[option] = ConfigEntry(parseValue(remainder, path, lineNumber), path, lineNumber)
        }
        return ConfigValues(values)
    }

    private fun parseValue(text: String, path: Path, line: Int): String {
        if (!text.startsWith('"')) {
            val comment = text.indices.firstOrNull { index ->
                text[index] == '#' && index > 0 && text[index - 1].isWhitespace()
            }
            val value = text.substring(0, comment ?: text.length).trimEnd()
            require(value.isNotEmpty()) { "$path:$line: value must not be empty" }
            return value
        }
        val value = StringBuilder()
        var index = 1
        while (index < text.length) {
            val character = text[index++]
            when (character) {
                '"' -> {
                    val trailing = text.substring(index).trimStart()
                    require(trailing.isEmpty() || trailing.startsWith('#')) {
                        "$path:$line: unexpected text after quoted value"
                    }
                    return value.toString()
                }
                '\\' -> {
                    require(index < text.length) { "$path:$line: incomplete escape" }
                    when (val escaped = text[index++]) {
                        '\\', '"' -> value.append(escaped)
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            require(index + 4 <= text.length) { "$path:$line: incomplete unicode escape" }
                            val digits = text.substring(index, index + 4)
                            val code = digits.toIntOrNull(16)
                                ?: throw IllegalArgumentException("$path:$line: invalid unicode escape \\u$digits")
                            value.append(code.toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("$path:$line: unsupported escape \\$escaped")
                    }
                }
                else -> {
                    require(character >= ' ') { "$path:$line: unescaped control character" }
                    value.append(character)
                }
            }
        }
        throw IllegalArgumentException("$path:$line: unterminated quoted value")
    }

    private val OPTION = Regex("[a-z][a-z0-9-]*")
}

private fun StringBuilder.option(name: String, value: String, quote: Boolean = true) {
    append(name).append(' ')
    if (quote) appendQuoted(value) else append(value)
    appendLine()
}

private fun StringBuilder.appendQuoted(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}
