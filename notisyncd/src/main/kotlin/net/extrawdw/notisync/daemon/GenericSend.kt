package net.extrawdw.notisync.daemon

import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.extrawdw.notisync.daemon.logging.DaemonLogger
import net.extrawdw.notisync.localapi.SendAccepted
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.HighDataSyncPolicy
import net.extrawdw.notisync.peer.channel.SignerSelection
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Urgency

/** Read-only trust/capability check required by the type-derived ACTION audience. */
fun interface ActionOriginPolicy {
    fun isTrustedOwnCapturePeer(clientId: ClientId): Boolean
}

/**
 * Resolves the optional local-API knobs into an entirely explicit daemon-lifetime outbox record. The
 * SecureChannel remains body-agnostic except for ACTION, whose authenticated protocol body names its
 * only valid destination.
 */
class GenericSendResolver(
    private val applications: ApplicationRegistry,
    private val actionOrigins: ActionOriginPolicy,
) {
    fun resolveAll(requests: List<SendRequest>): List<ResolvedSend> {
        require(requests.isNotEmpty()) { "send request must contain at least one record" }
        val applicationId = requests.first().applicationId
        require(applicationId.isNotBlank()) { "applicationId must not be blank" }
        require(requests.all { it.applicationId == applicationId }) {
            "all NDJSON send records must use the same applicationId"
        }
        if (applications.find(applicationId) == null) {
            throw ApplicationNotRegisteredException(applicationId)
        }
        // Resolve every line before the outbox sees any of them. Its accept call supplies the second,
        // daemon-lifetime atomic boundary (idempotency, sequence checks, supersession, and insertion).
        return requests.map(::resolve)
    }

    private fun resolve(request: SendRequest): ResolvedSend {
        require(request.body.isNotBlank()) { "body must be non-empty base64 CBOR" }
        val body = try {
            Base64.getDecoder().decode(request.body)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("body is not valid base64", error)
        }
        require(body.isNotEmpty()) { "body must decode to non-empty CBOR" }

        var scope = request.scope
        val defaultUrgency: Urgency
        when (request.messageType) {
            MessageType.NOTIFICATION -> {
                scope = scope ?: Recipients.OwnMeshFiltered(
                    requiredCapabilities = setOf(Capability.DISPLAY),
                )
                defaultUrgency = Urgency.HIGH
            }

            MessageType.DISMISSAL -> {
                scope = scope ?: Recipients.OwnMeshFiltered(
                    requiredCapabilities = setOf(Capability.DISMISS_SYNC),
                )
                defaultUrgency = Urgency.NORMAL
            }

            MessageType.ACTION -> {
                val action = runCatching { ProtocolCodec.decodeFromCbor<ActionEvent>(body) }
                    .getOrElse { throw IllegalArgumentException("ACTION body is not a valid ActionEvent", it) }
                val expected = Recipients.Only(action.sourceClientId)
                require(scope == null || scope == expected) {
                    "ACTION scope must target its sourceClientId"
                }
                require(actionOrigins.isTrustedOwnCapturePeer(action.sourceClientId)) {
                    "ACTION sourceClientId is not a trusted own peer with CAPTURE"
                }
                scope = expected
                defaultUrgency = Urgency.HIGH
            }

            MessageType.DATA_SYNC -> {
                scope = scope ?: Recipients.OwnMesh
                defaultUrgency = Urgency.NORMAL
            }
        }
        val urgency = request.urgency ?: defaultUrgency
        validateHighDataSync(request.messageType, body, requireNotNull(scope), urgency)
        return ResolvedSend(
            applicationId = request.applicationId,
            messageType = request.messageType,
            body = body,
            scope = scope,
            urgency = urgency,
            // Identity signing can therefore only happen through an explicit JSON field.
            signWith = request.signWith ?: SignerSelection.OPERATIONAL,
            submissionId = request.submissionId,
            queuePolicy = request.queuePolicy,
        )
    }

    private fun validateHighDataSync(type: MessageType, body: ByteArray, scope: Recipients, urgency: Urgency) {
        if (type != MessageType.DATA_SYNC || urgency != Urgency.HIGH) return
        HighDataSyncPolicy.validate(body, scope)
    }
}

