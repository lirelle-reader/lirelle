package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import app.tfdn.lirelle.core.library.IndexPagingSource
import app.tfdn.lirelle.core.library.PageResult
import app.tfdn.lirelle.core.library.PagedList
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.network.ApiResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Books list of the library the Library shows (spec §4), asked of [server]. While nothing narrows it, the server
 * pages it, in the sort asked; a genre is the server's filter too. With a status, a format or a text, the library's
 * whole list, kept by the profile ([whole]), is narrowed ([AbsBookFilters]) and sorted ([AbsBookSorts]) on the
 * device, then paged there. The read states come from the account's [progress]. A list given no library shows the
 * first book library, as the Library always shows one; a genre given none is each book library's
 * ([AbsGenreBooks]).
 *
 * A pull to refresh of a list reads again what it uses: the libraries, and the whole list it narrows. The screens
 * keep a list's pages while they show it, so its pager is collected once: each load of the list after its first is
 * a refresh.
 */
internal class AbsBookList(
    private val server: AbsProfileServer,
    private val progress: AbsProgress,
    private val whole: AbsWholeLibrary,
    private val pageSize: Int = BookPageSize,
) {

    private val genres = AbsGenreBooks(server, progress, whole, pageSize)

    /** The books [request] asks for, loaded as the list scrolls. */
    fun books(request: BookListRequest): PagedList<BookSummary> {
        val filters = AbsBookFilters.of(request)
        if (!filters.isActive && request.isOfGenreEverywhere) return genres.books(request)
        val total = MutableStateFlow<Int?>(null)
        val loads = AtomicInteger()
        val pager = Pager(PagingConfig(pageSize, initialLoadSize = pageSize, enablePlaceholders = true)) {
            val refresh = AbsWholeLibrary.Refresh().takeIf { loads.getAndIncrement() > 0 }
            // A narrowed list is made once per load of the list, then paged.
            var narrowed: List<BookSummary>? = null
            IndexPagingSource(pageSize, onTotal = { total.value = it }) { page, size ->
                server.loaded { remote ->
                    if (filters.isActive) {
                        val all = narrowed ?: narrowed(remote, request, filters, refresh).also { narrowed = it }
                        all.pageOf(page, size)
                    } else {
                        served(remote, request, refresh, page, size)
                    }
                }
            }
        }
        return PagedList(pager.flow, total)
    }

    /**
     * One page of the books [request] asks for, numbered from 0 and of [size] books at most: the server's page while
     * nothing narrows them, the device's slice of the narrowed list otherwise.
     */
    suspend fun bookPage(request: BookListRequest, page: Int, size: Int): ApiResult<PageResult<BookSummary>> =
        server.call { remote ->
            val filters = AbsBookFilters.of(request)
            when {
                filters.isActive -> narrowed(remote, request, filters, refresh = null).pageOf(page, size)
                request.isOfGenreEverywhere -> genres.page(remote, request, page, size)
                else -> served(remote, request, refresh = null, page, size)
            }
        }

    /** The page [page] of [size] of the library's books, or of its genre's, as the server sorts and pages them. */
    private suspend fun served(
        remote: AbsRemote,
        request: BookListRequest,
        refresh: AbsWholeLibrary.Refresh?,
        page: Int,
        size: Int,
    ): PageResult<BookSummary> {
        val library = shownLibrary(whole.libraries(remote, refresh), request.library)
            ?: return PageResult(emptyList(), 0)
        val answer = request.genre?.takeIf { it.isNotBlank() }
            ?.let { genre ->
                val order = AbsBookSorts.serverOrder(request.sort, request.direction)
                remote.api.groupItems(library.id, genreFilter(genre), size, page, order)
            }
            ?: remote.api.items(
                library.id,
                size,
                page,
                AbsBookSorts.serverField(request.sort),
                AbsBookSorts.serverDesc(request.direction),
            )
        val readings = progress.readings()
        return PageResult(answer.results.map { remote.books.summary(it, readings, library.coverShape) }, answer.total)
    }

    /**
     * Every book of the library (of each book library for a genre given none) that [filters] keep, sorted as
     * [request] asks, from the whole lists the profile keeps.
     */
    private suspend fun narrowed(
        remote: AbsRemote,
        request: BookListRequest,
        filters: AbsBookFilters,
        refresh: AbsWholeLibrary.Refresh?,
    ): List<BookSummary> {
        val libraries = whole.libraries(remote, refresh)
        val shown = if (request.isOfGenreEverywhere) {
            libraries
        } else {
            listOfNotNull(shownLibrary(libraries, request.library))
        }
        val readings = progress.readings()
        val kept = shown.flatMap { library ->
            whole.items(remote, library.id, refresh)
                .filter { filters.keeps(it, readings) }
                .map { it to library.coverShape }
        }
        return AbsBookSorts.sorted(kept, request.sort, request.direction) { it.first }
            .map { (item, shape) -> remote.books.summary(item, readings, shape) }
    }
}

/** The request is for a genre's books in every book library: no library narrows it. */
private val BookListRequest.isOfGenreEverywhere: Boolean
    get() = !genre.isNullOrBlank() && library == null

/** How many books a page of the Books list holds, as on the other servers. */
private const val BookPageSize = 60
