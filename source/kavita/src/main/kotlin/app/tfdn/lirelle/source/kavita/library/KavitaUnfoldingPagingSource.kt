package app.tfdn.lirelle.source.kavita.library

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.network.ApiErrorException
import java.io.IOException
import kotlinx.serialization.SerializationException

/** The books of one page of series, unfolded, and whether that page of series was the last. */
internal data class UnfoldedPage(val books: List<BookSummary>, val isLast: Boolean)

/**
 * Books that come by pages of series, each series unfolded into its books ([unfold], given a page of series, the
 * first being 1). The list is only ever read from its top down: it has no page before, and a refresh starts again
 * at the top. A page of series whose books are all left out goes on to the next one, so that the list does not
 * stop on an empty page. How many books there are in all is not known.
 *
 * [unfold] fails with an [IOException] when the server cannot be reached, and with an [ApiErrorException] naming
 * what the server answered otherwise: either is a load error, which the list can retry.
 */
internal class KavitaUnfoldingPagingSource(
    private val unfold: suspend (seriesPage: Int) -> UnfoldedPage,
) : PagingSource<Int, BookSummary>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, BookSummary> = try {
        var page = params.key ?: FirstPage
        var unfolded: UnfoldedPage
        do {
            unfolded = unfold(page)
            page++
        } while (unfolded.books.isEmpty() && !unfolded.isLast)
        LoadResult.Page(data = unfolded.books, prevKey = null, nextKey = page.takeUnless { unfolded.isLast })
    } catch (exception: IOException) {
        LoadResult.Error(exception)
    } catch (exception: ApiErrorException) {
        LoadResult.Error(exception)
    } catch (exception: SerializationException) {
        LoadResult.Error(exception)
    }

    override fun getRefreshKey(state: PagingState<Int, BookSummary>): Int? = null
}
