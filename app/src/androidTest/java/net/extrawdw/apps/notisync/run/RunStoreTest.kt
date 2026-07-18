package net.extrawdw.apps.notisync.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearBefore() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun clearAfter() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun higherRevisionWinsAndSurvivesReopen() {
        val store = RunStore(context, now = { 3_000 })
        assertEquals(RunApplyResult.INSERTED, store.apply(running(revision = 1)))
        assertEquals(RunApplyResult.UPDATED, store.apply(running(revision = 2)))
        assertEquals(RunApplyResult.EQUAL, store.apply(running(revision = 2)))
        assertEquals(RunApplyResult.OLDER, store.apply(running(revision = 1)))
        store.close()

        val reopened = RunStore(context, now = { 3_000 })
        assertEquals(2, reopened.runs.value.single().state.revision)
        reopened.close()
    }

    @Test
    fun presentationCheckpointSurvivesReopen() {
        val state = running(revision = 4)
        val key = RunKey(state.hostClientId.value, state.runId)
        val store = RunStore(context, now = { 3_000 })
        store.apply(state)
        assertTrue(store.runs.value.single().presentationPending)

        store.markPresented(key, state.revision)
        assertFalse(store.runs.value.single().presentationPending)
        store.close()

        val reopened = RunStore(context, now = { 3_000 })
        assertEquals(state.revision, reopened.runs.value.single().presentedRevision)
        assertFalse(reopened.runs.value.single().presentationPending)
        reopened.close()
    }

    @Test
    fun retentionDropsOldCompletedRunsButExemptsActiveRuns() {
        val fortyDays = 40L * 24 * 60 * 60 * 1000
        var clock = 0L
        val store = RunStore(context, now = { clock })
        val completed = running(runId = "old", revision = 2).copy(
            phase = RunPhase.COMPLETED,
            updateReason = RunUpdateReason.COMPLETED,
            updatedAt = 2_000,
            endedAt = 2_000,
            exitCode = 0,
        )
        store.apply(completed)
        store.apply(running(runId = "active", revision = 1))
        store.close()
        clock = fortyDays
        val reopened = RunStore(context, now = { clock })

        assertEquals(listOf("active"), reopened.runs.value.map { it.state.runId })
        assertTrue(reopened.runs.value.single().active)
        reopened.close()
    }

    @Test
    fun completedLogKeepsNewestFiftyAndExemptsActiveRuns() {
        var clock = 1_000L
        val store = RunStore(context, now = { clock })
        repeat(55) { index ->
            clock++
            store.apply(
                running(runId = "completed-$index", revision = 2).copy(
                    phase = RunPhase.COMPLETED,
                    updateReason = RunUpdateReason.COMPLETED,
                    updatedAt = 2_000L + index,
                    endedAt = 2_000L + index,
                    exitCode = 0,
                ),
            )
        }
        repeat(3) { index -> store.apply(running(runId = "active-$index", revision = 1)) }

        val completedIds = store.runs.value.filterNot { it.active }.map { it.state.runId }.toSet()
        assertEquals(50, completedIds.size)
        assertFalse("completed-0" in completedIds)
        assertFalse("completed-4" in completedIds)
        assertTrue("completed-5" in completedIds)
        assertTrue("completed-54" in completedIds)
        assertEquals(3, store.runs.value.count { it.active })
        store.close()

        val reopened = RunStore(context, now = { clock })
        assertEquals(50, reopened.runs.value.count { !it.active })
        assertEquals(3, reopened.runs.value.count { it.active })
        reopened.close()
    }

    @Test
    fun storageCapUsesDatabaseFootprintAndNeverPrunesActiveRuns() {
        val budget = 512L * 1024
        val store = RunStore(context, now = { 10_000 }, maxStorageBytes = budget)
        val active = running(runId = "active", revision = 1).copy(
            terminal = RunTerminalSnapshot("active".repeat(1_024), false, 6_144),
        )
        store.apply(active)
        repeat(40) { index ->
            store.apply(
                running(runId = "completed-$index", revision = 2).copy(
                    phase = RunPhase.COMPLETED,
                    updateReason = RunUpdateReason.COMPLETED,
                    updatedAt = 2_000L + index,
                    endedAt = 2_000L + index,
                    exitCode = 0,
                    terminal = RunTerminalSnapshot(
                        "$index:" + "x".repeat(24 * 1024),
                        truncated = true,
                        rawBytesSeen = 24L * 1024,
                    ),
                )
            )
        }

        store.prune()

        assertTrue(store.runs.value.any { it.state.runId == active.runId && it.active })
        assertTrue(store.runs.value.count { !it.active } < 40)
        assertTrue(store.storageUsage().accountedBytes <= budget)
        store.close()
    }

    private fun running(runId: String = "run-1", revision: Long) = RunState(
        hostClientId = ClientId("host"),
        runId = runId,
        revision = revision,
        phase = RunPhase.RUNNING,
        updateReason = if (revision == 1L) RunUpdateReason.INITIAL else RunUpdateReason.PERIODIC,
        startedAt = 1_000,
        updatedAt = 1_000,
        argv = listOf("make"),
        cwd = "/work",
        usesPty = false,
        terminal = RunTerminalSnapshot("", truncated = false, rawBytesSeen = 0),
    )

    companion object {
        private const val DB_NAME = "runs.db"
    }
}
