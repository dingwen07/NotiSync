package net.extrawdw.notisync.run

import java.util.concurrent.atomic.AtomicBoolean
import net.extrawdw.notisync.desktop.api.LocalApiException
import net.extrawdw.notisync.desktop.api.ReceiveStream
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunState

/** Serializes Run publication with atomic refresh completion and its uncertainty window. */
internal class RunReporter(
    private val bridge: RunApplicationBridge?,
    private val warning: (String) -> Unit,
) : AutoCloseable {
    private val warned = AtomicBoolean()
    private val publicationGate = Any()
    private var pendingRefreshCompletion: PendingRefreshCompletion? = null
    private var deferredState: RunState? = null
    val hostClientId: ClientId = bridge?.hostClientId ?: ClientId("offline")

    fun postRun(request: RunState): Boolean {
        val current = bridge ?: return false
        return synchronized(publicationGate) {
            if (pendingRefreshCompletion != null) {
                try {
                    completePendingRefresh(current)
                } catch (error: Throwable) {
                    retainNewest(request)
                    warnOnce(error)
                    return@synchronized true
                }
            }
            try {
                deferredState?.let { deferred ->
                    current.publish(deferred)
                    deferredState = null
                }
                current.publish(request)
                true
            } catch (error: Throwable) {
                retainNewest(request)
                warnOnce(error)
                false
            }
        }
    }

    fun reopenControl(): ReceiveStream = requireNotNull(bridge).reopenControl()

    fun reopenAction(): ReceiveStream = requireNotNull(bridge).reopenAction()

    fun decode(record: ReceiveRecord): RunInbound = requireNotNull(bridge).decode(record)

    fun validateAction(event: RunInbound.Action, currentGeneration: Long): RunActionCommand? =
        requireNotNull(bridge).validateAction(event, currentGeneration)

    fun ack(id: String) {
        val current = bridge ?: return
        runCatching { current.ack(id) }
            .onFailure(::warnOnce)
            .getOrThrow()
    }

    fun complete(id: String, completion: ControlCompletion) {
        val current = bridge ?: return
        synchronized(publicationGate) {
            val refreshCompletion = completion.refreshState != null
            if (refreshCompletion) pendingRefreshCompletion = PendingRefreshCompletion(id, completion)
            try {
                if (refreshCompletion) {
                    completePendingRefresh(current)
                } else {
                    current.complete(id, completion.sends(current))
                }
            } catch (error: Exception) {
                warnOnce(error)
                throw error
            }
            // Completion is durably accepted and the inbound event is ACKed. A later deferred-state
            // failure must retain only that state; the completed event can no longer be redelivered.
            if (pendingRefreshCompletion == null) {
                deferredState?.let { deferred ->
                    runCatching { current.publish(deferred) }
                        .onSuccess { deferredState = null }
                        .onFailure(::warnOnce)
                }
            }
        }
    }

    private fun completePendingRefresh(current: RunApplicationBridge) {
        val pending = pendingRefreshCompletion ?: return
        try {
            current.complete(pending.envelopeId, pending.completion.sends(current))
        } catch (error: LocalApiException) {
            // Inbox and completion memory intentionally disappear with the daemon. After restart
            // there is no event left to ACK, so it must not block later Run revisions forever.
            if (error.status == 409 && error.apiError?.message?.contains("event is not pending") == true) {
                pendingRefreshCompletion = null
                return
            }
            throw error
        }
        pendingRefreshCompletion = null
    }

    private fun ControlCompletion.sends(current: RunApplicationBridge): List<SendRequest> = buildList {
        refreshState?.let { add(current.nativeStateSend(it)) }
        add(current.controlResultSend(sender, result))
    }

    private fun retainNewest(request: RunState) {
        if (deferredState?.revision?.let { it > request.revision } != true) deferredState = request
    }

    override fun close() {
        runCatching { bridge?.close() }.onFailure(::warnOnce)
    }

    private fun warnOnce(error: Throwable) {
        if (warned.compareAndSet(false, true)) warning("NotiSync reporting failed: ${error.message}")
    }

    private data class PendingRefreshCompletion(
        val envelopeId: String,
        val completion: ControlCompletion,
    )
}
