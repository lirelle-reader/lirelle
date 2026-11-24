package app.tfdn.lirelle.source.komga.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ids.ShelfId
import app.tfdn.lirelle.core.library.model.AuthorRef
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PageDirection
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.RelatedBook
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.library.model.Shelf
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.newAuthenticatedClient
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.KomgaJson
import app.tfdn.lirelle.source.komga.network.KomgaKeyAuthorizer
import app.tfdn.lirelle.source.komga.testing.DeletedBookJson
import app.tfdn.lirelle.source.komga.testing.DeletedSeriesJson
import app.tfdn.lirelle.source.komga.testing.FakeProfileSession
import app.tfdn.lirelle.source.komga.testing.ReadListJson
import app.tfdn.lirelle.source.komga.testing.ReadOneShotJson
import app.tfdn.lirelle.source.komga.testing.ReadingBookJson
import app.tfdn.lirelle.source.komga.testing.SeriesJson
import app.tfdn.lirelle.source.komga.testing.UnreadBookJson
import app.tfdn.lirelle.source.komga.testing.UntitledSeriesJson
import app.tfdn.lirelle.source.komga.testing.bodyText
import app.tfdn.lirelle.source.komga.testing.bookJsonWithId
import app.tfdn.lirelle.source.komga.testing.komgaSession
import app.tfdn.lirelle.source.komga.testing.pageJson
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * A Komga profile's library: its books, series and read lists, as the screens of sequence 2 show a BookOrbit
 * library. Komga's ids are its own text, used as they are; covers carry the item's version.
 */
class KomgaLibraryTest {

