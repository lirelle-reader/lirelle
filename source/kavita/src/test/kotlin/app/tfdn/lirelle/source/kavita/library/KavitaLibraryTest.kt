package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesMetadataDto
import app.tfdn.lirelle.source.kavita.network.KavitaJson
import app.tfdn.lirelle.source.kavita.testing.FakeTitles
import app.tfdn.lirelle.source.kavita.testing.KavitaRoutes
import app.tfdn.lirelle.source.kavita.testing.TheKey
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import app.tfdn.lirelle.source.kavita.testing.fixtureWith
import app.tfdn.lirelle.source.kavita.testing.json
import app.tfdn.lirelle.source.kavita.testing.jsonBody
import app.tfdn.lirelle.source.kavita.testing.route
import app.tfdn.lirelle.source.kavita.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

private val Bd = ServerLibrary(LibraryId("1"), "BD")

/**
 * A Kavita profile's library: its libraries, its series and their pages, its books' pages and readings, each
 * brought to Lirelle's books by the rule of Kavita's books, asked with the profile's key. Home's rows are
 * [KavitaHomeTest]'s, Search [KavitaSearchTest]'s, the genres [KavitaGenresTest]'s, the authors [KavitaAuthorsTest]'s.
 */
class KavitaLibraryTest {

    private val server = MockWebServer()
    private lateinit var routes: KavitaRoutes
    private lateinit var url: String
    private lateinit var library: KavitaLibrary

