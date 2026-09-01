package net.extrawdw.notisync.desktop

import java.nio.file.Files
import java.nio.file.Path

/** Resolves a best-effort process display name independently of its executable path. */
class DesktopProcessNameResolver(
    osName: String = System.getProperty("os.name"),
    private val procRoot: Path = Path.of("/proc"),
) {
    private val isLinux = osName.contains("linux", ignoreCase = true)

    fun resolve(pid: Long): String? {
        if (pid <= 0 || !isLinux) return null
        return runCatching {
            Files.readString(procRoot.resolve(pid.toString()).resolve("comm"))
                .trim()
                .takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        }.getOrNull()
    }
}
