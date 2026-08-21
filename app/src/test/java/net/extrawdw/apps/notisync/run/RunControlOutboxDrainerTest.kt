package net.extrawdw.apps.notisync.run

import kotlinx.coroutines.runBlocking
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunControlOutboxDrainerTest {
    @Test
    fun failedSendRetainsControlAndRetryUsesSameRequestId() = runBlocking {
        val first = signal("00000000-0000-4000-8000-000000000001", "INT", 1)
        val second = signal("00000000-0000-4000-8000-000000000002", "TERM", 2)
        val queue = FakeQueue(first, second)
        val attempts = mutableListOf<String>()
        var accept = false

        assertFalse(
            RunControlOutboxDrainer.drain(queue, nowMillis = 10) { control ->
                attempts += control.requestId
                accept
            }
        )
        assertEquals(listOf(first, second), queue.pending())

        accept = true
        assertTrue(
            RunControlOutboxDrainer.drain(queue, nowMillis = 10) { control ->
                attempts += control.requestId
                true
            }
        )

        assertEquals(
            listOf(first.requestId, first.requestId, second.requestId),
            attempts,
        )
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun acceptedSendWithFailedRemovalResendsSameIdSafely() = runBlocking {
        val control = signal("00000000-0000-4000-8000-000000000003", "KILL", 3)
        val queue = FakeQueue(control).apply { failNextRemove = true }
        val attempts = mutableListOf<String>()

        assertFalse(
            RunControlOutboxDrainer.drain(queue, nowMillis = 10) { sent ->
                attempts += sent.requestId
                true
            }
        )
        assertEquals(listOf(control), queue.pending())

        assertTrue(
            RunControlOutboxDrainer.drain(queue, nowMillis = 10) { sent ->
                attempts += sent.requestId
                true
            }
        )

        assertEquals(listOf(control.requestId, control.requestId), attempts)
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun failedRunDoesNotBlockUnrelatedRunButPreservesItsOwnOrder() = runBlocking {
        val blockedFirst = signal(
            requestId = "00000000-0000-4000-8000-000000000004",
            value = "INT",
            requestedAt = 4,
            host = "offline-host",
            runId = "offline-run",
        )
        val blockedSecond = signal(
            requestId = "00000000-0000-4000-8000-000000000005",
            value = "TERM",
            requestedAt = 5,
            host = "offline-host",
            runId = "offline-run",
        )
        val unrelated = signal(
            requestId = "00000000-0000-4000-8000-000000000006",
            value = "KILL",
            requestedAt = 6,
            host = "online-host",
            runId = "online-run",
        )
        val queue = FakeQueue(blockedFirst, blockedSecond, unrelated)
        val attempts = mutableListOf<String>()

        assertFalse(
            RunControlOutboxDrainer.drain(queue, nowMillis = 10) { control ->
                attempts += control.requestId
                control.hostClientId.value == "online-host"
            }
        )

        assertEquals(listOf(blockedFirst.requestId, unrelated.requestId), attempts)
        assertEquals(listOf(blockedFirst, blockedSecond), queue.pending())
    }

    @Test
    fun noLongerOwnedControlsAreDroppedWithoutBlockingSendableRuns() = runBlocking {
        val stale = signal(
            requestId = "00000000-0000-4000-8000-000000000007",
            value = "INT",
            requestedAt = 7,
            host = "revoked-host",
            runId = "old-run",
        )
        val current = signal(
            requestId = "00000000-0000-4000-8000-000000000008",
            value = "TERM",
            requestedAt = 8,
            host = "trusted-host",
            runId = "current-run",
        )
        val queue = FakeQueue(stale, current)
        val sent = mutableListOf<String>()

        assertTrue(
            RunControlOutboxDrainer.drain(
                queue = queue,
                nowMillis = 10,
                classify = { control ->
                    if (control.hostClientId.value == "revoked-host") {
                        QueuedRunControlDisposition.DROP
                    } else {
                        QueuedRunControlDisposition.SEND
                    }
                },
                send = { control ->
                    sent += control.requestId
                    true
                },
            )
        )

        assertEquals(listOf(current.requestId), sent)
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun expiredOrFutureDatedControlsAreDroppedWithoutBeingSent() = runBlocking {
        val now = 1_000_000L
        val expired = signal(
            requestId = "00000000-0000-4000-8000-000000000009",
            value = "KILL",
            requestedAt = now - RunControlOutboxDrainer.CONTROL_TTL_MS,
            runId = "expired-run",
        )
        val futureDated = signal(
            requestId = "00000000-0000-4000-8000-00000000000a",
            value = "TERM",
            requestedAt = now + 1,
            runId = "future-run",
        )
        val fresh = signal(
            requestId = "00000000-0000-4000-8000-00000000000b",
            value = "INT",
            requestedAt = now - RunControlOutboxDrainer.CONTROL_TTL_MS + 1,
            runId = "fresh-run",
        )
        val queue = FakeQueue(expired, futureDated, fresh)
        val sent = mutableListOf<String>()

        assertTrue(
            RunControlOutboxDrainer.drain(
                queue = queue,
                nowMillis = now,
                send = { control ->
                    sent += control.requestId
                    true
                },
            )
        )

        assertEquals(listOf(fresh.requestId), sent)
        assertTrue(queue.pending().isEmpty())
    }

    private class FakeQueue(vararg controls: RunControl) : RunControlQueue {
        private val values = LinkedHashMap(controls.associateBy { it.requestId })
        var failNextRemove = false

        override fun enqueue(control: RunControl) {
            values.putIfAbsent(control.requestId, control)
        }

        override fun pending(): List<RunControl> = values.values.toList()

        override fun remove(requestId: String) {
            if (failNextRemove) {
                failNextRemove = false
                error("simulated crash before removal")
            }
            values.remove(requestId)
        }
    }

    private fun signal(
        requestId: String,
        value: String,
        requestedAt: Long,
        host: String = "host",
        runId: String = "run-1",
    ) = RunControl(
        requestId = requestId,
        hostClientId = ClientId(host),
        runId = runId,
        kind = RunControlKind.SIGNAL,
        requestedAt = requestedAt,
        signal = value,
    )
}
