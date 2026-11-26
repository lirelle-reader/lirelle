package app.tfdn.lirelle.source.kavita.library

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorDetail
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.SortDirection
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

private const val Persons = "POST /api/Person/all"
private const val Person = "GET /api/Person"
private const val AllSeries = "POST /api/Series/all-v2"
private const val Detail = "GET /api/Series/series-detail"

private const val Goscinny = "René Goscinny"

/** Goscinny's books: Astérix's first three, which he wrote with Uderzo, then the three Lucky Luke of the fixture. */
private val GoscinnyBooks = listOf("c44", "c55", "c56", "c861", "c844", "c862").map(::BookId)

/**
 * The authors of a Kavita profile: its writers and pencillers by pages, an author's page asked by the person's
 * name, and an author's books, the series they wrote or drew unfolded and kept to the books that name them.
 */
class KavitaAuthorsTest {

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

    private fun pagination(total: Int) = "Pagination" to """{"currentPage":1,"itemsPerPage":60,"totalItems":$total}"""

    private fun filterBody(vararg statements: String, or: Boolean = false, sortField: Int, ascending: Boolean = true) =
        Json.parseToJsonElement(
            """{"statements":[${statements.joinToString(",")}],"combination":${if (or) 0 else 1},""" +
                """"sortOptions":{"sortField":$sortField,"isAscending":$ascending},"limitTo":0}""",
        )

    /** [count] people from [from] on, each named after its number. */
    private fun people(from: Int, count: Int) =
        (from until from + count).joinToString(",", "[", "]") { """{"id":$it,"name":"P$it","chapterCount":1}""" }

    private fun answerGoscinnyBooks() {
        routes.answerFixture(Person, "person-by-name")
        routes.answerFixture(AllSeries, "series-by-person")
        routes.answer(Detail) { request ->
            when (request.url.queryParameter("seriesId")) {
                "7" -> json(fixtureBody("detail-asterix"))
                "65" -> json(fixtureBody("detail-luckyluke"))
                else -> MockResponse(code = 404)
            }
        }
    }

    private fun authorBooks(name: String = Goscinny) = runBlocking {
        (library.authorBooks(AuthorId(name)) as ApiResult.Success).value
    }

    @Test
    fun `the authors are the writers and pencillers by name, narrowed by text, whatever the library`() =
        runBlocking<Unit> {
            routes.answer(Persons, fixtureBody("persons-gosc"), pagination(1))
            val bd = ServerLibrary(LibraryId("1"), "BD")

            val list = library.authors(AuthorSort.Name, SortDirection.Ascending, text = " gosc ", library = bd)
            val authors = list.items.asSnapshot()

            assertThat(authors).containsExactly(Author(AuthorId(Goscinny), Goscinny, 47, portraitUrl = null))
            assertThat(list.total.value).isEqualTo(1)
            val request = routes.requests().single()
            assertThat(request.route()).isEqualTo(Persons)
            assertThat(request.url.encodedQuery).isEqualTo("PageNumber=1&PageSize=60")
            assertThat(request.jsonBody()).isEqualTo(
                filterBody(
                    """{"comparison":5,"field":1,"value":"3,4"}""",
                    """{"comparison":7,"field":2,"value":"gosc"}""",
                    sortField = 1,
                ),
            )
        }

    @Test
    fun `by number of books they are sorted on the chapters counted, without a text no name is asked`() =
        runBlocking<Unit> {
            routes.answer(Persons, fixtureBody("persons"), pagination(3))

            val authors = library.authors(AuthorSort.BookCount, SortDirection.Descending, text = "  ").items
                .asSnapshot()

            assertThat(authors.map { it.bookCount }).containsExactly(67, 1, 2).inOrder()
            assertThat(routes.requests().single().jsonBody()).isEqualTo(
                filterBody("""{"comparison":5,"field":1,"value":"3,4"}""", sortField = 3, ascending = false),
            )
        }

    @Test
    fun `the list's pages are Kavita's from 1, asked as it scrolls`() = runBlocking<Unit> {
        routes.answer(Persons) { request ->
            val first = request.url.queryParameter("PageNumber") == "1"
            json(if (first) people(1, 60) else people(61, 5), headers = arrayOf(pagination(65)))
        }

        val list = library.authors(AuthorSort.Name, SortDirection.Ascending)
        val authors = list.items.asSnapshot { appendScrollWhile { true } }

        assertThat(authors.map { it.name }).containsExactlyElementsIn((1..65).map { "P$it" }).inOrder()
        assertThat(list.total.value).isEqualTo(65)
        assertThat(routes.requests().map { it.url.encodedQuery })
            .containsExactly("PageNumber=1&PageSize=60", "PageNumber=2&PageSize=60").inOrder()
    }

    @Test
    fun `a list that fails to load is a load error the screen can tell`() = runBlocking<Unit> {
        routes.fail(Persons, 401)

        val thrown = assertThrows(ApiErrorException::class.java) {
            runBlocking { library.authors(AuthorSort.Name, SortDirection.Ascending).items.asSnapshot() }
        }

        assertThat(thrown.error).isEqualTo(ApiError.SessionExpired)
    }

