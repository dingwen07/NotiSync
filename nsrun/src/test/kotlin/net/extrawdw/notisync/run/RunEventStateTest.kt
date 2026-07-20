package net.extrawdw.notisync.run

import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind
import net.extrawdw.notisync.protocol.RunControlResult
import net.extrawdw.notisync.protocol.RunControlResultStatus
import net.extrawdw.notisync.run.output.OutputSnapshot
import net.extrawdw.notisync.run.output.PromptKind
import net.extrawdw.notisync.run.process.BlockedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RunEventStateTest {
    @Test
    fun `exact control replay returns the cached completion without executing twice`() {
        val registry = RunControlRegistry(CLOCK)
        val control = signalControl()
        val executions = AtomicInteger()
        val expected = completion(control)

        val first = registry.resolve(SENDER, control) {
            executions.incrementAndGet()
            expected
        }
        val replay = registry.resolve(SENDER, control) {
            fail("exact replay must not execute")
            expected
        }

        assertEquals(1, executions.get())
        assertSame(expected, (first as ControlResolution.Complete).completion)
        assertSame(expected, (replay as ControlResolution.Complete).completion)
    }

    @Test
    fun `conflicting request id reuse never executes or exposes the cached result`() {
        val registry = RunControlRegistry(CLOCK)
        val control = signalControl()
        var executions = 0
        registry.resolve(SENDER, control) {
            executions++
            completion(control)
        }

        val changedPayload = registry.resolve(
            SENDER,
            control.copy(signal = "TERM"),
        ) {
            executions++
            completion(control)
        }
        val changedSender = registry.resolve(ClientId("another-phone"), control) {
            executions++
            completion(control)
        }

        assertSame(ControlResolution.ConflictingReuse, changedPayload)
        assertSame(ControlResolution.ConflictingReuse, changedSender)
        assertEquals(1, executions)
    }

    @Test
    fun `ambiguous control failure keeps a deterministic failed tombstone`() {
        val registry = RunControlRegistry(CLOCK)
        val control = signalControl()
        var sideEffects = 0

        val first = registry.resolve(SENDER, control) {
            sideEffects++
            throw IOException("signal may already have been delivered")
        } as ControlResolution.Complete
        val replay = registry.resolve(SENDER, control) {
            sideEffects++
            completion(control)
        } as ControlResolution.Complete

        assertEquals(1, sideEffects)
        assertEquals(RunControlResultStatus.FAILED, first.completion.result.status)
        assertEquals("control execution failed", first.completion.result.message)
        assertEquals(CLOCK.millis(), first.completion.result.respondedAt)
        assertEquals(first.completion, replay.completion)
    }

    @Test
    fun `control request tombstones are retained for the whole Run`() {
        val registry = RunControlRegistry(CLOCK)
        val first = signalControl()
        registry.resolve(SENDER, first) { completion(first) }
        repeat(1_100) {
            val control = signalControl(requestId = UUID.randomUUID().toString())
            registry.resolve(SENDER, control) { completion(control) }
        }

        val replay = registry.resolve(SENDER, first) {
            fail("old tombstone must not be evicted")
            completion(first)
        }

        assertTrue(replay is ControlResolution.Complete)
        assertEquals(1_101, registry.size())
    }

    @Test
    fun `action envelope remains reserved when execution throws`() {
        val registry = RunActionRegistry()
        var executions = 0

        try {
            registry.executeOnce("envelope-1") {
                executions++
                throw IOException("write may already have reached the child")
            }
            fail("expected the original action failure")
        } catch (_: IOException) {
            // Expected: the receive stream reconnects and retries only the ACK.
        }

        assertFalse(registry.executeOnce("envelope-1") { executions++ })
        assertEquals(1, executions)
    }

    @Test
    fun `write input requires the current active prompt generation`() {
        val registered = mutableListOf<String>()
        val written = mutableListOf<String>()
        coordinator().use { coordinator ->
            coordinator.initial(OutputSnapshot("running", null, null))

            val beforePrompt = executeRunControl(
                control = inputControl(generation = 0),
                sender = SENDER,
                coordinator = coordinator,
                clock = CLOCK,
                registerInput = registered::add,
                writeInput = written::add,
                signal = { true },
            )
            assertEquals(RunControlResultStatus.STALE, beforePrompt.result.status)

            coordinator.blocked(
                OutputSnapshot("Value:", null, PromptKind.TEXT),
                BlockedReason.TERMINAL_INPUT,
            )
            val generation = coordinator.currentInteractionGeneration()
            val accepted = executeRunControl(
                control = inputControl(generation = generation),
                sender = SENDER,
                coordinator = coordinator,
                clock = CLOCK,
                registerInput = registered::add,
                writeInput = written::add,
                signal = { true },
            )
            val stale = executeRunControl(
                control = inputControl(generation = generation - 1),
                sender = SENDER,
                coordinator = coordinator,
                clock = CLOCK,
                registerInput = registered::add,
                writeInput = written::add,
                signal = { true },
            )
            coordinator.resumed(OutputSnapshot("running", null, null))
            val afterPrompt = executeRunControl(
                control = inputControl(generation = generation),
                sender = SENDER,
                coordinator = coordinator,
                clock = CLOCK,
                registerInput = registered::add,
                writeInput = written::add,
                signal = { true },
            )

            assertEquals(RunControlResultStatus.APPLIED, accepted.result.status)
            assertEquals(RunControlResultStatus.STALE, stale.result.status)
            assertEquals(RunControlResultStatus.STALE, afterPrompt.result.status)
            assertEquals(listOf("answer"), registered)
            assertEquals(listOf("answer"), written)
        }
    }

    private fun coordinator() = RunStateCoordinator(
        hostClientId = HOST,
        runId = RUN_ID,
        argv = listOf("build"),
        pwd = Path.of("/work"),
        usesPty = false,
        publish = { true },
        clock = CLOCK,
    )

    private fun signalControl(
        requestId: String = "123e4567-e89b-12d3-a456-426614174000",
    ) = RunControl(
        requestId = requestId,
        hostClientId = HOST,
        runId = RUN_ID,
        kind = RunControlKind.SIGNAL,
        requestedAt = CLOCK.millis(),
        signal = "INT",
    )

    private fun inputControl(generation: Long) = RunControl(
        requestId = UUID.randomUUID().toString(),
        hostClientId = HOST,
        runId = RUN_ID,
        kind = RunControlKind.WRITE_INPUT,
        requestedAt = CLOCK.millis(),
        interactionGeneration = generation,
        inputText = "answer",
    )

    private fun completion(control: RunControl) = ControlCompletion(
        sender = SENDER,
        result = RunControlResult(
            requestId = control.requestId,
            runId = control.runId,
            status = RunControlResultStatus.APPLIED,
            respondedAt = CLOCK.millis(),
        ),
        refreshState = null,
    )

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(2_000), ZoneOffset.UTC)
        val HOST = ClientId("desktop")
        val SENDER = ClientId("phone")
        const val RUN_ID = "run-1"
    }
}
