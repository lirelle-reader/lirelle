package app.tfdn.lirelle.source.bookorbit.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ids.ShelfId
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.EditionProgress
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.library.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.library.testing.bodyText
import app.tfdn.lirelle.source.bookorbit.library.testing.dispatcherOf
import app.tfdn.lirelle.source.bookorbit.library.testing.enqueueJson
import app.tfdn.lirelle.source.bookorbit.library.testing.enqueueRecorded
import app.tfdn.lirelle.source.bookorbit.library.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.library.testing.target
import app.tfdn.lirelle.source.bookorbit.network.AccountApi
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.ServerFeatures
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

@Suppress("LargeClass") // One test per repository operation.
class DefaultLibraryRepositoryTest {

    private val server = MockWebServer()
    private var signedIn = true
    private lateinit var repository: DefaultLibraryRepository

    @Before
    fun setUp() {
        server.start()
        repository = DefaultLibraryRepository(
            api = ApiProvider(OkHttpClient(), ApiJson, LibraryApi::class.java),
            errors = ErrorMapper(ApiJson),
            features = ServerFeatures(
                api = ApiProvider(OkHttpClient(), ApiJson, AccountApi::class.java),
                errors = ErrorMapper(ApiJson),
                serverUrl = { if (signedIn) server.serverUrl else null },
            ),
            serverUrl = { if (signedIn) server.serverUrl else null },
            audio = DefaultAudioRepository(
                api = ApiProvider(OkHttpClient(), ApiJson, AudioApi::class.java),
                errors = ErrorMapper(ApiJson),
                serverUrl = { if (signedIn) server.serverUrl else null },
            ),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun seriesPage(ids: IntRange, total: Int) = """{"items":[${
        ids.joinToString(",") { """{"id":$it,"name":"S$it","bookCount":2,"readCount":1}""" }
    }],"total":$total}"""

    private fun cards(ids: IntRange, total: Int) =
        """{"items":[${ids.joinToString(",") { """{"id":$it,"title":"B$it"}""" }}],"total":$total}"""

    private fun appInfo(vararg features: String) =
        """{"version":"v3.10.0","features":[${features.joinToString(",") { "\"$it\"" }}]}"""

    // --- books

    @Test
    fun `books of the whole library`() = runBlocking {
        server.enqueueRecorded("books-page")

        val list = repository.books(BookListSource.AllBooks, BookListRequest())
        val books = list.items.asSnapshot()

        assertThat(books).isNotEmpty()
        assertThat(list.total.value).isAtLeast(books.size)
        val request = server.takeRequest()
        assertThat(request.target()).isEqualTo("/api/v1/books/query")
        assertThat(request.bodyText()).contains(""""pagination":{"page":0,"size":60}""")
        assertThat(request.bodyText()).contains(""""collapseLinked":true""")
    }

    @Test
    fun `books of a collection and of a smart list use their own routes`() = runBlocking {
        server.enqueueJson(cards(1..2, 2))
        server.enqueueJson(cards(1..2, 2))

        repository.books(BookListSource.Collection(ShelfId("7")), BookListRequest()).items.asSnapshot()
        repository.books(BookListSource.SmartList(ShelfId("9")), BookListRequest()).items.asSnapshot()

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/collections/7/books/query")
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/smart-scopes/9/books/query")
    }

    @Test
    fun `filters reach the server`() = runBlocking {
        server.enqueueJson(cards(1..1, 1))
        val request = BookListRequest(filters = BookFilters(readAlongOnly = true))

        repository.books(BookListSource.AllBooks, request).items.asSnapshot()

        assertThat(server.takeRequest().bodyText()).contains("epub:readalong")
    }

    @Test
    fun `read-along books come out as read-along`() = runBlocking<Unit> {
        server.enqueueRecorded("books-read-along")

        val books = repository.books(BookListSource.AllBooks, BookListRequest()).items.asSnapshot()

        assertThat(books.map { it.media }.toSet()).containsExactly(MediaProfile.ReadAlong)
    }

    @Test
    fun `a failed page is a paging error that maps to a message`() {
        server.enqueueJson("""{"statusCode":500}""", code = 500)

        val thrown = runCatching {
            runBlocking { repository.books(BookListSource.AllBooks, BookListRequest()).items.asSnapshot() }
        }.exceptionOrNull()

        assertThat(thrown).isNotNull()
        assertThat(repository.errorOf(checkNotNull(thrown))).isEqualTo(ApiError.ServerFailed)
        assertThat(repository.errorOf(IOException("offline"))).isEqualTo(ApiError.ServerUnreachable)
    }

    @Test
    fun `a page the server refused reaches the paging as the error it names`() {
        server.enqueueJson("""{"statusCode":500}""", code = 500)
        server.enqueueJson("""{"statusCode":500}""", code = 500)

        val books = runCatching {
            runBlocking { repository.books(BookListSource.AllBooks, BookListRequest()).items.asSnapshot() }
        }.exceptionOrNull()
        val authors = runCatching {
            runBlocking { repository.authors(AuthorSort.Name, SortDirection.Ascending, null).items.asSnapshot() }
        }.exceptionOrNull()

        assertThat((books as ApiErrorException).error).isEqualTo(ApiError.ServerFailed)
        assertThat((authors as ApiErrorException).error).isEqualTo(ApiError.ServerFailed)
    }

    // --- series

    @Test
    fun `series are loaded whole, in batches of 100`() = runBlocking {
        server.enqueueJson(seriesPage(1..100, total = 130))
        server.enqueueJson(seriesPage(101..130, total = 130))

        val result = repository.series() as ApiResult.Success

        assertThat(result.value).hasSize(130)
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/series?page=0&size=100")
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/series?page=1&size=100")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a series sent in two batches is listed once`() = runBlocking {
        // A series added on the server between the two requests shifts the list by one.
        server.enqueueJson(seriesPage(1..100, total = 130))
        server.enqueueJson(seriesPage(100..129, total = 130))

        val result = repository.series() as ApiResult.Success

        assertThat(result.value.map { it.id }).containsExactlyElementsIn((1..129).map { SeriesId("$it") }).inOrder()
    }

    @Test
    fun `a single batch needs a single request`() = runBlocking {
        server.enqueueRecorded("series-page")

        val result = repository.series() as ApiResult.Success

        assertThat(result.value).isNotEmpty()
        assertThat(result.value.first().coverUrls.size).isAtMost(3)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a failing batch fails the whole load`() = runBlocking {
        server.enqueueJson(seriesPage(1..100, total = 130))
        server.enqueueJson("""{"statusCode":500}""", code = 500)

        assertThat(repository.series()).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `series books come in volume order with the series itself`() = runBlocking {
        server.enqueueRecorded("series-books")

        val result = repository.seriesBooks(SeriesId("4")) as ApiResult.Success

        assertThat(result.value.books).isNotEmpty()
        assertThat(result.value.series.name).isNotEmpty()
        assertThat(server.takeRequest().target())
            .isEqualTo("/api/v1/series/4/books?sort=seriesIndex&order=asc&page=0&size=100")
    }

    // --- authors

    @Test
    fun `authors are paged and sorted by the server`() = runBlocking {
        server.enqueueRecorded("authors-page")

        val authors = repository.authors(AuthorSort.BookCount, SortDirection.Descending).items.asSnapshot()

        assertThat(authors).isNotEmpty()
        assertThat(server.takeRequest().target())
            .isEqualTo("/api/v1/authors?page=0&size=100&sort=bookCount&order=desc")
    }

    @Test
    fun `authors sorted by name are ordered by their sort name, as books sorted by author are`() = runBlocking {
        server.enqueueRecorded("authors-page")

        repository.authors(AuthorSort.Name, SortDirection.Ascending).items.asSnapshot()

        assertThat(server.takeRequest().target())
            .isEqualTo("/api/v1/authors?page=0&size=100&sort=sortName&order=asc")
    }

    @Test
    fun `author and author books`() = runBlocking {
        server.enqueueRecorded("author")
        server.enqueueJson(appInfo("book-links"))
        server.enqueueRecorded("author-books")

        val author = repository.author(AuthorId("3")) as ApiResult.Success
        val books = repository.authorBooks(AuthorId("3")) as ApiResult.Success

        assertThat(author.value.author.name).isNotEmpty()
        assertThat(books.value).isNotEmpty()
    }

    @Test
    fun `the books of an author are folded by work on a server that groups editions`() = runBlocking {
        server.enqueueJson(appInfo("book-links", "book-editions"))
        server.enqueueJson(cards(1..2, 2))

        val books = repository.authorBooks(AuthorId("3")) as ApiResult.Success

        assertThat(books.value).hasSize(2)
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/app-info")
        assertThat(server.takeRequest().target())
            .isEqualTo("/api/v1/authors/3/books?sort=publishedYear&order=desc&page=0&size=100&collapseLinked=true")
    }

    @Test
    fun `a server that does not group editions is sent no parameter it would refuse`() = runBlocking {
        // 3.8 and 3.9 answer 400 to a query parameter they do not know.
        server.enqueueJson(appInfo("book-links"))
        server.enqueueJson(cards(1..2, 2))

        repository.authorBooks(AuthorId("3")) as ApiResult.Success

        server.takeRequest()
        assertThat(server.takeRequest().target())
            .isEqualTo("/api/v1/authors/3/books?sort=publishedYear&order=desc&page=0&size=100")
    }

    @Test
    fun `the books of an author are asked unfolded when the server's features cannot be read`() = runBlocking {
        server.enqueueJson("""{"statusCode":500,"message":"Internal server error"}""", code = 500)
        server.enqueueJson(cards(1..2, 2))

        val books = repository.authorBooks(AuthorId("3")) as ApiResult.Success

        assertThat(books.value).hasSize(2)
        server.takeRequest()
        assertThat(server.takeRequest().target())
            .isEqualTo("/api/v1/authors/3/books?sort=publishedYear&order=desc&page=0&size=100")
    }

    @Test
    fun `every batch of a folded author asks folded, and the features once`() = runBlocking {
        server.enqueueJson(appInfo("book-editions"))
        server.enqueueJson(cards(1..100, 101))
        server.enqueueJson(cards(101..101, 101))

        val books = repository.authorBooks(AuthorId("3")) as ApiResult.Success

        assertThat(books.value).hasSize(101)
        assertThat(server.requestCount).isEqualTo(3)
        server.takeRequest()
        server.takeRequest()
        assertThat(server.takeRequest().target())
            .isEqualTo("/api/v1/authors/3/books?sort=publishedYear&order=desc&page=1&size=100&collapseLinked=true")
    }

    @Test
    fun `the counts by work and by volume are read from the server's answers`() = runBlocking {
        server.enqueueJson("""{"id":53,"name":"A","bookCount":23,"workCount":7}""")
        server.enqueueJson(
            """{"items":[{"id":1,"title":"B1"}],"total":1,"seriesInfo":{"id":4,"name":"S","bookCount":16,
                "readCount":2,"ownedVolumeCount":15,"readVolumeCount":1}}""",
        )

        val author = repository.author(AuthorId("53")) as ApiResult.Success
        val series = repository.seriesBooks(SeriesId("4")) as ApiResult.Success

        assertThat(author.value.author.bookCount).isEqualTo(7)
        assertThat(series.value.series.bookCount).isEqualTo(15)
        assertThat(series.value.series.readCount).isEqualTo(1)
    }

    @Test
    fun `the counts are read from the lists of series and of authors too`() = runBlocking {
        server.enqueueJson(
            """{"items":[{"id":4,"name":"S","bookCount":16,"readCount":2,"ownedVolumeCount":15,
                "readVolumeCount":1}],"total":1}""",
        )
        server.enqueueJson("""{"items":[{"id":53,"name":"A","bookCount":23,"workCount":7}],"total":1}""")

        val series = (repository.series() as ApiResult.Success).value.single()
        val author = repository.authors(AuthorSort.Name, SortDirection.Ascending).items.asSnapshot().single()

        assertThat(series.bookCount).isEqualTo(15)
        assertThat(series.readCount).isEqualTo(1)
        assertThat(author.bookCount).isEqualTo(7)
    }

    // --- shelves

    @Test
    fun `shelves list smart lists then collections`() = runBlocking {
        server.enqueueRecorded("smart-scopes")
        server.enqueueRecorded("collections")

        val shelves = (repository.shelves() as ApiResult.Success).value

        assertThat(shelves.smartLists.map { it.kind }.toSet()).containsExactly(ShelfKind.SmartList)
        assertThat(shelves.collections.map { it.kind }.toSet()).containsExactly(ShelfKind.Collection)
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/smart-scopes")
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/collections")
    }

    // --- book

    @Test
    fun `a book`() = runBlocking {
        server.enqueueRecorded("book")

        val book = (repository.book(BookId("12")) as ApiResult.Success).value

        assertThat(book.files).isNotEmpty()
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/books/12")
    }

    @Test
    fun `a book with other editions lists them, named and ordered as its server does`() = runBlocking {
        server.enqueueJson(
            """{"id":1481,"addedAt":"2026-01-01T00:00:00.000Z","title":"22/11/63","editionName":"Version en français",
                "linkIsLead":false,
                "linkSelf":{"bookId":1481,"libraryName":"EBooks","title":"22/11/63","formats":["epub","m4b"],
                  "status":"present","isLead":false,"editionName":"Version en français",
                  "progress":{"percentage":42.5,"finished":false},"lastReadAt":"2026-11-20T10:00:00.000Z"},
                "linkedBooks":[{"bookId":1477,"libraryName":"EBooks","title":"11/22/63","formats":["epub"],
                  "status":"present","isLead":true,"editionName":"Version en anglais","progress":null,
                  "lastReadAt":null,"coverUpdatedAt":"2026-10-07T13:08:54.938Z"}]}""",
        )
        server.enqueueJson(appInfo("book-links", "book-editions"))

        val book = (repository.book(BookId("1481")) as ApiResult.Success).value

        assertThat(book.editionName).isEqualTo("Version en français")
        assertThat(book.editions.map { it.label })
            .containsExactly("Version en anglais", "Version en français").inOrder()
        assertThat(book.editions.map { it.isOpen }).containsExactly(false, true).inOrder()
        assertThat(book.editions.last().progress).isEqualTo(EditionProgress.InProgress(43))
        assertThat(book.editions.first().coverUrl).startsWith("${server.serverUrl}/api/v1/books/1477/thumbnail")
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/books/1481")
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/app-info")
    }

    @Test
    fun `a book that is gone`() = runBlocking {
        server.enqueueJson("""{"statusCode":404,"message":"Book 12 not found"}""", code = 404)

        assertThat(repository.book(BookId("12"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `a merged book opens the book that replaced it`() = runBlocking {
        server.enqueueJson(
            """{"statusCode":404,"errorCode":"BOOK_MERGED","errorMeta":{"targetBookId":34}}""",
            code = 404,
        )
        server.enqueueRecorded("book")

        val result = repository.book(BookId("12"))

        assertThat(result).isInstanceOf(ApiResult.Success::class.java)
        server.takeRequest()
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/books/34")
    }

    @Test
    fun `a merge is followed once only`() = runBlocking {
        repeat(2) {
            server.enqueueJson(
                """{"statusCode":404,"errorCode":"BOOK_MERGED","errorMeta":{"targetBookId":${34 + it}}}""",
                code = 404,
            )
        }

        assertThat(repository.book(BookId("12"))).isEqualTo(ApiResult.Failure(ApiError.Moved(BookId("35"))))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `reading combines progress and sessions`() = runBlocking {
        server.enqueueRecorded("book-reading-progress")
        server.enqueueRecorded("book-sessions")

        assertThat(repository.bookReading(BookId("12"))).isInstanceOf(ApiResult.Success::class.java)
    }

    @Test
    fun `related books leave out the book itself`() = runBlocking<Unit> {
        server.enqueueJson("""[{"id":12,"title":"Self"},{"id":13,"title":"Next"}]""")
        server.enqueueJson("""[{"id":20,"title":"Other"}]""")
        server.enqueueJson("""[{"id":30,"title":"Similar"}]""")

        val related = repository.relatedBooks(BookId("12"))

        // The three calls run in parallel, so only membership is asserted.
        val all = related.inSeries + related.sameAuthor + related.similar
        assertThat(all.map { it.id }).containsExactly(BookId("13"), BookId("20"), BookId("30"))
    }

    @Test
    fun `a related book sent twice is listed once`() = runBlocking<Unit> {
        val twice = """[{"id":13,"title":"First"},{"id":14,"title":"Other"},{"id":13,"title":"Again"}]"""
        repeat(3) { server.enqueueJson(twice) }

        val related = repository.relatedBooks(BookId("12"))

        listOf(related.inSeries, related.sameAuthor, related.similar).forEach { books ->
            assertThat(books.map { it.id to it.title })
                .containsExactly(BookId("13") to "First", BookId("14") to "Other").inOrder()
        }
    }

    @Test
    fun `a failed related call leaves its list empty and the others intact`() = runBlocking {
        repeat(3) { server.enqueueJson("""{"statusCode":500}""", code = 500) }

        val related = repository.relatedBooks(BookId("12"))

        assertThat(related.inSeries).isEmpty()
        assertThat(related.sameAuthor).isEmpty()
        assertThat(related.similar).isEmpty()
    }

    // --- signed out

    @Test
    fun `without a session every call reports an expired session`() = runBlocking {
        signedIn = false

        assertThat(repository.series()).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(repository.book(BookId("1"))).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(repository.relatedBooks(BookId("1")).similar).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    // --- home

    private fun row(ids: List<Int>, total: Int?, updated: (Int) -> String = { "2026-11-01T10:00:00Z" }) =
        """{"books":[${
            ids.joinToString(",") {
                """{"id":$it,"title":"B$it","readStatus":{"status":"reading","updatedAt":"${updated(it)}"}}"""
            }
        }],"total":$total}"""

    @Test
    fun `a home row asks its dashboard row with the limit`() = runBlocking {
        server.enqueueRecorded("home-up-next")

        val result = repository.homeRow(HomeRowKind.UpNext, limit = 20) as ApiResult.Success

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/dashboard/scrollers/up-next-in-series?limit=20")
        assertThat(result.value.kind).isEqualTo(HomeRowKind.UpNext)
        assertThat(result.value.books).isNotEmpty()
    }

    @Test
    fun `a home row asks twenty books unless told otherwise`() = runBlocking<Unit> {
        server.enqueueRecorded("home-want-to-read")

        repository.homeRow(HomeRowKind.WantToRead)

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/dashboard/scrollers/want-to-read?limit=20")
    }

    @Test
    fun `every Home row asks its BookOrbit dashboard rows`() = runBlocking<Unit> {
        server.dispatcher = dispatcherOf(
            "continue-reading" to row(emptyList(), total = 0),
            "continue-listening" to row(emptyList(), total = 0),
            "want-to-read" to row(emptyList(), total = 0),
            "up-next-in-series" to row(emptyList(), total = 0),
            "recently-added" to row(emptyList(), total = 0),
        )

        val asked = HomeRowKind.entries.associateWith { kind ->
            val before = server.requestCount
            repository.homeRow(kind)
            List(server.requestCount - before) { server.takeRequest().url.encodedPath.substringAfterLast('/') }.toSet()
        }

        assertThat(asked).containsExactly(
            HomeRowKind.Continue, setOf("continue-reading", "continue-listening"),
            HomeRowKind.WantToRead, setOf("want-to-read"),
            HomeRowKind.UpNext, setOf("up-next-in-series"),
            HomeRowKind.RecentlyAdded, setOf("recently-added"),
        )
    }

    @Test
    fun `a row asks twenty books, its See all screen fifty, BookOrbit's most`() = runBlocking<Unit> {
        server.enqueueJson(row(emptyList(), total = 0))
        server.enqueueJson(row(emptyList(), total = 0))

        repository.homeRow(HomeRowKind.UpNext, HomeRowExtent.Row)
        repository.homeRow(HomeRowKind.UpNext, HomeRowExtent.SeeAll)

        assertThat(server.takeRequest().url.queryParameter("limit")).isEqualTo("20")
        assertThat(server.takeRequest().url.queryParameter("limit")).isEqualTo("50")
    }

    @Test
    fun `continue merges reading and listening, one entry per book, latest first`() = runBlocking {
        val days = mapOf(1 to "2026-11-10T10:00:00Z", 2 to "2026-11-12T10:00:00Z", 3 to "2026-11-11T10:00:00Z")
        server.dispatcher = dispatcherOf(
            "continue-reading" to row(listOf(1, 2), total = 2) { days.getValue(it) },
            "continue-listening" to row(listOf(2, 3), total = 2) { days.getValue(it) },
        )

        val result = repository.homeRow(HomeRowKind.Continue, limit = 20) as ApiResult.Success

        assertThat(result.value.books.map { it.id }).containsExactly(BookId("2"), BookId("3"), BookId("1")).inOrder()
        assertThat(result.value.hasMore).isFalse()
    }

    @Test
    fun `continue keeps the latest books up to the limit, and says there are more`() = runBlocking {
        val days = mapOf(
            1 to "2026-11-10T10:00:00Z",
            2 to "2026-11-12T10:00:00Z",
            3 to "2026-11-11T10:00:00Z",
            4 to "2026-11-09T10:00:00Z",
        )
        server.dispatcher = dispatcherOf(
            "continue-reading" to row(listOf(1, 2), total = 2) { days.getValue(it) },
            "continue-listening" to row(listOf(3, 4), total = 2) { days.getValue(it) },
        )

        val result = repository.homeRow(HomeRowKind.Continue, limit = 3) as ApiResult.Success

        assertThat(result.value.books.map { it.id }).containsExactly(BookId("2"), BookId("3"), BookId("1")).inOrder()
        assertThat(result.value.hasMore).isTrue()
    }

    private fun progress(vararg updated: String?) = """{"items":[${
        updated.withIndex().joinToString(",") { (index, at) ->
            """{"fileId":${index + 1},"percentage":${if (at == null) 0 else 40},"updatedAt":${at?.let { "\"$it\"" }}}"""
        }
    }]}"""

    @Test
    fun `continue dates and orders its books by their last reading, not by their status`() = runBlocking {
        // The status of book 1 was written last, by a sync during the night; book 2 was read last.
        val statuses = mapOf(1 to "2026-11-13T03:00:00Z", 2 to "2026-11-12T03:00:00Z")
        server.dispatcher = dispatcherOf(
            "continue-reading" to row(listOf(1), total = 1) { statuses.getValue(it) },
            "continue-listening" to row(listOf(2), total = 1) { statuses.getValue(it) },
            "reading-progress?bookId=1" to progress("2026-11-08T20:00:00Z", "2026-11-10T20:00:00Z"),
            "reading-progress?bookId=2" to progress("2026-11-11T20:00:00Z"),
        )

        val result = repository.homeRow(HomeRowKind.Continue, limit = 20) as ApiResult.Success

        assertThat(result.value.books.map { it.id }).containsExactly(BookId("2"), BookId("1")).inOrder()
        assertThat(result.value.books.map { it.lastActivityAt }).containsExactly(
            Instant.parse("2026-11-11T20:00:00Z"),
            Instant.parse("2026-11-10T20:00:00Z"),
        ).inOrder()
        assertThat(server.requestCount).isEqualTo(4)
    }

    /** A card of book [id] with a text and an audiobook, its status written at [status]. */
    private fun textAndAudioCard(id: Int, status: String) =
        """{"id":$id,"title":"B$id","readStatus":{"status":"reading","updatedAt":"$status"},""" +
            """"files":[{"id":${id}1,"format":"epub","role":"primary"},""" +
            """{"id":${id}2,"format":"m4b","role":"content"}]}"""

    @Test
    fun `continue dates a book with audio by its listening when that is later than its reading`() = runBlocking {
        // Book 2 was read a month ago and listened to on the 15th (the recorded state); book 1 was read
        // on the 14th.
        server.dispatcher = dispatcherOf(
            "continue-reading" to row(listOf(1), total = 1) { "2026-11-01T03:00:00Z" },
            "continue-listening" to """{"books":[${textAndAudioCard(2, "2026-10-01T03:00:00Z")}],"total":1}""",
            "reading-progress?bookId=1" to progress("2026-11-14T20:00:00Z"),
            "reading-progress?bookId=2" to progress("2026-10-15T20:00:00Z"),
            "audiobooks/2/playback-state" to Fixtures.recorded("audio-playback"),
        )

        val result = repository.homeRow(HomeRowKind.Continue, limit = 1) as ApiResult.Success

        // The later date wins, and the book listened to is kept within the limit.
        assertThat(result.value.books.map { it.id }).containsExactly(BookId("2"))
        assertThat(result.value.books.single().lastActivityAt).isEqualTo(Instant.parse("2026-11-15T09:00:00Z"))
        // A book with a text only is not asked about listening.
        assertThat(server.requestCount).isEqualTo(5)
        // The state is handed on with the row, not to be asked again.
        assertThat(result.value.playback.keys).containsExactly(BookId("2"))
        assertThat(result.value.playback.getValue(BookId("2"))?.capturedAt)
            .isEqualTo(Instant.parse("2026-11-15T09:00:00Z"))
    }

    @Test
    fun `a book with audio being listened to but never yet is dated by its reading`() = runBlocking<Unit> {
        server.dispatcher = dispatcherOf(
            "continue-reading" to row(emptyList(), total = 0),
            "continue-listening" to """{"books":[${textAndAudioCard(2, "2026-10-01T03:00:00Z")}],"total":1}""",
            "reading-progress?bookId=2" to progress("2026-10-15T20:00:00Z"),
            // The recorded answer for a book without a state: a body of null.
            "audiobooks/2/playback-state" to "null",
        )

        val result = repository.homeRow(HomeRowKind.Continue, limit = 20) as ApiResult.Success

        assertThat(result.value.books.single().lastActivityAt).isEqualTo(Instant.parse("2026-10-15T20:00:00Z"))
        assertThat(result.value.playback).containsExactly(BookId("2"), null)
    }

    @Test
    fun `only the books being listened to are asked about listening, never more than the limit`() = runBlocking {
        // Twenty books with audio being read, twenty others being listened to.
        val reading = (1..20).joinToString(",") { textAndAudioCard(it, "2026-10-01T03:00:00Z") }
        val listening = (101..120).joinToString(",") { textAndAudioCard(it, "2026-10-01T03:00:00Z") }
        val answers = dispatcherOf(
            "continue-reading" to """{"books":[$reading],"total":20}""",
            "continue-listening" to """{"books":[$listening],"total":20}""",
            "playback-state" to "null",
        )
        val asked = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if ("playback-state" in request.target()) synchronized(asked) { asked += request.target() }
                return answers.dispatch(request)
            }
        }

        repository.homeRow(HomeRowKind.Continue, limit = 20)

        assertThat(asked).hasSize(20)
        assertThat(asked.all { it.removePrefix("/api/v1/audiobooks/").substringBefore('/').toLong() > 100 }).isTrue()
    }

    @Test
    fun `a failed progress call leaves the status time and the continue row intact`() = runBlocking {
        server.dispatcher = dispatcherOf(
            "continue-reading" to row(listOf(1), total = 1) { "2026-11-12T03:00:00Z" },
            "continue-listening" to row(listOf(2), total = 1) { "2026-11-13T03:00:00Z" },
            "reading-progress?bookId=2" to progress("2026-11-11T20:00:00Z"),
            failing = listOf("reading-progress?bookId=1"),
        )

        val result = repository.homeRow(HomeRowKind.Continue, limit = 20) as ApiResult.Success

        assertThat(result.value.books.map { it.id }).containsExactly(BookId("1"), BookId("2")).inOrder()
        assertThat(result.value.books.map { it.lastActivityAt }).containsExactly(
            Instant.parse("2026-11-12T03:00:00Z"),
            Instant.parse("2026-11-11T20:00:00Z"),
        ).inOrder()
    }

    @Test
    fun `continue ignores progress without a date`() = runBlocking {
        server.dispatcher = dispatcherOf(
            "continue-reading" to row(listOf(1), total = 1) { "2026-11-12T03:00:00Z" },
            "continue-listening" to row(listOf(2), total = 1) { "2026-11-13T03:00:00Z" },
            "reading-progress?bookId=1" to progress(null, "2026-11-10T20:00:00Z", null),
            "reading-progress?bookId=2" to progress(null),
        )

        val result = repository.homeRow(HomeRowKind.Continue, limit = 20) as ApiResult.Success

        assertThat(result.value.books.map { it.lastActivityAt }).containsExactly(
            Instant.parse("2026-11-13T03:00:00Z"),
            Instant.parse("2026-11-10T20:00:00Z"),
        ).inOrder()
    }

    @Test
    fun `a row other than continue asks nothing about progress`() = runBlocking {
        server.dispatcher = dispatcherOf("want-to-read" to row(listOf(1, 2), total = 2))

        val result = repository.homeRow(HomeRowKind.WantToRead, limit = 20) as ApiResult.Success

        assertThat(result.value.books).hasSize(2)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `continue fails when one of its two rows fails`() = runBlocking {
        server.dispatcher = dispatcherOf("continue-reading" to row(listOf(1), total = 1))

        assertThat(repository.homeRow(HomeRowKind.Continue)).isInstanceOf(ApiResult.Failure::class.java)
    }

    @Test
    fun `a row has more when the total says so or when it came back full`() = runBlocking {
        server.enqueueJson(row(listOf(1, 2), total = 5))
        server.enqueueJson(row(listOf(1, 2), total = null))
        server.enqueueJson(row(listOf(1, 2), total = 1))
        server.enqueueJson(row(listOf(1), total = null))

        val counted = repository.homeRow(HomeRowKind.WantToRead, limit = 20) as ApiResult.Success
        val full = repository.homeRow(HomeRowKind.UpNext, limit = 2) as ApiResult.Success
        val fullWithLowTotal = repository.homeRow(HomeRowKind.RecentlyAdded, limit = 2) as ApiResult.Success
        val short = repository.homeRow(HomeRowKind.UpNext, limit = 2) as ApiResult.Success

        assertThat(counted.value.hasMore).isTrue()
        assertThat(full.value.hasMore).isTrue()
        assertThat(fullWithLowTotal.value.hasMore).isTrue()
        assertThat(short.value.hasMore).isFalse()
    }

    @Test
    fun `a failed row is a failure`() = runBlocking {
        server.enqueue(MockResponse(code = 500))

        assertThat(repository.homeRow(HomeRowKind.UpNext, limit = 20)).isInstanceOf(ApiResult.Failure::class.java)
    }

    // --- search and genres

    @Test
    fun `search asks ten results per kind and maps the three sections`() = runBlocking {
        server.enqueueRecorded("search")

        val result = repository.search("mars") as ApiResult.Success

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/search?q=mars&limit=10")
        assertThat(result.value.text).isEqualTo("mars")
        assertThat(result.value.series.items).isNotEmpty()
        assertThat(result.value.series.total).isAtLeast(result.value.series.items.size)
        assertThat(result.value.books.items).isNotEmpty()
        assertThat(result.value.books.total).isAtLeast(result.value.books.items.size)
        assertThat(result.value.authors.items).isEmpty()
        assertThat(result.value.isEmpty).isFalse()
    }

    @Test
    fun `an author found by a search has the shape of an author of the authors list`() = runBlocking {
        // The item has the keys of an item of the recorded authors-search.json.
        server.enqueueJson(
            """{"books":{"items":[],"total":0},"series":{"items":[],"total":0},"authors":{"items":[{"item":{
                "bookCount":67,"coverBookId":116,"description":"Description 1","id":273,
                "imageUrl":"/api/v1/authors/273/thumbnail?t=1","lastAddedAt":"2017-08-20T10:05:17.785Z",
                "name":"Author 7","sortName":"Sort name 1"},"matchedField":"name","matchedText":"Author 7"}],
                "total":1}}""",
        )

        val result = repository.search("author") as ApiResult.Success

        val portrait = "${server.serverUrl}/api/v1/authors/273/thumbnail?t=1"
        assertThat(result.value.authors.items).containsExactly(Author(AuthorId("273"), "Author 7", 67, portrait))
        assertThat(result.value.authors.total).isEqualTo(1)
        assertThat(result.value.books.items).isEmpty()
    }

    @Test
    fun `a search section without a total counts its items`() = runBlocking {
        server.enqueueJson(
            """{"books":{"items":[{"item":{"id":1,"title":"B1"}},{"item":{"id":2,"title":"B2"}}],"total":null},
                "series":{"items":[]},"authors":{"items":[],"total":null}}""",
        )

        val result = repository.search("book") as ApiResult.Success

        assertThat(result.value.books.items.map { it.id }).containsExactly(BookId("1"), BookId("2")).inOrder()
        assertThat(result.value.books.total).isEqualTo(2)
        assertThat(result.value.series.total).isEqualTo(0)
        assertThat(result.value.authors.total).isEqualTo(0)
    }

    @Test
    fun `a genre without a name is dropped, the others are kept`() = runBlocking<Unit> {
        server.enqueueJson(
            """{"items":[{"genre":null,"count":3},{"count":2},{"genre":" ","count":2},{"genre":"A","count":1}]}""",
        )

        val result = repository.genres() as ApiResult.Success

        assertThat(result.value).containsExactly(Genre("A", 1))
    }

    @Test
    fun `genres keep the server order and drop empty ones`() = runBlocking {
        server.enqueueJson(
            """{"items":[{"genre":"A","count":3},{"genre":"","count":2},
                {"genre":"B","count":0},{"genre":"C","count":1}]}""",
        )

        val result = repository.genres() as ApiResult.Success

        assertThat(result.value).containsExactly(Genre("A", 3), Genre("C", 1)).inOrder()
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/statistics/genre-distribution")
    }

    @Test
    fun `the recorded genre distribution maps to genres`() = runBlocking<Unit> {
        server.enqueueRecorded("genres")

        val result = repository.genres() as ApiResult.Success

        assertThat(result.value).isNotEmpty()
    }

    @Test
    fun `series and authors can be narrowed to a text`() = runBlocking {
        server.enqueueRecorded("series-search")
        server.enqueueRecorded("authors-search")

        repository.series(text = "mars")
        repository.authors(AuthorSort.Name, SortDirection.Ascending, text = "king").items.asSnapshot()

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/series?page=0&size=100&q=mars")
        assertThat(server.takeRequest().target()).endsWith("&q=king")
    }
}
