package app.tfdn.lirelle.source.komga.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.newAuthenticatedClient
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.KomgaJson
import app.tfdn.lirelle.source.komga.network.KomgaKeyAuthorizer
import app.tfdn.lirelle.source.komga.testing.DeletedBookJson
import app.tfdn.lirelle.source.komga.testing.FakeProfileSession
import app.tfdn.lirelle.source.komga.testing.ReadOneShotJson
import app.tfdn.lirelle.source.komga.testing.ReadingBookJson
import app.tfdn.lirelle.source.komga.testing.TheKey
import app.tfdn.lirelle.source.komga.testing.UnreadBookJson
import app.tfdn.lirelle.source.komga.testing.bodyText
import app.tfdn.lirelle.source.komga.testing.bookJsonWithId
import app.tfdn.lirelle.source.komga.testing.komgaSession
import app.tfdn.lirelle.source.komga.testing.pageJson
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
 * The rows of Home on a Komga profile: the books being read (as Komga's web interface finds them), its "on deck"
 * and its latest books, each asked of the server with the profile's key whenever Home loads.
 */
class KomgaHomeTest {

    private val server = MockWebServer()
    private lateinit var session: FakeProfileSession
    private lateinit var library: KomgaLibrary
    private val answers = mutableMapOf<String, (RecordedRequest) -> MockResponse>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                answers["${request.method} ${request.url.encodedPath}"]?.invoke(request) ?: MockResponse(code = 404)
        }
        server.start()
        session = FakeProfileSession(komgaSession(server.serverUrl))
        // The profile's own authenticated client, its key and what a refusal of it does included.
        val client = newAuthenticatedClient(OkHttpClient(), KomgaKeyAuthorizer(session))
        library = KomgaLibrary(session, KomgaApis(client, KomgaJson, KomgaLibraryApi::class.java), KomgaErrors())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun answer(route: String, body: String) {
        val json = headersOf("Content-Type", "application/json")
        answers[route] = { MockResponse(code = 200, headers = json, body = body) }
    }

    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    private fun ApiResult<HomeRow>.row() = (this as ApiResult.Success).value

    @Test
    fun `Continue reading is the books in progress, the most recently read first`() = runBlocking<Unit> {
        answer("POST /api/v1/books/list", pageJson(ReadingBookJson))

        val row = library.homeRow(HomeRowKind.Continue).row()

        assertThat(row.kind).isEqualTo(HomeRowKind.Continue)
        assertThat(row.books.map { it.id }).containsExactly(BookId("0BK1A"))
        assertThat(row.hasMore).isFalse()
        val asked = requests().single()
        assertThat(asked.url.queryParameter("page")).isEqualTo("0")
        assertThat(asked.url.queryParameter("size")).isEqualTo("20")
        assertThat(asked.url.queryParameterValues("sort")).containsExactly("readProgress.readDate,desc")
        assertThat(Json.parseToJsonElement(asked.bodyText())).isEqualTo(continueSearch())
        assertThat(asked.headers["X-API-Key"]).isEqualTo(TheKey)
    }

    @Test
    fun `a comic in progress whose file Komga cannot serve for now goes on in the comics reader`() =
        runBlocking<Unit> {
            // A damaged file (ERROR), or one changed since (OUTDATED): Komga counts no page of it.
            val notReady = listOf("ERROR", "OUTDATED").map { status ->
                ReadingBookJson.replace(""""status":"READY"""", """"status":"$status"""")
                    .replace(""""pagesCount":48""", """"pagesCount":0""")
                    .replace(""""id":"0BK1A"""", """"id":"0BK1A-$status"""")
            }
            answer("POST /api/v1/books/list", pageJson(*notReady.toTypedArray()))

            val row = library.homeRow(HomeRowKind.Continue).row()

            assertThat(row.books.map { it.pagedKind }).containsExactly(PagedKind.Comic, PagedKind.Comic)
            assertThat(row.books.map { it.isPaged }).containsExactly(true, true)
        }

    @Test
    fun `Up next in series is Komga's on deck, without the books in the trash`() = runBlocking<Unit> {
        answer("GET /api/v1/books/ondeck", pageJson(UnreadBookJson, DeletedBookJson))

        val row = library.homeRow(HomeRowKind.UpNext).row()

        assertThat(row.books.map { it.id }).containsExactly(BookId("0BK2B"))
        val asked = requests().single()
        assertThat(asked.url.queryParameter("page")).isEqualTo("0")
        assertThat(asked.url.queryParameter("size")).isEqualTo("20")
    }

    @Test
    fun `Recently added is Komga's latest books, those in its library only`() = runBlocking<Unit> {
        answer("GET /api/v1/books/latest", pageJson(ReadOneShotJson, UnreadBookJson))

        val row = library.homeRow(HomeRowKind.RecentlyAdded).row()

        assertThat(row.books.map { it.id }).containsExactly(BookId("0BK3C"), BookId("0BK2B")).inOrder()
        val asked = requests().single()
        assertThat(asked.url.queryParameter("page")).isEqualTo("0")
        assertThat(asked.url.queryParameter("size")).isEqualTo("20")
        assertThat(asked.url.queryParameter("deleted")).isEqualTo("false")
    }

    @Test
    fun `a row's See all asks for fifty books, and a row knows when the server has more`() = runBlocking<Unit> {
        val twenty = List(20) { bookJsonWithId("0BKD$it") }
        answer("GET /api/v1/books/ondeck", pageJson(*twenty.toTypedArray(), total = 64))

        val row = library.homeRow(HomeRowKind.UpNext).row()
        library.homeRow(HomeRowKind.UpNext, HomeRowExtent.SeeAll)

        assertThat(row.books).hasSize(20)
        assertThat(row.hasMore).isTrue()
        assertThat(requests().map { it.url.queryParameter("size") }).containsExactly("20", "50").inOrder()
    }

    @Test
    fun `Komga has no want-to-read list, and is not asked for one`() = runBlocking<Unit> {
        assertThat(library.homeRow(HomeRowKind.WantToRead))
            .isEqualTo(ApiResult.Success(HomeRow(HomeRowKind.WantToRead, emptyList(), hasMore = false)))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a refresh of Home asks the server again, and a revoked key marks the profile`() = runBlocking<Unit> {
        answer("GET /api/v1/books/ondeck", pageJson(UnreadBookJson))
        assertThat(library.homeRow(HomeRowKind.UpNext)).isInstanceOf(ApiResult.Success::class.java)
        assertThat(session.signInNeeded.value).isFalse()

        // The key revoked in Komga's web interface since.
        answers["GET /api/v1/books/ondeck"] = { MockResponse(code = 401) }
        val refreshed = library.homeRow(HomeRowKind.UpNext)

        assertThat(refreshed).isInstanceOf(ApiResult.Failure::class.java)
        assertThat(session.signInNeeded.value).isTrue()
        assertThat(server.requestCount).isEqualTo(2)
    }
}
