package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
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
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaErrors

/**
 * The lists of a Kavita profile's library asked of its [server]: its libraries, its series, its reading lists with
 * their books, its genres with theirs, and its authors ([people]) with theirs. There is no list of all the
 * library's books: only a genre's and an author's. Those not asked yet stay empty ([EmptyKavitaLists]);
 * [KavitaLibrary] answers the pages.
 */
internal abstract class KavitaLibraryLists(protected val server: KavitaProfileServer) : EmptyKavitaLists() {

    private val seriesPages = KavitaSeriesPages(server)
    private val shelves = KavitaShelves(server)
    private val genres = KavitaGenres(server)
    protected val people = KavitaAuthors(server)

    override suspend fun libraries(): ApiResult<List<ServerLibrary>> = seriesPages.libraries()

    override suspend fun series(text: String?, library: ServerLibrary?): ApiResult<List<Series>> =
        seriesPages.series(text, library)

    override suspend fun seriesBooks(seriesId: SeriesId): ApiResult<SeriesBooks> = seriesPages.seriesBooks(seriesId)

    override suspend fun shelves(): ApiResult<Shelves> = shelves.shelves()

    override fun books(source: BookListSource, request: BookListRequest): PagedList<BookSummary> =
        if (source == BookListSource.AllBooks && request.isOfGenre) {
            genres.books(request)
        } else {
            shelves.books(source, request)
        }

    override suspend fun genres(): ApiResult<List<Genre>> = genres.genres()

    /** The authors; Kavita cannot list a library's people, so [library] does not narrow them. */
    override fun authors(
        sort: AuthorSort,
        direction: SortDirection,
        text: String?,
        library: ServerLibrary?,
    ): PagedList<Author> = people.authors(sort, direction, text)

    override suspend fun authorBooks(authorId: AuthorId): ApiResult<List<BookSummary>> = people.authorBooks(authorId)
}

/** The request is for a genre's books, the only books Kavita lists outside a series, a shelf or an author. */
private val BookListRequest.isOfGenre: Boolean get() = genre.orNullIfBlank() != null

/**
 * A Kavita profile's library, asked of its server with its key: Kavita's series, volumes and chapters brought to
 * the app's books by one rule (`KavitaBooks.kt`), their pages, readings and reading lists ([KavitaLibraryLists]),
 * Home's rows ([KavitaHome], whose time [clock] tells, in milliseconds), the search ([KavitaSearch]) and an
 * author's page ([KavitaAuthors]). Reads the profile's server on every call, through [session].
 */
internal class KavitaLibrary(
    session: ProfileSession,
    apis: KavitaApis<KavitaLibraryApi>,
    titles: KavitaTitles,
    errors: KavitaErrors,
    clock: () -> Long = ::monotonicMillis,
) : KavitaLibraryLists(KavitaProfileServer(session, apis, titles, errors)) {

    private val bookPages = KavitaBookPages(server)
    private val home = KavitaHome(server, clock)
    private val search = KavitaSearch(server)

    override suspend fun homeRow(kind: HomeRowKind, extent: HomeRowExtent): ApiResult<HomeRow> =
        home.homeRow(kind, extent)

    override suspend fun search(text: String): ApiResult<SearchResults> = search.search(text)

    override suspend fun author(authorId: AuthorId): ApiResult<AuthorDetail> = people.author(authorId)

    override suspend fun book(bookId: BookId): ApiResult<BookDetail> = bookPages.book(bookId)

    override suspend fun bookReading(bookId: BookId): ApiResult<BookReading> = bookPages.bookReading(bookId)

    override suspend fun relatedBooks(bookId: BookId): RelatedBooks = bookPages.relatedBooks(bookId)

    override fun errorOf(throwable: Throwable): ApiError = server.errorOf(throwable)
}
