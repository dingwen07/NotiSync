package net.extrawdw.notisync.peer.pairing

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrokerPairingHostTest {
    private class Exchanges(private val closeMillis: Long = 0) {
        val results = mutableMapOf<Int, CompletableDeferred<String>>()
        val active = mutableSetOf<Int>()
        var peak = 0
        val host = BrokerPairingHost(sessionMillis = 100, rotationLeadMillis = 20, retryMillis = 10) { ready ->
            val id = results.size + 1
            val result = CompletableDeferred<String>()
            results[id] = result
            active += id
            peak = maxOf(peak, active.size)
            try {
                ready(BrokerPairingLink(id.toString().padStart(22, 'a'), "A".repeat(43), "a".repeat(32)))
                result.await()
            } finally {
                withContext(NonCancellable) { delay(closeMillis) }
                active -= id
            }
        }
    }

    @Test fun `rotation preserves the old session until expiry and never exceeds two`() = runTest {
        val exchanges = Exchanges()
        var displayed: BrokerPairingLink? = null
        val result = async { exchanges.host.awaitCard({ displayed = it }, {}) }
        runCurrent()
        val first = displayed
        assertNotNull(first)
        assertEquals(setOf(1), exchanges.active)
        advanceTimeBy(79)
        runCurrent()
        assertEquals(first, displayed)
        advanceTimeBy(1)
        runCurrent()
        assertNotEquals(first, displayed)
        assertEquals(setOf(1, 2), exchanges.active)
        advanceTimeBy(20)
        runCurrent()
        assertEquals(setOf(2), exchanges.active)
        advanceTimeBy(60)
        runCurrent()
        assertEquals(setOf(2, 3), exchanges.active)
        assertEquals(2, exchanges.peak)
        result.cancelAndJoin()
        assertTrue(exchanges.active.isEmpty())
        assertNull(displayed)
    }

    @Test fun `either overlapping session can finish and closes all sessions before review`() = runTest {
        for (winner in 1..2) {
            val exchanges = Exchanges(closeMillis = 5)
            var displayed: BrokerPairingLink? = null
            val result = async { exchanges.host.awaitCard({ displayed = it }, {}) }
            runCurrent()
            advanceTimeBy(80)
            runCurrent()
            assertEquals(setOf(1, 2), exchanges.active)
            exchanges.results.getValue(winner).complete("authenticated CARD $winner")
            runCurrent()
            assertFalse(result.isCompleted)
            advanceTimeBy(5)
            runCurrent()
            assertFalse(result.isCompleted) // The other connection is still closing.
            advanceTimeBy(5)
            runCurrent()
            assertEquals("authenticated CARD $winner", result.await())
            assertTrue(exchanges.active.isEmpty())
            assertNull(displayed)
        }
    }

    @Test fun `failed replacement falls back to the still live QR and retries`() = runTest {
        val exchanges = Exchanges()
        var displayed: BrokerPairingLink? = null
        var unavailable = 0
        val result = async { exchanges.host.awaitCard({ displayed = it }, { unavailable++ }) }
        runCurrent()
        val first = displayed
        advanceTimeBy(80)
        runCurrent()
        exchanges.results.getValue(2).completeExceptionally(IOException("disconnected"))
        runCurrent()
        assertEquals(first, displayed)
        assertEquals(0, unavailable)
        advanceTimeBy(10)
        runCurrent()
        assertEquals(setOf(1, 3), exchanges.active)
        assertNotEquals(first, displayed)
        assertEquals(2, exchanges.peak)
        result.cancelAndJoin()
    }

    @Test fun `failure removes the unusable QR while waiting for reconnection`() = runTest {
        val exchanges = Exchanges()
        var displayed: BrokerPairingLink? = null
        var unavailable = 0
        val result = async { exchanges.host.awaitCard({ displayed = it }, { unavailable++ }) }
        runCurrent()
        exchanges.results.getValue(1).completeExceptionally(IOException("disconnected"))
        runCurrent()
        assertNull(displayed)
        assertEquals(1, unavailable)
        advanceTimeBy(10)
        runCurrent()
        assertNotNull(displayed)
        assertEquals(setOf(2), exchanges.active)
        result.cancelAndJoin()
    }

    @Test fun `connection failures back off and leaving cancels pending retry`() = runTest {
        val starts = mutableListOf<Long>()
        val host = BrokerPairingHost(sessionMillis = 100, rotationLeadMillis = 20, retryMillis = 10) {
            starts += testScheduler.currentTime
            throw IOException("offline")
        }
        val result = async { host.awaitCard({}, {}) }
        runCurrent()
        advanceTimeBy(70)
        runCurrent()
        assertEquals(listOf(0L, 10L, 30L, 70L), starts)
        result.cancelAndJoin()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(4, starts.size)
    }

    @Test fun `slow connection cleanup still counts toward the two session limit`() = runTest {
        val exchanges = Exchanges(closeMillis = 90)
        var displayed: BrokerPairingLink? = null
        val result = async { exchanges.host.awaitCard({ displayed = it }, {}) }
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        assertNull(displayed) // Both sessions have expired, even while their sockets are closing.
        advanceTimeBy(10)
        runCurrent()
        assertNotNull(displayed) // The overdue rotation runs as soon as a slot is available.
        assertEquals(2, exchanges.peak)
        result.cancelAndJoin()
        assertTrue(exchanges.active.isEmpty())
    }
}
