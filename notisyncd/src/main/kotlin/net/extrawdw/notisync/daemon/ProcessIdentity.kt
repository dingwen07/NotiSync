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

    private fun portableStartTime(pid: Long): String? = runCatching {
        ProcessHandle.of(pid).orElse(null)?.info()?.startInstant()?.orElse(null)?.toString()
    }.getOrNull()
}
