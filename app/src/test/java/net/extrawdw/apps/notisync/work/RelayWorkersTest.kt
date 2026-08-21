package net.extrawdw.apps.notisync.work

import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.peer.channel.DeliveryOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayWorkersTest {
    @Test
    fun handledRelayIsAckedAfterDurableDelivery() = runBlocking {
        val acked = mutableListOf<List<String>>()
        val queued = mutableListOf<String>()

        val result = finishRelayDelivery(
            messageId = "message-1",
            outcome = DeliveryOutcome.HANDLED,
            ack = { ids -> acked += ids; true },
            queueAck = queued::add,
        )

        assertEquals(RelayHandleResult.COMPLETE, result)
        assertEquals(listOf(listOf("message-1")), acked)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun failedNetworkAckIsQueuedAfterDuplicateDelivery() = runBlocking {
        val queued = mutableListOf<String>()

        val result = finishRelayDelivery(
            messageId = "message-2",
            outcome = DeliveryOutcome.DUPLICATE,
            ack = { false },
            queueAck = queued::add,
        )

        assertEquals(RelayHandleResult.COMPLETE, result)
        assertEquals(listOf("message-2"), queued)
    }

    @Test
    fun durablyDeferredRelayIsImmediatelyAckable() = runBlocking {
        val acked = mutableListOf<List<String>>()

        val result = finishRelayDelivery(
            messageId = "message-deferred",
            outcome = DeliveryOutcome.DEFERRED,
            ack = { ids -> acked += ids; true },
            queueAck = {},
        )

        assertEquals(RelayHandleResult.COMPLETE, result)
        assertEquals(listOf(listOf("message-deferred")), acked)
    }

    @Test
    fun deferredQuietDelayResetsFromTheLastDistinctArrival() {
        val now = 1_000_000L

        assertEquals(
            120_000L,
            RelayDrainWorker.remainingDeferredQuietDelay(lastDeferredAt = now, now = now),
        )
        assertEquals(
            30_000L,
            RelayDrainWorker.remainingDeferredQuietDelay(lastDeferredAt = now - 90_000L, now = now),
        )
        assertEquals(
            0L,
            RelayDrainWorker.remainingDeferredQuietDelay(
                lastDeferredAt = now - 120_000L,
                now = now,
                allowImmediate = true,
            ),
        )
    }

    @Test
    fun retryableDeliveryRemainsUnacked() = runBlocking {
        var ackCalled = false
        val queued = mutableListOf<String>()

        val result = finishRelayDelivery(
            messageId = "message-3",
            outcome = DeliveryOutcome.DROPPED,
            ack = { ackCalled = true; true },
            queueAck = queued::add,
        )

        assertEquals(RelayHandleResult.RETRY, result)
        assertTrue(!ackCalled)
        assertTrue(queued.isEmpty())
    }
}
