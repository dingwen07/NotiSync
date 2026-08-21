package net.extrawdw.notisync.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable

/**
 * A platform process-instance token used only for local PID-reuse checks.
 *
 * On Linux, [startToken] is `/proc/<pid>/stat` field 22 and [bootId] is the
 * kernel boot ID. On other desktop platforms, [startToken] is the process
 * creation instant reported by [ProcessHandle]. Callers must treat the token
 * as opaque and compare the complete value for exact equality.
 */
@Serializable
data class ProcessInstanceIdentity(
    val bootId: String? = null,
    val startToken: String,
) {
    init {
        require(bootId == null || BOOT_ID.matches(bootId)) { "invalid process boot ID" }
        require(startToken.isNotBlank() && startToken.length <= MAX_START_TOKEN_LENGTH) {
            "invalid process start token"
        }
        require(startToken.none(Char::isISOControl)) { "invalid process start token" }
    }

    private companion object {
        const val MAX_START_TOKEN_LENGTH = 128
        val BOOT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/** Resolves stable local process-instance tokens without reconstructing Linux wall-clock time. */
class ProcessInstanceIdentityResolver(
    osName: String = System.getProperty("os.name"),
    private val procRoot: Path = Path.of("/proc"),
) {
    private val osName = osName.lowercase()

    fun resolve(pid: Long): ProcessInstanceIdentity? {
        if (pid <= 0) return null
        return if (osName.contains("linux")) resolveLinux(pid) else resolvePortable(pid)
    }

    private fun resolveLinux(pid: Long): ProcessInstanceIdentity? = runCatching {
        val bootId = Files.readString(procRoot.resolve("sys/kernel/random/boot_id"))
            .trim()
            .lowercase()
            .takeIf(String::isNotBlank)
            ?: return@runCatching null
        val line = Files.readString(procRoot.resolve(pid.toString()).resolve("stat"))
        // comm is parenthesized and may contain spaces or ')'; fields after the final ')' are stable.
        val close = line.lastIndexOf(')')
        require(close >= 0)
        val remainder = line.substring(close + 1).trim().split(WHITESPACE)
        // remainder[0] is field 3 (state), therefore field 22 (starttime) is index 19.
        val startTicks = remainder.getOrNull(19)
            ?.takeIf { it.toULongOrNull()?.let { value -> value > 0u } == true }
            ?: return@runCatching null
        ProcessInstanceIdentity(bootId = bootId, startToken = startTicks)
    }.getOrNull()

    private fun resolvePortable(pid: Long): ProcessInstanceIdentity? = runCatching {
        val start = ProcessHandle.of(pid).orElse(null)
            ?.info()
            ?.startInstant()
            ?.orElse(null)
            ?: return@runCatching null
        ProcessInstanceIdentity(startToken = start.toString())
    }.getOrNull()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
