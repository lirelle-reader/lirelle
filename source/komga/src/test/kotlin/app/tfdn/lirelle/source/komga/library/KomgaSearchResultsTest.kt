package app.tfdn.lirelle.source.komga.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.KomgaJson
import app.tfdn.lirelle.source.komga.testing.DeletedBookJson
import app.tfdn.lirelle.source.komga.testing.DeletedSeriesJson
import app.tfdn.lirelle.source.komga.testing.FakeProfileSession
import app.tfdn.lirelle.source.komga.testing.ReadOneShotJson
import app.tfdn.lirelle.source.komga.testing.ReadingBookJson
import app.tfdn.lirelle.source.komga.testing.SeriesJson
import app.tfdn.lirelle.source.komga.testing.UntitledSeriesJson
import app.tfdn.lirelle.source.komga.testing.bodyText
import app.tfdn.lirelle.source.komga.testing.komgaSession
import app.tfdn.lirelle.source.komga.testing.pageJson
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Search on a Komga profile: the series and the books matching the text (Komga's full-text search), the first of
 * each with how many there are in all, as the Search screen shows them. Komga has no authors to find.
 */
class KomgaSearchResultsTest {

    private val server = MockWebServer()
    private lateinit var library: KomgaLibrary
    private val answers = mutableMapOf<String, (RecordedRequest) -> MockResponse>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                answers["${request.method} ${request.url.encodedPath}"]?.invoke(request) ?: MockResponse(code = 404)
        }
        server.start()
        library = KomgaLibrary(
            FakeProfileSession(komgaSession(server.serverUrl)),
            KomgaApis(OkHttpClient(), KomgaJson, KomgaLibraryApi::class.java),
            KomgaErrors(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun answer(route: String, body: String) {
        val json = headersOf("Content-Type", "application/json")
        answers[route] = { MockResponse(code = 200, headers = json, body = body) }
    }

    /** Every request the server had, by route (the two sections are asked together, in no set order). */
    private fun requests(): Map<String, RecordedRequest> =
        List(server.requestCount) { server.takeRequest() }.associateBy { "${it.method} ${it.url.encodedPath}" }

    private fun ApiResult<SearchResults>.results() = (this as ApiResult.Success).value

    private fun RecordedRequest.fullText() =
        (Json.parseToJsonElement(bodyText()) as JsonObject)["fullTextSearch"]?.jsonPrimitive?.content

    @Test
    fun `a search finds the first ten series and books matching the text, with how many there are`() =
        runBlocking<Unit> {
            answer("POST /api/v1/series/list", pageJson(SeriesJson, UntitledSeriesJson, total = 14))
            answer("POST /api/v1/books/list", pageJson(ReadingBookJson, ReadOneShotJson, total = 23))

            val results = library.search("tide").results()

            assertThat(results.text).isEqualTo("tide")
            assertThat(results.series.items.map { it.id }).containsExactly(SeriesId("0SR1A"), SeriesId("0SR2B"))
                .inOrder()
            assertThat(results.series.items.first().name).isEqualTo("Tidewater Tales")
            assertThat(results.series.total).isEqualTo(14)
            assertThat(results.books.items.map { it.id }).containsExactly(BookId("0BK1A"), BookId("0BK3C")).inOrder()
            assertThat(results.books.items.first().title).isEqualTo("The Third Tide")
            assertThat(results.books.total).isEqualTo(23)
            assertThat(results.authors.items).isEmpty()
            val asked = requests()
            val expected = Json.parseToJsonElement(
                """{"condition":{"allOf":[{"deleted":{"operator":"isFalse"}}]},"fullTextSearch":"tide"}""",
            )
            listOf("POST /api/v1/series/list", "POST /api/v1/books/list").forEach { route ->
                val request = asked.getValue(route)
                assertThat(Json.parseToJsonElement(request.bodyText())).isEqualTo(expected)
                assertThat(request.url.queryParameter("page")).isEqualTo("0")
                assertThat(request.url.queryParameter("size")).isEqualTo("10")
                // Unsorted: Komga then gives the best matches first.
                assertThat(request.url.queryParameterValues("sort")).isEmpty()
            }
        }

    @Test
    fun `what the user typed is searched as text, Lucene's characters and operators escaped`() = runBlocking<Unit> {
        answer("POST /api/v1/series/list", pageJson())
        answer("POST /api/v1/books/list", pageJson())

        library.search("  Tide: (part 2) AND \"more\" ")

        val asked = requests()
        val escaped = """Tide\: \(part 2\) and \"more\""""
        assertThat(asked.getValue("POST /api/v1/series/list").fullText()).isEqualTo(escaped)
        assertThat(asked.getValue("POST /api/v1/books/list").fullText()).isEqualTo(escaped)
    }

    @Test
    fun `nothing in the trash is found`() = runBlocking<Unit> {
        answer("POST /api/v1/series/list", pageJson(DeletedSeriesJson, SeriesJson))
        answer("POST /api/v1/books/list", pageJson(DeletedBookJson, ReadingBookJson))

        val results = library.search("tide").results()

        assertThat(results.series.items.map { it.id }).containsExactly(SeriesId("0SR1A"))
        assertThat(results.books.items.map { it.id }).containsExactly(BookId("0BK1A"))
    }

    @Test
    fun `nothing found is empty results`() = runBlocking<Unit> {
        answer("POST /api/v1/series/list", pageJson())
        answer("POST /api/v1/books/list", pageJson())

        val results = library.search("zzz").results()

        assertThat(results.isEmpty).isTrue()
        assertThat(results.series.total).isEqualTo(0)
        assertThat(results.books.total).isEqualTo(0)
    }

    @Test
    fun `a section the server refuses fails the search`() = runBlocking<Unit> {
        answer("POST /api/v1/series/list", pageJson(SeriesJson))
        answers["POST /api/v1/books/list"] = { MockResponse(code = 500) }

        assertThat(library.search("tide")).isInstanceOf(ApiResult.Failure::class.java)
    }

    @Test
    fun `a blank text finds nothing, and the server is not asked`() = runBlocking<Unit> {
        assertThat(library.search("   ")).isEqualTo(ApiResult.Success(SearchResults("   ")))
        assertThat(server.requestCount).isEqualTo(0)
    }
}
