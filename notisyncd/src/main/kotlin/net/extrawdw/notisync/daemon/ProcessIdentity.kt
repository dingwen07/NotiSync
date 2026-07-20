package net.extrawdw.notisync.daemon

import java.nio.file.Files
import java.nio.file.Path
import org.newsclub.net.unix.AFUNIXSocket

/** Credentials attached by the kernel to one accepted local connection. */
data class LocalPeer(
    val uid: Long,
    val pid: Long?,
    val startTime: String?,
) {
    val processIdentityVerified: Boolean get() = pid != null && startTime != null
}

/** Best-effort OS view of the executable attached to a peer PID. */
data class ProcessExecutable(
    val name: String?,
    val path: String?,
)

/**
 * Resolves the process half of the local security boundary. Linux exposes the exact boot-tick
 * start time in `/proc/<pid>/stat`; on macOS ProcessHandle is backed by libproc and gives the
 * process creation instant. The value is intentionally opaque -- only exact equality matters.
 */
class ProcessIdentityResolver(
    private val osName: String = System.getProperty("os.name").lowercase(),
    private val procRoot: Path = Path.of("/proc"),
) {
    fun resolve(socket: AFUNIXSocket): LocalPeer {
        val credentials = socket.peerCredentials
        val uid = credentials.uid
        require(uid >= 0) { "the operating system did not provide the peer uid" }
        val pid = credentials.pid.takeIf { it > 0 }
        return LocalPeer(uid, pid, pid?.let(::startTime))
    }

    fun currentUid(dataDirectory: Path): Long {
        val unixUid = runCatching {
            (Files.getAttribute(dataDirectory, "unix:uid") as Number).toLong()
        }.getOrNull()
        return unixUid ?: error("cannot determine daemon uid for $dataDirectory")
    }

    fun stillMatches(peer: LocalPeer): Boolean {
        val pid = peer.pid ?: return false
        val expected = peer.startTime ?: return false
        return startTime(pid) == expected
    }

    /** Best-effort executable identity for diagnostics; authorization never relies on these display values. */
    fun executable(pid: Long): ProcessExecutable? {
        val portable = portableExecutable(pid)
        val executable = if (osName.contains("linux")) {
            ProcessExecutable(
                name = linuxProgramName(pid) ?: portable?.name,
                path = linuxExecutablePath(pid) ?: portable?.path,
            )
        } else {
            portable
        }
        return executable?.takeIf { it.name != null || it.path != null }
    }

    internal fun startTime(pid: Long): String? = when {
        osName.contains("linux") -> linuxStartTime(pid) ?: portableStartTime(pid)
        osName.contains("mac") || osName.contains("darwin") -> portableStartTime(pid)
        else -> portableStartTime(pid)
    }

    private fun linuxStartTime(pid: Long): String? = runCatching {
        val line = Files.readString(procRoot.resolve(pid.toString()).resolve("stat"))
        // comm is parenthesized and may itself contain spaces or ')'; fields after the final ')' are stable.
        val close = line.lastIndexOf(')')
        require(close >= 0)
        val remainder = line.substring(close + 1).trim().split(Regex("\\s+"))
        // remainder[0] is field 3 (state), therefore field 22 (starttime) is index 19.
        remainder.getOrNull(19)?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun linuxProgramName(pid: Long): String? = runCatching {
        Files.readString(procRoot.resolve(pid.toString()).resolve("comm")).trim().takeIf(String::isNotBlank)
    }.getOrNull()

    private fun linuxExecutablePath(pid: Long): String? = runCatching {
        val link = procRoot.resolve(pid.toString()).resolve("exe")
        val target = Files.readSymbolicLink(link)
        (if (target.isAbsolute) target else link.parent.resolve(target).normalize()).toString()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun portableStartTime(pid: Long): String? = runCatching {
        ProcessHandle.of(pid).orElse(null)?.info()?.startInstant()?.orElse(null)?.toString()
    }.getOrNull()

    private fun portableExecutable(pid: Long): ProcessExecutable? = runCatching {
        val command = ProcessHandle.of(pid).orElse(null)?.info()?.command()?.orElse(null)
            ?.takeIf(String::isNotBlank) ?: return@runCatching null
        ProcessExecutable(
            name = runCatching { Path.of(command).fileName?.toString() }.getOrNull()?.takeIf(String::isNotBlank),
            path = command,
        )
    }.getOrNull()
}
