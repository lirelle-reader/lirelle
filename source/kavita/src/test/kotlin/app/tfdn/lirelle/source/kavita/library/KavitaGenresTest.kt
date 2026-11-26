package app.tfdn.lirelle.source.kavita.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.testing.KavitaRoutes
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import app.tfdn.lirelle.source.kavita.testing.json
import app.tfdn.lirelle.source.kavita.testing.jsonBody
import app.tfdn.lirelle.source.kavita.testing.route
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

private const val Genres = "GET /api/Metadata/genres"
private const val AllSeries = "POST /api/Series/all-v2"
private const val Detail = "GET /api/Series/series-detail"

/** Astérix (7) and Dune (261), the series of the genre in the tests that do not say otherwise. */
private const val TwoSeries = """[{"id":7,"name":"Astérix","format":1},{"id":261,"name":"Dune","format":3}]"""

/** Astérix's books in its order, then Dune's. */
private val TwoSeriesBooks = listOf("c44", "c55", "c56", "c80", "c81", "c82", "v407", "v406", "v405").map(::BookId)

/**
 * The genres of a Kavita profile, by name and without counts, and a genre's books: its series asked by pages of 10,
 * each unfolded into its books as the list scrolls.
 */
class KavitaGenresTest {

    private val server = MockWebServer()
    private lateinit var routes: KavitaRoutes
    private lateinit var library: KavitaLibrary

