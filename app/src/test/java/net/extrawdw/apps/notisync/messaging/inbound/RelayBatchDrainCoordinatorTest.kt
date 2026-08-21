package net.extrawdw.apps.notisync.messaging.inbound

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import net.extrawdw.apps.notisync.data.relay.AuthenticatedRelayToken
import net.extrawdw.apps.notisync.data.relay.RelayBatchItem
import net.extrawdw.apps.notisync.data.relay.RelayBatchPresentation
import net.extrawdw.apps.notisync.data.relay.RelayBatchRecordOutcome
import net.extrawdw.apps.notisync.data.relay.RelayBatchSessionRepository
import net.extrawdw.apps.notisync.data.relay.RelayDeliveryMode
import net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition
import net.extrawdw.apps.notisync.data.relay.RelayOperationalContinuity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayBatchDrainCoordinatorTest {
    @Test
    fun incompleteBatchClearsScratchAndPerformsNoPresentationOrAck() = runTest {
        val fixture = fixture(
            items = listOf("notification-1" to InboundAcknowledgementPrerequisite.NOTIFICATION_PRESENTATION),
            fetchResult = RelayFiniteBatchFetchResult.INCOMPLETE_RETRY,
        )

        val result = fixture.coordinator.drain(CONTINUITY)

        assertEquals(RelayBatchDrainResult.RETRY_REQUIRED, result)
        assertEquals(0, fixture.delivery.replayCalls)
        assertTrue(fixture.acked.isEmpty())
        assertEquals(2, fixture.scratch.clearCalls)
        assertTrue(fixture.scratch.rows.isEmpty())
    }

    @Test
    fun completeBatchReplaysDismissalBeforeNotificationThenAcksNonPresentation() = runTest {
        val fixture = fixture(
            items = listOf(
                "notification-1" to InboundAcknowledgementPrerequisite.NOTIFICATION_PRESENTATION,
                "plain-1" to InboundAcknowledgementPrerequisite.NONE,
                "dismissal-1" to InboundAcknowledgementPrerequisite.DISMISSAL_PRESENTATION,
            ),
        )
        fixture.batchSource.beforeComplete = {
            assertTrue("ACK escaped before validated END", fixture.acked.isEmpty())
            assertEquals(0, fixture.delivery.replayCalls)
        }

        val result = fixture.coordinator.drain(CONTINUITY)

        assertEquals(RelayBatchDrainResult.COMPLETE, result)
        assertEquals(
            listOf("replay-dismissal-1", "replay-notification-1"),
            fixture.events.filter { it.startsWith("replay-") },
        )
        assertEquals(listOf("dismissal-1", "notification-1", "plain-1"), fixture.acked)
        assertEquals(2, fixture.delivery.replayCalls)
        assertTrue(fixture.scratch.rows.isEmpty())
    }

    @Test
    fun sameIdDifferentFingerprintLatchesConflictAndNeverAcks() = runTest {
        val fixture = fixture(
            items = listOf(
                "plain-1" to InboundAcknowledgementPrerequisite.NONE,
                "plain-1" to InboundAcknowledgementPrerequisite.NONE,
            ),
        )
        fixture.delivery.tokenOverrides["plain-1#2"] = token(99)

        val result = fixture.coordinator.drain(CONTINUITY)

        assertEquals(RelayBatchDrainResult.RETRY_REQUIRED, result)
        assertTrue(fixture.acked.isEmpty())
        assertEquals(0, fixture.delivery.replayCalls)
    }

    @Test
    fun refetchFingerprintMismatchCannotPresentOrAck() = runTest {
        val fixture = fixture(
            items = listOf("notification-1" to InboundAcknowledgementPrerequisite.NOTIFICATION_PRESENTATION),
        )
        fixture.delivery.replayConflict = true

        val result = fixture.coordinator.drain(CONTINUITY)

        assertEquals(RelayBatchDrainResult.RETRY_REQUIRED, result)
        assertEquals(1, fixture.delivery.replayCalls)
        assertTrue(fixture.acked.isEmpty())
    }

    @Test
    fun continuityMismatchDuringFinalResolveLeavesBrokerMessageUnacked() = runTest {
        val fixture = fixture(
            items = listOf("plain-1" to InboundAcknowledgementPrerequisite.NONE),
        )
        fixture.ackResult = InboundAcknowledgementResult.StorageContinuityChanged

        val result = fixture.coordinator.drain(CONTINUITY)

        assertEquals(RelayBatchDrainResult.RETRY_REQUIRED, result)
        assertEquals(listOf("plain-1"), fixture.ackAttempts)
        assertTrue(fixture.acked.isEmpty())
    }

    @Test
    fun backlogLargerThanOnePageHasNoArbitraryClientCap() = runTest {
        val items = (0 until 257).map { index ->
            "plain-${index.toString().padStart(3, '0')}" to InboundAcknowledgementPrerequisite.NONE
        }
        val fixture = fixture(items = items, pageSize = 17)

        val result = fixture.coordinator.drain(CONTINUITY)

        assertEquals(RelayBatchDrainResult.COMPLETE, result)
        assertEquals(257, fixture.acked.size)
        assertTrue(fixture.scratch.pageLimits.all { it == 17 })
        assertTrue(fixture.scratch.pageLimits.size > 15)
    }

    @Test
    fun cancellationPropagatesAfterNonCancellableScratchCleanup() = runTest {
        val cancellation = CancellationException("worker stopped")
        val fixture = fixture(emptyList())
        fixture.batchSource.failure = cancellation

        val thrown = expectFailure<CancellationException> { fixture.coordinator.drain(CONTINUITY) }

        assertSame(cancellation, thrown)
        assertEquals(2, fixture.scratch.clearCalls)
    }

    @Test
    fun directDeliveryIsNotBlockedByFiniteBatchNetworkWait() = runTest {
        val gate = MutexInboundProcessGate()
        val enteredBatch = CompletableDeferred<Unit>()
        val releaseBatch = CompletableDeferred<Unit>()
        val scratch = FakeScratch()
        val delivery = FakeBatchDelivery(emptyMap(), mutableListOf())
        val batch = RelayBatchDrainCoordinator(
            processGate = gate,
            scratch = scratch,
            delivery = delivery,
            acknowledgements = InboundAcknowledgementPort { InboundAcknowledgementResult.Acknowledged },
            batchSource = RelayFiniteBatchFetchPort {
                enteredBatch.complete(Unit)
                releaseBatch.await()
                RelayFiniteBatchFetchResult.COMPLETE
            },
            exactSource = RelayExactFetchPort { _, _ -> RelayExactFetchResult.Missing },
        )
        var directCalls = 0
        val direct = SerializedDirectInboundProcessor(
            processGate = gate,
            delivery = InboundDirectDeliveryPort {
                directCalls += 1
                InboundCoordinatorResult.RetryRequired(
                    it.messageId,
                    net.extrawdw.apps.notisync.data.relay.RelayStableCode.of("retry"),
                )
            },
            acknowledgements = InboundAcknowledgementPort { InboundAcknowledgementResult.Acknowledged },
        )

        val draining = async { batch.drain(CONTINUITY) }
        enteredBatch.await()
        val directRun = async { direct.process(arrival("plain-1", CONTINUITY)) }
        directRun.await()
        assertEquals(1, directCalls)

        releaseBatch.complete(Unit)
        assertEquals(RelayBatchDrainResult.COMPLETE, draining.await())
    }

    private fun fixture(
        items: List<Pair<String, InboundAcknowledgementPrerequisite>>,
        fetchResult: RelayFiniteBatchFetchResult = RelayFiniteBatchFetchResult.COMPLETE,
        pageSize: Int = 64,
        processGate: InboundProcessGate = MutexInboundProcessGate(),
    ): Fixture {
        val scratch = FakeScratch()
        val events = mutableListOf<String>()
        val delivery = FakeBatchDelivery(items.toMap(), events)
        val batchSource = FakeBatchSource(items, fetchResult, delivery)
        val acked = mutableListOf<String>()
        val ackAttempts = mutableListOf<String>()
        val fixture = Fixture(scratch, delivery, batchSource, events, acked, ackAttempts)
        val exactSource = RelayExactFetchPort { messageId, continuity ->
            RelayExactFetchResult.Found(arrival(messageId, continuity))
        }
        fixture.coordinator = RelayBatchDrainCoordinator(
            processGate = processGate,
            scratch = scratch,
            delivery = delivery,
            acknowledgements = InboundAcknowledgementPort { candidate ->
                ackAttempts += candidate.messageId
                if (fixture.ackResult == InboundAcknowledgementResult.Acknowledged) {
                    acked += candidate.messageId
                }
                fixture.ackResult
            },
            batchSource = batchSource,
            exactSource = exactSource,
            pageSize = pageSize,
        )
        return fixture
    }

    private class Fixture(
        val scratch: FakeScratch,
        val delivery: FakeBatchDelivery,
        val batchSource: FakeBatchSource,
        val events: MutableList<String>,
        val acked: MutableList<String>,
        val ackAttempts: MutableList<String>,
    ) {
        lateinit var coordinator: RelayBatchDrainCoordinator
        var ackResult: InboundAcknowledgementResult = InboundAcknowledgementResult.Acknowledged
    }

    private class FakeBatchSource(
        private val items: List<Pair<String, InboundAcknowledgementPrerequisite>>,
        private val result: RelayFiniteBatchFetchResult,
        private val delivery: FakeBatchDelivery,
    ) : RelayFiniteBatchFetchPort {
        var beforeComplete: () -> Unit = {}
        var failure: Throwable? = null

        override suspend fun fetch(
            consume: suspend (InboundEnvelopeArrival) -> Unit,
        ): RelayFiniteBatchFetchResult {
            items.forEachIndexed { index, (messageId, _) ->
                delivery.currentOccurrence = index + 1
                consume(arrival(messageId, CONTINUITY))
            }
            failure?.let { throw it }
            beforeComplete()
            return result
        }
    }

    private class FakeBatchDelivery(
        private val prerequisites: Map<String, InboundAcknowledgementPrerequisite>,
        private val events: MutableList<String>,
    ) : InboundBatchDeliveryPort {
        val tokenOverrides = mutableMapOf<String, AuthenticatedRelayToken>()
        var currentOccurrence = 1
        var replayCalls = 0
        var replayConflict = false

        override suspend fun receiveForBatch(
            arrival: InboundEnvelopeArrival,
            observation: InboundBatchObservationPort,
        ): InboundCoordinatorResult {
            val key = "${arrival.messageId}#$currentOccurrence"
            val authenticatedToken = tokenOverrides[key] ?: token(arrival.messageId.hashCode().toByte())
            val prerequisite = prerequisites.getValue(arrival.messageId)
            val observed = observation.observe(
                InboundBatchObservation(arrival.messageId, authenticatedToken, prerequisite),
            )
            return if (observed == InboundBatchObservationResult.CONFLICT) {
                InboundCoordinatorResult.ConflictNoAck(
                    arrival.messageId,
                    InboundConflictReason.HANDLED_FINGERPRINT_CONFLICT,
                )
            } else {
                InboundCoordinatorResult.AcknowledgementPending(
                    candidate(arrival.messageId, authenticatedToken, prerequisite),
                )
            }
        }

        override suspend fun replayBatchPresentation(
            arrival: InboundEnvelopeArrival,
            expected: InboundBatchObservation,
        ): InboundCoordinatorResult {
            replayCalls += 1
            events += "replay-${arrival.messageId}"
            return if (replayConflict) {
                InboundCoordinatorResult.ConflictNoAck(
                    arrival.messageId,
                    InboundConflictReason.HANDLED_FINGERPRINT_CONFLICT,
                )
            } else {
                InboundCoordinatorResult.AcknowledgementPending(
                    candidate(
                        arrival.messageId,
                        expected.authenticatedToken,
                        InboundAcknowledgementPrerequisite.NONE,
                    ),
                )
            }
        }
    }

    private class FakeScratch : RelayBatchSessionRepository {
        val rows = linkedMapOf<String, RelayBatchItem>()
        val pageLimits = mutableListOf<Int>()
        var clearCalls = 0

        override suspend fun clearAtDrainBoundary(): Int {
            clearCalls += 1
            return rows.size.also { rows.clear() }
        }

        override suspend fun record(
            messageId: String,
            authenticatedToken: AuthenticatedRelayToken,
            presentation: RelayBatchPresentation,
        ): RelayBatchRecordOutcome {
            val existing = rows[messageId]
            if (existing == null) {
                rows[messageId] = RelayBatchItem(messageId, authenticatedToken, false, presentation)
                return RelayBatchRecordOutcome.INSERTED
            }
            if (existing.conflict) return RelayBatchRecordOutcome.CONFLICT
            if (existing.authenticatedToken == authenticatedToken) return RelayBatchRecordOutcome.EXACT
            rows[messageId] = RelayBatchItem(
                existing.messageId,
                existing.authenticatedToken,
                conflict = true,
                existing.presentation,
            )
            return RelayBatchRecordOutcome.CONFLICT
        }

        override suspend fun presentationPage(afterMessageId: String?, limit: Int): List<RelayBatchItem> =
            page(afterMessageId, limit) { it.presentation != RelayBatchPresentation.NONE }

        override suspend fun nonPresentationPage(afterMessageId: String?, limit: Int): List<RelayBatchItem> =
            page(afterMessageId, limit) { it.presentation == RelayBatchPresentation.NONE }

        override suspend fun find(messageId: String): RelayBatchItem? = rows[messageId]

        override suspend fun deleteExact(expected: RelayBatchItem): Boolean {
            val current = rows[expected.messageId] ?: return false
            if (current != expected) return false
            rows.remove(expected.messageId)
            return true
        }

        private fun page(
            afterMessageId: String?,
            limit: Int,
            predicate: (RelayBatchItem) -> Boolean,
        ): List<RelayBatchItem> {
            pageLimits += limit
            return rows.values
                .filter(predicate)
                .filter { afterMessageId == null || it.messageId > afterMessageId }
                .sortedBy { it.messageId }
                .take(limit)
        }
    }

    private companion object {
        val CONTINUITY = RelayOperationalContinuity(7, "incarnation-1")

        fun token(value: Byte): AuthenticatedRelayToken =
            AuthenticatedRelayToken.of(ByteArray(32) { value })

        fun arrival(
            messageId: String,
            continuity: RelayOperationalContinuity,
        ) = InboundEnvelopeArrival(
            messageId = messageId,
            encodedEnvelope = byteArrayOf(1),
            acceptedAt = 1,
            deliveryMode = RelayDeliveryMode.RELAY_DRAIN,
            continuity = continuity,
        )

        fun candidate(
            messageId: String,
            authenticatedToken: AuthenticatedRelayToken,
            prerequisite: InboundAcknowledgementPrerequisite,
        ) = InboundAcknowledgementCandidate(
            messageId = messageId,
            authenticatedToken = authenticatedToken,
            continuity = CONTINUITY,
            disposition = RelayHandledDisposition.APPLIED,
            prerequisite = prerequisite,
        )
    }

    private suspend inline fun <reified T : Throwable> expectFailure(
        noinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }
}