/** The runtime adapter around SecureChannel.sendAllStrict. */
fun interface GenericBatchSender {
    /** Returns the resolved recipient count; zero is a successful empty fan-out. */
    suspend fun send(batch: List<PendingSend>, onAccepted: (PendingSend) -> Unit): Int
}

/** One globally ordered, failure-stopping daemon outbox pump. */
class GenericSendDispatcher(
    private val outbox: GenericSendOutbox,
    private val sender: GenericBatchSender,
    parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val logger: DaemonLogger = DaemonLogger("WARN"),
    private val retryDelayMillis: Long = 1_000,
) : AutoCloseable {
    private val lifecycle = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifecycle + CoroutineName("notisyncd-send"))
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)
    /** Prevent coalescing/deletion from invalidating a batch between peek and broker checkpoint. */
    private val dispatchPermit = Semaphore(1, true)

    init {
        require(retryDelayMillis > 0) { "retryDelayMillis must be positive" }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { run() }
    }

    fun accepted(records: List<ResolvedSend>): List<SendAccepted> {
        val accepted = serialized { outbox.accept(records) }
        logger.info(
            "Accepted ${accepted.size} send${if (accepted.size == 1) "" else "s"} " +
                "for application ${records.first().applicationId}",
        )
        wake.trySend(Unit)
        return accepted
    }

    fun wake() {
        wake.trySend(Unit)
    }

    /** Clear one application's daemon-lifetime outbound state after its registration is removed. */
    fun removeApplication(applicationId: String) = serialized {
        outbox.removeApplication(applicationId)
    }

    fun <T> serialized(operation: () -> T): T {
        dispatchPermit.acquireUninterruptibly()
        return try {
            operation()
        } finally {
            dispatchPermit.release()
        }
    }

    override fun close() {
        lifecycle.cancel()
        wake.close()
    }

    private suspend fun run() {
        while (currentCoroutineContext().isActive) {
            dispatchPermit.acquireUninterruptibly()
            val batch = try {
                outbox.peekConsecutive()
            } catch (error: Throwable) {
                dispatchPermit.release()
                throw error
            }
            if (batch.isEmpty()) {
                dispatchPermit.release()
                wake.receiveCatching()
                continue
            }
            var failed = false
            try {
                val recipientCount = sender.send(batch) { accepted ->
                    check(outbox.checkpoint(accepted.messageId)) {
                        "accepted outbox item ${accepted.messageId} disappeared before checkpoint"
                    }
                    logger.info(
                        "Delivered ${accepted.messageType} ${accepted.messageId} for " +
                        "application ${accepted.applicationId}",
                    )
                }
                if (recipientCount == 0) {
                    // An empty capability-routed audience is a valid no-op, not a transport outage.
                    // Completing this group lets disjoint projections (for example nsrun's iOS fallback)
                    // advance. A matching peer that lacks key material fails before this return and retries.
                    batch.forEach { skipped ->
                        check(outbox.checkpoint(skipped.messageId)) {
                            "empty-audience outbox item ${skipped.messageId} disappeared before checkpoint"
                        }
                        logger.info(
                            "Skipped ${skipped.messageType} ${skipped.messageId} for application " +
                                "${skipped.applicationId}; no eligible recipients",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Accepted prefix items were checkpointed synchronously; the in-memory head is the
                // actual failed item (or a safe diagnostic fallback if an external store changed).
                val failedItem = outbox.peekConsecutive().firstOrNull() ?: batch.last()
                logger.warn(
                    "Send ${failedItem.messageId} for application ${failedItem.applicationId} failed; " +
                        "retrying: ${error.message ?: error.javaClass.simpleName}",
                )
                failed = true
            } finally {
                dispatchPermit.release()
            }
            if (failed) delay(retryDelayMillis)
        }
    }
}
