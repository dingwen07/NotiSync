package net.extrawdw.notisync.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform

/**
 * Resolves a process executable without making the process arguments a prerequisite.
 *
 * macOS OpenJDK obtains [ProcessHandle.Info.command] from `KERN_PROCARGS2`, which also exposes the
 * complete argument vector and is commonly restricted for processes owned by another user. The
 * narrower `proc_pidpath` API can still provide the executable in those cases.
 */
class DesktopProcessExecutableResolver(
    osName: String = System.getProperty("os.name"),
    private val portableCommand: (Long) -> String? = { pid ->
        ProcessHandle.of(pid).orElse(null)?.info()?.command()?.orElse(null)
    },
    private val macProcessPath: (Long) -> String? = MacProcessPath::resolve,
) {
    private val isMacOs = osName.contains("mac", ignoreCase = true)

    fun resolve(pid: Long): String? {
        if (pid <= 0) return null
        return runCatching {
            if (isMacOs) macProcessPath(pid) else portableCommand(pid)
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private object MacProcessPath {
        fun resolve(pid: Long): String? {
            if (!Platform.isMac() || pid > Int.MAX_VALUE) return null
            Memory(PROC_PIDPATHINFO_MAXSIZE.toLong()).use { buffer ->
                buffer.clear()
                val length = libProc.proc_pidpath(pid.toInt(), buffer, PROC_PIDPATHINFO_MAXSIZE)
                return if (length > 0) buffer.getString(0).takeIf(String::isNotBlank) else null
            }
        }

        private interface LibProc : Library {
            fun proc_pidpath(pid: Int, buffer: Memory, bufferSize: Int): Int
        }

        private val libProc: LibProc by lazy {
            Native.load(Platform.C_LIBRARY_NAME, LibProc::class.java)
        }

        private const val PROC_PIDPATHINFO_MAXSIZE = 4_096
    }
}
