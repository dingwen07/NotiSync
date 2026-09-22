package net.extrawdw.apps.notisync.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.apps.notisync.testsupport.RoomStorageTestContext
import net.extrawdw.apps.notisync.testsupport.initializeOperationalTestDatabase
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunStoreTest {
    private val context: Context = RoomStorageTestContext(
        ApplicationProvider.getApplicationContext(),
        "run-store",
    )

    @Before
    fun clearBefore() {
        context.deleteDatabase(DB_NAME)
        initializeOperationalTestDatabase(context)
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
    fun everyReceivedRevisionIsRetainedAndDuplicateBodiesCannotReplaceIt() {
        var clock = 3_000L
        val key = RunKey("host", "run-1")
        val latest = running(revision = 5).copy(argv = listOf("new-command"), cwd = "/new")
        val older = running(revision = 2).copy(argv = listOf("old-command"), cwd = "/old")
        RunStore(context, now = { clock }).use { store ->
            assertEquals(RunApplyResult.INSERTED, store.apply(latest))
            store.markInactive(key)
            clock = 4_000
            assertEquals(RunApplyResult.OLDER, store.apply(older))
            clock = 5_000
            assertEquals(RunApplyResult.OLDER, store.apply(older.copy(argv = listOf("conflicting-old"))))
            assertEquals(RunApplyResult.EQUAL, store.apply(latest.copy(argv = listOf("conflicting-current"))))
            assertEquals(latest, store.find(key)!!.state)
            assertEquals(3_000L, store.find(key)!!.receivedAt)
            assertFalse(store.find(key)!!.active)
            assertFalse(store.find(key)!!.presentationPending)
            assertEquals(listOf(StoredRunRevision(latest, 3_000), StoredRunRevision(older, 4_000)), store.revisions(key))
        }
        RunStore(context, now = { clock }).use { reopened ->
            assertEquals(listOf(latest, older), reopened.revisions(key).map { it.state })
            assertFalse(reopened.find(key)!!.active)
            assertEquals(5L, reopened.find(key)!!.presentedRevision)
        }
    }

    @Test
    fun revisionsUseStableExclusivePagesAndAreScopedToTheirHost() {
        val key = RunKey("host", "run-1")
        RunStore(context, now = { 3_000 }).use { store ->
            listOf(5L, 1L, 3L, 2L, 4L).forEach { store.apply(running(revision = it)) }
            store.apply(running(revision = 9).copy(hostClientId = ClientId("another-host")))
            val first = store.revisions(key, limit = 2)
            assertEquals(listOf(5L, 4L), first.map { it.state.revision })
            val second = store.revisions(key, limit = 2, beforeRevision = first.last().state.revision)
            assertEquals(listOf(3L, 2L), second.map { it.state.revision })
            assertEquals(listOf(1L), store.revisions(key, beforeRevision = 2).map { it.state.revision })
            assertTrue(store.revisions(key, beforeRevision = 1).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { store.revisions(key, limit = 0) }
        }
    }

    @Test
    fun revisionInsertFailureRollsBackSessionCreationAndCurrentPointer() {
        val key = RunKey("host", "run-1")
        RunStore(context, now = { 3_000 }).use { store ->
            store.apply(running(revision = 1))
            store.writableDatabase.execSQL(
                "CREATE TRIGGER reject_test_run_revision BEFORE INSERT ON run_revisions " +
                    "WHEN NEW.revision=2 BEGIN SELECT RAISE(ABORT, 'test failure'); END",
            )
            assertThrows(Exception::class.java) { store.apply(running(revision = 2)) }
            assertThrows(Exception::class.java) { store.apply(running(runId = "new-session", revision = 2)) }
            assertEquals(1L, store.find(key)!!.state.revision)
            assertEquals(listOf(1L), store.revisions(key).map { it.state.revision })
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM runs", emptyArray()).use {
                assertTrue(it.moveToFirst())
                assertEquals(1L, it.getLong(0))
            }
        }
        RunStore(context, now = { 3_000 }).use { reopened ->
            assertEquals(1L, reopened.find(key)!!.state.revision)
            assertEquals(1, reopened.runs.value.size)
        }
    }

    @Test
    fun deletingSessionsCascadesAllTheirRevisionsAndPreservesActiveHistory() {
        RunStore(context, now = { 3_000 }).use { store ->
            listOf("inactive", "active").forEach { runId ->
                (1L..3L).forEach { store.apply(running(runId, it)) }
            }
            val inactive = RunKey("host", "inactive")
            val active = RunKey("host", "active")
            store.markInactive(inactive)
            store.clearHistory()
            assertTrue(store.revisions(inactive).isEmpty())
            assertEquals(3, store.revisions(active).size)
            store.readableDatabase.rawQuery("PRAGMA foreign_key_check", emptyArray()).use {
                assertFalse(it.moveToFirst())
            }
        }
    }

    @Test
    fun defaultRetentionKeepsFortyDayCompletedRuns() {
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
        store.markPresented(RunKey("host", "old"), completed.revision)
        clock = fortyDays
        store.apply(running(runId = "active", revision = 1))
        store.close()
        val reopened = RunStore(context, now = { clock })

        assertEquals(listOf("active"), reopened.runs.value.map { it.state.runId })
        val expected = StoredRun(completed, receivedAt = 0, presentedRevision = completed.revision, active = false)
        assertEquals(expected, reopened.find(RunKey("host", "old")))
        assertEquals(listOf(expected), reopened.historyPage().items)
        reopened.close()
    }

    @Test
    fun configuredAgeRetentionStillDeletesExpiredCompletedRuns() {
        val retention = 30L * 24 * 60 * 60 * 1000
        var clock = 0L
        val store = RunStore(context, now = { clock }, completedRetentionMs = retention)
        store.apply(
            running(runId = "old", revision = 2).copy(
                phase = RunPhase.COMPLETED,
                updateReason = RunUpdateReason.COMPLETED,
                updatedAt = 2_000,
                endedAt = 2_000,
                exitCode = 0,
            ),
        )
        store.markPresented(RunKey("host", "old"), 2)
        clock = retention + 1
        store.prune()

        assertTrue(store.runs.value.isEmpty())
        assertTrue(store.history().isEmpty())
        assertEquals(null, store.find(RunKey("host", "old")))
        store.close()
    }

    @Test
    fun activeRunMovesToHistoryAfterThreeHoursAndNewRevisionReactivatesIt() {
        var clock = 10_000L
        val store = RunStore(context, now = { clock })
        val initial = running(revision = 1)
        val key = RunKey(initial.hostClientId.value, initial.runId)
        store.apply(initial)

        clock += RunStore.ACTIVE_STALE_AFTER_MS
        store.prune()
        assertTrue("exactly three hours is not more than three hours", store.find(key)!!.active)

        clock++
        store.prune()
        assertFalse(store.find(key)!!.active)
        assertFalse(store.find(key)!!.presentationPending)
        store.close()

        val reopened = RunStore(context, now = { clock })
        assertFalse(reopened.find(key)!!.active)
        assertEquals(RunApplyResult.UPDATED, reopened.apply(running(revision = 2)))
        assertTrue(reopened.find(key)!!.active)
        reopened.close()
    }

    @Test
    fun manualInactivePersistsUntilANewerRevision() {
        val store = RunStore(context, now = { 10_000L })
        val initial = running(revision = 1)
        val key = RunKey(initial.hostClientId.value, initial.runId)
        store.apply(initial)

        assertTrue(store.markInactive(key))
        assertFalse(store.find(key)!!.active)
        assertFalse(store.find(key)!!.presentationPending)
        assertEquals(RunApplyResult.EQUAL, store.apply(initial))
        assertFalse(store.find(key)!!.active)
        store.close()

        val reopened = RunStore(context, now = { 10_000L })
        assertFalse(reopened.find(key)!!.active)
        reopened.apply(running(revision = 2))
        assertTrue(reopened.find(key)!!.active)
        reopened.close()
    }

    @Test
    fun clearHistoryDeletesInactiveRunsOnly() {
        val store = RunStore(context, now = { 10_000L })
        val manuallyInactive = running(runId = "manual", revision = 1)
        store.apply(manuallyInactive)
        store.markInactive(RunKey(manuallyInactive.hostClientId.value, manuallyInactive.runId))
        store.apply(
            running(runId = "completed", revision = 2).copy(
                phase = RunPhase.COMPLETED,
                updateReason = RunUpdateReason.COMPLETED,
                updatedAt = 2_000,
                endedAt = 2_000,
                exitCode = 0,
            )
        )
        store.apply(running(runId = "active", revision = 1))

        store.clearHistory()

        assertEquals(listOf("active"), store.runs.value.map { it.state.runId })
        store.close()
        val reopened = RunStore(context, now = { 10_000L })
        assertEquals(listOf("active"), reopened.runs.value.map { it.state.runId })
        reopened.close()
    }

    @Test
    fun defaultCompletedLogIsNotCappedAtFifty() {
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
            store.markPresented(RunKey("host", "completed-$index"), 2)
        }
        repeat(3) { index -> store.apply(running(runId = "active-$index", revision = 1)) }

        val completedIds = store.history(limit = 100).map { it.state.runId }.toSet()
        assertEquals(55, completedIds.size)
        assertTrue("completed-0" in completedIds)
        assertTrue("completed-54" in completedIds)
        assertEquals(3, store.runs.value.count { it.active })
        assertTrue(store.runs.value.all { it.active })
        store.close()

        val reopened = RunStore(context, now = { clock })
        assertEquals(55, reopened.history(limit = 100).size)
        assertEquals(3, reopened.runs.value.count { it.active })
        assertTrue(reopened.runs.value.all { it.active })
        reopened.close()
    }

    @Test
    fun configuredCompletedRowLimitStillDeletesOldestRuns() {
        var clock = 1_000L
        val store = RunStore(context, now = { clock }, maxCompletedRuns = 3)
        repeat(5) { index ->
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
            store.markPresented(RunKey("host", "completed-$index"), 2)
        }

        assertEquals("message processing must not prune history", 5, store.history().size)
        assertTrue(store.runs.value.isEmpty())
        store.prune()

        assertEquals(
            setOf("completed-2", "completed-3", "completed-4"),
            store.history().map { it.state.runId }.toSet(),
        )
        store.close()
    }

    @Test
    fun maintenanceAppliesAgeAndCountLimitsTogetherWithoutDeletingActiveRuns() {
        var clock = 0L
        val store = RunStore(context, now = { clock }, completedRetentionMs = 100L, maxCompletedRuns = 2)
        repeat(5) { index ->
            clock = index * 50L
            store.apply(
                running(runId = "completed-$index", revision = 2).copy(
                    phase = RunPhase.COMPLETED,
                    updateReason = RunUpdateReason.COMPLETED,
                    updatedAt = 2_000L,
                    endedAt = 2_000L,
                    exitCode = 0,
                ),
            )
            store.markPresented(RunKey("host", "completed-$index"), 2)
        }
        store.apply(running(runId = "active", revision = 1))
        assertEquals(5, store.history().size)
        assertEquals(listOf("active"), store.runs.value.map { it.state.runId })

        store.prune()
        assertEquals(setOf("completed-3", "completed-4"), store.history().map { it.state.runId }.toSet())
        assertEquals(listOf("active"), store.runs.value.map { it.state.runId })
        store.close()
        RunStore(context, now = { clock }, completedRetentionMs = 100L, maxCompletedRuns = 2).use { reopened ->
            assertEquals(setOf("completed-3", "completed-4"), reopened.history().map { it.state.runId }.toSet())
            assertEquals(listOf("active"), reopened.runs.value.map { it.state.runId })
        }
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
        private const val DB_NAME = OperationalDatabase.DATABASE_NAME
    }
}
