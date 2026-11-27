package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.PagingData
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.PageResult
import app.tfdn.lirelle.core.library.PagedList
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
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** Nothing of that id: what a library that shows nothing answers to what is asked by its id. */
internal val NotFound = ApiResult.Failure(ApiError.NotFound)

/** A list that is there and empty. */
internal fun <T> none(): ApiResult<List<T>> = ApiResult.Success(emptyList())

/** A paged list that is there and empty: no page, and none in all. */
private fun <T : Any> emptyPages() = PagedList(flowOf(PagingData.empty<T>()), MutableStateFlow(0))

/**
 * The lists of an Audiobookshelf profile's library until it is browsed (#67): each is there, with nothing in it. The
 * server is asked nothing. [EmptyAbsLibrary] answers what is asked by its id.
 */
internal abstract class EmptyAbsLists : LibraryRepository {

    override suspend fun libraries(): ApiResult<List<ServerLibrary>> = none()

    override fun books(source: BookListSource, request: BookListRequest): PagedList<BookSummary> = emptyPages()

    override suspend fun bookPage(
        request: BookListRequest,
        page: Int,
        size: Int,
    ): ApiResult<PageResult<BookSummary>> = ApiResult.Success(PageResult(emptyList(), 0))

    override fun authors(
        sort: AuthorSort,
        direction: SortDirection,
        text: String?,
        library: ServerLibrary?,
    ): PagedList<Author> = emptyPages()

    override suspend fun series(text: String?, library: ServerLibrary?): ApiResult<List<Series>> = none()

    override suspend fun authorBooks(authorId: AuthorId): ApiResult<List<BookSummary>> = none()

    override suspend fun shelves(): ApiResult<Shelves> = ApiResult.Success(Shelves(emptyList(), emptyList()))

    override suspend fun homeRow(kind: HomeRowKind, extent: HomeRowExtent): ApiResult<HomeRow> =
        ApiResult.Success(HomeRow(kind, emptyList(), hasMore = false))

    override suspend fun search(text: String): ApiResult<SearchResults> = ApiResult.Success(SearchResults(text))

    override suspend fun genres(): ApiResult<List<Genre>> = none()
}

/**
 * An Audiobookshelf profile's library until it is browsed (#67): empty, every list with nothing in it
 * ([EmptyAbsLists]) and everything asked by its id not found. The server is asked nothing.
 */
internal class EmptyAbsLibrary : EmptyAbsLists() {

    override suspend fun seriesBooks(seriesId: SeriesId): ApiResult<SeriesBooks> = NotFound

    override suspend fun author(authorId: AuthorId): ApiResult<AuthorDetail> = NotFound

    override suspend fun book(bookId: BookId): ApiResult<BookDetail> = NotFound

    override suspend fun bookReading(bookId: BookId): ApiResult<BookReading> = NotFound

    override suspend fun relatedBooks(bookId: BookId): RelatedBooks = RelatedBooks()

    override fun errorOf(throwable: Throwable): ApiError = ApiError.Unexpected
}
