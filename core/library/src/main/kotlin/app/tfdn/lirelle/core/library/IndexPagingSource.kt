package app.tfdn.lirelle.core.library

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.tfdn.lirelle.core.network.ApiErrorException
import java.io.IOException
import kotlinx.serialization.SerializationException

data class PageResult<T>(val items: List<T>, val total: Int)

/**
 * Pages addressed by zero-based page number, as the server's list routes are. The total is
 * known from the first answer, so the list can show placeholders for what is not loaded.
 * [pageSize] must be the size used for every load, initial load included.
 *
 * [fetch] fails with an [IOException] when the server cannot be reached, and with an [ApiErrorException] naming
 * what the server answered otherwise: either is a load error, which the list can retry.
 */
class IndexPagingSource<T : Any>(
    private val pageSize: Int,
    private val onTotal: (Int) -> Unit = {},
    private val fetch: suspend (page: Int, size: Int) -> PageResult<T>,
) : PagingSource<Int, T>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val page = params.key ?: 0
        return try {
            val result = fetch(page, pageSize)
            onTotal(result.total)
            val before = page * pageSize
            // A page shorter than what was asked for is the last one, whatever the total says.
            val isLast = result.items.size < pageSize
            val after = if (isLast) 0 else (result.total - before - result.items.size).coerceAtLeast(0)
            LoadResult.Page(
                data = result.items,
                prevKey = if (page == 0) null else page - 1,
                nextKey = if (after == 0) null else page + 1,
                itemsBefore = before,
                itemsAfter = after,
            )
        } catch (exception: IOException) {
            LoadResult.Error(exception)
        } catch (exception: ApiErrorException) {
            LoadResult.Error(exception)
        } catch (exception: SerializationException) {
            LoadResult.Error(exception)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? = state.anchorPosition?.let { it / pageSize }
}
