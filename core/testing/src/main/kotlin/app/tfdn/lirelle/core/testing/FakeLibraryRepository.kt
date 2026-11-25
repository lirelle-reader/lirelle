package app.tfdn.lirelle.core.testing

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.LibraryRepository
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

private val Idle = LoadState.NotLoading(endOfPaginationReached = true)

/**
 * A fully loaded list. Without load states a list never reads as loaded, and `asSnapshot()` waits
 * for ever on a view model's flow, which does not complete.
 */
private val Loaded = LoadStates(refresh = Idle, prepend = Idle, append = Idle)

/**
 * Scriptable [LibraryRepository]. Set the result fields, then read what was asked.
 * [gate], when set, holds every suspending call until it is completed.
 */
@Suppress("TooManyFunctions") // One function per repository operation.
class FakeLibraryRepository : LibraryRepository {
    var librariesResult: ApiResult<List<ServerLibrary>> = ApiResult.Success(emptyList())
    var books: List<BookSummary> = emptyList()
    var authorsList: List<Author> = emptyList()
    var seriesResult: ApiResult<List<Series>> = ApiResult.Success(emptyList())
    var seriesBooksResult: ApiResult<SeriesBooks> = ApiResult.Failure(ApiError.NotFound)
    var authorResult: ApiResult<AuthorDetail> = ApiResult.Failure(ApiError.NotFound)
    var authorBooksResult: ApiResult<List<BookSummary>> = ApiResult.Success(emptyList())
    var shelvesResult: ApiResult<Shelves> = ApiResult.Success(Shelves(emptyList(), emptyList()))
    var bookResult: ApiResult<BookDetail> = ApiResult.Failure(ApiError.NotFound)
    var readingResult: ApiResult<BookReading> = ApiResult.Failure(ApiError.Unexpected)
    var related: RelatedBooks = RelatedBooks()
    var homeRows: Map<HomeRowKind, ApiResult<HomeRow>> =
        HomeRowKind.entries.associateWith { ApiResult.Success(HomeRow(it, emptyList(), hasMore = false)) }
    var searchAnswer: (String) -> ApiResult<SearchResults> = { ApiResult.Success(SearchResults(it)) }
    var genresResult: ApiResult<List<Genre>> = ApiResult.Success(emptyList())
    var gate: CompletableDeferred<Unit>? = null

    /** The load states the paged lists report; fully loaded unless a test sets them. */
    var loadStates: LoadStates = Loaded

    val bookRequests = mutableListOf<Pair<BookListSource, BookListRequest>>()
    val authorRequests = mutableListOf<Pair<AuthorSort, SortDirection>>()

    /** The text of each authors request, in step with [authorRequests]. */
    val authorTexts = mutableListOf<String?>()

    /** The library of each authors request, in step with [authorRequests]. */
    val authorLibraries = mutableListOf<ServerLibrary?>()

    /** The library of each series request, in step with the "series" entries of [calls]. */
    val seriesLibraries = mutableListOf<ServerLibrary?>()
    val calls = mutableListOf<String>()

    override fun books(source: BookListSource, request: BookListRequest): PagedList<BookSummary> {
        bookRequests += source to request
        return PagedList(flowOf(PagingData.from(books, loadStates)), MutableStateFlow(books.size))
    }

    override fun authors(
        sort: AuthorSort,
        direction: SortDirection,
        text: String?,
        library: ServerLibrary?,
    ): PagedList<Author> {
        authorRequests += sort to direction
        authorTexts += text
        authorLibraries += library
        return PagedList(flowOf(PagingData.from(authorsList, loadStates)), MutableStateFlow(authorsList.size))
    }

    private suspend fun <T> answer(call: String, result: T): T {
        calls += call
        gate?.await()
        return result
    }

    override suspend fun libraries() = answer("libraries", librariesResult)

    override suspend fun series(text: String?, library: ServerLibrary?): ApiResult<List<Series>> {
        seriesLibraries += library
        return answer(if (text == null) "series" else "series $text", seriesResult)
    }

    override suspend fun seriesBooks(seriesId: SeriesId) = answer("seriesBooks $seriesId", seriesBooksResult)

    override suspend fun author(authorId: AuthorId) = answer("author $authorId", authorResult)

    override suspend fun authorBooks(authorId: AuthorId) = answer("authorBooks $authorId", authorBooksResult)

    override suspend fun shelves() = answer("shelves", shelvesResult)

    override suspend fun book(bookId: BookId) = answer("book $bookId", bookResult)

    override suspend fun bookReading(bookId: BookId) = answer("bookReading $bookId", readingResult)

    override suspend fun relatedBooks(bookId: BookId) = answer("relatedBooks $bookId", related)

    override suspend fun homeRow(kind: HomeRowKind, extent: HomeRowExtent) =
        answer("homeRow $kind $extent", homeRows.getValue(kind))

    override suspend fun search(text: String) = answer("search $text", searchAnswer(text))

    override suspend fun genres() = answer("genres", genresResult)

    override fun errorOf(throwable: Throwable): ApiError = ApiError.ServerUnreachable
}
