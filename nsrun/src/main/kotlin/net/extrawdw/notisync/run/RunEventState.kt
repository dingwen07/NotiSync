package net.extrawdw.notisync.run

import java.time.Clock
import java.util.LinkedHashMap
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import net.extrawdw.notisync.protocol.RunControlResult
import net.extrawdw.notisync.protocol.RunControlResultStatus
import net.extrawdw.notisync.protocol.RunState

internal data class ControlCompletion(
    val sender: ClientId,
    val result: RunControlResult,
    val refreshState: RunState?,
)

internal sealed interface ControlResolution {
    data class Complete(val completion: ControlCompletion) : ControlResolution
    data object ConflictingReuse : ControlResolution
}

/**
 * Run-lifetime request-id tombstones. Entries are deliberately never evicted: replaying an old
 * input or signal is more dangerous than allowing this per-process map to grow with actual controls.
 */
internal class RunControlRegistry(
    private val clock: Clock,
) {
    private val handled = LinkedHashMap<String, HandledControl>()

    @Synchronized
    fun resolve(
        sender: ClientId,
        control: RunControl,
        execute: () -> ControlCompletion,
    ): ControlResolution {
        handled[control.requestId]?.let { previous ->
            return if (previous.sender == sender && previous.control == control) {
                ControlResolution.Complete(previous.completion)
            } else {
                ControlResolution.ConflictingReuse
            }
        }

        // Reserve before invoking process I/O. If write/flush/signal throws after taking effect,
        // the deterministic FAILED completion becomes the permanent tombstone and the effect is
        // never attempted again.
        val reservation = HandledControl(
            sender = sender,
            control = control,
            completion = failedCompletion(sender, control),
        )
        handled[control.requestId] = reservation
        val completion = try {
            execute()
        } catch (_: Exception) {
            reservation.completion
        }
        handled[control.requestId] = reservation.copy(completion = completion)
        return ControlResolution.Complete(completion)
    }

    @Synchronized
    internal fun size(): Int = handled.size

    private fun failedCompletion(sender: ClientId, control: RunControl) = ControlCompletion(
        sender = sender,
        result = RunControlResult(
            requestId = control.requestId,
            runId = control.runId,
            status = RunControlResultStatus.FAILED,
            respondedAt = clock.millis(),
            message = "control execution failed",
        ),
        refreshState = null,
    )

    private data class HandledControl(
        val sender: ClientId,
        val control: RunControl,
        val completion: ControlCompletion,
    )
}

/** Run-lifetime envelope tombstones for non-idempotent compatibility notification actions. */
internal class RunActionRegistry {
    private val handled = mutableSetOf<String>()

    /** Returns false for a replay. The reservation survives even when [execute] throws. */
    fun executeOnce(envelopeId: String, execute: () -> Unit): Boolean {
        synchronized(handled) {
            if (!handled.add(envelopeId)) return false
        }
        execute()
        return true
    }
}

internal fun executeRunControl(
    control: RunControl,
    sender: ClientId,
    coordinator: RunStateCoordinator,
    clock: Clock,
    registerInput: (String) -> Unit,
    writeInput: (String) -> Unit,
    signal: (String) -> Boolean,
): ControlCompletion {
    var refreshState: RunState? = null
    val outcome = when (control.kind) {
        RunControlKind.REFRESH -> {
            refreshState = coordinator.prepareRefresh(control.requestId)
            if (refreshState != null) {
                RunControlResultStatus.APPLIED to null
            } else {
                RunControlResultStatus.FAILED to "could not build the refreshed Run state"
            }
        }
        RunControlKind.WRITE_INPUT -> {
            if (!coordinator.canAcceptInput(control.interactionGeneration)) {
                RunControlResultStatus.STALE to "input context is stale"
            } else {
                val remoteInput = requireNotNull(control.inputText)
                registerInput(remoteInput)
                writeInput(remoteInput)
                RunControlResultStatus.APPLIED to null
            }
        }
        RunControlKind.SIGNAL -> {
            val requestedSignal = requireNotNull(control.signal)
            if (signal(requestedSignal)) {
                RunControlResultStatus.APPLIED to null
            } else {
                RunControlResultStatus.FAILED to
                    "signal $requestedSignal is unsupported or could not be delivered"
            }
        }
    }
    return ControlCompletion(
        sender = sender,
        result = RunControlResult(
            requestId = control.requestId,
            runId = control.runId,
            status = outcome.first,
            respondedAt = clock.millis(),
            message = outcome.second,
        ),
        refreshState = refreshState,
    )
}
