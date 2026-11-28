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
 * device, then paged there. The read states come from the account's [progress].
 *
 * A list given no library, as Search's "See all" and its genres ask, is every book library's: while nothing narrows
 * it, each library's pages one library after the other, each sorted by the server on its own
 * ([AbsEveryLibraryBooks]); narrowed, the libraries' whole lists joined, then sorted together on the device. The
 * Library's tabs always name their library.
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

    private val everywhere = AbsEveryLibraryBooks(server, progress, whole, pageSize)

    /** The books [request] asks for, loaded as the list scrolls. */
    fun books(request: BookListRequest): PagedList<BookSummary> {
        val filters = AbsBookFilters.of(request)
        if (!filters.isActive && request.library == null) return everywhere.books(request)
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
                        served(remote, request, page, size, refresh)
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
                request.library == null -> everywhere.page(remote, request, page, size)
                else -> served(remote, request, page, size)
            }
        }

    /**
     * The page [page] of [size] of the books of [request]'s library, or of its genre's, as the server sorts and pages
     * them. [refresh] reads the libraries again, for their covers' shapes.
     */
    private suspend fun served(
        remote: AbsRemote,
        request: BookListRequest,
        page: Int,
        size: Int,
        refresh: AbsWholeLibrary.Refresh? = null,
    ): PageResult<BookSummary> {
        val shown = request.library?.let { shownLibrary(whole.libraries(remote, refresh), it) }
            ?: return PageResult(emptyList(), 0)
        val answer = remote.libraryItems(shown.id, request, size, page)
        val readings = progress.readings()
        return PageResult(answer.results.map { remote.books.summary(it, readings, shown.coverShape) }, answer.total)
    }

    /**
     * Every book of the library (of every book library when given none) that [filters] keep, sorted as [request]
     * asks, from the whole lists the profile keeps.
     */
    private suspend fun narrowed(
        remote: AbsRemote,
        request: BookListRequest,
        filters: AbsBookFilters,
        refresh: AbsWholeLibrary.Refresh?,
    ): List<BookSummary> {
        val shown = shownLibraries(whole.libraries(remote, refresh), request.library)
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

/** How many books a page of the Books list holds, as on the other servers. */
private const val BookPageSize = 60
