package net.extrawdw.notisync.gpg

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.PrivateFiles
import net.extrawdw.notisync.protocol.OpenPgpSignLimits

enum class UnavailableFallback { FAIL_CLOSED }

data class NotisyncGpgConfig(
    val realGpgPath: Path,
    val timeoutSeconds: Int = 120,
    val maximumPayloadBytes: Int = OpenPgpSignLimits.MAX_PAYLOAD_BYTES,
    val unavailableFallback: UnavailableFallback = UnavailableFallback.FAIL_CLOSED,
) {
    fun validate(): NotisyncGpgConfig = apply {
        require(realGpgPath.isAbsolute) { "real-gpg-path must be absolute" }
        require(Files.isRegularFile(realGpgPath)) {
            "real-gpg-path is not a regular file"
        }
        if (!System.getProperty("os.name").contains("windows", ignoreCase = true)) {
            require(Files.isExecutable(realGpgPath)) { "real-gpg-path is not executable" }
        }
        require(!realGpgPath.toRealPath().isNotisyncGpgExecutable()) {
            "real-gpg-path must not resolve to notisync-gpg"
        }
        require(timeoutSeconds in 30..300) { "timeout-seconds must be between 30 and 300" }
        require(maximumPayloadBytes in 1..OpenPgpSignLimits.MAX_PAYLOAD_BYTES) {
            "maximum-payload-bytes must be between 1 and ${OpenPgpSignLimits.MAX_PAYLOAD_BYTES}"
        }
        require(unavailableFallback == UnavailableFallback.FAIL_CLOSED) {
            "only fail-closed is supported"
        }
    }
}

class NotisyncGpgConfigStore(
    val path: Path = DesktopPaths.default().notisyncGpgConfig,
) {
    fun load(): NotisyncGpgConfig {
        require(configExists()) {
            "configuration is missing; run 'notisync-gpg config set-real-gpg ABSOLUTE_PATH'"
        }
        return read().validate()
    }

    fun loadUsing(realGpgPath: Path): NotisyncGpgConfig {
        val config = if (configExists()) {
            read().copy(realGpgPath = realGpgPath)
        } else {
            NotisyncGpgConfig(realGpgPath)
        }
        return config.validate()
    }

    fun save(config: NotisyncGpgConfig) {
        PrivateFiles.atomicWrite(path, encode(config.validate()).encodeToByteArray())
    }

    private fun configExists(): Boolean {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    }

    private fun read(): NotisyncGpgConfig {
        PrivateFiles.validatePrivateFile(path)
        return decode(Files.readString(path), path)
    }

    internal fun decode(text: String, source: Path = path): NotisyncGpgConfig {
        val values = linkedMapOf<String, String>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val split = line.indexOf(' ')
            require(split > 0) { "$source:${index + 1}: expected option and value" }
            val key = line.substring(0, split)
            require(key in OPTIONS) { "$source:${index + 1}: unknown option '$key'" }
            require(key !in values) { "$source:${index + 1}: duplicate option '$key'" }
            values[key] = unquote(line.substring(split + 1).trim(), source, index + 1)
        }
        val real = values["real-gpg-path"]
            ?: throw IllegalArgumentException("$source: real-gpg-path is required")
        return NotisyncGpgConfig(
            realGpgPath = Path.of(real),
            timeoutSeconds = values["timeout-seconds"]?.toIntOrNull()
                ?: values["timeout-seconds"]?.let { throw IllegalArgumentException("$source: invalid timeout-seconds") }
                ?: 120,
            maximumPayloadBytes = values["maximum-payload-bytes"]?.toIntOrNull()
                ?: values["maximum-payload-bytes"]?.let {
                    throw IllegalArgumentException("$source: invalid maximum-payload-bytes")
                }
                ?: OpenPgpSignLimits.MAX_PAYLOAD_BYTES,
            unavailableFallback = when (values["unavailable-fallback"]?.lowercase() ?: "fail-closed") {
                "fail-closed" -> UnavailableFallback.FAIL_CLOSED
                else -> throw IllegalArgumentException("$source: unavailable-fallback must be fail-closed")
            },
        )
    }

    internal fun encode(config: NotisyncGpgConfig): String = buildString {
        appendLine("# NotiSync Seal Git signing adapter configuration")
        appendLine("# Private desktop state; permissions must remain owner-only.")
        append("real-gpg-path \"").append(escape(config.realGpgPath.toString())).appendLine("\"")
        appendLine("timeout-seconds ${config.timeoutSeconds}")
        appendLine("maximum-payload-bytes ${config.maximumPayloadBytes}")
        appendLine("unavailable-fallback fail-closed")
    }

    private fun unquote(value: String, source: Path, line: Int): String {
        if (!value.startsWith('"')) return value.substringBefore(" #").trimEnd()
        require(value.endsWith('"') && value.length >= 2) { "$source:$line: unterminated quoted value" }
        val inner = value.substring(1, value.lastIndex)
        val result = StringBuilder()
        var index = 0
        while (index < inner.length) {
            val character = inner[index++]
            if (character != '\\') result.append(character)
            else {
                require(index < inner.length) { "$source:$line: trailing escape" }
                result.append(when (val escaped = inner[index++]) {
                    '\\', '"' -> escaped
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> throw IllegalArgumentException("$source:$line: unsupported escape \\$escaped")
                })
            }
        }
        return result.toString()
    }

    private fun escape(value: String): String = buildString {
        value.forEach { character ->
            append(when (character) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> character
            })
        }
    }

    private companion object {
        val OPTIONS = setOf(
            "real-gpg-path",
            "timeout-seconds",
            "maximum-payload-bytes",
            "unavailable-fallback",
        )
    }
}
