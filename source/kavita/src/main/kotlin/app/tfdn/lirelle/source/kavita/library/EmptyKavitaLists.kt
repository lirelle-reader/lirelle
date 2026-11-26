package app.tfdn.lirelle.source.kavita.library

import androidx.paging.PagingData
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.PageResult
import app.tfdn.lirelle.core.library.PagedList
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** Nothing of that id: an id that is none of Kavita's, or something the server does not have. */
internal val NotFound = ApiResult.Failure(ApiError.NotFound)

/** A list that is there and empty. */
internal fun <T> none(): ApiResult<List<T>> = ApiResult.Success(emptyList())

/** A paged list that is there and empty: no page, and none in all. */
internal fun <T : Any> emptyPages() = PagedList(flowOf(PagingData.empty<T>()), MutableStateFlow(0))

/**
 * The lists of a Kavita profile's library that are not asked of its server yet: each is there, with nothing in it,
 * and the server is asked nothing. [KavitaLibrary] answers the others over it, as #50's tasks bring them.
 */
internal abstract class EmptyKavitaLists : LibraryRepository {

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