    @Before
    fun setUp() {
        routes = KavitaRoutes(server)
        server.start()
        library = routes.library()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun answerTwoSeries() {
        routes.answerFixture(Genres, "genres")
        routes.answer(AllSeries, TwoSeries)
        routes.answer(Detail) { request ->
            when (request.url.queryParameter("seriesId")) {
                "7" -> json(fixtureBody("detail-asterix"))
                "261" -> json(fixtureBody("detail-dune"))
                else -> MockResponse(code = 404)
            }
        }
    }

    /** A series detail of one chapter, whose id is the series'. */
    private fun answerOneBookEach() = routes.answer(Detail) { request ->
        val id = request.url.queryParameter("seriesId")
        json("""{"storylineChapters":[{"id":$id,"number":"1","range":"1","pages":10}]}""")
    }

    /** Series [from] to [to], each named after its id. */
    private fun seriesList(from: Int, to: Int) =
        (from..to).joinToString(",", "[", "]") { """{"id":$it,"name":"S$it"}""" }

    private fun genre(name: String = "Action", sort: BookSort = BookSort.Title, filters: BookFilters = BookFilters()) =
        BookListRequest(sort = sort, direction = sort.defaultDirection, filters = filters, genre = name)

    private fun filterBody(vararg statements: String, sortField: Int, ascending: Boolean) = Json.parseToJsonElement(
        """{"statements":[${statements.joinToString(",")}],"combination":1,""" +
            """"sortOptions":{"sortField":$sortField,"isAscending":$ascending},"limitTo":0}""",
    )

    @Test
    fun `the genres are by name, trimmed, each once, without counts`() = runBlocking<Unit> {
        routes.answer(
            Genres,
            """[{"id":24,"title":"arts"},{"id":3,"title":"Écologie"},{"id":5,"title":"Action"},""" +
                """{"id":2,"title":"Aventure"},{"id":9,"title":" Aventure "},{"id":4,"title":"  "},""" +
                """{"id":6,"title":"biographie"}]""",
        )

        val genres = (library.genres() as ApiResult.Success).value

        assertThat(genres.map { it.name }).containsExactly("Action", "arts", "Aventure", "biographie", "Écologie")
            .inOrder()
        assertThat(genres.first()).isEqualTo(Genre("Action", bookCount = null))
    }

    @Test
    fun `a genre's books are its series by name, each unfolded in its own order`() = runBlocking<Unit> {
        answerTwoSeries()

        val list = library.books(BookListSource.AllBooks, genre())
        val books = list.items.asSnapshot()

        assertThat(books.map { it.id }).containsExactlyElementsIn(TwoSeriesBooks).inOrder()
        assertThat(books.last().series).isEqualTo(SeriesRef(SeriesId("261"), "Dune", "3"))
        assertThat(list.total.value).isNull()
        val requests = routes.requests()
        assertThat(requests.count { it.route() == Genres }).isEqualTo(1)
        // Two series are fewer than a page: the first page is the last.
        val page = requests.single { it.route() == AllSeries }
        assertThat(page.url.encodedQuery).isEqualTo("PageNumber=1&PageSize=10")
        assertThat(page.jsonBody()).isEqualTo(
            filterBody("""{"comparison":5,"field":18,"value":"5"}""", sortField = 1, ascending = true),
        )
        assertThat(requests.filter { it.route() == Detail }.map { it.url.queryParameter("seriesId") })
            .containsExactly("7", "261")
    }

    @Test
    fun `recently added, the default, sorts the series by date added, newest first`() = runBlocking<Unit> {
        answerTwoSeries()

        library.books(BookListSource.AllBooks, BookListRequest(genre = "Action")).items.asSnapshot()

        assertThat(routes.requests().single { it.route() == AllSeries }.jsonBody()).isEqualTo(
            filterBody("""{"comparison":5,"field":18,"value":"5"}""", sortField = 2, ascending = false),
        )
    }

    @Test
    fun `the formats go to the server with the genre`() = runBlocking<Unit> {
        answerTwoSeries()
        val formats = BookFilters(formats = setOf(FormatFilter.Epub, FormatFilter.Comics))

        library.books(BookListSource.AllBooks, genre(filters = formats)).items.asSnapshot()

        assertThat(routes.requests().single { it.route() == AllSeries }.jsonBody()).isEqualTo(
            filterBody(
                """{"comparison":5,"field":18,"value":"5"}""",
                """{"comparison":5,"field":21,"value":"0,1,3"}""",
                sortField = 1,
                ascending = true,
            ),
        )
    }

    @Test
    fun `the reading states narrow the unfolded books on the device`() = runBlocking<Unit> {
        answerTwoSeries()
        val read = BookFilters(statuses = setOf(StatusFilter.Read))

        val books = library.books(BookListSource.AllBooks, genre(filters = read)).items.asSnapshot()

        assertThat(books.map { it.id }).containsExactly(BookId("v407"), BookId("v406"), BookId("v405")).inOrder()
    }

    @Test
    fun `a full page of series asks the next as the list scrolls, the genre once`() = runBlocking<Unit> {
        routes.answerFixture(Genres, "genres")
        routes.answer(AllSeries) { request ->
            json(if (request.url.queryParameter("PageNumber") == "1") seriesList(1, 10) else seriesList(11, 11))
        }
        answerOneBookEach()

        val books = library.books(BookListSource.AllBooks, genre()).items.asSnapshot { appendScrollWhile { true } }

        assertThat(books.map { it.id.value }).containsExactlyElementsIn((1..11).map { "c$it" }).inOrder()
        val requests = routes.requests()
        assertThat(requests.filter { it.route() == AllSeries }.map { it.url.encodedQuery })
            .containsExactly("PageNumber=1&PageSize=10", "PageNumber=2&PageSize=10").inOrder()
        assertThat(requests.count { it.route() == Genres }).isEqualTo(1)
    }

    @Test
    fun `a page whose books are all narrowed out goes on to the next`() = runBlocking<Unit> {
        routes.answerFixture(Genres, "genres")
        routes.answer(AllSeries) { request ->
            if (request.url.queryParameter("PageNumber") == "1") json(seriesList(1, 10)) else json(TwoSeries)
        }
        routes.answer(Detail) { request ->
            when (val id = request.url.queryParameter("seriesId")) {
                "261" -> json(fixtureBody("detail-dune"))
                else -> json("""{"storylineChapters":[{"id":$id,"number":"1","range":"1","pages":10}]}""")
            }
        }
        val read = BookFilters(statuses = setOf(StatusFilter.Read))

        val books = library.books(BookListSource.AllBooks, genre(filters = read)).items.asSnapshot()

        assertThat(books.map { it.id }).containsExactly(BookId("v407"), BookId("v406"), BookId("v405")).inOrder()
    }

    @Test
    fun `many pages narrowed out are skipped in short steps, and the books after them still come`() =
        runBlocking<Unit> {
            routes.answerFixture(Genres, "genres")
            // Pages 1 to 8 hold ten unread series each, page 9 Dune, read.
            routes.answer(AllSeries) { request ->
                val page = request.url.queryParameter("PageNumber")!!.toInt()
                json(if (page <= 8) seriesList(page * 10 - 9, page * 10) else TwoSeries)
            }
            routes.answer(Detail) { request ->
                when (val id = request.url.queryParameter("seriesId")) {
                    "261" -> json(fixtureBody("detail-dune"))
                    else -> json("""{"storylineChapters":[{"id":$id,"number":"1","range":"1","pages":10}]}""")
                }
            }
            val read = BookFilters(statuses = setOf(StatusFilter.Read))

            val books = library.books(BookListSource.AllBooks, genre(filters = read)).items.asSnapshot()

            assertThat(books.map { it.id }).containsExactly(BookId("v407"), BookId("v406"), BookId("v405")).inOrder()
            val pages = routes.requests().filter { it.route() == AllSeries }.map { it.url.queryParameter("PageNumber") }
            assertThat(pages).containsExactlyElementsIn((1..9).map { "$it" }).inOrder()
        }

    @Test
    fun `reading states Kavita does not keep leave the genre's books empty, without asking its series`() =
        runBlocking<Unit> {
            answerTwoSeries()
            val kept = BookFilters(statuses = setOf(StatusFilter.WantToRead, StatusFilter.Abandoned))

            val books = library.books(BookListSource.AllBooks, genre(filters = kept)).items.asSnapshot()

            assertThat(books).isEmpty()
            assertThat(routes.requests().map { it.route() }).doesNotContain(AllSeries)
        }

    @Test
    fun `a genre the server does not know is empty, after one look at the genres`() = runBlocking<Unit> {
        routes.answerFixture(Genres, "genres")

        val books = library.books(BookListSource.AllBooks, genre(name = "Western")).items.asSnapshot()

        assertThat(books).isEmpty()
        assertThat(routes.requests().map { it.route() }).containsExactly(Genres)
    }

    @Test
    fun `a series gone before its detail is left out`() = runBlocking<Unit> {
        answerTwoSeries()
        routes.answer(Detail) { request ->
            if (request.url.queryParameter("seriesId") == "261") json(fixtureBody("detail-dune")) else
                MockResponse(code = 404)
        }

        val books = library.books(BookListSource.AllBooks, genre()).items.asSnapshot()

        assertThat(books.map { it.id }).containsExactly(BookId("v407"), BookId("v406"), BookId("v405")).inOrder()
    }

    @Test
    fun `a list that fails to load is a load error the screen can tell`() = runBlocking<Unit> {
        routes.answerFixture(Genres, "genres")
        routes.fail(AllSeries, 401)

        val thrown = assertThrows(ApiErrorException::class.java) {
            runBlocking { library.books(BookListSource.AllBooks, genre()).items.asSnapshot() }
        }

        assertThat(thrown.error).isEqualTo(ApiError.SessionExpired)
    }

    @Test
    fun `no more than 6 details at once`() = runBlocking<Unit> {
        routes.answerFixture(Genres, "genres")
        routes.answer(AllSeries) { request ->
            json(if (request.url.queryParameter("PageNumber") == "1") seriesList(1, 10) else "[]")
        }
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        routes.answer(Detail) {
            most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            Thread.sleep(100)
            inFlight.decrementAndGet()
            json("""{"storylineChapters":[{"id":${it.url.queryParameter("seriesId")},"number":"1"}]}""")
        }

        assertThat(library.books(BookListSource.AllBooks, genre()).items.asSnapshot()).hasSize(10)
        assertThat(most.get()).isIn(2..6)
    }

    @Test
    fun `a page of a genre's books is a page of its series, unfolded`() = runBlocking<Unit> {
        answerTwoSeries()

        val page = (library.bookPage(genre(), page = 1, size = 2) as ApiResult.Success).value

        assertThat(page.items.map { it.id }).containsExactlyElementsIn(TwoSeriesBooks).inOrder()
        // A full page of series may be followed by more: Kavita does not count a genre's books.
        assertThat(page.total).isEqualTo(Int.MAX_VALUE)
        assertThat(routes.requests().single { it.route() == AllSeries }.url.encodedQuery)
            .isEqualTo("PageNumber=2&PageSize=2")
    }
}
