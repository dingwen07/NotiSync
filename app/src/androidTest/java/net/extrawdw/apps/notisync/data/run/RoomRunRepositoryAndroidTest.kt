package net.extrawdw.apps.notisync.data.run

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.data.storage.operational.OperationalDatabase
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunTerminalSnapshot
import net.extrawdw.notisync.protocol.RunUpdateReason
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRunRepositoryAndroidTest {
    private lateinit var database: OperationalDatabase
    private lateinit var repository: RunRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<OperationalDatabase>(
            ApplicationProvider.getApplicationContext<Context>(),
        ).setDriver(AndroidSQLiteDriver())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        repository = RoomRunRepository(database.runDao()) { clock }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun canonicalBytesDriveLwwEqualityAndConflict() = runBlocking {
        val revisionOne = running(revision = 1)
        val revisionTwoState = running(revision = 2)
        val sameRevisionConflict = running(revision = 2, terminalText = "different")

        assertEquals(RunApplyResult.INSERTED, repository.apply(revisionOne))
        clock++
        assertEquals(RunApplyResult.UPDATED, repository.apply(revisionTwoState))
        clock++
        assertEquals(RunApplyResult.EQUAL, repository.apply(revisionTwoState.copy()))
        assertEquals(RunApplyResult.OLDER, repository.apply(revisionOne))
        assertEquals(RunApplyResult.CONFLICT, repository.apply(sameRevisionConflict))

        val entity = requireNotNull(database.runDao().find("host-1", "run-1"))
        assertArrayEquals(ProtocolCodec.encodeToCbor(revisionTwoState), entity.payload)
        val stored = requireNotNull(repository.find(RunKey(ClientId("host-1"), "run-1")))
        assertEquals(revisionTwoState, stored.state)
        assertEquals(listOf(stored), repository.observeAll().first())
    }

    @Test
    fun presentationAndManualHistoryStateSurviveEqualReplayAndNewerRevisionReactivates() = runBlocking {
        val key = RunKey(ClientId("host-1"), "run-1")
        assertEquals(RunApplyResult.INSERTED, repository.apply(running(revision = 1)))

        assertTrue(repository.markPresented(key, revision = 1))
        assertFalse(repository.markPresented(key, revision = 1))
        assertFalse(repository.markPresented(key, revision = 2))
        assertTrue(repository.markInactive(key))
        assertFalse(repository.markInactive(key))
        var stored = requireNotNull(repository.find(key))
        assertFalse(stored.active)
        assertFalse(stored.presentationPending)

        assertEquals(RunApplyResult.EQUAL, repository.apply(running(revision = 1)))
        assertFalse(requireNotNull(repository.find(key)).active)

        clock++
        assertEquals(RunApplyResult.UPDATED, repository.apply(running(revision = 2)))
        stored = requireNotNull(repository.find(key))
        assertTrue(stored.active)
        assertEquals(1L, stored.presentedRevision)
        assertTrue(stored.presentationPending)
    }

    @Test
    fun clearHistoryDeletesOnlyInactiveRows() = runBlocking {
        repository.apply(running(runId = "manual", revision = 1))
        repository.markInactive(RunKey(ClientId("host-1"), "manual"))
        clock++
        repository.apply(completed(runId = "completed", revision = 1))
        clock++
        repository.apply(running(runId = "active", revision = 1))

        assertEquals(2, repository.clearHistory())

        assertNull(repository.find(RunKey(ClientId("host-1"), "manual")))
        assertNull(repository.find(RunKey(ClientId("host-1"), "completed")))
        assertTrue(requireNotNull(repository.find(RunKey(ClientId("host-1"), "active"))).active)
        assertEquals(0, repository.clearHistory())
    }

    @Test
    fun applyAtomicallyRetainsNewestFiftyCompletedRowsAndAllActiveRows() = runBlocking {
        repeat(3) { index ->
            assertEquals(
                RunApplyResult.INSERTED,
                repository.apply(running(runId = "active-$index", revision = 1)),
            )
            clock++
        }
        repeat(55) { index ->
            assertEquals(
                RunApplyResult.INSERTED,
                repository.apply(
                    completed(runId = "completed-$index", revision = 1, updatedAt = 5_000L + index),
                ),
            )
            clock++
        }

        val rows = repository.observeAll().first()
        val completedIds = rows.filterNot(StoredRun::active).map { it.state.runId }.toSet()
        assertEquals(50, completedIds.size)
        assertFalse("completed-0" in completedIds)
        assertFalse("completed-4" in completedIds)
        assertTrue("completed-5" in completedIds)
        assertTrue("completed-54" in completedIds)
        assertEquals(3, rows.count(StoredRun::active))
    }

    @Test
    fun pruneUsesStrictStaleAndCompletedAgeBoundaries() = runBlocking {
        val key = RunKey(ClientId("host-1"), "stale")
        repository.apply(running(runId = "stale", revision = 1))
        val receivedAt = clock

        assertEquals(0, repository.prune(receivedAt + THREE_HOURS_MILLIS))
        assertTrue(requireNotNull(repository.find(key)).active)
        assertEquals(0, repository.prune(receivedAt + THREE_HOURS_MILLIS + 1))
        val inactive = requireNotNull(repository.find(key))
        assertFalse(inactive.active)
        assertFalse(inactive.presentationPending)

        assertEquals(0, repository.prune(receivedAt + THIRTY_DAYS_MILLIS))
        assertTrue(repository.find(key) != null)
        assertEquals(1, repository.prune(receivedAt + THIRTY_DAYS_MILLIS + 1))
        assertNull(repository.find(key))
    }

    private companion object {
        const val THREE_HOURS_MILLIS = 3L * 60 * 60 * 1_000
        const val THIRTY_DAYS_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}

private fun running(
    runId: String = "run-1",
    revision: Long,
    terminalText: String = "",
): RunState = RunState(
    hostClientId = ClientId("host-1"),
    runId = runId,
    revision = revision,
    phase = RunPhase.RUNNING,
    updateReason = if (revision == 1L) RunUpdateReason.INITIAL else RunUpdateReason.PERIODIC,
    startedAt = 1_000,
    updatedAt = 2_000 + revision,
    argv = listOf("make"),
    cwd = "/work",
    usesPty = false,
    terminal = RunTerminalSnapshot(
        text = terminalText,
        truncated = false,
        rawBytesSeen = terminalText.length.toLong(),
    ),
)

private fun completed(runId: String, revision: Long, updatedAt: Long = 4_000 + revision): RunState =
    RunState(
        hostClientId = ClientId("host-1"),
        runId = runId,
        revision = revision,
        phase = RunPhase.COMPLETED,
        updateReason = RunUpdateReason.COMPLETED,
        startedAt = 1_000,
        updatedAt = updatedAt,
        argv = listOf("make"),
        cwd = "/work",
        usesPty = false,
        terminal = RunTerminalSnapshot("done", truncated = false, rawBytesSeen = 4),
        endedAt = updatedAt - 1,
        exitCode = 0,
    )
