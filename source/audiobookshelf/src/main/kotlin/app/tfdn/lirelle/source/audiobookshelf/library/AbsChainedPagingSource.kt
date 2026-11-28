package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.network.ApiErrorException
import java.io.IOException
import kotlinx.serialization.SerializationException

/** Where a list made of several libraries' lists is: the page [page] (from 0) of the library [part] (from 0). */
internal data class AbsChainKey(val part: Int, val page: Int)

/** The books of one page of one of the lists, and where the next page is; null after the last list's last page. */
internal data class AbsChainedPage(val books: List<BookSummary>, val next: AbsChainKey?)

/**
 * Books that come from several lists, each paged by the server, one after the other: the second library's pages
 * after the first's ([fetch], given where to read). The list is only ever read from its top down: it has no page
 * before, and a refresh starts again at the top. A page that holds no book goes on to the next one, so that the list
 * does not stop on an empty library.
 *
 * [fetch] fails with an [IOException] when the server cannot be reached, and with an [ApiErrorException] naming what
 * the server answered otherwise: either is a load error, which the list can retry.
 */
internal class AbsChainedPagingSource(
    private val fetch: suspend (AbsChainKey) -> AbsChainedPage,
) : PagingSource<AbsChainKey, BookSummary>() {

    override suspend fun load(params: LoadParams<AbsChainKey>): LoadResult<AbsChainKey, BookSummary> = try {
        var page = fetch(params.key ?: First)
        while (page.books.isEmpty() && page.next != null) {
            page = fetch(page.next)
        }
        LoadResult.Page(data = page.books, prevKey = null, nextKey = page.next)
    } catch (exception: IOException) {
        LoadResult.Error(exception)
    } catch (exception: ApiErrorException) {
        LoadResult.Error(exception)
    } catch (exception: SerializationException) {
        LoadResult.Error(exception)
    }

    override fun getRefreshKey(state: PagingState<AbsChainKey, BookSummary>): AbsChainKey? = null
}

private val First = AbsChainKey(part = 0, page = 0)
