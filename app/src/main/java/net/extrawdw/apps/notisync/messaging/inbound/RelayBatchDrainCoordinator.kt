package net.extrawdw.apps.notisync.messaging.inbound

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.data.relay.RelayBatchItem
import net.extrawdw.apps.notisync.data.relay.RelayBatchPresentation
import net.extrawdw.apps.notisync.data.relay.RelayBatchRecordOutcome
import net.extrawdw.apps.notisync.data.relay.RelayBatchSessionRepository
import net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition
import net.extrawdw.apps.notisync.data.relay.RelayLimits
import net.extrawdw.apps.notisync.data.relay.RelayOperationalContinuity

/** Complete means the source validated END and its advertised item count. */
internal enum class RelayFiniteBatchFetchResult {
    COMPLETE,
    INCOMPLETE_RETRY,
    UNSUPPORTED_RETRY,
}

internal fun interface RelayFiniteBatchFetchPort {
    suspend fun fetch(consume: suspend (InboundEnvelopeArrival) -> Unit): RelayFiniteBatchFetchResult
}

internal sealed interface RelayExactFetchResult {
    data class Found(val arrival: InboundEnvelopeArrival) : RelayExactFetchResult
    data object Missing : RelayExactFetchResult
    data object RetryRequired : RelayExactFetchResult
}

internal fun interface RelayExactFetchPort {
    suspend fun fetch(
        messageId: String,
        continuity: RelayOperationalContinuity,
    ): RelayExactFetchResult
}

internal enum class RelayBatchDrainResult {
    COMPLETE,
    RETRY_REQUIRED,
}

/**
 * One finite broker drain with disposable metadata scratch.
 *
 * Feature and handled commits may stream before END, but network ACK and NotificationManager presentation never
 * do. The source port may return [RelayFiniteBatchFetchResult.COMPLETE] only after validating END/count; all other
 * exits clear scratch and leave the broker copy authoritative. Dismissals reconcile before notification posts.
 * Concurrent batch drains are serialized, while the shared inbound gate covers only each owner/presentation step so
 * a live WebSocket delivery is never parked behind batch network I/O.
 */
