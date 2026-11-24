package app.tfdn.lirelle.core.library

import androidx.paging.PagingData
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorDetail
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A paged list and the number of items the server reports for it (null until known). */
class PagedList<T : Any>(val items: Flow<PagingData<T>>, val total: StateFlow<Int?>)

/** Read-only access to the library. Nothing here modifies the server. */
interface LibraryRepository {
    fun books(source: BookListSource, request: BookListRequest): PagedList<BookSummary>

    /** [text], when given, narrows the list to the authors matching it. */
    fun authors(sort: AuthorSort, direction: SortDirection, text: String? = null): PagedList<Author>

    /**
     * The whole list: series are sorted and filtered on the device.
     * [text], when given, narrows the list to the series matching it.
     */
    suspend fun series(text: String? = null): ApiResult<List<Series>>

    suspend fun seriesBooks(seriesId: SeriesId): ApiResult<SeriesBooks>

    suspend fun author(authorId: AuthorId): ApiResult<AuthorDetail>

    suspend fun authorBooks(authorId: AuthorId): ApiResult<List<BookSummary>>

    suspend fun shelves(): ApiResult<Shelves>

    /** Follows a merge once: asking for a merged book returns the book that replaced it. */
    suspend fun book(bookId: BookId): ApiResult<BookDetail>

    suspend fun bookReading(bookId: BookId): ApiResult<BookReading>

    /** Never fails: a list whose call failed is empty. */
    suspend fun relatedBooks(bookId: BookId): RelatedBooks

    /**
     * One row of Home, as many books as [extent] asks (the source's numbers). A row fed by several server rows
     * fails when one of them fails.
     */
    suspend fun homeRow(kind: HomeRowKind, extent: HomeRowExtent = HomeRowExtent.Row): ApiResult<HomeRow>

    /** The first results of each kind for [text], with how many there are in all. */
    suspend fun search(text: String): ApiResult<SearchResults>

    /**
     * The genres that have books, in the server's order (most books first); by name, without their number of books,
     * from a server that does not count them.
     */
    suspend fun genres(): ApiResult<List<Genre>>

    /** The error to show for a failed page load. */
    fun errorOf(throwable: Throwable): ApiError
}
