package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.testing.KavitaRoutes
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import app.tfdn.lirelle.source.kavita.testing.fixtureWith
import app.tfdn.lirelle.source.kavita.testing.json
import app.tfdn.lirelle.source.kavita.testing.jsonBody
import app.tfdn.lirelle.source.kavita.testing.route
import app.tfdn.lirelle.source.kavita.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val Search = "GET /api/Search/search"
private const val Volume = "GET /api/Volume"
private const val Persons = "POST /api/Person/all"

/**
 * Search on a Kavita profile: one search call, its series as series, its chapters as books (each volume and each
 * series it does not name asked once), its people as authors counted in one call; Kavita gives up to 15 of each.
 */
class KavitaSearchTest {

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

    private fun search(text: String): SearchResults = runBlocking {
        (library.search(text) as ApiResult.Success).value
    }

    private fun asked(requests: List<RecordedRequest>, route: String) = requests.filter { it.route() == route }

    /** Volume 7 of Astérix (series 7); 407, 449 and 448 hold Dune's books, the last two in series 500. */
    private fun answerVolumes() = routes.answer(Volume) { request ->
        when (request.url.queryParameter("volumeId")) {
            "7" -> json(fixtureBody("volume-asterix"))
            "407" -> json(fixtureBody("volume-dune"))
            "449" -> json(fixtureWith("volume-dune", "id" to 449, "seriesId" to 500))
            "448" -> json(
                fixtureWith(
                    "volume-dune",
                    "id" to 448,
                    "seriesId" to 500,
                    "number" to 2,
                    "minNumber" to 2,
                    "maxNumber" to 2,
                ),
            )
            else -> MockResponse(code = 404)
        }
    }

    @Test
    fun `a search asks once, its series are series and its chapters books in the series it names`() {
        routes.answerFixture(Search, "search-asterix")
        answerVolumes()

        val found = search("astérix")

        assertThat(found.text).isEqualTo("astérix")
        assertThat(found.series.items).containsExactly(
            Series(
                id = SeriesId("7"),
                name = "Astérix",
                authors = emptyList(),
                bookCount = 0,
                readCount = 0,
                coverUrls = listOf("${server.serverUrl}/api/Image/series-cover?seriesId=7"),
                lastAddedAt = null,
            ),
        )
        assertThat(found.series.total).isEqualTo(1)
        assertThat(found.books.items.map { it.id }).containsExactly(BookId("c62"), BookId("c51"), BookId("c68"))
            .inOrder()
        assertThat(found.books.total).isEqualTo(3)
        val corse = found.books.items.first()
        assertThat(corse.title).isEqualTo("Astérix en Corse")
        assertThat(corse.series).isEqualTo(SeriesRef(SeriesId("7"), "Astérix", "20"))
        assertThat(found.authors.items).isEmpty()
        val requests = routes.requests()
        assertThat(asked(requests, Search).single().url.encodedQuery)
            .isEqualTo("queryString=ast%C3%A9rix&includeChapterAndFiles=true")
        // The three chapters are of one volume, asked once; the search names their series.
        assertThat(asked(requests, Volume).map { it.url.queryParameter("volumeId") }).containsExactly("7")
        assertThat(requests.map { it.route() }).containsNoneOf("GET /api/Series/7", Persons)
    }

    @Test
    fun `a volume book takes its volume's number, and a series the search does not name is asked once`() {
        routes.answerFixture(Search, "search-dune")
        answerVolumes()
        routes.answer("GET /api/Series/500", """{"id":500,"name":"Dune (en)","format":3}""")

        val books = search("dune").books.items

        assertThat(books.map { it.id }).containsExactly(BookId("v407"), BookId("v449"), BookId("v448")).inOrder()
        assertThat(books.map { it.title }).containsExactly("Dune", "Dune", "Dune Messiah").inOrder()
        assertThat(books.map { it.series }).containsExactly(
            SeriesRef(SeriesId("261"), "Dune", "1"),
            SeriesRef(SeriesId("500"), "Dune (en)", "1"),
            SeriesRef(SeriesId("500"), "Dune (en)", "2"),
        ).inOrder()
        val requests = routes.requests()
        assertThat(asked(requests, Volume).map { it.url.queryParameter("volumeId") })
            .containsExactly("407", "449", "448")
        assertThat(requests.filter { it.route().startsWith("GET /api/Series/") }.map { it.route() })
            .containsExactly("GET /api/Series/500")
    }

