package net.extrawdw.notisync.run.process

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

data class ProcessSnapshot(
    val pid: Long,
    val startIdentity: String,
    val state: Char,
    val processGroupId: Long,
    val foregroundProcessGroupId: Long,
    val cpuTicks: Long,
    val waitChannel: String?,
    /** Linux reports a blocking read/readv whose first argument is fd 0. */
    val waitingForStdin: Boolean = false,
)

fun interface ProcessInspector {
    fun snapshot(pid: Long): ProcessSnapshot?

    /** All visible members of the child's active foreground process group (for example git -> vim). */
    fun foregroundProcessGroup(pid: Long): List<ProcessSnapshot> {
        val root = snapshot(pid) ?: return emptyList()
        val foregroundGroup = root.foregroundProcessGroupId.takeIf { it > 0 } ?: root.processGroupId
        val result = linkedMapOf(root.pid to root)
        ProcessHandle.of(pid).orElse(null)?.descendants()?.use { descendants ->
            descendants.forEach { handle ->
                snapshot(handle.pid())?.takeIf { it.processGroupId == foregroundGroup }?.let { result[it.pid] = it }
            }
        }
        return result.values.filter { it.processGroupId == foregroundGroup || it.pid == root.pid }
    }

    fun currentProcessGroupId(): Long? = snapshot(ProcessHandle.current().pid())?.processGroupId
}

object ProcessInspectors {
    fun system(): ProcessInspector = when {
        System.getProperty("os.name").contains("linux", ignoreCase = true) -> LinuxProcessInspector()
        System.getProperty("os.name").contains("mac", ignoreCase = true) -> MacProcessInspector()
        else -> ProcessHandleInspector()
    }
}

class LinuxProcessInspector(private val proc: Path = Path.of("/proc")) : ProcessInspector {
    override fun snapshot(pid: Long): ProcessSnapshot? = runCatching {
        val directory = proc.resolve(pid.toString())
        val stat = Files.readString(directory.resolve("stat")).trim()
        // comm is parenthesized and may itself contain spaces or ')'; the final ')' ends field 2.
        val close = stat.lastIndexOf(')')
        require(close >= 0)
        val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
        require(fields.size >= 20)
        ProcessSnapshot(
            pid = pid,
            startIdentity = fields[19], // field 22: clock ticks since boot
            state = fields[0].single(), // field 3
            processGroupId = fields[2].toLong(), // field 5
            foregroundProcessGroupId = fields[5].toLong(), // field 8
            cpuTicks = fields[11].toLong() + fields[12].toLong(), // fields 14 + 15
            waitChannel = runCatching { Files.readString(directory.resolve("wchan")).trim() }
                .getOrNull()?.takeIf { it.isNotEmpty() && it != "0" },
            waitingForStdin = runCatching {
                isStdinRead(Files.readString(directory.resolve("syscall")))
            }.getOrDefault(false),
        )
    }.getOrNull()

    private fun isStdinRead(value: String): Boolean {
        val fields = value.trim().split(Regex("\\s+"))
        if (fields.size < 2) return false
        val syscall = fields[0].number() ?: return false
        val fd = fields[1].number() ?: return false
        // read/readv on x86_64, i386 and arm64. Other architectures still fall back to wchan/prompt.
        return fd == 0L && syscall in setOf(0L, 3L, 19L, 63L, 65L, 145L)
    }

    private fun String.number(): Long? =
        if (startsWith("0x", ignoreCase = true)) substring(2).toLongOrNull(16) else toLongOrNull()

    override fun foregroundProcessGroup(pid: Long): List<ProcessSnapshot> {
        val root = snapshot(pid) ?: return emptyList()
        val foregroundGroup = root.foregroundProcessGroupId.takeIf { it > 0 } ?: root.processGroupId
        if (foregroundGroup == currentProcessGroupId()) {
            // A non-PTY child inherits nsrun's group. Do not aggregate the wrapper/parent shell;
            // inspect only the child subtree while still following an editor descendant.
            return super<ProcessInspector>.foregroundProcessGroup(pid)
        }
        val members = Files.list(proc).use { stream ->
            stream.iterator().asSequence()
                .mapNotNull { it.fileName.toString().toLongOrNull() }
                .mapNotNull(::snapshot)
                .filter { it.processGroupId == foregroundGroup }
                .toList()
        }
        return if (members.isEmpty()) listOf(root) else members
    }
}

