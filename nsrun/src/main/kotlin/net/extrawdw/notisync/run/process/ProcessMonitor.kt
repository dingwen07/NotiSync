package net.extrawdw.notisync.run.process

import java.time.Duration
import net.extrawdw.notisync.run.output.PromptKind

enum class BlockedReason { TERMINAL_INPUT, OUTPUT_AND_CPU_IDLE }

data class MonitorTransition(val blocked: Boolean, val reason: BlockedReason? = null)

/** Stateful detector separated from scheduling so its edge transitions can be fixture-tested. */
class ProcessMonitor(
    private val inspector: ProcessInspector,
    private val pid: Long,
    private val stuckAfter: Duration?,
    private val outputActivityNanos: () -> Long,
) {
    private var previousCpu: Map<String, Long>? = null
    private var directSamples = 0
    private var blockedReason: BlockedReason? = null

    fun sample(nowNanos: Long = System.nanoTime(), prompt: PromptKind? = null): MonitorTransition? {
        val snapshots = inspector.foregroundProcessGroup(pid)
        if (snapshots.isEmpty()) return null
        val cpu = snapshots.associate { "${it.pid}:${it.startIdentity}" to it.cpuTicks }
        val cpuIdle = previousCpu?.let { previous ->
            previous.keys == cpu.keys && cpu.all { (identity, ticks) -> previous[identity] == ticks }
        } ?: false
        previousCpu = cpu
        val directWait = snapshots.any { snapshot ->
            snapshot.state == 'T' || snapshot.state == 't' ||
                snapshot.waitingForStdin ||
                snapshot.waitChannel.orEmpty().contains("tty", ignoreCase = true)
        } ||
            (prompt != null && cpuIdle)
        directSamples = if (directWait) directSamples + 1 else 0

        val desired = when {
            directSamples >= 3 -> BlockedReason.TERMINAL_INPUT
            stuckAfter != null && cpuIdle && nowNanos - outputActivityNanos() >= stuckAfter.toNanos() &&
                snapshots.none { isExpectedIdleWait(it.waitChannel) } -> BlockedReason.OUTPUT_AND_CPU_IDLE
            else -> null
        }
        if (desired == blockedReason) return null
        blockedReason = desired
        return MonitorTransition(blocked = desired != null, reason = desired)
    }

    private fun isExpectedIdleWait(channel: String?): Boolean {
        val value = channel.orEmpty().lowercase()
        return listOf("sleep", "timer", "poll", "epoll", "kqueue", "futex", "select", "kevent")
            .any(value::contains)
    }
}
