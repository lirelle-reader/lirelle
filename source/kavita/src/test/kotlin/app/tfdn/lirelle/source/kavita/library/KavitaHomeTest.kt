package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.testing.KavitaRoutes
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import app.tfdn.lirelle.source.kavita.testing.fixtureWith
import app.tfdn.lirelle.source.kavita.testing.json
import app.tfdn.lirelle.source.kavita.testing.jsonBody
import app.tfdn.lirelle.source.kavita.testing.route
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val OnDeck = "POST /api/Series/on-deck"
private const val ContinuePoint = "GET /api/Reader/continue-point"
private const val Volume = "GET /api/Volume"
private const val Detail = "GET /api/Series/series-detail"

/**
 * Home's rows on a Kavita profile: Continue and Up next from the on-deck series and their continue points, shared
 * between the two rows for a few seconds; Want to read from the want-to-read series; Recently added from the
 * recently updated series, each read with its series detail.
 */
class KavitaHomeTest {

    private val server = MockWebServer()
    private val now = AtomicLong(0)
    private lateinit var routes: KavitaRoutes
    private lateinit var library: KavitaLibrary

    @Before
    fun setUp() {
        routes = KavitaRoutes(server)
        server.start()
        library = routes.library(clock = now::get)
    }

    @After
    fun tearDown() {
        server.close()
    }

    /**
     * The on-deck series 117 (Astérix, not started), 349 (Dune, read), 257 (Harry Potter in English, not started),
     * 264 (Harry Potter in French, started, a volume book) and 82 (Naruto, a started chapter).
     */
    private fun answerOnDeck() {
        routes.answerFixture(OnDeck, "ondeck")
        answerContinuePoints(
            "117" to "continue-asterix",
            "349" to "continue-dune",
            "257" to "continue-harry-potter",
            "264" to "continue-potter",
            "82" to "continue-naruto",
        )
        answerVolumes()
    }

    private fun answerContinuePoints(vararg points: Pair<String, String>) = routes.answer(ContinuePoint) { request ->
        val name = points.toMap()[request.url.queryParameter("seriesId")]
        if (name == null) MockResponse(code = 404) else json(fixtureBody(name))
    }

    private fun answerVolumes() = routes.answer(Volume) { request ->
        when (request.url.queryParameter("volumeId")) {
            "430" -> json(
                fixtureWith("volume-harry-potter", "id" to 430, "number" to 2, "minNumber" to 2, "maxNumber" to 2),
            )
            "190" -> json(fixtureBody("volume-harry-potter"))
            "407" -> json(fixtureBody("volume-dune"))
            else -> MockResponse(code = 404)
        }
    }

    private fun HomeRow.ids() = books.map { it.id }

    private fun row(kind: HomeRowKind, extent: HomeRowExtent = HomeRowExtent.Row): HomeRow = runBlocking {
        (library.homeRow(kind, extent) as ApiResult.Success).value
    }

    private fun asked(route: String): List<RecordedRequest> = routes.requests().filter { it.route() == route }

    @Test
    fun `the rows ask 20 series, their "See all" 50`() {
        assertThat(HomeRowExtent.Row.size).isEqualTo(20)
        assertThat(HomeRowExtent.SeeAll.size).isEqualTo(50)
    }

    @Test
    fun `Continue holds the started books of the on-deck series, a volume book with its volume's number`() {
        answerOnDeck()

        val continuing = row(HomeRowKind.Continue)

        assertThat(continuing.ids()).containsExactly(BookId("v430"), BookId("c1262")).inOrder()
        assertThat(continuing.hasMore).isFalse()
        val potter = continuing.books.first()
        assertThat(potter.title).isEqualTo("Harry Potter et la Chambre des Secrets")
        assertThat(potter.series).isEqualTo(SeriesRef(SeriesId("264"), "Harry Potter (fr)", "2"))
        assertThat(potter.readState).isEqualTo(ReadState.Reading)
        assertThat(potter.progressPercent).isEqualTo(36)
        assertThat(continuing.books[1].series?.name).isEqualTo("Naruto")
        val requests = routes.requests()
        val onDeck = requests.single { it.route() == OnDeck }
        assertThat(onDeck.url.encodedQuery).isEqualTo("PageNumber=1&PageSize=20&libraryId=0")
        assertThat(requests.filter { it.route() == ContinuePoint }.map { it.url.queryParameter("seriesId") })
            .containsExactly("117", "349", "257", "264", "82")
        // Only the kept volume book asks its volume.
        assertThat(requests.filter { it.route() == Volume }.map { it.url.queryParameter("volumeId") })
            .containsExactly("430")
    }

