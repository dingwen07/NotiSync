package net.extrawdw.notisync.run

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.extrawdw.notisync.localapi.NotificationPhase
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.localapi.NotificationActionLifetime
import net.extrawdw.notisync.run.output.OutputSnapshot
import net.extrawdw.notisync.run.output.DetectedProgress
import net.extrawdw.notisync.run.output.PromptKind
import net.extrawdw.notisync.run.process.BlockedReason
import net.extrawdw.notisync.run.llm.ContentGenerator
import net.extrawdw.notisync.run.llm.GeneratedContent

class RunNotificationCoordinatorTest {
    @Test
    fun `notification lifecycle observes silence and three-action budget`() {
        val requests = mutableListOf<NotificationRequest>()
        RunNotificationCoordinator("s", listOf("git", "push"), Path.of("/work"), requests::add).use { coordinator ->
            coordinator.initial(OutputSnapshot("", null, null))
            coordinator.blocked(OutputSnapshot("Continue? [Y/n]", null, PromptKind.YES_NO), BlockedReason.TERMINAL_INPUT)
            coordinator.resumed(OutputSnapshot("running", null, null))
            coordinator.completed(0, OutputSnapshot("done", null, null))
        }

        assertTrue(requests[0].silent)
        assertEquals(2, requests[0].actions.size)
        assertTrue(requests[0].actions.all { it.lifetime == NotificationActionLifetime.SESSION })
        assertFalse(requests[1].silent)
        assertEquals(listOf("yes", "no", "signal-int"), requests[1].actions.map { it.id })
        assertEquals(
            listOf(
                NotificationActionLifetime.GENERATION,
                NotificationActionLifetime.GENERATION,
                NotificationActionLifetime.SESSION,
            ),
            requests[1].actions.map { it.lifetime },
        )
        assertTrue(requests[2].silent)
        assertEquals(NotificationPhase.COMPLETED, requests.last().phase)
        assertFalse(requests.last().ongoing)
        assertTrue(requests.last().actions.isEmpty())
        assertEquals(requests.indices.map { it.toLong() + 1 }, requests.map { it.generation })
    }

    @Test
    fun `model generation is single-flight and a newer state cancels old context`() {
        val started = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val calls = AtomicInteger()
        val generator = ContentGenerator {
            val count = active.incrementAndGet()
            maximum.accumulateAndGet(count, ::maxOf)
            val call = calls.incrementAndGet()
            try {
                if (call == 1) {
                    started.countDown()
                    Thread.sleep(30_000)
                } else {
                    secondFinished.countDown()
                }
                GeneratedContent("Generated", "Generated")
            } finally {
                active.decrementAndGet()
            }
        }
        RunNotificationCoordinator(
            "s", listOf("build"), Path.of("/work"), {}, generator,
        ).use { coordinator ->
            coordinator.initial(OutputSnapshot("starting", null, null))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            coordinator.blocked(
                OutputSnapshot("input", null, PromptKind.TEXT),
                BlockedReason.TERMINAL_INPUT,
            )
            assertTrue(secondFinished.await(2, TimeUnit.SECONDS))
        }
        assertEquals(1, maximum.get())
    }

    @Test
    fun `completion removes promoted progress style and keeps final output`() {
        val requests = mutableListOf<NotificationRequest>()
        RunNotificationCoordinator("s", listOf("build"), Path.of("/work"), requests::add).use { coordinator ->
            coordinator.completed(0, OutputSnapshot("final line", DetectedProgress(100, 100), null))
        }

        val completed = requests.single()
        assertEquals(NotificationPhase.COMPLETED, completed.phase)
        assertEquals(null, completed.progress)
        assertFalse(completed.requestPromotedOngoing)
        assertEquals("final line", completed.expandedText)
    }
}