    @Test
    fun `an author's page is the person asked by name, counted by a list of that name`() = runBlocking<Unit> {
        routes.answerFixture(Person, "person-by-name")
        routes.answerFixture(Persons, "person-count")

        val detail = library.author(AuthorId(Goscinny))

        assertThat(detail).isEqualTo(
            ApiResult.Success(AuthorDetail(Author(AuthorId(Goscinny), Goscinny, 47, portraitUrl = null), null)),
        )
        val requests = routes.requests()
        assertThat(requests.single { it.route() == Person }.url.encodedQuery).isEqualTo("name=Ren%C3%A9%20Goscinny")
        val count = requests.single { it.route() == Persons }
        assertThat(count.url.queryParameter("PageNumber")).isEqualTo("1")
        assertThat(count.jsonBody())
            .isEqualTo(filterBody("""{"comparison":0,"field":2,"value":"$Goscinny"}""", sortField = 1))
    }

    @Test
    fun `an author's description is the biography, none when blank`() = runBlocking<Unit> {
        routes.answer(Person, """{"id":43,"name":"$Goscinny","description":"  Scénariste.  "}""")
        routes.answer(Persons, "[]")

        val detail = (library.author(AuthorId(Goscinny)) as ApiResult.Success).value

        assertThat(detail.biography).isEqualTo("Scénariste.")
        assertThat(detail.author.bookCount).isEqualTo(0)

        routes.answer(Person, """{"id":43,"name":"$Goscinny","description":"   "}""")
        assertThat((library.author(AuthorId(Goscinny)) as ApiResult.Success).value.biography).isNull()
    }

    @Test
    fun `an author the server does not know is not found`() = runBlocking<Unit> {
        // Kavita answers 404 to a name it does not know, or does not show this account.
        routes.fail(Person, 404)
        routes.answerFixture(Persons, "person-count")
        assertThat(library.author(AuthorId("Personne"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))

        // An answer with no content, which Kavita does not give, is taken the same way: a guard.
        routes.answer(Person) { MockResponse(code = 204) }
        assertThat(library.author(AuthorId("Personne"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `a blank author is not found, without asking`() = runBlocking<Unit> {
        assertThat(library.author(AuthorId("  "))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(library.authorBooks(AuthorId(""))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `an author's books are those of the series they wrote or drew that name them`() = runBlocking<Unit> {
        answerGoscinnyBooks()

        val books = authorBooks()

        assertThat(books.map { it.id }).containsExactlyElementsIn(GoscinnyBooks).inOrder()
        assertThat(books.first().series).isEqualTo(SeriesRef(SeriesId("7"), "Astérix", "1"))
        val requests = routes.requests()
        assertThat(requests.single { it.route() == Person }.url.encodedQuery).isEqualTo("name=Ren%C3%A9%20Goscinny")
        val series = requests.single { it.route() == AllSeries }
        assertThat(series.url.encodedQuery).isEqualTo("PageNumber=1&PageSize=100")
        assertThat(series.jsonBody()).isEqualTo(
            filterBody(
                """{"comparison":5,"field":17,"value":"43"}""",
                """{"comparison":5,"field":16,"value":"43"}""",
                or = true,
                sortField = 1,
            ),
        )
        assertThat(requests.filter { it.route() == Detail }.map { it.url.queryParameter("seriesId") })
            .containsExactly("7", "65")
    }

    @Test
    fun `the books of someone who both wrote and drew come once, and only theirs`() = runBlocking<Unit> {
        answerGoscinnyBooks()
        routes.answer(Person, """{"id":44,"name":"Albert Uderzo"}""")

        val books = authorBooks("Albert Uderzo")

        assertThat(books.map { it.id }).containsExactly(BookId("c44"), BookId("c55"), BookId("c56")).inOrder()
    }

    @Test
    fun `the series are in reading order, each in its own order`() = runBlocking<Unit> {
        answerGoscinnyBooks()
        // Plain text order would put "Écho" after "Lucky Luke".
        routes.answer(
            AllSeries,
            """[{"id":65,"name":"Lucky Luke","sortName":"Lucky Luke"},{"id":7,"name":"Astérix","sortName":"Écho"}]""",
        )

        assertThat(authorBooks().map { it.id }).containsExactlyElementsIn(GoscinnyBooks).inOrder()
    }

    @Test
    fun `a series gone before its detail is left out`() = runBlocking<Unit> {
        answerGoscinnyBooks()
        routes.answer(Detail) { request ->
            if (request.url.queryParameter("seriesId") == "65") json(fixtureBody("detail-luckyluke")) else
                MockResponse(code = 404)
        }

        assertThat(authorBooks().map { it.id })
            .containsExactly(BookId("c861"), BookId("c844"), BookId("c862")).inOrder()
    }

    @Test
    fun `an author the server does not know has no page of books, and no series is asked`() = runBlocking<Unit> {
        answerGoscinnyBooks()
        routes.fail(Person, 404)

        assertThat(library.authorBooks(AuthorId("Personne"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(routes.requests().map { it.route() }).containsExactly(Person)
    }

    @Test
    fun `a failing list of series is told, without what the server said`() = runBlocking<Unit> {
        answerGoscinnyBooks()
        routes.answer(AllSeries) { json("System.Exception at API.Controllers", code = 500) }

        assertThat(library.authorBooks(AuthorId(Goscinny))).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `no more than 6 details at once`() = runBlocking<Unit> {
        routes.answerFixture(Person, "person-by-name")
        routes.answer(AllSeries, (1..10).joinToString(",", "[", "]") { """{"id":$it,"name":"S$it"}""" })
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        routes.answer(Detail) {
            most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            Thread.sleep(100)
            inFlight.decrementAndGet()
            val id = it.url.queryParameter("seriesId")
            json("""{"storylineChapters":[{"id":$id,"number":"1","writers":[{"id":43,"name":"$Goscinny"}]}]}""")
        }

        assertThat(authorBooks()).hasSize(10)
        assertThat(most.get()).isIn(2..6)
    }
}
