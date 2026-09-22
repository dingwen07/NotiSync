package net.extrawdw.apps.notisync.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HistoryDirection { OLDER, NEWER }

/**
 * Items always use display order, newest first. [nextCursor] continues in the requested direction:
 * the last displayed row for OLDER, or the first displayed row for NEWER.
 */
data class HistoryPage<T, C>(val items: List<T>, val nextCursor: C?)

/** Paging owns prefetch, cancellation, retries, and the list's loaded pages. */
internal fun <T : Any, C : Any> historyPager(
    pageSize: Int = 50,
    cursorOf: (T) -> C,
    loadPage: suspend (limit: Int, cursor: C?, direction: HistoryDirection, includeCursor: Boolean) -> HistoryPage<T, C>,
): Pager<C, T> = Pager(
    config = PagingConfig(
        pageSize = pageSize,
        initialLoadSize = pageSize,
        prefetchDistance = 10,
        enablePlaceholders = false,
    ),
    pagingSourceFactory = { HistoryPagingSource(cursorOf, loadPage) },
)

internal class HistoryPagingSource<T : Any, C : Any>(
    private val cursorOf: (T) -> C,
    private val loadPage: suspend (limit: Int, cursor: C?, direction: HistoryDirection, includeCursor: Boolean) -> HistoryPage<T, C>,
) : PagingSource<C, T>() {
    override fun getRefreshKey(state: PagingState<C, T>): C? =
        state.anchorPosition?.let(state::closestItemToPosition)?.let(cursorOf)

    override suspend fun load(params: LoadParams<C>): LoadResult<C, T> = try {
        withContext(Dispatchers.IO) {
            when (params) {
                is LoadParams.Refresh -> refresh(params)
                is LoadParams.Append -> {
                    val page = loadPage(params.loadSize, params.key, HistoryDirection.OLDER, false)
                    LoadResult.Page(
                        data = page.items,
                        prevKey = page.items.firstOrNull()?.let(cursorOf) ?: params.key,
                        nextKey = page.nextCursor,
                    )
                }
                is LoadParams.Prepend -> {
                    val page = loadPage(params.loadSize, params.key, HistoryDirection.NEWER, false)
                    LoadResult.Page(
                        data = page.items,
                        prevKey = page.nextCursor,
                        nextKey = page.items.lastOrNull()?.let(cursorOf) ?: params.key,
                    )
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        LoadResult.Error(failure)
    }

    private suspend fun refresh(params: LoadParams.Refresh<C>): LoadResult.Page<C, T> {
        val anchor = params.key
        if (anchor == null) {
            val page = loadPage(params.loadSize, null, HistoryDirection.OLDER, false)
            return LoadResult.Page(data = page.items, prevKey = null, nextKey = page.nextCursor)
        }
        // Keep the visible record inside the replacement generation without re-reading every
        // previously visited page. Prepending can still reach new completions above this window.
        val newerCount = params.loadSize / 2
        val newer = if (newerCount == 0) HistoryPage<T, C>(emptyList(), anchor) else {
            loadPage(newerCount, anchor, HistoryDirection.NEWER, false)
        }
        val older = loadPage(params.loadSize - newer.items.size, anchor, HistoryDirection.OLDER, true)
        return LoadResult.Page(
            data = newer.items + older.items,
            prevKey = if (newer.nextCursor != null) (newer.items + older.items).firstOrNull()?.let(cursorOf) else null,
            nextKey = older.nextCursor,
        )
    }
}