    @Test
    fun `Up next holds the books not started, and shares the on-deck call with Continue`() {
        answerOnDeck()

        row(HomeRowKind.Continue)
        val next = row(HomeRowKind.UpNext)

        assertThat(next.ids()).containsExactly(BookId("c44"), BookId("v190")).inOrder()
        assertThat(next.books.first().readState).isEqualTo(ReadState.Unread)
        val requests = routes.requests()
        assertThat(requests.count { it.route() == OnDeck }).isEqualTo(1)
        assertThat(requests.count { it.route() == ContinuePoint }).isEqualTo(5)
    }

    @Test
    fun `Continue and Up next asked together make one on-deck call`() = runBlocking {
        answerOnDeck()

        val continuing = async(Dispatchers.IO) { library.homeRow(HomeRowKind.Continue) }
        val next = async(Dispatchers.IO) { library.homeRow(HomeRowKind.UpNext) }

        assertThat((continuing.await() as ApiResult.Success).value.books).hasSize(2)
        assertThat((next.await() as ApiResult.Success).value.books).hasSize(2)
        assertThat(asked(OnDeck)).hasSize(1)
    }

    @Test
    fun `the on-deck series are asked again after 10 seconds, and a "See all" asks its own 50`() {
        answerOnDeck()

        row(HomeRowKind.Continue)
        now.set(9_999)
        row(HomeRowKind.UpNext)
        now.set(20_000)
        row(HomeRowKind.UpNext)
        row(HomeRowKind.Continue, HomeRowExtent.SeeAll)

        assertThat(asked(OnDeck).map { it.url.queryParameter("PageSize") }).containsExactly("20", "20", "50")
            .inOrder()
    }

    @Test
    fun `a failure is not kept, the next row asks again`() {
        routes.fail(OnDeck, 500)
        assertThat(runBlocking { library.homeRow(HomeRowKind.Continue) })
            .isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
        answerOnDeck()

        assertThat(row(HomeRowKind.UpNext).books).hasSize(2)
        assertThat(asked(OnDeck)).hasSize(2)
    }

    @Test
    fun `a row has more when the server holds more series than it asked`() {
        answerOnDeck()
        routes.answer(OnDeck, fixtureBody("ondeck"), "Pagination" to """{"currentPage":1,"totalItems":21}""")

        assertThat(row(HomeRowKind.Continue).hasMore).isTrue()
    }

    @Test
    fun `a row whose server holds as many series as it asked has no more`() {
        answerOnDeck()
        routes.answerFixture(OnDeck, "ondeck20")

        assertThat(row(HomeRowKind.Continue).hasMore).isFalse()
    }

    @Test
    fun `a series gone before its continue point is left out`() {
        answerOnDeck()
        answerContinuePoints("82" to "continue-naruto")

        assertThat(row(HomeRowKind.Continue).ids()).containsExactly(BookId("c1262"))
    }

    @Test
    fun `a volume gone before it is read is left out`() {
        answerOnDeck()
        routes.fail(Volume, 404)

        assertThat(row(HomeRowKind.Continue).ids()).containsExactly(BookId("c1262"))
    }

