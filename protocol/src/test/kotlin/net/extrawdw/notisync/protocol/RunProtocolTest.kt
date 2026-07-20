package net.extrawdw.notisync.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunProtocolTest {
    private val host = ClientId("desktop-host")
    private val requestId = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun dataSync_fullRunStateRoundTripsWithIndependentSummaryAndTerminal() {
        val state = RunState(
            hostClientId = host,
            runId = "8421-1750000000000",
            revision = 7,
            phase = RunPhase.BLOCKED,
            updateReason = RunUpdateReason.BLOCKED,
            startedAt = 1_750_000_000_000,
            updatedAt = 1_750_000_005_000,
            argv = listOf("/usr/bin/git", "push", "origin", "main"),
            cwd = "/work/repository",
            usesPty = true,
            terminal = RunTerminalSnapshot(
                text = "Enumerating objects: 100%\nContinue? [Y/n]",
                truncated = false,
                rawBytesSeen = 8_192,
            ),
            interactionGeneration = 2,
            blockedReason = RunBlockedReason.TERMINAL_INPUT,
            prompt = RunPromptKind.YES_NO,
            progress = RunProgress(current = 100, total = 100),
            llmSummary = RunLlmSummary(
                title = "Git push needs confirmation",
                text = "Choose whether to continue.",
                expandedText = "The terminal is waiting at a yes/no prompt.",
            ),
        )
        val body = DataSync(
            kind = DataSyncKind.RUN,
            run = RunSync(RunSyncKind.STATE, state = state),
        )

        val decoded = ProtocolCodec.decodeFromCbor<DataSync>(ProtocolCodec.encodeToCbor(body))

        assertEquals(DataSyncKind.RUN, decoded.kind)
        assertEquals(state, decoded.run?.state)
        assertEquals("Continue? [Y/n]", decoded.run?.state?.terminal?.text?.lineSequence()?.last())
        assertEquals("Git push needs confirmation", decoded.run?.state?.llmSummary?.title)
        assertNull(decoded.run?.control)
        assertNull(decoded.notification)
    }

    @Test
    fun runState_terminalOutcomesRoundTrip() {
        val completed = runningState().copy(
            revision = 8,
            phase = RunPhase.COMPLETED,
            updateReason = RunUpdateReason.COMPLETED,
            updatedAt = 1_750_000_010_000,
            endedAt = 1_750_000_010_000,
            durationMs = 10_000,
            progress = null,
            exitCode = 17,
        )
        val failed = runningState().copy(
            revision = 2,
            phase = RunPhase.FAILED_TO_START,
            updateReason = RunUpdateReason.FAILED,
            updatedAt = 1_750_000_000_100,
            endedAt = 1_750_000_000_100,
            progress = null,
            failureMessage = "Executable not found",
        )

        assertEquals(completed, completed.cborRoundTrip())
        assertEquals(10_000L, completed.cborRoundTrip().durationMs)
        assertEquals(failed, failed.cborRoundTrip())
    }

    @Test
    fun controls_roundTripRefreshInputAndArbitrarySignal() {
        val refresh = RunControl(requestId, host, "run-1", RunControlKind.REFRESH, requestedAt = 10)
        val input = RunControl(
            requestId = "123e4567-e89b-12d3-a456-426614174001",
            hostClientId = host,
            runId = "run-1",
            kind = RunControlKind.WRITE_INPUT,
            requestedAt = 11,
            interactionGeneration = 4,
            inputText = "y\n",
        )
        val signal = RunControl(
            requestId = "123e4567-e89b-12d3-a456-426614174002",
            hostClientId = host,
            runId = "run-1",
            kind = RunControlKind.SIGNAL,
            requestedAt = 12,
            signal = "RTMIN+1",
        )

        for (control in listOf(refresh, input, signal)) {
            val sync = RunSync(RunSyncKind.CONTROL, control = control)
            assertEquals(sync, ProtocolCodec.decodeFromCbor<RunSync>(ProtocolCodec.encodeToCbor(sync)))
        }
        assertEquals("y\n", input.inputText)
        assertEquals("RTMIN+1", signal.signal)
    }

    @Test
    fun controlResult_roundTripsAllStatuses() {
        for (status in RunControlResultStatus.entries) {
            val result = RunControlResult(requestId, "run-1", status, respondedAt = 20, message = status.name)
            val sync = RunSync(RunSyncKind.CONTROL_RESULT, controlResult = result)
            assertEquals(sync, ProtocolCodec.decodeFromCbor<RunSync>(ProtocolCodec.encodeToCbor(sync)))
        }
    }

    @Test
    fun commandRequest_roundTripsOrderedEnvironmentChangesWithoutRuntimeBehavior() {
        val request = RunCommandRequest(
            requestId = requestId,
            hostClientId = host,
            argv = listOf("/usr/bin/env", "bash", "-lc", "printf ignored-by-protocol", ""),
            cwd = "/work",
            environmentChanges = listOf(
                RunEnvironmentChange("PATH", RunEnvironmentOperation.PREPEND, "/opt/tool/bin", ":"),
                RunEnvironmentChange("TOKEN", RunEnvironmentOperation.UNSET),
                RunEnvironmentChange("MODE", RunEnvironmentOperation.SET, "test"),
                RunEnvironmentChange("PATH", RunEnvironmentOperation.APPEND, "/opt/fallback/bin", ":"),
            ),
            requestedAt = 100,
            expiresAt = 200,
        )
        val sync = RunSync(RunSyncKind.COMMAND_REQUEST, commandRequest = request)

        val decoded = ProtocolCodec.decodeFromCbor<RunSync>(ProtocolCodec.encodeToCbor(sync))

        assertEquals(sync, decoded)
        assertEquals("", decoded.commandRequest?.argv?.last())
        assertEquals(
            listOf(
                RunEnvironmentOperation.PREPEND,
                RunEnvironmentOperation.UNSET,
                RunEnvironmentOperation.SET,
                RunEnvironmentOperation.APPEND,
            ),
            decoded.commandRequest?.environmentChanges?.map(RunEnvironmentChange::operation),
        )
    }

    @Test
    fun taggedUnionRejectsMissingMismatchedAndMultipleBodies() {
        val state = runningState()
        val control = RunControl(requestId, host, state.runId, RunControlKind.REFRESH, requestedAt = 10)

        assertThrows(IllegalArgumentException::class.java) { RunSync(RunSyncKind.STATE) }
        assertThrows(IllegalArgumentException::class.java) {
            RunSync(RunSyncKind.STATE, control = control)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunSync(RunSyncKind.STATE, state = state, control = control)
        }
    }

    @Test
    fun stateAndControlInvariantsRejectAmbiguousPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            runningState().copy(phase = RunPhase.BLOCKED, updateReason = RunUpdateReason.BLOCKED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runningState().copy(durationMs = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runningState().copy(durationMs = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunControl(requestId, host, "run-1", RunControlKind.WRITE_INPUT, requestedAt = 1, inputText = "yes\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunControl(requestId, host, "run-1", RunControlKind.SIGNAL, requestedAt = 1, signal = "TERM; whoami")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunEnvironmentChange("TOKEN", RunEnvironmentOperation.UNSET, value = "secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunCommandRequest(requestId, host, listOf("tool"), "relative/path", emptyList(), 2, 1)
        }
    }

    @Test
    fun terminalSnapshotEnforcesSanitizationAndUtf8ByteLimit() {
        val maximum = RunTerminalSnapshot("x".repeat(RUN_TERMINAL_MAX_UTF8_BYTES), true, rawBytesSeen = 1)
        assertEquals(RUN_TERMINAL_MAX_UTF8_BYTES, maximum.text.encodeToByteArray().size)

        assertThrows(IllegalArgumentException::class.java) {
            RunTerminalSnapshot("x".repeat(RUN_TERMINAL_MAX_UTF8_BYTES + 1), true, rawBytesSeen = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunTerminalSnapshot("safe\u001b[31munsafe", false, rawBytesSeen = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunTerminalSnapshot("safe\u009b31munsafe", false, rawBytesSeen = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunTerminalSnapshot("safe\u202eunsafe", false, rawBytesSeen = 1)
        }
        assertTrue(RunTerminalSnapshot("unicode 👍", false, rawBytesSeen = 4).text.endsWith("👍"))
        val unicodeSeparator = runningState().copy(
            terminal = RunTerminalSnapshot("line one\u2028line two", false, rawBytesSeen = 17),
        ).cborRoundTrip()
        assertEquals("line one\u2028line two", unicodeSeparator.terminal.text)
    }

    @Test
    fun llmSummaryAllowsParagraphsButRejectsUnsafeCopy() {
        assertEquals(
            "Paragraph one\nParagraph two",
            RunLlmSummary("Build app", "Paragraph one\nParagraph two").text,
        )
        assertEquals("Line one\nLine two", RunLlmSummary("Build app", "Running", "Line one\nLine two").expandedText)
        assertThrows(IllegalArgumentException::class.java) {
            RunLlmSummary("Build\napp", "Running")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunLlmSummary("Build app", "Run\u009bning")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunLlmSummary("Build app", "Paragraph one\u2028Paragraph two")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunLlmSummary("Build app", "Running", "safe\u202eunsafe")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RunLlmSummary("Build app", "Running", "   ")
        }
    }

    private fun runningState() = RunState(
        hostClientId = host,
        runId = "run-1",
        revision = 1,
        phase = RunPhase.RUNNING,
        updateReason = RunUpdateReason.INITIAL,
        startedAt = 1_750_000_000_000,
        updatedAt = 1_750_000_000_000,
        argv = listOf("/usr/bin/make"),
        cwd = "/work",
        usesPty = false,
        terminal = RunTerminalSnapshot("building", truncated = false, rawBytesSeen = 8),
        progress = RunProgress(current = 1, total = 10),
    )

    private fun RunState.cborRoundTrip(): RunState =
        ProtocolCodec.decodeFromCbor(ProtocolCodec.encodeToCbor(this))
}
