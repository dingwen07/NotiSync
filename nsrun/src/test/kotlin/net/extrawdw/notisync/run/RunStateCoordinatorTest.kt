package net.extrawdw.notisync.run

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.extrawdw.notisync.protocol.ClientId
import java.util.concurrent.atomic.AtomicInteger
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunUpdateReason
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.run.llm.ContentGenerator
import net.extrawdw.notisync.run.llm.GeneratedContent
import net.extrawdw.notisync.run.llm.GenerationContext
import net.extrawdw.notisync.run.llm.TitleGenerationMode
import net.extrawdw.notisync.run.output.DetectedProgress
import net.extrawdw.notisync.run.output.OutputSnapshot
import net.extrawdw.notisync.run.output.PromptKind
import net.extrawdw.notisync.run.process.BlockedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStateCoordinatorTest {
    @Test
    fun `lifecycle publishes complete monotonic snapshots and contextual generations`() {
        val states = mutableListOf<RunState>()
        coordinator(states::add).use { coordinator ->
            coordinator.initial(OutputSnapshot("starting", null, null, rawBytesSeen = 8))
            coordinator.blocked(
                OutputSnapshot("Continue? [Y/n]", null, PromptKind.YES_NO, rawBytesSeen = 15),
                BlockedReason.TERMINAL_INPUT,
            )
            coordinator.resumed(OutputSnapshot("running", DetectedProgress(5, 10), null, rawBytesSeen = 22))
            coordinator.completed(
                0,
                OutputSnapshot("done", DetectedProgress(10, 10), null, rawBytesSeen = 27),
                durationMs = 1_234,
            )
        }

        assertEquals(listOf(1L, 2L, 3L, 4L), states.map { it.revision })
        assertEquals(
            listOf(
                RunUpdateReason.INITIAL,
                RunUpdateReason.BLOCKED,
                RunUpdateReason.RESUMED,
                RunUpdateReason.COMPLETED,
            ),
            states.map { it.updateReason },
        )
        assertEquals(listOf(0L, 1L, 2L, 2L), states.map { it.interactionGeneration })
        assertEquals(RunPhase.BLOCKED, states[1].phase)
        assertEquals(PromptKind.YES_NO.name, states[1].prompt?.name)
        assertEquals(RunPhase.COMPLETED, states.last().phase)
        assertEquals(0, states.last().exitCode)
        assertEquals(1_234L, states.last().durationMs)
        assertNull(states.last().progress)
        assertEquals("done", states.last().terminal.text)
    }

    @Test
    fun `refresh is immediate full state correlated to request`() {
        val states = mutableListOf<RunState>()
        val requestId = "123e4567-e89b-12d3-a456-426614174000"
        coordinator(states::add).use { coordinator ->
            coordinator.initial(OutputSnapshot("current tail", DetectedProgress(2, 4), null, rawBytesSeen = 12))
            coordinator.refresh(requestId)
        }

        val refresh = states.last()
        assertEquals(2L, refresh.revision)
        assertEquals(RunUpdateReason.REFRESH, refresh.updateReason)
        assertEquals(requestId, refresh.responseToRequestId)
        assertEquals("current tail", refresh.terminal.text)
        assertEquals(states.first().argv, refresh.argv)
        assertEquals(states.first().startedAt, refresh.startedAt)
    }

    @Test
    fun `hang lifecycle without an input prompt does not change interaction generation`() {
        val states = mutableListOf<RunState>()
        coordinator(states::add).use { coordinator ->
            coordinator.initial(OutputSnapshot("starting", null, null))
            coordinator.blocked(
                OutputSnapshot("quiet", null, PromptKind.TEXT),
                BlockedReason.OUTPUT_AND_CPU_IDLE,
            )
            coordinator.resumed(OutputSnapshot("running", null, null))
            coordinator.completed(0, OutputSnapshot("done", null, null))
        }

        assertEquals(listOf(0L, 0L, 0L, 0L), states.map { it.interactionGeneration })
        assertTrue(states.all { it.prompt == null })
    }

    @Test
    fun `interaction generation changes only when prompt contract enters changes or leaves`() {
        val states = mutableListOf<RunState>()
        coordinator(states::add).use { coordinator ->
            coordinator.initial(OutputSnapshot("starting", null, null))
            coordinator.blocked(
                OutputSnapshot("Continue?", null, PromptKind.YES_NO),
                BlockedReason.TERMINAL_INPUT,
            )
            coordinator.blocked(
                OutputSnapshot("Continue?", null, PromptKind.YES_NO),
                BlockedReason.TERMINAL_INPUT,
            )
            coordinator.blocked(
                OutputSnapshot("Value:", null, PromptKind.TEXT),
                BlockedReason.TERMINAL_INPUT,
            )
            coordinator.resumed(OutputSnapshot("running", null, null))
            coordinator.completed(0, OutputSnapshot("done", null, null))
        }

        assertEquals(listOf(0L, 1L, 1L, 2L, 3L, 3L), states.map { it.interactionGeneration })
        assertEquals("YES_NO", states[1].prompt?.name)
        assertEquals("TEXT", states[3].prompt?.name)
    }

    @Test
    fun `injected attempt boundary and completion remain ordered across clock rollback`() {
        val clock = MutableClock(1_000)
        val states = mutableListOf<RunState>()
        RunStateCoordinator(
            hostClientId = ClientId("desktop"),
            runId = "run",
            argv = listOf("build"),
            pwd = Path.of("/work"),
            usesPty = false,
            publish = states::add,
            clock = clock,
            startedAt = 1_000,
        ).use { coordinator ->
            clock.now = 900
            coordinator.initial(OutputSnapshot("running", null, null))
            clock.now = 800
            coordinator.completed(0, OutputSnapshot("done", null, null), completedAt = 700)
        }

        assertEquals(listOf(1_000L, 1_000L), states.map { it.startedAt })
        assertEquals(listOf(1_001L, 1_002L), states.map { it.updatedAt })
        assertEquals(1_000L, states.last().endedAt)
    }

    @Test
    fun `LLM presentation is separate and superseded context is single flight`() {
        val started = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val calls = AtomicInteger()
        val states = mutableListOf<RunState>()
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
                GeneratedContent("Model title", "Model text", "Model detail")
            } finally {
                active.decrementAndGet()
            }
        }
        RunStateCoordinator(
            ClientId("desktop"), "run", listOf("build"), Path.of("/work"), false,
            publish = { synchronized(states) { states.add(it) } },
            generator = generator,
            clock = CLOCK,
        ).use { coordinator ->
            coordinator.initial(OutputSnapshot("starting", null, null))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            coordinator.blocked(OutputSnapshot("idle", null, null), BlockedReason.OUTPUT_AND_CPU_IDLE)
            assertTrue(secondFinished.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (synchronized(states) { states.none { it.updateReason == RunUpdateReason.LLM_SUMMARY } } &&
                System.nanoTime() < deadline
            ) Thread.yield()
        }

        val snapshot = synchronized(states) { states.toList() }
        val deterministic = snapshot.first { it.updateReason == RunUpdateReason.BLOCKED }
        val refined = snapshot.last { it.updateReason == RunUpdateReason.LLM_SUMMARY }
        assertNull(deterministic.llmSummary)
        assertEquals("idle", deterministic.terminal.text)
        assertEquals("Model title", refined.llmSummary?.title)
        assertEquals("idle", refined.terminal.text)
        assertTrue(refined.revision > deterministic.revision)
        assertEquals(1, maximum.get())
    }

    @Test
    fun `LLM refreshes main content while title changes only for task hang recovery and end`() {
        data class ModelCall(
            val event: String,
            val titleMode: TitleGenerationMode,
            val currentTitle: String?,
        )
        val calls = mutableListOf<ModelCall>()
        val states = mutableListOf<RunState>()
        val generator = ContentGenerator { context ->
            synchronized(calls) {
                calls += ModelCall(requireNotNull(context.event), context.titleMode, context.currentTitle)
            }
            GeneratedContent("${context.titleMode} title", "${context.event} text")
        }
        RunStateCoordinator(
            ClientId("desktop"), "run", listOf("build"), Path.of("/work"), false,
            publish = { synchronized(states) { states.add(it) } },
            generator = generator,
            clock = CLOCK,
        ).use { coordinator ->
            coordinator.initial(OutputSnapshot("starting", null, null))
            awaitState(states) { it.llmSummary?.title == "TASK_IDENTITY title" }

            coordinator.periodic(OutputSnapshot("25%", DetectedProgress(1, 4), null))
            awaitState(states) {
                it.llmSummary?.let { summary ->
                    summary.title == "TASK_IDENTITY title" && summary.text == "PERIODIC text"
                } == true
            }
            val callsBeforeRefresh = synchronized(calls) { calls.size }
            coordinator.refresh("123e4567-e89b-12d3-a456-426614174001")
            assertEquals(callsBeforeRefresh, synchronized(calls) { calls.size })
            coordinator.blocked(
                OutputSnapshot("Continue? [Y/n]", null, PromptKind.YES_NO),
                BlockedReason.TERMINAL_INPUT,
            )
            awaitState(states) {
                it.llmSummary?.let { summary ->
                    summary.title == "TASK_IDENTITY title" && summary.text == "BLOCKED text"
                } == true
            }
            coordinator.resumed(OutputSnapshot("continuing", null, null))
            awaitState(states) {
                it.llmSummary?.let { summary ->
                    summary.title == "TASK_IDENTITY title" && summary.text == "RESUMED text"
                } == true
            }

            coordinator.blocked(OutputSnapshot("no output", null, null), BlockedReason.OUTPUT_AND_CPU_IDLE)
            awaitState(states) { it.llmSummary?.title == "HANG title" }
            coordinator.resumed(OutputSnapshot("moving again", null, null))
            awaitState(states) { it.llmSummary?.title == "RECOVERY title" }
            coordinator.completed(0, OutputSnapshot("done", null, null))
            awaitState(states) { it.llmSummary?.title == "OUTCOME title" }
        }

        assertEquals(
            listOf(
                "INITIAL" to TitleGenerationMode.TASK_IDENTITY,
                "PERIODIC" to TitleGenerationMode.KEEP,
                "BLOCKED" to TitleGenerationMode.KEEP,
                "RESUMED" to TitleGenerationMode.KEEP,
                "BLOCKED" to TitleGenerationMode.HANG,
                "RESUMED" to TitleGenerationMode.RECOVERY,
                "COMPLETED" to TitleGenerationMode.OUTCOME,
            ),
            synchronized(calls) { calls.map { it.event to it.titleMode } },
        )
        assertTrue(
            synchronized(calls) {
                calls.filter { it.titleMode == TitleGenerationMode.KEEP }
                    .all { it.currentTitle == "TASK_IDENTITY title" }
            }
        )
    }

    @Test
    fun `failed-start model context receives only the capped untrusted failure detail`() {
        val contexts = mutableListOf<GenerationContext>()
        val generator = ContentGenerator { context ->
            synchronized(contexts) { contexts += context }
            GeneratedContent("Task title", "Status text")
        }
        RunStateCoordinator(
            ClientId("desktop"), "normal", listOf("build"), Path.of("/work"), false,
            publish = { true }, generator = generator, clock = CLOCK,
        ).use { coordinator ->
            coordinator.initial(OutputSnapshot("starting", null, null))
            awaitContextCount(contexts, 1)
        }
        val failure = "permission denied " + "界".repeat(2_000) + "FAILURE_SENTINEL"
        RunStateCoordinator(
            ClientId("desktop"), "failed", listOf("build"), Path.of("/work"), false,
            publish = { true }, generator = generator, clock = CLOCK,
        ).use { coordinator ->
            coordinator.spawnFailed(failure)
            awaitContextCount(contexts, 2)
        }

        val captured = synchronized(contexts) { contexts.toList() }
        assertNull(captured[0].failureMessage)
        assertTrue(requireNotNull(captured[1].failureMessage).encodeToByteArray().size <= 2 * 1024)
        assertFalse(requireNotNull(captured[1].failureMessage).contains("FAILURE_SENTINEL"))
    }

    @Test
    fun `untrusted model copy is normalized before publication`() {
        val states = mutableListOf<RunState>()
        RunStateCoordinator(
            ClientId("desktop"), "run", listOf("build"), Path.of("/work"), false,
            publish = { synchronized(states) { states += it }; true },
            generator = ContentGenerator {
                GeneratedContent(
                    title = "  Build\r\n\u202e app  ",
                    text = "  Running\r\n now\u009b  ",
                    expandedText = "Line one\r\nLine two\u202e",
                )
            },
            clock = CLOCK,
        ).use { coordinator ->
            coordinator.initial(OutputSnapshot("starting", null, null))
            awaitState(states) { it.llmSummary != null }
        }

        val summary = synchronized(states) { requireNotNull(states.last().llmSummary) }
        assertEquals("Build app", summary.title)
        assertEquals("Running\nnow", summary.text)
        assertEquals("Line one\nLine two", summary.expandedText)
    }

    private fun awaitState(
        states: MutableList<RunState>,
        predicate: (RunState) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (synchronized(states) { states.any(predicate) }) return
            Thread.yield()
        }
        assertTrue("timed out waiting for Run state", synchronized(states) { states.any(predicate) })
    }

    private fun awaitContextCount(contexts: MutableList<GenerationContext>, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (synchronized(contexts) { contexts.size >= expected }) return
            Thread.yield()
        }
        assertTrue("timed out waiting for model context", synchronized(contexts) { contexts.size >= expected })
    }

    private fun coordinator(publish: (RunState) -> Boolean) = RunStateCoordinator(
        hostClientId = ClientId("desktop"),
        runId = "run",
        argv = listOf("git", "push"),
        pwd = Path.of("/work"),
        usesPty = false,
        publish = publish,
        clock = CLOCK,
    )

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
    }

    private class MutableClock(var now: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }
}
