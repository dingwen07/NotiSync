package net.extrawdw.apps.notisync.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import net.extrawdw.apps.notisync.R
import net.extrawdw.apps.notisync.data.HistoryPage
import net.extrawdw.apps.notisync.data.HistoryDirection
import net.extrawdw.apps.notisync.data.historyPager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real LazyPagingItems viewport hints and the production retry footer. */
@RunWith(AndroidJUnit4::class)
class HistoryAutomaticPagingTest {
    @get:Rule
    val compose = createComposeRule()

    private val visibleItemCount = AtomicInteger(0)
    private val appendFailed = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)
    private val completedRefreshes = AtomicInteger(0)
    private val visibleAnchor = AtomicInteger(Int.MIN_VALUE)
    private val visibleOffset = AtomicInteger(0)
    private val firstLoadedId = AtomicInteger(Int.MIN_VALUE)
    private val refresh = AtomicReference<(() -> Unit)?>(null)
    private val loadStates = AtomicReference("not composed")
    private var activeSource: SyntheticHistory? = null

    @Test
    fun scrollingAutomaticallyLoadsBeyondInitialPageWithoutAnyLoadMoreButton() {
        val source = SyntheticHistory()
        showHistory(source)
        waitForItems(50)
        compose.waitForIdle()
        assertEquals(50, visibleItemCount.get())
        assertEquals(listOf(PageRequest(50, null)), source.requests.toList())
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)

        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(45)
        waitForItems(100)
        compose.onNodeWithText("History 45").assertIsDisplayed()

        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(95)
        waitForItems(125)
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(124)
        compose.onNodeWithText("History 124").assertIsDisplayed()
        compose.waitForIdle()

        assertEquals(
            listOf(PageRequest(50, null), PageRequest(50, 49), PageRequest(50, 99)),
            source.requests.toList(),
        )
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun appendFailureKeepsLoadedRowsAndRetryContinuesFromFailedCursor() {
        val source = SyntheticHistory(failFirstAppend = true)
        val retryText = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.history_retry)
        val errorText = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.history_load_failed)
        showHistory(source)
        waitForItems(50)

        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(45)
        compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) { appendFailed.get() }
        assertEquals(50, visibleItemCount.get())
        compose.onNodeWithText("History 50").assertDoesNotExist()
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(50)
        compose.onNodeWithText(errorText).assertIsDisplayed()
        compose.onNodeWithText(retryText).assertIsDisplayed().performClick()

        waitForItems(100)
        compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) { !appendFailed.get() }
        assertEquals(
            listOf(PageRequest(50, null), PageRequest(50, 49), PageRequest(50, 49)),
            source.requests.toList(),
        )
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(95)
        waitForItems(125)
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(124)
        compose.onNodeWithText("History 124").assertIsDisplayed()
        compose.waitForIdle()

        assertFalse(appendFailed.get())
        assertEquals(1, source.requests.count { it.after == null })
        assertEquals(PageRequest(50, 99), source.requests.last())
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun automaticRefreshRetainsDeepVisibleAnchorAndBothDirectionsRemainReachable() {
        val source = SyntheticHistory(totalRecords = 180)
        showHistory(source)
        waitForItems(50)
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(45)
        waitForItems(100)
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(95)
        waitForItems(150)
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(110)
        compose.waitForIdle()
        val originalAnchor = visibleAnchor.get()
        val originalOffset = visibleOffset.get()
        val completedBefore = completedRefreshes.get()
        assertEquals(110, originalAnchor)

        source.insertNewest(-1)
        source.remove(178)
        val gate = source.pauseNextLoad()
        compose.runOnIdle { requireNotNull(refresh.get()).invoke() }
        compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) { refreshing.get() }
        // Existing content remains available while the replacement generation is being loaded.
        compose.onNodeWithText("History $originalAnchor").assertIsDisplayed()
        gate.complete(Unit)
        compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) { completedRefreshes.get() > completedBefore }
        compose.waitForIdle()

        assertEquals(originalAnchor, visibleAnchor.get())
        assertEquals(originalOffset, visibleOffset.get())
        compose.onNodeWithText("History $originalAnchor").assertIsDisplayed()
        assertEquals(1, source.requests.count { it.includeCursor })
        assertFalse("Refresh must not reload the entire visited window", visibleItemCount.get() >= 150)

        // Scroll back through newer pages, including the completion inserted before the refresh.
        repeat(3) {
            if (firstLoadedId.get() != -1) {
                val previousCount = visibleItemCount.get()
                compose.onNodeWithTag(LIST_TAG).performScrollToIndex(0)
                compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) { visibleItemCount.get() > previousCount }
            }
        }
        assertEquals(-1, firstLoadedId.get())
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(0)
        compose.onNodeWithText("History -1").assertIsDisplayed()

        // The older end is still reachable by ordinary viewport-driven append loads.
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(visibleItemCount.get() - 1)
        waitForItems(180)
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(179)
        compose.onNodeWithText("History 179").assertIsDisplayed()
        compose.onNodeWithText("History 178").assertDoesNotExist()
    }

    private fun showHistory(source: SyntheticHistory) {
        activeSource = source
        compose.setContent {
            val history = remember(source) {
                historyPager(cursorOf = { id: Int -> id }) { limit, cursor, direction, includeCursor ->
                    source.load(limit, cursor, direction, includeCursor)
                }.flow
            }.collectAsLazyPagingItems()
            val listState = rememberLazyListState()
            val itemCount = history.itemCount
            val hasAppendError = history.loadState.append is LoadState.Error
            val isRefreshing = history.loadState.refresh is LoadState.Loading
            val anchor = listState.firstVisibleItemIndex.takeIf { it < itemCount }?.let(history::peek)
            val offset = listState.firstVisibleItemScrollOffset
            val first = if (itemCount > 0) history.peek(0) else null
            val currentLoadStates = history.loadState.toString()
            SideEffect {
                visibleItemCount.set(itemCount)
                appendFailed.set(hasAppendError)
                if (refreshing.getAndSet(isRefreshing) && !isRefreshing) completedRefreshes.incrementAndGet()
                visibleAnchor.set(anchor ?: Int.MIN_VALUE)
                visibleOffset.set(offset)
                firstLoadedId.set(first ?: Int.MIN_VALUE)
                refresh.set(history::refresh)
                loadStates.set(currentLoadStates)
            }
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    LazyColumn(Modifier.fillMaxSize().testTag(LIST_TAG), state = listState) {
                        items(
                            // Read this in the lazy DSL's snapshot, alongside itemKey's live
                            // snapshot, rather than capturing a count from outer composition.
                            count = history.itemCount,
                            key = history.itemKey { it },
                            contentType = { "history_row" },
                        ) { index ->
                            history[index]?.let { id ->
                                Text("History $id", Modifier.fillMaxWidth().height(48.dp))
                            }
                        }
                        if (history.itemCount > 0) {
                            item(key = "history_load_state") {
                                HistoryLoadStateFooter(history.loadState, history::retry)
                            }
                        }
                    }
                    // A sole keyed loading footer would become the first visible item and move
                    // to the end as records arrive, causing a synthetic scroll through every page.
                    if (itemCount == 0) {
                        HistoryLoadStateFooter(history.loadState, history::retry)
                    }
                }
            }
        }
    }

    private fun waitForItems(count: Int) {
        try {
            // Viewport hints may legitimately prefetch another bounded page before the test's
            // next observation. Exact transient item counts are not a Paging contract.
            compose.waitUntil(timeoutMillis = TIMEOUT_MILLIS) { visibleItemCount.get() >= count }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError(
                "Expected at least $count items; observed=${visibleItemCount.get()}, " +
                    "firstLoaded=${firstLoadedId.get()}, anchor=${visibleAnchor.get()}, " +
                    "requests=${activeSource?.requests}, loadStates=${loadStates.get()}",
                failure,
            )
        }
    }

    private data class PageRequest(
        val limit: Int,
        val after: Int?,
        val direction: HistoryDirection = HistoryDirection.OLDER,
        val includeCursor: Boolean = false,
    )

    private class SyntheticHistory(failFirstAppend: Boolean = false, totalRecords: Int = 125) {
        val requests = CopyOnWriteArrayList<PageRequest>()
        private val records = CopyOnWriteArrayList((0 until totalRecords).toList())
        private val shouldFailAppend = AtomicBoolean(failFirstAppend)
        private val nextLoadGate = AtomicReference<CompletableDeferred<Unit>?>(null)

        fun insertNewest(id: Int) { records.add(0, id) }
        fun remove(id: Int) { records.removeAll { it == id } }
        fun pauseNextLoad(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also(nextLoadGate::set)

        suspend fun load(
            limit: Int,
            cursor: Int?,
            direction: HistoryDirection,
            includeCursor: Boolean,
        ): HistoryPage<Int, Int> {
            requests += PageRequest(limit, cursor, direction, includeCursor)
            nextLoadGate.getAndSet(null)?.await()
            if (direction == HistoryDirection.OLDER && cursor == 49 && shouldFailAppend.compareAndSet(true, false)) {
                throw IOException("Synthetic append failure")
            }
            val matching = records.filter {
                cursor == null || (includeCursor && it == cursor) || when (direction) {
                    HistoryDirection.OLDER -> it > cursor
                    HistoryDirection.NEWER -> it < cursor
                }
            }
            val items = if (direction == HistoryDirection.OLDER) matching.take(limit) else matching.takeLast(limit)
            val next = if (matching.size > limit) {
                if (direction == HistoryDirection.OLDER) items.lastOrNull() else items.firstOrNull()
            } else null
            return HistoryPage(items, next)
        }
    }

    private companion object {
        const val LIST_TAG = "automatic_history"
        const val TIMEOUT_MILLIS = 15_000L
    }
}