    @Before
    fun setUp() {
        routes = KavitaRoutes(server)
        server.start()
        url = server.serverUrl
        library = routes.library()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private inline fun <reified T> read(name: String): T = KavitaJson.decodeFromString<T>(fixtureBody(name))

    private fun mapper() = KavitaMapper(url, FakeTitles)

    @Test
    fun `the libraries are those the account sees, in the server's order`() = runBlocking {
        routes.answerFixture("GET /api/Library/libraries", "libraries")

        assertThat(library.libraries()).isEqualTo(
            ApiResult.Success(
                listOf(
                    ServerLibrary(LibraryId("1"), "BD"),
                    ServerLibrary(LibraryId("3"), "Livres"),
                    ServerLibrary(LibraryId("2"), "Mangas"),
                ),
            ),
        )
        assertThat(routes.requests().single().headers["x-api-key"]).isEqualTo(TheKey)
    }

    @Test
    fun `the series are asked by batches of 100, narrowed by library and name, each once`() = runBlocking {
        routes.answerFixture("POST /api/Series/all-v2", "all-lib-name")

        val series = (library.series(text = "ast", library = Bd) as ApiResult.Success).value

        assertThat(series.map { it.id }).containsExactly(SeriesId("7"), SeriesId("73")).inOrder()
        assertThat(series.first().name).isEqualTo("Astérix")
        assertThat(series.first().coverUrls).containsExactly("$url/api/Image/series-cover?seriesId=7")
        assertThat(series.map { it.bookCount to it.readCount }).containsExactly(0 to 0, 0 to 0)
        val asked = routes.requests().single()
        assertThat(asked.route()).isEqualTo("POST /api/Series/all-v2")
        assertThat(asked.url.encodedQuery).isEqualTo("PageNumber=1&PageSize=100")
        assertThat(asked.jsonBody()).isEqualTo(
            Json.parseToJsonElement(
                """{"statements":[{"comparison":0,"field":19,"value":"1"},""" +
                    """{"comparison":7,"field":1,"value":"ast"}],"combination":1,""" +
                    """"sortOptions":{"sortField":1,"isAscending":true},"limitTo":0}""",
            ),
        )
    }

    @Test
    fun `a full batch asks the next, a shorter one is the last, and a series that came twice is kept once`() =
        runBlocking {
            routes.answer("POST /api/Series/all-v2") { request ->
                val ids = if (request.url.queryParameter("PageNumber") == "1") 1..100 else 100..101
                json(
                    ids.joinToString(",", "[", "]") { """{"id":$it,"name":"S$it"}""" },
                    headers = arrayOf("Pagination" to """{"currentPage":1,"itemsPerPage":100,"totalItems":500}"""),
                )
            }

            val series = (library.series() as ApiResult.Success).value

            assertThat(series).hasSize(101)
            assertThat(series.last().id).isEqualTo(SeriesId("101"))
            val asked = routes.requests()
            assertThat(asked.map { it.url.queryParameter("PageNumber") }).containsExactly("1", "2").inOrder()
            assertThat(asked.first().jsonBody().toString()).contains(""""statements":[]""")
        }

    @Test
    fun `a full batch that reaches the total is the last`() = runBlocking {
        routes.answer("POST /api/Series/all-v2") {
            json(
                (1..100).joinToString(",", "[", "]") { """{"id":$it,"name":"S$it"}""" },
                headers = arrayOf("Pagination" to """{"currentPage":1,"itemsPerPage":100,"totalItems":100}"""),
            )
        }

        val series = (library.series() as ApiResult.Success).value

        assertThat(series).hasSize(100)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a series page holds its books in order, which are what it counts`() = runBlocking {
        routes.answerFixture("GET /api/Series/261", "series-dune")
        routes.answerFixture("GET /api/Series/series-detail", "detail-dune")

        val page = (library.seriesBooks(SeriesId("261")) as ApiResult.Success).value

        assertThat(page.books.map { it.id }).containsExactly(BookId("v407"), BookId("v406"), BookId("v405")).inOrder()
        assertThat(page.books.map { it.title }).containsExactly("Dune", "Le Messie de Dune", "Les Enfants de Dune")
            .inOrder()
        assertThat(page.books.first().series).isEqualTo(SeriesRef(SeriesId("261"), "Dune", "1"))
        assertThat(page.series.name).isEqualTo("Dune")
        assertThat(page.series.bookCount).isEqualTo(3)
        assertThat(page.series.readCount).isEqualTo(3)
        val detail = routes.requests().single { it.url.encodedPath == "/api/Series/series-detail" }
        assertThat(detail.url.queryParameter("seriesId")).isEqualTo("261")
    }

    @Test
    fun `a lone book's series shows it without a series line`() = runBlocking {
        routes.answerFixture("GET /api/Series/372", "series-lone")
        routes.answerFixture("GET /api/Series/series-detail", "detail-lone")

        val page = (library.seriesBooks(SeriesId("372")) as ApiResult.Success).value

        assertThat(page.books.map { it.id }).containsExactly(BookId("c1857"))
        assertThat(page.books.single().series).isNull()
    }

    @Test
    fun `a chapter's page is its chapter, its series and the series' metadata`() = runBlocking {
        routes.answerFixture("GET /api/Chapter", "chapter-asterix")
        routes.answerFixture("GET /api/Search/series-for-chapter", "series-for-chapter")
        routes.answerFixture("GET /api/Series/metadata", "metadata-asterix")

        val detail = library.book(BookId("c44"))

        val chapter = read<ChapterDto>("chapter-asterix")
        val expected = mapper().detail(
            chapter.book(volumeIndex = null),
            read<SeriesDto>("series-for-chapter"),
            read<SeriesMetadataDto>("metadata-asterix"),
        )
        assertThat(detail).isEqualTo(ApiResult.Success(expected))
        assertThat(expected.title).isEqualTo("Astérix le Gaulois")
        assertThat(expected.series).isEqualTo(SeriesRef(SeriesId("7"), "Astérix", "1"))
        val asked = routes.requests().associateBy { it.url.encodedPath }
        assertThat(asked.getValue("/api/Chapter").url.queryParameter("chapterId")).isEqualTo("44")
        assertThat(asked.getValue("/api/Search/series-for-chapter").url.queryParameter("chapterId")).isEqualTo("44")
        assertThat(asked.getValue("/api/Series/metadata").url.queryParameter("seriesId")).isEqualTo("7")
    }

    @Test
    fun `a volume's page is its volume's book, its series and the series' metadata`() = runBlocking {
        routes.answerFixture("GET /api/Volume", "volume-dune")
        routes.answerFixture("GET /api/Series/261", "series-dune")
        routes.answerFixture("GET /api/Series/metadata", "metadata-dune")

        val detail = (library.book(BookId("v407")) as ApiResult.Success).value

        assertThat(detail.id).isEqualTo(BookId("v407"))
        assertThat(detail.title).isEqualTo("Dune")
        assertThat(detail.series).isEqualTo(SeriesRef(SeriesId("261"), "Dune", "1"))
        assertThat(detail.libraryName).isEqualTo("Livres")
        val asked = routes.requests().associateBy { it.url.encodedPath }
        assertThat(asked.getValue("/api/Volume").url.queryParameter("volumeId")).isEqualTo("407")
        assertThat(asked.getValue("/api/Series/metadata").url.queryParameter("seriesId")).isEqualTo("261")
    }

    @Test
    fun `a book whose series' metadata fails shows without it`() = runBlocking {
        routes.answerFixture("GET /api/Chapter", "chapter-asterix")
        routes.answerFixture("GET /api/Search/series-for-chapter", "series-for-chapter")
        routes.fail("GET /api/Series/metadata", 500)

        val detail = library.book(BookId("c44"))

        val expected = mapper().detail(
            read<ChapterDto>("chapter-asterix").book(volumeIndex = null),
            read<SeriesDto>("series-for-chapter"),
            metadata = null,
        )
        assertThat(detail).isEqualTo(ApiResult.Success(expected))
    }

    @Test
    fun `a special alone in its series has no series line on its page`() = runBlocking {
        routes.answerFixture("GET /api/Chapter", "chapter-lone")
        routes.answer("GET /api/Search/series-for-chapter", fixtureBody("series-lone").replace("11/22/63", "Nouvelles"))
        routes.answerFixture("GET /api/Series/series-detail", "detail-lone")

        val detail = (library.book(BookId("c1857")) as ApiResult.Success).value

        assertThat(detail.title).isEqualTo("11/22/63")
        assertThat(detail.series).isNull()
    }

    @Test
    fun `a book the server does not have, or an id that is none of Kavita's, is not found`() = runBlocking {
        routes.fail("GET /api/Chapter", 404)

        assertThat(library.book(BookId("c44"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        val asked = routes.requests().size

        assertThat(library.book(BookId("zzz"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(library.bookReading(BookId("12"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(server.requestCount).isEqualTo(asked)
    }

    @Test
    fun `a volume that does not hold the book asked is not found`() = runBlocking {
        routes.answerFixture("GET /api/Volume", "volume-dune")

        assertThat(library.book(BookId("v999"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `my reading is the pages read of the book's chapter`() = runBlocking {
        routes.answer("GET /api/Chapter", fixtureWith("chapter-asterix", "pagesRead" to 26))
        routes.answerFixture("GET /api/Volume", "volume-dune")

        val chapter = (library.bookReading(BookId("c44")) as ApiResult.Success).value
        val volume = (library.bookReading(BookId("v407")) as ApiResult.Success).value

        assertThat(chapter.progressPercent).isEqualTo(49)
        assertThat(chapter.page).isEqualTo(26)
        assertThat(chapter.totalSeconds).isEqualTo(0)
        assertThat(volume).isEqualTo(
            BookReading(
                progressPercent = 100,
                lastReadAt = volume.lastReadAt,
                totalSeconds = 0,
                sessionCount = 0,
                page = 60,
            ),
        )
        assertThat(volume.lastReadAt).isNotNull()
    }

    @Test
    fun `the related books are the other books of its series`() = runBlocking {
        routes.answerFixture("GET /api/Search/series-for-chapter", "series-for-chapter")
        routes.answerFixture("GET /api/Series/series-detail", "detail-asterix")

        val related = library.relatedBooks(BookId("c44"))

        assertThat(related.inSeries.map { it.id }).containsExactly(
            BookId("c55"),
            BookId("c56"),
            BookId("c80"),
            BookId("c81"),
            BookId("c82"),
        ).inOrder()
        assertThat(related.inSeries.first().title).isEqualTo("La Serpe d'or")
        assertThat(related.sameAuthor).isEmpty()
        val detail = routes.requests().single { it.url.encodedPath == "/api/Series/series-detail" }
        assertThat(detail.url.queryParameter("seriesId")).isEqualTo("7")
    }

    @Test
    fun `a volume's related books come from its series`() = runBlocking {
        routes.answerFixture("GET /api/Volume", "volume-dune")
        routes.answerFixture("GET /api/Series/series-detail", "detail-dune")

        val related = library.relatedBooks(BookId("v407"))

        assertThat(related.inSeries.map { it.id }).containsExactly(BookId("v406"), BookId("v405")).inOrder()
    }

    @Test
    fun `a lone book has no related books, and a failure leaves none`() = runBlocking {
        routes.answerFixture("GET /api/Search/series-for-chapter", "series-lone")
        routes.answerFixture("GET /api/Series/series-detail", "detail-lone")

        assertThat(library.relatedBooks(BookId("c1857"))).isEqualTo(RelatedBooks())

        routes.fail("GET /api/Series/series-detail", 500)
        assertThat(library.relatedBooks(BookId("c44"))).isEqualTo(RelatedBooks())
        assertThat(library.relatedBooks(BookId("zzz"))).isEqualTo(RelatedBooks())
    }

    @Test
    fun `a key the server refuses is a session that ended`() = runBlocking {
        routes.fail("GET /api/Library/libraries", 401)
        routes.fail("POST /api/Series/all-v2", 401)
        routes.fail("GET /api/Chapter", 401)
        routes.fail("GET /api/Series/261", 401)
        routes.fail("GET /api/Series/series-detail", 401)

        val expired = ApiResult.Failure(ApiError.SessionExpired)
        assertThat(library.libraries()).isEqualTo(expired)
        assertThat(library.series()).isEqualTo(expired)
        assertThat(library.book(BookId("c44"))).isEqualTo(expired)
        assertThat(library.bookReading(BookId("c44"))).isEqualTo(expired)
        assertThat(library.seriesBooks(SeriesId("261"))).isEqualTo(expired)
    }

    @Test
    fun `a failing server is told, without what it said`() = runBlocking {
        routes.answer("GET /api/Library/libraries") { json("System.Exception at API.Controllers", code = 500) }

        assertThat(library.libraries()).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }
}
