package net.extrawdw.apps.notisync.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.extrawdw.apps.notisync.data.HistoryDirection
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunStorePagingTest {
    private val context: Context = RoomStorageTestContext(ApplicationProvider.getApplicationContext(), "run-paging")

    @Before
    fun prepare() {
        context.deleteDatabase(OperationalDatabase.DATABASE_NAME)
        initializeOperationalTestDatabase(context)
    }

    @After
    fun cleanup() { context.deleteDatabase(OperationalDatabase.DATABASE_NAME) }

    @Test
    fun tiedHistoryPagesHaveNoMissingOrRepeatedSessionsAndDoNotFillLiveCache() {
        val expected = buildList {
            RunStore(context, now = { 9_000 }).use { store ->
                repeat(115) { index ->
                    val state = running("run-${index.toString().padStart(3, '0')}").copy(
                        hostClientId = ClientId("host-${index % 2}"), updatedAt = 1_000L + index % 3,
                    )
                    store.apply(state)
                    store.markInactive(RunKey(state.hostClientId.value, state.runId))
                    add(state)
                }
                store.apply(running("active"))
                assertEquals(listOf("active"), store.runs.value.map { it.state.runId })
            }
        }.sortedWith(compareByDescending<RunState> { it.updatedAt }.thenBy { it.hostClientId.value }.thenBy { it.runId })

        RunStore(context, now = { 9_000 }).use { store ->
            assertEquals(listOf("active"), store.runs.value.map { it.state.runId })
            val first = store.history()
            val second = store.history(after = RunHistoryCursor.after(first.last()))
            val third = store.history(after = RunHistoryCursor.after(second.last()))
            assertEquals(listOf(50, 50, 15), listOf(first.size, second.size, third.size))
            assertEquals(expected, (first + second + third).map { it.state })
            assertTrue(store.history(after = RunHistoryCursor.after(third.last())).isEmpty())
            val oldest = third.last()
            assertEquals(oldest, store.find(oldest.key))
            assertEquals(1, store.runs.value.size)
        }
    }

    @Test
    fun unseenOlderRevisionInvalidatesHistoryWithoutReactivatingOrRefreshingSession() {
        var now = 3_000L
        RunStore(context, now = { now }).use { store ->
            val current = running("inactive", revision = 5)
            val key = RunKey("host", current.runId)
            store.apply(current)
            store.markInactive(key)
            val before = store.changeVersion.value
            now = 4_000
            assertEquals(RunApplyResult.OLDER, store.apply(running("inactive", revision = 2)))
            assertTrue(store.changeVersion.value > before)
            assertFalse(store.find(key)!!.active)
            assertEquals(3_000L, store.find(key)!!.receivedAt)
            assertTrue(store.runs.value.isEmpty())
            val after = store.changeVersion.value
            store.apply(running("inactive", revision = 2))
            assertEquals(after, store.changeVersion.value)
        }
    }

    @Test
    fun sessionPagesRebuildAnAnchoredWindowAndPageBothWaysAcrossTiedIdentities() {
        RunStore(context, now = { 9_000 }).use { store ->
            repeat(121) { index ->
                val state = running("run-${index.toString().padStart(3, '0')}").copy(hostClientId = ClientId("host-${index % 3}"))
                store.apply(state)
                store.markInactive(RunKey(state.hostClientId.value, state.runId))
            }
            val all = store.history(limit = 200)
            val anchor = RunHistoryCursor.after(all[60])
            val newer = store.historyPage(25, anchor, HistoryDirection.NEWER)
            val older = store.historyPage(25, anchor, HistoryDirection.OLDER, includeCursor = true)
            assertEquals(all.subList(35, 85), newer.items + older.items)
            assertEquals(RunHistoryCursor.after(all[35]), newer.nextCursor)
            assertEquals(RunHistoryCursor.after(all[84]), older.nextCursor)
            assertEquals(all.subList(10, 35), store.historyPage(25, newer.nextCursor, HistoryDirection.NEWER).items)
            assertEquals(all.subList(85, 110), store.historyPage(25, older.nextCursor).items)
            assertEquals(all.subList(36, 61), store.historyPage(25, anchor, HistoryDirection.NEWER, includeCursor = true).items)
            assertEquals(all.take(10), store.historyPage(25, RunHistoryCursor.after(all[10]), HistoryDirection.NEWER).items)
            assertEquals(null, store.historyPage(25, RunHistoryCursor.after(all[10]), HistoryDirection.NEWER).nextCursor)

            // Neither deleting the anchor nor inserting newer history makes the cursor depend on OFFSET.
            store.writableDatabase.delete("runs", "host_client=? AND run_id=?", arrayOf(anchor.hostClientId, anchor.runId))
            val inserted = running("newest").copy(updatedAt = 2_000)
            store.apply(inserted)
            store.markInactive(RunKey("host", "newest"))
            val refreshed = store.historyPage(25, anchor, HistoryDirection.NEWER).items +
                store.historyPage(25, anchor, HistoryDirection.OLDER, includeCursor = true).items
            assertEquals(all.subList(35, 60) + all.subList(61, 86), refreshed)
            assertEquals(50, refreshed.map { it.key }.distinct().size)
        }
    }

    @Test
    fun revisionPagesRebuildAnAnchoredWindowAndSupportPrependingAfterHistoryChanges() {
        RunStore(context, now = { 9_000 }).use { store ->
            val key = RunKey("host", "revisions")
            (1L..120L).forEach { store.apply(running(key.runId, it)) }
            val newer = store.revisionPage(key, 25, 60, HistoryDirection.NEWER)
            val older = store.revisionPage(key, 25, 60, HistoryDirection.OLDER, includeCursor = true)
            assertEquals((85L downTo 36L).toList(), (newer.items + older.items).map { it.state.revision })
            assertEquals(85L, newer.nextCursor)
            assertEquals(36L, older.nextCursor)
            assertEquals((110L downTo 86L).toList(), store.revisionPage(key, 25, newer.nextCursor, HistoryDirection.NEWER).items.map { it.state.revision })
            assertEquals((35L downTo 11L).toList(), store.revisionPage(key, 25, older.nextCursor).items.map { it.state.revision })
            store.writableDatabase.delete("run_revisions", "host_client=? AND run_id=? AND revision=?", arrayOf("host", key.runId, "60"))
            store.apply(running(key.runId, 121))
            val refreshed = store.revisionPage(key, 25, 60, HistoryDirection.NEWER).items +
                store.revisionPage(key, 25, 60, HistoryDirection.OLDER, includeCursor = true).items
            assertEquals((85L downTo 61L).toList() + (59L downTo 35L).toList(), refreshed.map { it.state.revision })
            val end = store.revisionPage(key, 25, 110, HistoryDirection.NEWER)
            assertEquals((121L downTo 111L).toList(), end.items.map { it.state.revision })
            assertEquals(null, end.nextCursor)
        }
    }

    @Test
    fun bothHistoryDirectionsSeekTheirTimestampRangeWithoutRescanningSessions() {
        RunStore(context, now = { 9_000 }).use { store ->
            val after = RunHistoryCursor(5_000, "host", "run-50")
            HistoryDirection.entries.forEach { direction ->
                val details = store.readableDatabase.rawQuery(
                    "EXPLAIN QUERY PLAN " + RunStore.historyQuery(after, direction),
                    arrayOf("5000", "5000", "5000", "host", "host", "run-50", "51"),
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
                }
                val comparison = if (direction == HistoryDirection.OLDER) "<" else ">"
                assertTrue(details.toString(), details.any {
                    it.contains("SEARCH session USING INDEX runs_order_idx") &&
                        it.contains("active=?") && Regex("updated_at$comparison=?\\?").containsMatchIn(it)
                })
                assertFalse(details.toString(), details.any { it.contains("TEMP B-TREE") })
            }
        }
    }

    @Test
    fun presentationReconciliationAndClearHistoryIncludeRowsOutsideVisiblePage() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dismissed = mutableSetOf<RunKey>()
        val rendered = mutableSetOf<String>()
        try {
            RunStore(context, now = { 9_000 }).use { store ->
                repeat(61) { index ->
                    val state = running("old-$index")
                    store.apply(state)
                    store.markInactive(RunKey("host", state.runId))
                }
                val pending = running("pending").copy(
                    phase = RunPhase.COMPLETED, updateReason = RunUpdateReason.COMPLETED,
                    endedAt = 1_000, exitCode = 0,
                )
                store.apply(pending)
            }
            RunStore(context, now = { 9_000 }).use { store ->
                assertEquals(listOf("pending"), store.runs.value.map { it.state.runId })
                val engine = RunEngine(
                    repository = store,
                    presenter = object : RunStatePresenter {
                        override fun render(state: RunState): Boolean { rendered += state.runId; return true }
                        override fun dismiss(key: RunKey) { dismissed += key }
                    },
                    scope = scope,
                    sendControl = { true },
                )
                engine.reconcilePendingPresentations()
                assertEquals(setOf("pending"), rendered)
                assertEquals(61, dismissed.size)
                assertTrue(store.runs.value.isEmpty())
                assertNotNull(store.find(RunKey("host", "old-60")))
                assertTrue(engine.clearHistory())
                assertEquals(62, dismissed.size)
                assertTrue(store.history().isEmpty())
                assertTrue(store.revisions(RunKey("host", "old-60")).isEmpty())
            }
        } finally {
            scope.cancel()
        }
    }

    private fun running(id: String, revision: Long = 1) = RunState(
        hostClientId = ClientId("host"), runId = id, revision = revision,
        phase = RunPhase.RUNNING, updateReason = RunUpdateReason.PERIODIC,
        startedAt = 1_000, updatedAt = 1_000, argv = listOf("make"), cwd = "/work", usesPty = false,
        terminal = RunTerminalSnapshot("", false, 0),
    )
}
