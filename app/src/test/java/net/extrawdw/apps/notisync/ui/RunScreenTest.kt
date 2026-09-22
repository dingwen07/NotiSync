package net.extrawdw.apps.notisync.ui

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import net.extrawdw.apps.notisync.run.StoredRun
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason

class RunScreenTest {
    @Test
    fun savedActiveRevisionsCannotExposeCurrentRunControls() {
        val state = RunState(
            hostClientId = ClientId("host"), runId = "run", revision = 1,
            phase = RunPhase.RUNNING, updateReason = RunUpdateReason.INITIAL,
            startedAt = 100, updatedAt = 100, argv = listOf("make"), cwd = "/work", usesPty = false,
            terminal = RunTerminalSnapshot("", false, 0),
        )
        val run = StoredRun(state, 100)
        assertTrue(runDetailCanControl(run, readOnly = false))
        assertTrue(runDetailCanRefresh(state, readOnly = false))
        assertFalse(runDetailCanControl(run, readOnly = true))
        assertFalse(runDetailCanRefresh(state, readOnly = true))
        assertFalse(runDetailCanControl(run.copy(active = false), readOnly = false))
        assertTrue("Locally inactive current snapshots remain refreshable", runDetailCanRefresh(state, readOnly = false))
    }

    @Test
    fun failedInputSubmissionPreservesDraft() = runBlocking {
        var submitted = ""

        val result = submitRunInput("retry me\n\n") { terminalLine ->
            submitted = terminalLine
            false
        }

        assertFalse(result.accepted)
        assertEquals("retry me\n\n", result.input)
        assertEquals("retry me\n", submitted)
    }

    @Test
    fun acceptedInputSubmissionClearsDraft() = runBlocking {
        val result = submitRunInput("continue") { true }

        assertTrue(result.accepted)
        assertEquals("", result.input)
    }

    @Test
    fun historyDeletionUsesProvidedIoDispatcher() {
        val callerThread = Thread.currentThread().name
        var clearThread = callerThread
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "run-history-test-io")
        }

        executor.asCoroutineDispatcher().use { dispatcher ->
            val cleared = runBlocking {
                runStorageMutation(
                    mutation = {
                        clearThread = Thread.currentThread().name
                        true
                    },
                    ioDispatcher = dispatcher,
                )
            }

            assertTrue(cleared)
        }
        assertNotEquals(callerThread, clearThread)
        assertTrue(clearThread.startsWith("run-history-test-io"))
    }
}
