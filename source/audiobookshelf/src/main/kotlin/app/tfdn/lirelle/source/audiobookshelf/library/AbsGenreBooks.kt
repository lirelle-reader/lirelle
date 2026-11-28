package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import app.tfdn.lirelle.core.library.PageResult
import app.tfdn.lirelle.core.library.PagedList
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSummary
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A genre's books across the book libraries, for Search's genres, which no library narrows: the server's genre
 * filter on each library, paged by the server, one library after the other in the server's order, each in the sort
 * asked. How many there are is known once the last library was reached. The libraries come from what the profile
 * keeps ([whole]); a pull to refresh reads them again.
 */
internal class AbsGenreBooks(
    private val server: AbsProfileServer,
    private val progress: AbsProgress,
    private val whole: AbsWholeLibrary,
    private val pageSize: Int,
) {

    /** The books of [request]'s genre in every book library, loaded as the list scrolls. */
    fun books(request: BookListRequest): PagedList<BookSummary> {
        val total = MutableStateFlow<Int?>(null)
        val loads = AtomicInteger()
        val pager = Pager(PagingConfig(pageSize, initialLoadSize = pageSize, enablePlaceholders = false)) {
            val refresh = AbsWholeLibrary.Refresh().takeIf { loads.getAndIncrement() > 0 }
            val totals = ConcurrentHashMap<Int, Int>()
            AbsChainedPagingSource { key ->
                server.loaded { remote ->
                    val libraries = whole.libraries(remote, refresh)
                    val library = libraries.getOrNull(key.part) ?: run {
                        total.value = totals.values.sum()
                        return@loaded AbsChainedPage(emptyList(), next = null)
                    }
                    val answer = remote.api.groupItems(
                        library.id,
                        genreFilter(request.genre.orEmpty()),
                        limit = pageSize,
                        page = key.page,
                        order = AbsBookSorts.serverOrder(request.sort, request.direction),
                    )
                    totals[key.part] = answer.total
                    if (totals.size == libraries.size) total.value = totals.values.sum()
                    val readings = progress.readings()
                    val books = answer.results.map { remote.books.summary(it, readings, library.coverShape) }
                    AbsChainedPage(books, nextKey(key, answer.results.size, answer.total, libraries.size))
                }
            }
        }
        return PagedList(pager.flow, total)
    }

    /**
     * The page [page] of [size] books of [request]'s genre across the book libraries, in the order [books] lists
     * them: each library's whole list of the genre is asked, as a page may straddle two libraries.
     */
    suspend fun page(remote: AbsRemote, request: BookListRequest, page: Int, size: Int): PageResult<BookSummary> {
        val readings = progress.readings()
        val all = whole.libraries(remote, refresh = null).flatMap { library ->
            remote.api.groupItems(
                library.id,
                genreFilter(request.genre.orEmpty()),
                limit = 0,
                page = 0,
                order = AbsBookSorts.serverOrder(request.sort, request.direction),
            ).results.map { remote.books.summary(it, readings, library.coverShape) }
        }
        return all.pageOf(page, size)
    }

    /** Where the list goes after the page at [key] that held [count] books of the library's [total]. */
    private fun nextKey(key: AbsChainKey, count: Int, total: Int, libraries: Int): AbsChainKey? = when {
        count == pageSize && (key.page + 1) * pageSize < total -> key.copy(page = key.page + 1)
        key.part + 1 < libraries -> AbsChainKey(key.part + 1, page = 0)
        else -> null
    }
}

/**
 * The server's filter for the genre [name]: `genres.` and the name in base64, which Retrofit then escapes as any
 * query value.
 */
internal fun genreFilter(name: String): String =
    "genres." + Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))

/** The page [page] (from 0) of [size] of these books, and how many there are in all. */
internal fun <T> List<T>.pageOf(page: Int, size: Int): PageResult<T> =
    PageResult(drop(page * size).take(size), this.size)