internal class RelayBatchDrainCoordinator(
    private val processGate: InboundProcessGate,
    private val scratch: RelayBatchSessionRepository,
    private val delivery: InboundBatchDeliveryPort,
    private val acknowledgements: InboundAcknowledgementPort,
    private val batchSource: RelayFiniteBatchFetchPort,
    private val exactSource: RelayExactFetchPort,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    private val drainGate = Mutex()

    init {
        require(pageSize in 1..RelayLimits.MAX_BATCH_PAGE_ROWS) { "relay batch page size is outside its bound" }
    }

    suspend fun drain(continuity: RelayOperationalContinuity): RelayBatchDrainResult =
        drainGate.withLock { drainExclusive(continuity) }

    private suspend fun drainExclusive(continuity: RelayOperationalContinuity): RelayBatchDrainResult {
        scratch.clearAtDrainBoundary()
        var primaryFailure: Throwable? = null
        try {
            var retryRequired = false
            val fetchResult = batchSource.fetch { arrival ->
                currentCoroutineContext().ensureActive()
                check(arrival.continuity == continuity) { "relay batch item crossed Operational continuity" }
                val result = processGate.withInboundExclusive {
                    delivery.receiveForBatch(
                        arrival,
                        InboundBatchObservationPort { observation ->
                            when (
                                scratch.record(
                                    messageId = observation.messageId,
                                    authenticatedToken = observation.authenticatedToken,
                                    presentation = observation.prerequisite.toBatchPresentation(),
                                )
                            ) {
                                RelayBatchRecordOutcome.INSERTED,
                                RelayBatchRecordOutcome.EXACT,
                                -> InboundBatchObservationResult.RECORDED
                                RelayBatchRecordOutcome.CONFLICT -> InboundBatchObservationResult.CONFLICT
                            }
                        },
                    )
                }
                if (result !is InboundCoordinatorResult.AcknowledgementPending) retryRequired = true
            }
            if (fetchResult != RelayFiniteBatchFetchResult.COMPLETE) {
                return RelayBatchDrainResult.RETRY_REQUIRED
            }

            retryRequired = reconcilePresentations(
                continuity,
                RelayBatchPresentation.DISMISSAL,
            ) || retryRequired
            retryRequired = reconcilePresentations(
                continuity,
                RelayBatchPresentation.NOTIFICATION,
            ) || retryRequired
            retryRequired = acknowledgeNonPresentation(continuity) || retryRequired
            return if (retryRequired) RelayBatchDrainResult.RETRY_REQUIRED else RelayBatchDrainResult.COMPLETE
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                withContext(NonCancellable) { scratch.clearAtDrainBoundary() }
            } catch (cleanupFailure: Throwable) {
                val primary = primaryFailure
                if (primary == null) throw cleanupFailure
                primary.addSuppressed(cleanupFailure)
            }
        }
    }

    private suspend fun reconcilePresentations(
        continuity: RelayOperationalContinuity,
        kind: RelayBatchPresentation,
    ): Boolean {
        var retryRequired = false
        page(scratch::presentationPage) { row ->
            if (row.presentation != kind) return@page
            if (row.conflict) {
                retryRequired = true
                return@page
            }
            when (val fetched = exactSource.fetch(row.messageId, continuity)) {
                RelayExactFetchResult.Missing -> {
                    // Another correctly fenced path may already have ACKed it. The next drain reconstructs state.
                    if (!scratch.deleteExact(row)) retryRequired = true
                }
                RelayExactFetchResult.RetryRequired -> retryRequired = true
                is RelayExactFetchResult.Found -> {
                    val arrival = fetched.arrival
                    if (arrival.messageId != row.messageId || arrival.continuity != continuity) {
                        retryRequired = true
                        return@page
                    }
                    val replay = processGate.withInboundExclusive {
                        delivery.replayBatchPresentation(
                            arrival = arrival,
                            expected = InboundBatchObservation(
                                messageId = row.messageId,
                                authenticatedToken = row.authenticatedToken,
                                prerequisite = kind.toAcknowledgementPrerequisite(),
                            ),
                        )
                    }
                    val candidate = (replay as? InboundCoordinatorResult.AcknowledgementPending)?.candidate
                    if (
                        candidate == null ||
                        candidate.messageId != row.messageId ||
                        candidate.authenticatedToken != row.authenticatedToken ||
                        candidate.continuity != continuity ||
                        candidate.prerequisite != InboundAcknowledgementPrerequisite.NONE
                    ) {
                        retryRequired = true
                        return@page
                    }
                    if (acknowledgements.acknowledge(candidate) == InboundAcknowledgementResult.Acknowledged) {
                        if (!scratch.deleteExact(row)) retryRequired = true
                    } else {
                        retryRequired = true
                    }
                }
            }
        }
        return retryRequired
    }

    private suspend fun acknowledgeNonPresentation(continuity: RelayOperationalContinuity): Boolean {
        var retryRequired = false
        page(scratch::nonPresentationPage) { row ->
            if (row.conflict) {
                retryRequired = true
                return@page
            }
            val result = acknowledgements.acknowledge(
                InboundAcknowledgementCandidate(
                    messageId = row.messageId,
                    authenticatedToken = row.authenticatedToken,
                    continuity = continuity,
                    disposition = RelayHandledDisposition.DUPLICATE,
                    prerequisite = InboundAcknowledgementPrerequisite.NONE,
                ),
            )
            if (result == InboundAcknowledgementResult.Acknowledged) {
                if (!scratch.deleteExact(row)) retryRequired = true
            } else {
                retryRequired = true
            }
        }
        return retryRequired
    }

    private suspend fun page(
        read: suspend (String?, Int) -> List<RelayBatchItem>,
        consume: suspend (RelayBatchItem) -> Unit,
    ) {
        var cursor: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = read(cursor, pageSize)
            check(rows.size <= pageSize) { "relay batch scratch exceeded the requested page size" }
            if (rows.isEmpty()) return
            var previous = cursor
            for (row in rows) {
                check(previous == null || row.messageId > previous) { "relay batch scratch page is not ordered" }
                consume(row)
                previous = row.messageId
            }
            cursor = previous
        }
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 64
    }
}

private fun InboundAcknowledgementPrerequisite.toBatchPresentation(): RelayBatchPresentation = when (this) {
    InboundAcknowledgementPrerequisite.NONE -> RelayBatchPresentation.NONE
    InboundAcknowledgementPrerequisite.NOTIFICATION_PRESENTATION -> RelayBatchPresentation.NOTIFICATION
    InboundAcknowledgementPrerequisite.DISMISSAL_PRESENTATION -> RelayBatchPresentation.DISMISSAL
}

private fun RelayBatchPresentation.toAcknowledgementPrerequisite(): InboundAcknowledgementPrerequisite =
    when (this) {
        RelayBatchPresentation.NONE -> InboundAcknowledgementPrerequisite.NONE
        RelayBatchPresentation.NOTIFICATION -> InboundAcknowledgementPrerequisite.NOTIFICATION_PRESENTATION
        RelayBatchPresentation.DISMISSAL -> InboundAcknowledgementPrerequisite.DISMISSAL_PRESENTATION
    }
