package net.extrawdw.apps.notisync.data.storage.operational

import net.extrawdw.apps.notisync.run.StoredRun
import net.extrawdw.apps.notisync.run.StoredRunRevision
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunBlockedReason
import net.extrawdw.notisync.protocol.RunLlmSummary
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunProgress
import net.extrawdw.notisync.protocol.RunPromptKind
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RunStateStorageTest {
    @Test
    fun completeSnapshotsRoundTripEveryPhaseAndOptionalField() {
        val active = running()
        val states = listOf(
            active,
            active.copy(progress = RunProgress(indeterminate = true)),
            active.copy(
                phase = RunPhase.BLOCKED,
                updateReason = RunUpdateReason.BLOCKED,
                blockedReason = RunBlockedReason.TERMINAL_INPUT,
                prompt = RunPromptKind.TEXT,
                progress = RunProgress(12, 23),
                interactionGeneration = 37,
            ),
            active.copy(
                phase = RunPhase.COMPLETED,
                updateReason = RunUpdateReason.COMPLETED,
                endedAt = 150,
                durationMs = 49,
                exitCode = -1,
            ),
            active.copy(
                phase = RunPhase.FAILED_TO_START,
                updateReason = RunUpdateReason.FAILED,
                endedAt = 150,
                failureMessage = "No such executable",
                llmSummary = null,
                responseToRequestId = null,
            ),
        )
        states.forEach { state ->
            val values = RunStateStorage.revisionValues(state, 999)
            // SQLite INTEGER values are read as Long even when the writer supplied an Int.
            val sqliteRow = values.mapValues { (_, value) -> if (value is Int) value.toLong() else value }
            assertEquals(StoredRunRevision(state, 999), RunStateStorage.reconstructRevision(sqliteRow))
            assertFalse(values.values.any { it is ByteArray })
            assertEquals(listOf("argv_json"), values.keys.filter { it.endsWith("_json") })
        }
    }

    @Test
    fun localInactiveStateAndPresentationCheckpointAreSeparateFromRevision() {
        val stored = StoredRun(running(), receivedAt = 987, presentedRevision = 9, active = false)
        val session = RunStateStorage.sessionValues(stored)
        assertEquals(0, session["active"])
        assertEquals(9L, session["presented_revision"])
        assertEquals(stored.state.revision, session["current_revision"])
        assertEquals(987L, session["received_at"])
        assertFalse(RunStateStorage.revisionValues(stored.state, stored.receivedAt).containsKey("active"))
    }

    @Test
    fun inconsistentOptionalGroupsAndInvalidValuesAreRejected() {
        val row = RunStateStorage.revisionValues(running(), 999)
        listOf(
            row + ("progress_current" to 1L),
            row + ("llm_title" to null),
            row + ("uses_pty" to 2L),
            row + ("exit_code" to Long.MAX_VALUE),
            row + ("argv_json" to "not-json"),
        ).forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) { RunStateStorage.reconstructRevision(malformed) }
        }
    }

    private fun running() = RunState(
        hostClientId = ClientId("host"),
        runId = "run-with-details",
        revision = 9,
        phase = RunPhase.RUNNING,
        updateReason = RunUpdateReason.REFRESH,
        startedAt = 100,
        updatedAt = 200,
        argv = listOf("/bin/tool", "", "quoted \"value\"", "line\nbreak", "雪"),
        cwd = "/work/雪",
        usesPty = true,
        terminal = RunTerminalSnapshot("first line\nlast line 雪", truncated = true, rawBytesSeen = 100_000),
        llmSummary = RunLlmSummary("Build", "Compiling", "Detailed\nprogress"),
        responseToRequestId = "e869db12-31d5-4c22-9e0f-bca0ea7dbf5d",
    )
}