    private val server = MockWebServer()
    private lateinit var url: String
    private lateinit var library: KomgaLibrary
    private val answers = mutableListOf<Pair<String, (RecordedRequest) -> MockResponse>>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val route = "${request.method} ${request.url.encodedPath}"
                return answers.lastOrNull { it.first == route }?.second?.invoke(request) ?: MockResponse(code = 404)
            }
        }
        server.start()
        url = server.serverUrl
        library = KomgaLibrary(
            FakeProfileSession(komgaSession(url)),
            KomgaApis(OkHttpClient(), KomgaJson, KomgaLibraryApi::class.java),
            KomgaErrors(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun answer(route: String, body: (RecordedRequest) -> String) {
        answers += route to { request -> json(body(request)) }
    }

    private fun answer(route: String, body: String) = answer(route) { body }

    private fun fail(route: String, code: Int) {
        answers += route to { _ -> MockResponse(code = code) }
    }

    private fun json(body: String) =
        MockResponse(code = 200, headers = headersOf("Content-Type", "application/json"), body = body)

    /** Every request the server had, in order. */
    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    private fun RecordedRequest.route() = "$method ${url.encodedPath}"

    private fun RecordedRequest.body() = Json.parseToJsonElement(bodyText())

    /** A thumbnail's URL, unversioned: Komga keeps an item's `lastModified` when its thumbnail changes. */
    private fun cover(kind: String, id: String) = "$url/api/v1/$kind/$id/thumbnail"

    /** The card of [ReadingBookJson]. */
    private fun readingSummary() = BookSummary(
        id = BookId("0BK1A"),
        title = "The Third Tide",
        authors = listOf("Writer One", "Artist Two"),
        series = SeriesRef(SeriesId("0SR1A"), "Tidewater", "3"),
        coverUrl = cover("books", "0BK1A"),
        coverShape = CoverShape.Portrait,
        media = MediaProfile.TextOnly,
        readState = ReadState.Reading,
        progressPercent = 25,
        lastActivityAt = Instant.parse("2026-11-21T20:30:00Z"),
        pagedKind = PagedKind.Comic,
    )

    @Test
    fun `a book card shows its title, series and number, authors once each, progress and cover`() =
        runBlocking<Unit> {
            answer("POST /api/v1/books/list", pageJson(ReadingBookJson, UnreadBookJson, ReadOneShotJson))

            val books = library.books(BookListSource.AllBooks, BookListRequest()).items.asSnapshot()

            assertThat(books).containsExactly(
                readingSummary(),
                BookSummary(
                    id = BookId("0BK2B"),
                    // No title of its own: the file's name.
                    title = "Tidewater 04",
                    authors = emptyList(),
                    series = SeriesRef(SeriesId("0SR1A"), "Tidewater", "4"),
                    coverUrl = cover("books", "0BK2B"),
                    coverShape = CoverShape.Portrait,
                    media = MediaProfile.TextOnly,
                    readState = ReadState.Unread,
                    progressPercent = null,
                    lastActivityAt = null,
                    pagedKind = PagedKind.Comic,
                ),
                BookSummary(
                    id = BookId("0BK3C"),
                    title = "Lantern",
                    authors = listOf("Writer Three"),
                    // A one-shot is a series of its own in Komga: not shown as one.
                    series = null,
                    coverUrl = cover("books", "0BK3C"),
                    coverShape = CoverShape.Portrait,
                    media = MediaProfile.TextOnly,
                    readState = ReadState.Read,
                    progressPercent = 100,
                    lastActivityAt = Instant.parse("2026-11-01T22:00:00Z"),
                ),
            ).inOrder()
            // A comic's card opens the comics reader; a PDF of Komga's opens nothing yet.
            assertThat(books.map { it.isPaged }).containsExactly(true, true, false).inOrder()
        }

    @Test
    fun `a comic's card Komga has not made ready still opens the comics reader, which reads a kept copy`() =
        runBlocking<Unit> {
            val statuses = listOf("UNKNOWN", "ERROR", "UNSUPPORTED", "OUTDATED")
            answer(
                "POST /api/v1/books/list",
                pageJson(
                    *statuses.map { status ->
                        UnreadBookJson.replace(""""status":"READY"""", """"status":"$status"""")
                    }.toTypedArray(),
                ),
            )

            val books = library.books(BookListSource.AllBooks, BookListRequest()).items.asSnapshot()

            assertThat(books).hasSize(statuses.size)
            // Never the reader of EPUBs: the comics reader opens the kept copy, or says there is no comic.
            assertThat(books.map { it.pagedKind }).containsExactly(*Array(statuses.size) { PagedKind.Comic })
            assertThat(books.map { it.isPaged }).containsExactly(true, true, true, true)
        }

    @Test
    fun `a card Komga does not read as pictures is no comic, ready or not`() = runBlocking<Unit> {
        val epub = UnreadBookJson.replace(""""mediaProfile":"DIVINA"""", """"mediaProfile":"EPUB"""")
        answer(
            "POST /api/v1/books/list",
            pageJson(
                epub,
                ReadOneShotJson,
                // Not analysed yet: Komga does not know the profile.
                UnreadBookJson.replace(""""mediaProfile":"DIVINA"""", """"mediaProfile":""""")
                    .replace(""""status":"READY"""", """"status":"UNKNOWN""""),
            ),
        )

        val books = library.books(BookListSource.AllBooks, BookListRequest()).items.asSnapshot()

        assertThat(books.map { it.pagedKind }).containsExactly(null, null, null)
    }

    @Test
    fun `all books are asked a page at a time, sorted and narrowed in the request`() = runBlocking<Unit> {
        answer("POST /api/v1/books/list", pageJson(ReadingBookJson))
        val request = BookListRequest(
            sort = BookSort.Series,
            direction = SortDirection.Ascending,
            filters = BookFilters(statuses = setOf(StatusFilter.Reading)),
            text = "tide",
        )

        val list = library.books(BookListSource.AllBooks, request)
        list.items.asSnapshot()

        val asked = requests().single()
        assertThat(asked.route()).isEqualTo("POST /api/v1/books/list")
        assertThat(asked.url.queryParameter("page")).isEqualTo("0")
        assertThat(asked.url.queryParameter("size")).isEqualTo("60")
        assertThat(asked.url.queryParameterValues("sort")).containsExactly("series,asc", "metadata.numberSort,asc")
            .inOrder()
        assertThat(asked.body()).isEqualTo(bookSearch(request))
        assertThat(list.total.value).isEqualTo(1)
    }

    @Test
    fun `the books are asked newest first unless told otherwise`() = runBlocking<Unit> {
        answer("POST /api/v1/books/list", pageJson())

        library.books(BookListSource.AllBooks, BookListRequest()).items.asSnapshot()

        assertThat(requests().single().url.queryParameterValues("sort")).containsExactly("createdDate,desc")
    }

    @Test
    fun `pages are numbered from zero, the next asked while there are more`() = runBlocking<Unit> {
        val first = List(60) { bookJsonWithId("0BKA$it") }
        answer("POST /api/v1/books/list") { request ->
            when (request.url.queryParameter("page")) {
                "0" -> pageJson(*first.toTypedArray(), total = 61)
                else -> pageJson(bookJsonWithId("0BKLAST"), total = 61, number = 1)
            }
        }

        val books = library.books(BookListSource.AllBooks, BookListRequest()).items.asSnapshot { scrollTo(60) }

        assertThat(books).hasSize(61)
        assertThat(books.last().id).isEqualTo(BookId("0BKLAST"))
        assertThat(requests().map { it.url.queryParameter("page") }).containsExactly("0", "1").inOrder()
    }

    @Test
    fun `the books of a genre are those of its series, the series asked first`() = runBlocking<Unit> {
        answer("POST /api/v1/series/list", pageJson(SeriesJson))
        answer("POST /api/v1/books/list", pageJson(ReadingBookJson))
        val request = BookListRequest(genre = "Drama")

        val books = library.books(BookListSource.AllBooks, request).items.asSnapshot()

        assertThat(books.map { it.id }).containsExactly(BookId("0BK1A"))
        val (series, booksAsked) = requests()
        assertThat(series.body()).isEqualTo(seriesSearch(text = null, genre = "Drama"))
        assertThat(booksAsked.body()).isEqualTo(bookSearch(request, seriesIds = listOf("0SR1A")))
    }

    @Test
    fun `a genre no series has has no books, and they are not asked`() = runBlocking<Unit> {
        answer("POST /api/v1/series/list", pageJson())

        val list = library.books(BookListSource.AllBooks, BookListRequest(genre = "Western"))

        assertThat(list.items.asSnapshot()).isEmpty()
        assertThat(list.total.value).isEqualTo(0)
        assertThat(requests().map { it.route() }).containsExactly("POST /api/v1/series/list")
    }

    @Test
    fun `a page the server refuses is a paging error naming what it refused`() {
        fail("POST /api/v1/books/list", 401)

        val error = assertThrows(ApiErrorException::class.java) {
            runBlocking { library.books(BookListSource.AllBooks, BookListRequest()).items.asSnapshot() }
        }

        assertThat(error.error).isEqualTo(ApiError.SessionExpired)
        assertThat(library.errorOf(error)).isEqualTo(ApiError.SessionExpired)
    }

    @Test
    fun `a read list's books come in its order by default, narrowed as every list of books`() = runBlocking<Unit> {
        answer("POST /api/v1/books/list", pageJson(UnreadBookJson, ReadingBookJson))
        val request = BookListRequest(
            filters = BookFilters(statuses = setOf(StatusFilter.Read, StatusFilter.Unread)),
            text = "tide",
        )

        val books = library.books(BookListSource.Collection(ShelfId("0RL1")), request).items.asSnapshot()

        assertThat(books.map { it.id }).containsExactly(BookId("0BK2B"), BookId("0BK1A")).inOrder()
        val asked = requests().single()
        assertThat(asked.url.queryParameter("page")).isEqualTo("0")
        assertThat(asked.url.queryParameter("size")).isEqualTo("60")
        assertThat(asked.url.queryParameterValues("sort")).containsExactly("readList.number,asc")
        assertThat(asked.body()).isEqualTo(readListBookSearch("0RL1", request))
    }

    @Test
    fun `a read list's books follow the sort the user chose`() = runBlocking<Unit> {
        answer("POST /api/v1/books/list", pageJson(ReadingBookJson))
        val request = BookListRequest(sort = BookSort.Title, direction = SortDirection.Ascending)

        library.books(BookListSource.Collection(ShelfId("0RL1")), request).items.asSnapshot()

        assertThat(requests().single().url.queryParameterValues("sort")).containsExactly("metadata.title,asc")
    }

    @Test
    fun `a refresh of a genre's books asks again for the series of that genre`() = runBlocking<Unit> {
        answer("POST /api/v1/series/list", pageJson(SeriesJson))
        answer("POST /api/v1/books/list", pageJson(ReadingBookJson))

        library.books(BookListSource.AllBooks, BookListRequest(genre = "Drama")).items.asSnapshot { refresh() }

        assertThat(requests().map { it.route() }).containsExactly(
            "POST /api/v1/series/list",
            "POST /api/v1/books/list",
            "POST /api/v1/series/list",
            "POST /api/v1/books/list",
        ).inOrder()
    }

    @Test
    fun `the genres are Komga's names, each once, by name, as Komga does not count their books`() =
        runBlocking<Unit> {
            answer("GET /api/v2/genres", pageJson("\"drama\"", "\"Adventure\"", "\"\"", "\"Écologie\"", "\"drama\""))

            assertThat(library.genres()).isEqualTo(
                ApiResult.Success(listOf(Genre("Adventure", null), Genre("drama", null), Genre("Écologie", null))),
            )
            val asked = requests().single()
            assertThat(asked.url.queryParameter("unpaged")).isEqualTo("true")
        }

    @Test
    fun `a Komga without genres v2, older than 1-26, is asked its v1 list of names instead`() =
        runBlocking<Unit> {
            // No answer for api/v2/genres: the server's 404.
            answer("GET /api/v1/genres", """["drama","Adventure","","drama"]""")

            assertThat(library.genres()).isEqualTo(
                ApiResult.Success(listOf(Genre("Adventure", null), Genre("drama", null))),
            )
            assertThat(requests().map { it.route() }).containsExactly("GET /api/v2/genres", "GET /api/v1/genres")
                .inOrder()
        }

    @Test
    fun `genres v2 failing otherwise than missing is an error, the v1 list not asked`() = runBlocking<Unit> {
        fail("GET /api/v2/genres", 500)
        answer("GET /api/v1/genres", """["drama"]""")

        assertThat(library.genres()).isInstanceOf(ApiResult.Failure::class.java)
        assertThat(requests().map { it.route() }).containsExactly("GET /api/v2/genres")
    }

    @Test
    fun `the books of a book's series are its related books, but itself and those in the trash`() =
        runBlocking<Unit> {
            answer("GET /api/v1/books/0BK1A", ReadingBookJson)
            answer("POST /api/v1/books/list", pageJson(ReadingBookJson, DeletedBookJson, UnreadBookJson))

            val related = library.relatedBooks(BookId("0BK1A"))

            assertThat(related).isEqualTo(
                RelatedBooks(
                    inSeries = listOf(
                        RelatedBook(
                            id = BookId("0BK2B"),
                            title = "Tidewater 04",
                            authors = emptyList(),
                            coverUrl = cover("books", "0BK2B"),
                            coverShape = CoverShape.Portrait,
                            seriesIndex = "4",
                            readState = ReadState.Unread,
                        ),
                    ),
                ),
            )
            val books = requests().single { it.route() == "POST /api/v1/books/list" }
            assertThat(books.body()).isEqualTo(seriesBooksSearch("0SR1A"))
            assertThat(books.url.queryParameterValues("sort")).containsExactly("metadata.numberSort,asc")
        }

    @Test
    fun `a one-shot has no related books, and a book that cannot be read has none either`() = runBlocking<Unit> {
        answer("GET /api/v1/books/0BK3C", ReadOneShotJson)
        fail("GET /api/v1/books/0BK1A", 500)

        assertThat(library.relatedBooks(BookId("0BK3C"))).isEqualTo(RelatedBooks())
        assertThat(library.relatedBooks(BookId("0BK1A"))).isEqualTo(RelatedBooks())
        assertThat(requests().map { it.route() }).containsExactly("GET /api/v1/books/0BK3C", "GET /api/v1/books/0BK1A")
    }

    @Test
    fun `Komga has no smart list, and is not asked for one`() = runBlocking<Unit> {
        assertThat(library.books(BookListSource.SmartList(ShelfId("1")), BookListRequest()).items.asSnapshot())
            .isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `series are the account's whole list, by title, those in the trash left out`() = runBlocking<Unit> {
        answer("POST /api/v1/series/list", pageJson(SeriesJson, DeletedSeriesJson, UntitledSeriesJson))

        val series = (library.series() as ApiResult.Success).value

        assertThat(series).containsExactly(
            Series(
                id = SeriesId("0SR1A"),
                name = "Tidewater Tales",
                authors = listOf("Writer One", "Artist Two"),
                bookCount = 5,
                readCount = 2,
                coverUrls = listOf(cover("series", "0SR1A")),
                lastAddedAt = Instant.parse("2026-11-20T08:15:00Z"),
            ),
            Series(
                id = SeriesId("0SR2B"),
                // No title in its metadata: its name.
                name = "Lantern",
                authors = emptyList(),
                bookCount = 1,
                readCount = 1,
                coverUrls = listOf(cover("series", "0SR2B")),
                lastAddedAt = Instant.parse("2026-08-03T09:00:00Z"),
            ),
        ).inOrder()
        val asked = requests().single()
        assertThat(asked.url.queryParameter("page")).isEqualTo("0")
        assertThat(asked.url.queryParameter("size")).isEqualTo("100")
        assertThat(asked.url.queryParameterValues("sort")).containsExactly("metadata.titleSort,asc")
        assertThat(asked.body()).isEqualTo(seriesSearch(text = null))
    }

    @Test
    fun `series are asked in batches until all are there`() = runBlocking<Unit> {
        val first = List(100) { SeriesJson.replace(""""id":"0SR1A"""", """"id":"0SRA$it"""") }
        answer("POST /api/v1/series/list") { request ->
            when (request.url.queryParameter("page")) {
                "0" -> pageJson(*first.toTypedArray(), total = 101)
                else -> pageJson(UntitledSeriesJson, total = 101, number = 1)
            }
        }

        val series = (library.series("tide") as ApiResult.Success).value

        assertThat(series).hasSize(101)
        val asked = requests()
        assertThat(asked.map { it.url.queryParameter("page") }).containsExactly("0", "1").inOrder()
        assertThat(asked.first().body()).isEqualTo(seriesSearch(text = "tide"))
    }

    @Test
    fun `a series page has the series and its books in volume order, none from the trash`() = runBlocking<Unit> {
        answer("GET /api/v1/series/0SR1A", SeriesJson)
        answer("POST /api/v1/books/list", pageJson(ReadingBookJson, DeletedBookJson, UnreadBookJson))

        val result = (library.seriesBooks(SeriesId("0SR1A")) as ApiResult.Success).value

        assertThat(result.series.name).isEqualTo("Tidewater Tales")
        assertThat(result.series.coverUrls).containsExactly(cover("series", "0SR1A"))
        assertThat(result.books.map { it.id }).containsExactly(BookId("0BK1A"), BookId("0BK2B")).inOrder()
        val books = requests().single { it.route() == "POST /api/v1/books/list" }
        assertThat(books.url.queryParameterValues("sort")).containsExactly("metadata.numberSort,asc")
        assertThat(books.url.queryParameter("size")).isEqualTo("100")
        assertThat(books.body()).isEqualTo(seriesBooksSearch("0SR1A"))
    }

    @Test
    fun `a series gone from the server is not found`() = runBlocking<Unit> {
        answer("POST /api/v1/books/list", pageJson())

        assertThat(library.seriesBooks(SeriesId("0SRGONE"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `the shelves are the read lists, as collections with their number of books`() = runBlocking<Unit> {
        answer("GET /api/v1/readlists", pageJson(ReadListJson))

        assertThat(library.shelves()).isEqualTo(
            ApiResult.Success(
                Shelves(emptyList(), listOf(Shelf(ShelfKind.Collection, ShelfId("0RL1"), "Harbour Saga", 2))),
            ),
        )
        val asked = requests().single()
        assertThat(asked.url.queryParameter("page")).isEqualTo("0")
        assertThat(asked.url.queryParameter("size")).isEqualTo("100")
    }

    @Test
    fun `a book page has the book's facts, its series' genres and publisher, and its comic to read by pages`() =
        runBlocking<Unit> {
            answer("GET /api/v1/books/0BK1A", ReadingBookJson)
            answer("GET /api/v1/series/0SR1A", SeriesJson)

            val book = (library.book(BookId("0BK1A")) as ApiResult.Success).value

            assertThat(book).isEqualTo(
                BookDetail(
                    id = BookId("0BK1A"),
                    title = "The Third Tide",
                    subtitle = null,
                    authors = listOf(
                        AuthorRef(AuthorId("Writer One"), "Writer One"),
                        AuthorRef(AuthorId("Artist Two"), "Artist Two"),
                    ),
                    series = SeriesRef(SeriesId("0SR1A"), "Tidewater", "3"),
                    coverUrl = cover("books", "0BK1A"),
                    coverShape = CoverShape.Portrait,
                    media = MediaProfile.TextOnly,
                    description = "A storm reaches the harbour.",
                    genres = listOf("Adventure", "Drama"),
                    rating = null,
                    pageCount = 48,
                    publishedYear = 2019,
                    publisher = "North Press",
                    language = "en",
                    isbn = "9780000000001",
                    libraryName = "",
                    addedAt = Instant.parse("2026-10-01T09:00:00Z"),
                    narrators = emptyList(),
                    collections = emptyList(),
                    // A Komga book is one file: the comic, whose id is the book's, versioned by its file's date.
                    files = listOf(
                        BookFile(
                            id = FileId("0BK1A"),
                            kind = BookFileKind.Text,
                            format = "CBZ",
                            sizeBytes = 52_428_800,
                            isPrimary = true,
                            narrationSeconds = null,
                            chapterCount = null,
                            contentVersion = "2026-09-30T18:00:00Z",
                        ),
                    ),
                    readState = ReadState.Reading,
                    startedAt = Instant.parse("2026-11-02T19:00:00Z"),
                    // The series' reading direction, the comics reader's default for it.
                    pageDirection = PageDirection.LeftToRight,
                ),
            )
            assertThat(book.readerFile).isNull()
            assertThat(book.pagedFile?.id).isEqualTo(FileId("0BK1A"))
            assertThat(book.pagedKind).isEqualTo(PagedKind.Comic)
            assertThat(requests().map { it.route() })
                .containsExactly("GET /api/v1/books/0BK1A", "GET /api/v1/series/0SR1A").inOrder()
        }

    @Test
    fun `a manga's series reads right to left, vertical, webtoon and left to right ones left to right`() =
        runBlocking<Unit> {
            answer("GET /api/v1/books/0BK1A", ReadingBookJson)
            val said = mapOf(
                "RIGHT_TO_LEFT" to PageDirection.RightToLeft,
                "LEFT_TO_RIGHT" to PageDirection.LeftToRight,
                "VERTICAL" to PageDirection.LeftToRight,
                "WEBTOON" to PageDirection.LeftToRight,
                // None said, or one the app does not know: the reader's own default.
                "" to null,
                "DIAGONAL" to null,
            )

            said.forEach { (komga, direction) ->
                answer(
                    "GET /api/v1/series/0SR1A",
                    SeriesJson.replace(""""readingDirection":"LEFT_TO_RIGHT"""", """"readingDirection":"$komga""""),
                )
                val book = (library.book(BookId("0BK1A")) as ApiResult.Success).value
                assertWithMessage(komga).that(book.pageDirection).isEqualTo(direction)
            }
        }

    @Test
    fun `a book whose series cannot be read is shown without its series' facts`() = runBlocking<Unit> {
        answer("GET /api/v1/books/0BK2B", UnreadBookJson)
        fail("GET /api/v1/series/0SR1A", 500)

        val book = (library.book(BookId("0BK2B")) as ApiResult.Success).value

        assertThat(book.title).isEqualTo("Tidewater 04")
        assertThat(book.genres).isEmpty()
        assertThat(book.publisher).isNull()
        assertThat(book.description).isNull()
        assertThat(book.isbn).isNull()
        assertThat(book.publishedYear).isNull()
        assertThat(book.readState).isEqualTo(ReadState.Unread)
        assertThat(book.startedAt).isNull()
        assertThat(book.pageDirection).isNull()
    }

    @Test
    fun `a PDF of Komga's has nothing to read yet, and a book not analysed none either`() = runBlocking<Unit> {
        answer("GET /api/v1/books/0BK3C", ReadOneShotJson)
        answer("GET /api/v1/series/0SR2B", UntitledSeriesJson)
        answer("GET /api/v1/books/0BK5E", DeletedBookJson.replace(""""deleted":true""", """"deleted":false"""))

        val pdf = (library.book(BookId("0BK3C")) as ApiResult.Success).value
        val unknown = (library.book(BookId("0BK5E")) as ApiResult.Success).value

        assertThat(pdf.files).isEmpty()
        assertThat(pdf.isPaged).isFalse()
        // Komga serves the PDF's file: it is only not read yet.
        assertThat(pdf.filesUnavailable).isFalse()
        assertThat(unknown.files).isEmpty()
        assertThat(unknown.filesUnavailable).isTrue()
    }

    @Test
    fun `a comic Komga has not made ready offers nothing to read, its pages not served for now`() =
        runBlocking<Unit> {
            fail("GET /api/v1/series/0SR1A", 500)
            listOf("UNKNOWN", "ERROR", "UNSUPPORTED", "OUTDATED").forEach { status ->
                answer(
                    "GET /api/v1/books/0BK2B",
                    UnreadBookJson.replace(""""status":"READY"""", """"status":"$status""""),
                )

                val book = (library.book(BookId("0BK2B")) as ApiResult.Success).value

                assertWithMessage(status).that(book.files).isEmpty()
                assertWithMessage(status).that(book.pagedFile).isNull()
                // Komga holds the file, but serves none of it until it has analysed it.
                assertWithMessage(status).that(book.filesUnavailable).isTrue()
            }
        }

    @Test
    fun `a comic Komga has made ready has its files`() = runBlocking<Unit> {
        fail("GET /api/v1/series/0SR1A", 500)
        answer("GET /api/v1/books/0BK2B", UnreadBookJson)

        val book = (library.book(BookId("0BK2B")) as ApiResult.Success).value

        assertThat(book.filesUnavailable).isFalse()
        assertThat(book.pagedFile).isNotNull()
    }

    @Test
    fun `a comic's file is named by its archive, its size and version absent when Komga gives none`() =
        runBlocking<Unit> {
            val types = listOf(
                "application/x-rar-compressed; version=4" to "CBR",
                "application/x-rar-compressed; version=5" to "CBR",
                "application/vnd.rar" to "CBR",
                "application/x-7z-compressed" to "CB7",
                "application/zip" to "CBZ",
                // A comic of a kind Komga reads and the app has no name for: still an album of pictures.
                "application/x-tar" to "CBZ",
            )
            fail("GET /api/v1/series/0SR1A", 500)
            types.forEach { (type, format) ->
                answer("GET /api/v1/books/0BK2B", UnreadBookJson.replace("application/zip", type))
                val book = (library.book(BookId("0BK2B")) as ApiResult.Success).value
                val file = checkNotNull(book.pagedFile)

                assertThat(file.format).isEqualTo(format)
                assertThat(file.id).isEqualTo(FileId("0BK2B"))
                assertThat(file.sizeBytes).isNull()
                assertThat(file.contentVersion).isNull()
                assertThat(book.pagedKind).isEqualTo(PagedKind.Comic)
            }
        }

    @Test
    fun `a book gone from the server or in its trash is not found`() = runBlocking<Unit> {
        answer("GET /api/v1/books/0BK9Z", DeletedBookJson)

        assertThat(library.book(BookId("0BK9Z"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(library.book(BookId("0BKGONE"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        // The trash's book is not asked about its series.
        assertThat(requests().map { it.route() })
            .containsExactly("GET /api/v1/books/0BK9Z", "GET /api/v1/books/0BKGONE")
    }

    @Test
    fun `a book the account may not see is not found`() = runBlocking<Unit> {
        // A library no longer shared with the account, or a content restriction: Komga refuses the item.
        fail("GET /api/v1/books/0BK1A", 403)

        assertThat(library.book(BookId("0BK1A"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `a refused key still asks for a sign-in, not a book not found`() = runBlocking<Unit> {
        fail("GET /api/v1/books/0BK1A", 401)
        val session = FakeProfileSession(komgaSession(url))
        // The profile's own authenticated client, its key and what a refusal of it does included.
        val client = newAuthenticatedClient(OkHttpClient(), KomgaKeyAuthorizer(session))
        val keyed = KomgaLibrary(session, KomgaApis(client, KomgaJson, KomgaLibraryApi::class.java), KomgaErrors())

        val answer = keyed.book(BookId("0BK1A"))

        assertThat(answer).isNotEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(session.signInNeeded.value).isTrue()
    }

    @Test
    fun `my reading is the page reached over the page count, with its date`() = runBlocking<Unit> {
        answer("GET /api/v1/books/0BK1A", ReadingBookJson)
        answer("GET /api/v1/books/0BK2B", UnreadBookJson)
        answer("GET /api/v1/books/0BK3C", ReadOneShotJson)
        val read = Instant.parse("2026-11-21T20:30:00Z")

        assertThat(library.bookReading(BookId("0BK1A")))
            .isEqualTo(ApiResult.Success(BookReading(25, read, 0, 0, page = 12, positionAt = read)))
        assertThat(library.bookReading(BookId("0BK2B"))).isEqualTo(ApiResult.Success(BookReading(null, null, 0, 0)))
        assertThat((library.bookReading(BookId("0BK3C")) as ApiResult.Success).value.progressPercent).isEqualTo(100)
    }
}
