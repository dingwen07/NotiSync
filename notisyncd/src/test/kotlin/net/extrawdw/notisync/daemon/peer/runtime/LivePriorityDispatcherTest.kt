package net.extrawdw.notisync.daemon.peer.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LivePriorityDispatcherTest {
    @Test
    fun `live overtakes queued recovery while both lanes retain FIFO and recovery progresses`() = runTest {
        val first = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val dispatcher = LivePriorityDispatcher<String, Unit>(backgroundScope) { item ->
            if (item == "in-progress") first.await()
            order += item
        }
        val running = async { dispatcher.submit("in-progress", isLive = false) }
        runCurrent()
        val replay = async { dispatcher.submit("replay", isLive = false) }
        val live = (1..10).map { i -> async { dispatcher.submit("live-$i", isLive = true) } }
        runCurrent()
        assertTrue(order.isEmpty()) // No ACK/result before the active handler completes.
        first.complete(Unit)
        running.await()
        replay.await()
        live.forEach { it.await() }
        assertEquals(listOf("in-progress") + (1..8).map { "live-$it" } + "replay" + listOf("live-9", "live-10"), order)
    }

    @Test
    fun `cancelled queued work is skipped and handler failure does not stop live work`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val dispatcher = LivePriorityDispatcher<String, Unit>(backgroundScope) { item ->
            if (item == "first") gate.await()
            if (item == "failed") error("injected failure")
            order += item
        }
        val first = async { dispatcher.submit("first", isLive = true) }
        runCurrent()
        val cancelled = async { dispatcher.submit("cancelled", isLive = false) }
        runCurrent()
        cancelled.cancelAndJoin()
        gate.complete(Unit)
        first.await()
        val failed = runCatching { dispatcher.submit("failed", isLive = false) }
        assertTrue(failed.isFailure)
        dispatcher.submit("last", isLive = true)
        assertEquals(listOf("first", "last"), order)
    }
}
