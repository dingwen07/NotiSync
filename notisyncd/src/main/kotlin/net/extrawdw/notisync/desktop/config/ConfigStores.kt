package net.extrawdw.notisync.desktop.config

import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.PrivateFiles

private const val DEFAULT_BROKER_URL = "https://notisync-api-v2.extrawdw.net"
private const val DEFAULT_AUTO_APPLY_TRUSTED_DEVICE_TABLES = false
private const val DEFAULT_LOG_LEVEL = "WARN"
private const val DEFAULT_WEBSOCKET_PING_SECONDS = 30
const val NOTISYNCD_PLATFORM_NAME = "desktop"

@Serializable
data class NotisyncdConfig(
    val brokerUrl: String = DEFAULT_BROKER_URL,
    val deviceName: String = defaultDeviceName(),
    val automaticallyApplyTrustedDeviceTables: Boolean = DEFAULT_AUTO_APPLY_TRUSTED_DEVICE_TABLES,
    val logLevel: String = DEFAULT_LOG_LEVEL,
    val websocketPingSeconds: Int = DEFAULT_WEBSOCKET_PING_SECONDS,
    val unverifiedDeviceCleanupV1Completed: Boolean = false,
) {
    fun validate(): NotisyncdConfig = apply {
        require(
            brokerUrl.startsWith("http://") || brokerUrl.startsWith("https://") ||
                brokerUrl.startsWith("ws://") || brokerUrl.startsWith("wss://"),
        ) {
            "broker-url must use http://, https://, ws://, or wss://"
        }
        require(deviceName.isNotBlank()) { "device-name must not be blank" }
        require(logLevel.uppercase() in setOf("TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "OFF")) {
            "log-level must be trace, debug, info, warn, error, or off"
        }
        require(websocketPingSeconds in 5..300) { "websocket-ping-seconds must be between 5 and 300" }
    }
}

interface ConfigStore<T> {
    val path: Path
    fun load(): T
    fun save(value: T)
}

class NotisyncdConfigStore(
    override val path: Path = DesktopPaths.default().daemonConfig,
) : ConfigStore<NotisyncdConfig> {
    override fun load(): NotisyncdConfig {
        val loaded = readConfig(path, ::NotisyncdConfig, NotisyncdConfigCodec::decode).validate()
        val upgraded = loaded.copy(brokerUrl = upgradeLegacyDefaultBrokerUrl(loaded.brokerUrl))
        if (upgraded != loaded) save(upgraded)
        return upgraded
    }

    override fun save(value: NotisyncdConfig) {
        val upgraded = value.copy(brokerUrl = upgradeLegacyDefaultBrokerUrl(value.brokerUrl)).validate()
        PrivateFiles.atomicWrite(path, NotisyncdConfigCodec.encode(upgraded).encodeToByteArray())
    }

    /** Independent from broker migration: changing broker-url cannot re-arm this versioned cleanup. */
    fun markUnverifiedDeviceCleanupV1Completed() {
        val current = load()
        if (!current.unverifiedDeviceCleanupV1Completed) {
            save(current.copy(unverifiedDeviceCleanupV1Completed = true))
        }
    }

    fun loadRecovering(onRecovery: (String) -> Unit = {}): NotisyncdConfig = try {
        load()
    } catch (original: Exception) {
        recoverMalformed(path, ::NotisyncdConfig, ::save, original, onRecovery)
    }
}

/** Upgrade only NotiSync's former production default; test, local, and user-provided brokers are untouched. */
internal fun upgradeLegacyDefaultBrokerUrl(value: String): String {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return value
    val isFormerDefault = uri.scheme?.lowercase() in setOf("http", "https", "ws", "wss") &&
        uri.host.equals("notisync-api.extrawdw.net", ignoreCase = true) &&
        uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null &&
        (uri.path.isNullOrEmpty() || uri.path == "/")
    return if (isFormerDefault) DEFAULT_BROKER_URL else value
}

private fun <T> recoverMalformed(
    path: Path,
    defaults: () -> T,
    save: (T) -> Unit,
    original: Exception,
    onRecovery: (String) -> Unit,
): T {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return defaults()

    // Repeat the security checks outside readConfig's catch. A symlink, wrong owner, or
    // non-regular node is never treated as a merely malformed configuration.
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

internal object NotisyncdConfigCodec {
    private val options = setOf(
        "broker-url",
        "device-name",
        "auto-apply-trusted-device-tables",
        "log-level",
        "websocket-ping-seconds",
        "unverified-device-cleanup-v1-completed",
    )

    fun decode(text: String, path: Path): NotisyncdConfig = decodeWithDefaults(
        text,
        path,
        deviceNameDefault = ::defaultDeviceName,
    )

    internal fun decodeWithDefaults(
        text: String,
        path: Path,
        deviceNameDefault: () -> String,
    ): NotisyncdConfig {
        val values = LineConfig.parse(text, path, options)
        return NotisyncdConfig(
            brokerUrl = values.string("broker-url") ?: DEFAULT_BROKER_URL,
            deviceName = values.string("device-name") ?: deviceNameDefault(),
            automaticallyApplyTrustedDeviceTables = values.boolean("auto-apply-trusted-device-tables")
                ?: DEFAULT_AUTO_APPLY_TRUSTED_DEVICE_TABLES,
            logLevel = values.string("log-level") ?: DEFAULT_LOG_LEVEL,
            websocketPingSeconds = values.int("websocket-ping-seconds") ?: DEFAULT_WEBSOCKET_PING_SECONDS,
            unverifiedDeviceCleanupV1Completed =
                values.boolean("unverified-device-cleanup-v1-completed") ?: false,
        ).validate()
    }

    fun encode(value: NotisyncdConfig): String = buildString {
        appendLine("# NotiSync desktop peer daemon configuration")
        appendLine("# Managed by notisyncd config; permissions must remain 0600.")
        option("broker-url", value.brokerUrl)
        option("device-name", value.deviceName)
        option("auto-apply-trusted-device-tables", if (value.automaticallyApplyTrustedDeviceTables) "yes" else "no", false)
        option("log-level", value.logLevel)
        option("websocket-ping-seconds", value.websocketPingSeconds.toString(), false)
        option(
            "unverified-device-cleanup-v1-completed",
            if (value.unverifiedDeviceCleanupV1Completed) "yes" else "no",
            false,
        )
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
    fun boolean(option: String): Boolean? = values[option]?.let { entry ->
        when (entry.value.lowercase()) {
            "yes", "true", "on" -> true
            "no", "false", "off" -> false
            else -> throw entry.error("$option requires yes or no")
        }
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

internal fun defaultDeviceName(
    resolveHostname: () -> String? = { java.net.InetAddress.getLocalHost().hostName },
    environmentHostname: String? = System.getenv("HOSTNAME"),
): String =
    runCatching(resolveHostname).getOrNull().normalizedHostname()
        ?: environmentHostname.normalizedHostname()
        ?: "NotiSync Desktop"

private fun String?.normalizedHostname(): String? = this?.trim()?.takeIf(String::isNotEmpty)
