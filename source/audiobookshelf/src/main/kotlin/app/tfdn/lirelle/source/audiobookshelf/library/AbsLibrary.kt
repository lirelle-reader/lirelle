package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
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
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult

/**
 * The lists of an Audiobookshelf profile's library asked of its [server]: its book libraries, the Books list of the
 * one the Library shows ([AbsBookList]), its series ([AbsSeries]), its authors ([people]) and its collections and
 * playlists ([AbsShelves]). The search and the genres stay empty for now ([EmptyAbsLists]); [AbsLibrary]
 * answers Home's rows and the pages.
 */
internal abstract class AbsLibraryLists(
    protected val server: AbsProfileServer,
    progress: AbsProgress,
    whole: AbsWholeLibrary,
) : EmptyAbsLists() {

    private val bookList = AbsBookList(server, progress, whole)
    private val seriesList = AbsSeries(server, progress, whole)
    private val shelves = AbsShelves(server, progress, whole)
    protected val people = AbsAuthors(server, progress, whole)

    override suspend fun libraries(): ApiResult<List<ServerLibrary>> = server.libraries()

    /** The library's books, or a collection's or a playlist's; Audiobookshelf has no smart list. */
    override fun books(source: BookListSource, request: BookListRequest): PagedList<BookSummary> = when (source) {
        BookListSource.AllBooks -> bookList.books(request)
        is BookListSource.Collection -> shelves.books(source, request)
        is BookListSource.SmartList -> emptyPages()
    }

    override suspend fun bookPage(request: BookListRequest, page: Int, size: Int): ApiResult<PageResult<BookSummary>> =
        bookList.bookPage(request, page, size)

    override fun authors(
        sort: AuthorSort,
        direction: SortDirection,
        text: String?,
        library: ServerLibrary?,
    ): PagedList<Author> = people.authors(sort, direction, text, library)

    override suspend fun series(text: String?, library: ServerLibrary?): ApiResult<List<Series>> =
        seriesList.series(text, library)

    override suspend fun seriesBooks(seriesId: SeriesId): ApiResult<SeriesBooks> = seriesList.seriesBooks(seriesId)

    override suspend fun authorBooks(authorId: AuthorId): ApiResult<List<BookSummary>> = people.authorBooks(authorId)

    override suspend fun shelves(): ApiResult<Shelves> = shelves.shelves()
}

/**
 * An Audiobookshelf profile's library, asked of its server with the profile's token: a library item is a book. Its
 * lists come from [AbsLibraryLists], Home's rows from [AbsHome] (whose time [clock] tells, in milliseconds), an
 * author's page from [AbsAuthors], a book's page, reading and related books from [AbsBookPages]. The account's
 * [progress] and what the profile keeps of its server ([whole]) are the profile's own, shared with whatever else of
 * it reads them. Whatever a list's load throws is shown as the error a call with the profile's token meets.
 */
internal class AbsLibrary(
    server: AbsProfileServer,
    progress: AbsProgress,
    whole: AbsWholeLibrary,
    clock: () -> Long = ::monotonicMillis,
) : AbsLibraryLists(server, progress, whole) {

    private val bookPages = AbsBookPages(server, progress, whole)
    private val home = AbsHome(server, progress, whole, clock)

    override suspend fun homeRow(kind: HomeRowKind, extent: HomeRowExtent): ApiResult<HomeRow> =
        home.homeRow(kind, extent)

    override suspend fun author(authorId: AuthorId): ApiResult<AuthorDetail> = people.author(authorId)

    override suspend fun book(bookId: BookId): ApiResult<BookDetail> = bookPages.book(bookId)

    override suspend fun bookReading(bookId: BookId): ApiResult<BookReading> = bookPages.bookReading(bookId)

    override suspend fun relatedBooks(bookId: BookId): RelatedBooks = bookPages.relatedBooks(bookId)

    override fun errorOf(throwable: Throwable): ApiError = server.errorOf(throwable)
}