    @Test
    fun `a book found twice is shown once`() {
        val recorded = Json.parseToJsonElement(fixtureBody("search-asterix")) as JsonObject
        val chapters = recorded["chapters"] as JsonArray
        routes.answer(Search, JsonObject(recorded + ("chapters" to JsonArray(chapters + chapters))).toString())
        answerVolumes()

        assertThat(search("astérix").books.items.map { it.id })
            .containsExactly(BookId("c62"), BookId("c51"), BookId("c68")).inOrder()
    }

    @Test
    fun `a volume or a series gone between two calls leaves its book out`() {
        routes.answerFixture(Search, "search-dune")
        // Volume 407 is not there; 449 is in series 261, which the search names; 448 in series 500, not there.
        routes.answer(Volume) { request ->
            when (request.url.queryParameter("volumeId")) {
                "449" -> json(fixtureWith("volume-dune", "id" to 449))
                "448" -> json(fixtureWith("volume-dune", "id" to 448, "seriesId" to 500))
                else -> MockResponse(code = 404)
            }
        }

        assertThat(search("dune").books.items.map { it.id }).containsExactly(BookId("v449"))
    }

    @Test
    fun `the people found are authors by name, counted in one call`() {
        routes.answerFixture(Search, "search-christie")
        routes.answerFixture(Persons, "persons-christie")

        val authors = search("christie").authors

        assertThat(authors.items).containsExactly(Author(AuthorId("Agatha Christie"), "Agatha Christie", 67, null))
        assertThat(authors.total).isEqualTo(1)
        val count = routes.requests().single { it.route() == Persons }
        assertThat(count.url.encodedQuery).isEqualTo("PageNumber=1&PageSize=1")
        assertThat(count.jsonBody()).isEqualTo(
            Json.parseToJsonElement(
                """{"statements":[{"comparison":0,"field":2,"value":"Agatha Christie"}],"combination":0,""" +
                    """"sortOptions":{"sortField":1,"isAscending":true},"limitTo":0}""",
            ),
        )
    }

    @Test
    fun `several people are counted in one call, each with a statement on its name, any of them holding`() {
        routes.answer(
            Search,
            """{"persons":[{"id":2361,"name":"Agatha Christie"},{"id":43,"name":"René Goscinny"}]}""",
        )
        routes.answer(Persons, """[{"id":43,"name":"René Goscinny","chapterCount":47}]""")

        val authors = search("e").authors.items

        assertThat(authors).containsExactly(
            Author(AuthorId("Agatha Christie"), "Agatha Christie", 0, null),
            Author(AuthorId("René Goscinny"), "René Goscinny", 47, null),
        ).inOrder()
        val count = routes.requests().single { it.route() == Persons }
        assertThat(count.url.encodedQuery).isEqualTo("PageNumber=1&PageSize=2")
        assertThat(count.jsonBody().toString()).isEqualTo(
            """{"statements":[{"comparison":0,"field":2,"value":"Agatha Christie"},""" +
                """{"comparison":0,"field":2,"value":"René Goscinny"}],"combination":0,""" +
                """"sortOptions":{"sortField":1,"isAscending":true},"limitTo":0}""",
        )
    }

    @Test
    fun `a full group of series or authors has one more in all, so that See all shows, and books have what came`() {
        val series = (1..15).joinToString(",") { """{"seriesId":$it,"name":"S$it"}""" }
        val persons = (1..15).joinToString(",") { """{"id":$it,"name":"P$it"}""" }
        val chapters = (1..15).joinToString(",") { """{"id":$it,"volumeId":7,"number":"$it","range":"$it"}""" }
        routes.answer(Search, """{"series":[$series],"persons":[$persons],"chapters":[$chapters]}""")
        routes.answer(Persons, "[]")
        answerVolumes()

        val found = search("s")

        assertThat(found.series.items).hasSize(15)
        assertThat(found.series.total).isEqualTo(16)
        assertThat(found.authors.items).hasSize(15)
        assertThat(found.authors.total).isEqualTo(16)
        assertThat(found.books.items).hasSize(15)
        assertThat(found.books.total).isEqualTo(15)
        // Series 7 is in the group: no series is asked.
        assertThat(routes.requests().map { it.route() }).doesNotContain("GET /api/Series/7")
    }

    @Test
    fun `a blank text asks nothing`() {
        assertThat(search("  ")).isEqualTo(SearchResults("  "))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a refused key is a session that ended, and a failing count fails the search`() = runBlocking {
        routes.fail(Search, 401)
        assertThat(library.search("dune")).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))

        routes.answerFixture(Search, "search-christie")
        routes.answer(Persons) { json("System.Exception at API.Controllers", code = 500) }
        assertThat(library.search("christie")).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }
}