    @Test
    fun `any other failure of a continue point fails the row`() = runBlocking {
        answerOnDeck()
        routes.answer(ContinuePoint) { request ->
            val failing = request.url.queryParameter("seriesId") == "82"
            if (failing) MockResponse(code = 500) else json(fixtureBody("continue-asterix"))
        }

        assertThat(library.homeRow(HomeRowKind.Continue)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `a refused key fails the row as a session that ended`() = runBlocking {
        routes.fail(OnDeck, 401)

        assertThat(library.homeRow(HomeRowKind.UpNext)).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
    }

    @Test
    fun `no more than 6 calls at once`() {
        val series = (1..14).joinToString(",", "[", "]") { """{"id":$it,"name":"S$it"}""" }
        routes.answer(OnDeck, series)
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        routes.answer(ContinuePoint) {
            most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            Thread.sleep(100)
            inFlight.decrementAndGet()
            json(fixtureBody("continue-naruto"))
        }

        assertThat(row(HomeRowKind.Continue).books).hasSize(1)
        assertThat(most.get()).isIn(2..6)
    }

    @Test
    fun `Want to read holds each series' book to resume, a read one too`() {
        routes.answer(
            "POST /api/want-to-read/v2",
            """[{"id":349,"name":"Dune","format":3},{"id":117,"name":"Astérix","format":1}]""",
            "Pagination" to """{"currentPage":1,"itemsPerPage":20,"totalItems":30,"totalPages":2}""",
        )
        answerContinuePoints("349" to "continue-dune", "117" to "continue-asterix")
        answerVolumes()

        val wanted = row(HomeRowKind.WantToRead)

        assertThat(wanted.ids()).containsExactly(BookId("v407"), BookId("c44")).inOrder()
        assertThat(wanted.books.first().readState).isEqualTo(ReadState.Read)
        assertThat(wanted.books.first().series).isEqualTo(SeriesRef(SeriesId("349"), "Dune", "1"))
        assertThat(wanted.hasMore).isTrue()
        val asked = asked("POST /api/want-to-read/v2").single()
        assertThat(asked.url.encodedQuery).isEqualTo("PageNumber=1&PageSize=20")
        assertThat(asked.jsonBody()).isEqualTo(Json.parseToJsonElement(seriesFilter(emptyList()).toString()))
    }

    @Test
    fun `Want to read empty asks nothing more`() {
        routes.answerFixture("POST /api/want-to-read/v2", "wanttoread")

        assertThat(row(HomeRowKind.WantToRead, HomeRowExtent.SeeAll).books).isEmpty()
        assertThat(asked(ContinuePoint)).isEmpty()
    }

    /** The groups' series 29, 52, 453, 452 and 451 read as Astérix, Lucky Luke, Dune, gone, and a lone book. */
    private fun answerRecent() {
        routes.answerFixture("POST /api/Series/recently-updated-series", "recent-updated")
        routes.answer(Detail) { request ->
            when (request.url.queryParameter("seriesId")) {
                "29" -> json(fixtureBody("detail-asterix"))
                "52" -> json(fixtureBody("detail-luckyluke"))
                "453" -> json(fixtureBody("detail-dune"))
                "451" -> json(fixtureBody("detail-lone"))
                else -> MockResponse(code = 404)
            }
        }
    }

    @Test
    fun `Recently added holds each group's newest book, named in the group's series`() {
        answerRecent()

        val recent = row(HomeRowKind.RecentlyAdded)

        assertThat(recent.ids())
            .containsExactly(BookId("c82"), BookId("c862"), BookId("v407"), BookId("c1857")).inOrder()
        assertThat(recent.books[0].series).isEqualTo(SeriesRef(SeriesId("29"), "Bellatrix", "41"))
        assertThat(recent.books[2].series).isEqualTo(SeriesRef(SeriesId("453"), "Le Maître du Haut Château", "1"))
        assertThat(recent.books[3].series).isNull()
        assertThat(recent.hasMore).isFalse()
        assertThat(asked("POST /api/Series/recently-updated-series").single().url.encodedQuery)
            .isEqualTo("PageNumber=1&PageSize=20")
    }

    @Test
    fun `any other failure of a series detail fails Recently added`() = runBlocking {
        answerRecent()
        routes.fail(Detail, 503)

        assertThat(library.homeRow(HomeRowKind.RecentlyAdded)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }
}
