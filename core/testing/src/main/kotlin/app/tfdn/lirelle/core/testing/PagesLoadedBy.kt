package app.tfdn.lirelle.core.testing

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import java.io.IOException
import kotlinx.coroutines.flow.Flow

private const val PageSize = 20

/**
 * A paged list that asks [load] for its items when it is shown, as a list read from a server: a test holds the
 * answer to see the list while it loads, or throws an [IOException] to see it fail. One page holds every item.
 */
fun <T : Any> pagesLoadedBy(load: suspend () -> List<T>): Flow<PagingData<T>> =
    Pager(PagingConfig(pageSize = PageSize)) {
        object : PagingSource<Int, T>() {
            override fun getRefreshKey(state: PagingState<Int, T>): Int? = null

            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = try {
                LoadResult.Page(load(), prevKey = null, nextKey = null)
            } catch (failure: IOException) {
                LoadResult.Error(failure)
            }
        }
    }.flow
