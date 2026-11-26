package app.tfdn.lirelle.source.kavita.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import app.tfdn.lirelle.core.library.PageResult
import app.tfdn.lirelle.core.library.PagedList
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.library.dto.GenreDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDto
import app.tfdn.lirelle.source.kavita.network.answer
import java.text.Collator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The genres of the profile's server and a genre's books, asked of [server] (spec §4). Kavita lists a genre's
 * series, not its books: they come by pages of series, each series unfolded into its books by its detail, at most
 * six details at once ([KavitaCallLimit]). The formats are the series' and go to the server; the reading states
 * are the books' and narrow them on the device. Kavita does not count a genre's books.
 */
internal class KavitaGenres(private val server: KavitaProfileServer) {

    private val calls = KavitaCallLimit()

    /** The genres by name, as a reader sorts them, each name once; Kavita does not count their books. */
    suspend fun genres(): ApiResult<List<Genre>> = server.call { remote ->
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        remote.api.genres()
            .mapNotNull { it.title.orNullIfBlank() }
            .distinct()
            .sortedWith(collator::compare)
            .map { Genre(it, bookCount = null) }
    }

    /**
     * The books of [request]'s genre, loaded as the list scrolls; the genre is looked up once per load of the list.
     * A genre the server does not know has none.
     */
    fun books(request: BookListRequest): PagedList<BookSummary> {
        val pager = Pager(PagingConfig(SeriesPageSize, initialLoadSize = SeriesPageSize, enablePlaceholders = false)) {
            var genreIds: String? = null
            KavitaUnfoldingPagingSource { page ->
                server.loaded { remote ->
                    val ids = genreIds ?: genreIds(remote, request).also { genreIds = it }
                    if (ids.isEmpty()) {
                        UnfoldedPage(emptyList(), isLast = true)
                    } else {
                        unfold(remote, request, ids, page, SeriesPageSize)
                    }
                }
            }
        }
        return PagedList(pager.flow, MutableStateFlow(null))
    }

    /**
     * Page [page] (from 0) of [size] series of [request]'s genre, unfolded into their books: as many books as they
     * hold, which may be more or fewer than [size]. Kavita does not count a genre's books: a full page of series
     * says that there may be any number more ([Int.MAX_VALUE]); a shorter one is the last, and its total counts the
     * books of that page only, not those of the pages before it.
     */
    suspend fun bookPage(request: BookListRequest, page: Int, size: Int): ApiResult<PageResult<BookSummary>> =
        server.call { remote ->
            val ids = genreIds(remote, request)
            val unfolded = if (ids.isEmpty()) {
                UnfoldedPage(emptyList(), isLast = true)
            } else {
                unfold(remote, request, ids, page + FirstPage, size)
            }
            PageResult(unfolded.books, if (unfolded.isLast) unfolded.books.size else Int.MAX_VALUE)
        }

    /** The ids of the genres named as [request]'s, joined with commas; empty when the server knows none. */
    private suspend fun genreIds(remote: KavitaRemote, request: BookListRequest): String {
        val name = request.genre.orNullIfBlank() ?: return ""
        return calls.limited { remote.api.genres() }.idsOf(name)
    }

    /** Page [page] (from 1) of [size] series of the genres [genreIds], in [request]'s sort, unfolded. */
    private suspend fun unfold(
        remote: KavitaRemote,
        request: BookListRequest,
        genreIds: String,
        page: Int,
        size: Int,
    ): UnfoldedPage {
        val filters = request.filters
        val formats = filters.formats.flatMap { it.kavitaFormats }.toSortedSet()
        val states = filters.statuses.mapNotNull { it.readState }.toSet()
        // Only formats Kavita does not have (audio), or reading states it does not keep: nothing is of them.
        val noFormat = filters.formats.isNotEmpty() && formats.isEmpty()
        val noState = filters.statuses.isNotEmpty() && states.isEmpty()
        if (noFormat || noState) return UnfoldedPage(emptyList(), isLast = true)
        val statements = listOfNotNull(
            Statement(SeriesField.Genres, Comparison.Contains, genreIds),
            formats.takeIf { it.isNotEmpty() }?.let {
                Statement(SeriesField.Formats, Comparison.Contains, it.joinToString(","))
            },
        )
        val filter = seriesFilter(
            statements,
            sortField = if (request.sort == BookSort.Title) SeriesSortField.SortName else SeriesSortField.CreatedDate,
            ascending = request.direction == SortDirection.Ascending,
        )
        val series = calls.limited { remote.api.series(filter, page, size) }.answer()
        val books = coroutineScope { series.map { async { booksOf(remote, it) } }.awaitAll() }.flatten()
            .filter { filters.statuses.isEmpty() || it.readState in states }
        return UnfoldedPage(books, isLast = series.size < size)
    }

    /** A series' books in its order; none when the series is gone. A lone book is shown without a series. */
    private suspend fun booksOf(remote: KavitaRemote, series: SeriesDto): List<BookSummary> {
        val detail = calls.orGone { remote.api.detail(series.id) } ?: return emptyList()
        val shownIn = SeriesRefInfo(series.id, series.name).takeUnless { detail.isLoneBook }
        return detail.books().map { remote.mapper.summary(it, shownIn, series.format) }
    }
}

/** The ids of the genres whose name is [name], once trimmed, joined with commas. */
private fun List<GenreDto>.idsOf(name: String): String =
    filter { it.title.orNullIfBlank() == name }.map { it.id }.distinct().joinToString(",")

/** How many series a page of a genre's books unfolds. */
private const val SeriesPageSize = 10
