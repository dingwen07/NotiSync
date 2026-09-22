package net.extrawdw.apps.notisync.data

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPagingSourceTest {
    @Test
    fun refreshKeyUsesVisibleRecordBeyondTheFirstPage() {
        val source = source()
        val pages = listOf(
            PagingSource.LoadResult.Page(data = (0 until 50).toList(), prevKey = null, nextKey = 49),
            PagingSource.LoadResult.Page(data = (50 until 100).toList(), prevKey = 50, nextKey = 99),
            PagingSource.LoadResult.Page(data = (100 until 150).toList(), prevKey = 100, nextKey = 149),
        )
        val state = PagingState(pages, anchorPosition = 112, config = PagingConfig(50), leadingPlaceholderCount = 0)
        assertEquals(112, source.getRefreshKey(state))
        assertNull(source.getRefreshKey(PagingState(pages, null, PagingConfig(50), 0)))
    }

    @Test
    fun refreshKeepsAnchorInBoundedWindowAndBothDirectionsCanContinue() = runBlocking {
        val source = source()
        val refreshed = source.load(PagingSource.LoadParams.Refresh(95, 50, false))
            as PagingSource.LoadResult.Page<Int, Int>
        assertEquals((70 until 120).toList(), refreshed.data)
        assertEquals(70, refreshed.prevKey)
        assertEquals(119, refreshed.nextKey)

        val newer = source.load(PagingSource.LoadParams.Prepend(requireNotNull(refreshed.prevKey), 50, false))
            as PagingSource.LoadResult.Page<Int, Int>
        assertEquals((20 until 70).toList(), newer.data)
        assertEquals(20, newer.prevKey)
        assertEquals(69, newer.nextKey)

        val older = source.load(PagingSource.LoadParams.Append(requireNotNull(refreshed.nextKey), 50, false))
            as PagingSource.LoadResult.Page<Int, Int>
        assertEquals((120 until 170).toList(), older.data)
        assertEquals(120, older.prevKey)
        assertEquals(169, older.nextKey)
    }

    @Test
    fun refreshNearNewestFillsPageFromOlderRowsAndDoesNotOfferAnEmptyPrepend() = runBlocking {
        val page = source().load(PagingSource.LoadParams.Refresh(3, 50, false)) as PagingSource.LoadResult.Page<Int, Int>
        assertEquals((0 until 50).toList(), page.data)
        assertNull(page.prevKey)
        assertEquals(49, page.nextKey)
    }

    @Test
    fun deletingAnchorKeepsNeighbouringWindowInsteadOfJumpingToNewestPage() = runBlocking {
        val page = source((0 until 200).filterNot { it == 95 })
            .load(PagingSource.LoadParams.Refresh(95, 50, false)) as PagingSource.LoadResult.Page<Int, Int>
        assertEquals((70 until 95).toList() + (96 until 121).toList(), page.data)
        assertEquals(70, page.prevKey)
        assertEquals(120, page.nextKey)
    }

    @Test
    fun failedSecondHalfOfRefreshReturnsErrorWithoutPublishingPartialWindow() = runBlocking {
        val failure = IOException("Refresh could not load older rows")
        val source = HistoryPagingSource<Int, Int>(cursorOf = { it }) { _, _, direction, _ ->
            if (direction == HistoryDirection.OLDER) throw failure
            HistoryPage((70 until 95).toList(), 70)
        }
        val result = source.load(PagingSource.LoadParams.Refresh(95, 50, false))
        assertTrue(result is PagingSource.LoadResult.Error)
        // Coroutine stack-trace recovery can copy exceptions across dispatcher boundaries.
        val error = (result as PagingSource.LoadResult.Error<Int, Int>).throwable
        assertTrue(error is IOException)
        assertEquals(failure.message, error.message)
    }

    private fun source(records: List<Int> = (0 until 200).toList()) =
        HistoryPagingSource<Int, Int>(cursorOf = { it }) { limit, cursor, direction, inclusive ->
            val matching = records.filter { id ->
                cursor == null || (inclusive && id == cursor) || when (direction) {
                    HistoryDirection.OLDER -> id > cursor
                    HistoryDirection.NEWER -> id < cursor
                }
            }
            val items = if (direction == HistoryDirection.OLDER) matching.take(limit) else matching.takeLast(limit)
            val next = if (matching.size > limit) {
                if (direction == HistoryDirection.OLDER) items.last() else items.first()
            } else null
            HistoryPage(items, next)
        }
}