/** macOS adapter. libproc supplies durable identity/state/groups/CPU; ps is only a wait-channel fallback. */
class MacProcessInspector : ProcessInspector {
    override fun snapshot(pid: Long): ProcessSnapshot? = runCatching {
        require(pid in 1..Int.MAX_VALUE)
        val bsd = Memory(PROC_BSD_INFO_SIZE.toLong())
        val task = Memory(PROC_TASK_INFO_SIZE.toLong())
        check(libproc.proc_pidinfo(pid.toInt(), PROC_PIDTBSDINFO, 0, bsd, PROC_BSD_INFO_SIZE) >= PROC_BSD_INFO_SIZE)
        check(libproc.proc_pidinfo(pid.toInt(), PROC_PIDTASKINFO, 0, task, PROC_TASK_INFO_SIZE) >= PROC_TASK_INFO_SIZE)
        val status = bsd.getInt(PBI_STATUS_OFFSET.toLong())
        val processGroup = bsd.getInt(PBI_PGID_OFFSET.toLong()).toLong()
        val foregroundGroup = bsd.getInt(PBI_TPGID_OFFSET.toLong()).toLong()
        val startSeconds = bsd.getLong(PBI_START_SECONDS_OFFSET.toLong())
        val startMicros = bsd.getLong(PBI_START_MICROS_OFFSET.toLong())
        ProcessSnapshot(
            pid = pid,
            startIdentity = "$startSeconds:$startMicros",
            state = when (status) {
                PROC_STATE_STOPPED -> 'T'
                PROC_STATE_ZOMBIE -> 'Z'
                PROC_STATE_RUNNING -> 'R'
                else -> 'S'
            },
            processGroupId = processGroup,
            foregroundProcessGroupId = foregroundGroup,
            cpuTicks = task.getLong(PTI_TOTAL_USER_OFFSET.toLong()) +
                task.getLong(PTI_TOTAL_SYSTEM_OFFSET.toLong()),
            waitChannel = runCatching { ps(pid, "wchan=").trim() }
                .getOrNull()?.takeIf { it != "-" && it.isNotBlank() },
        )
    }.getOrElse { processHandleFallback(pid) }

    private fun processHandleFallback(pid: Long): ProcessSnapshot? = runCatching {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return null
        val start = handle.info().startInstant().orElse(Instant.EPOCH).toEpochMilli().toString()
        val line = ps(pid, "state=,pgid=,tpgid=,time=,wchan=").trim()
        val fields = line.split(Regex("\\s+"), limit = 5)
        require(fields.size >= 4)
        ProcessSnapshot(
            pid,
            start,
            fields[0].first(),
            fields[1].toLong(),
            fields[2].toLong(),
            parseCpuSeconds(fields[3]),
            fields.getOrNull(4)?.takeIf { it != "-" && it.isNotBlank() },
        )
    }.getOrNull()

    private fun ps(pid: Long, format: String): String {
        val process = ProcessBuilder("/bin/ps", "-o", format, "-p", pid.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0)
        return output
    }

    private fun parseCpuSeconds(value: String): Long {
        val daySplit = value.split('-', limit = 2)
        val days = if (daySplit.size == 2) daySplit[0].toLong() else 0L
        val clock = daySplit.last().split(':').map(String::toLong)
        val seconds = when (clock.size) {
            3 -> clock[0] * 3600 + clock[1] * 60 + clock[2]
            2 -> clock[0] * 60 + clock[1]
            else -> clock.single()
        }
        return days * 86_400 + seconds
    }

    private interface LibProc : Library {
        fun proc_pidinfo(pid: Int, flavor: Int, arg: Long, buffer: Memory, bufferSize: Int): Int
    }

    private val libproc: LibProc by lazy { Native.load("proc", LibProc::class.java) }

    private companion object {
        const val PROC_PIDTBSDINFO = 3
        const val PROC_PIDTASKINFO = 4
        const val PROC_BSD_INFO_SIZE = 136
        const val PROC_TASK_INFO_SIZE = 96
        const val PBI_STATUS_OFFSET = 4
        const val PBI_PGID_OFFSET = 100
        const val PBI_TPGID_OFFSET = 112
        const val PBI_START_SECONDS_OFFSET = 120
        const val PBI_START_MICROS_OFFSET = 128
        const val PTI_TOTAL_USER_OFFSET = 16
        const val PTI_TOTAL_SYSTEM_OFFSET = 24
        const val PROC_STATE_RUNNING = 2
        const val PROC_STATE_STOPPED = 4
        const val PROC_STATE_ZOMBIE = 5
    }
}

class ProcessHandleInspector : ProcessInspector {
    override fun snapshot(pid: Long): ProcessSnapshot? {
        val process = ProcessHandle.of(pid).orElse(null) ?: return null
        return ProcessSnapshot(
            pid,
            process.info().startInstant().orElse(Instant.EPOCH).toEpochMilli().toString(),
            if (process.isAlive) 'R' else 'X',
            pid,
            pid,
            process.info().totalCpuDuration().orElse(java.time.Duration.ZERO).toMillis(),
            null,
            false,
        )
    }
}
