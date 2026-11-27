package app.tfdn.lirelle.source.kavita.history

import app.tfdn.lirelle.core.history.ReadRecordCache
import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadPage
import app.tfdn.lirelle.core.history.model.ReadRecord
import app.tfdn.lirelle.core.history.model.SeriesRef
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.testing.KavitaRoutes
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import app.tfdn.lirelle.source.kavita.testing.json
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val HistoryRoute = "GET /api/Stats/reading-history"

/**
 * A Kavita profile's History: every session of the account's libraries, made into reads and named with the app's
 * books (Astérix, series 7, its first album chapter 44; 11/22/63, series 451, a lone book).
 */
class KavitaHistoryTest {

    private val server = MockWebServer()
    private lateinit var routes: KavitaRoutes

    @Before
    fun setUp() {
        server.start()
        routes = KavitaRoutes(server)
        routes.answer("GET /api/Library/libraries", fixtureBody("libraries"))
        routes.answer("GET /api/Series/series-detail") { request ->
            when (request.url.queryParameter("seriesId")) {
                "7" -> json(fixtureBody("detail-asterix"))
                "451" -> json(fixtureBody("detail-lone"))
                else -> MockResponse(code = 404)
            }
        }
    }

    @After
    fun tearDown() = server.close()

    private fun log(
        history: KavitaHistory,
        offset: Int = 0,
        limit: Int = 20,
        period: HistoryPeriod = HistoryPeriod(null),
        outcomes: Set<ReadOutcome> = setOf(ReadOutcome.Completed),
    ): ApiResult<ReadPage> = runBlocking { history.log(period, outcomes, newestFirst = true, offset, limit) }

    private fun records(result: ApiResult<ReadPage>): List<ReadRecord> =
        (result as ApiResult.Success).value.records

    private fun historyRequests(): List<RecordedRequest> =
        routes.requests().filter { "${it.method} ${it.url.encodedPath}" == HistoryRoute }

    @Test
    fun `the history is asked of every library of the account, on the device's calendar, page after page`() {
        routes.answer(HistoryRoute, items(item(1, "2026-11-20", 7, 44, completed = true)))

        log(routes.history())

        val request = historyRequests().single()
        assertThat(request.url.queryParameterValues("Libraries")).containsExactly("1", "3", "2").inOrder()
        assertThat(request.url.queryParameter("TimeZoneId")).isEqualTo("Europe/Paris")
        assertThat(request.url.queryParameter("PageNumber")).isEqualTo("1")
        assertThat(request.url.queryParameter("PageSize")).isEqualTo("100")
    }

    @Test
    fun `a full first page that does not reach the total is followed by the next`() {
        val unfinished = List(100) { item(100 + it, "2026-11-10", 43, 535, completed = false) }
        val finished = item(1, "2026-11-20", 7, 44, completed = true)
        routes.answer(HistoryRoute) { request ->
            val page = if (request.url.queryParameter("PageNumber") == "1") unfinished else listOf(finished)
            json(items(*page.toTypedArray()), headers = arrayOf("Pagination" to pagination(101)))
        }

        val result = log(routes.history())

        assertThat(historyRequests().map { it.url.queryParameter("PageNumber") }).containsExactly("1", "2")
        assertThat(records(result).map { it.bookId }).containsExactly(BookId("c44"))
    }

    @Test
    fun `a read of Astérix's first album is a row of the app's book`() {
        routes.answer(
            HistoryRoute,
            items(
                item(1, "2026-11-20", 7, 44, completed = false, seconds = 600),
                item(2, "2026-11-22", 7, 44, completed = true, seconds = 300),
            ),
        )

        val result = log(routes.history())

        val cover = routes.urlOf("/api/Image/chapter-cover", "chapterId=44")
        assertThat(records(result)).containsExactly(
            ReadRecord(
                id = ReadId("c44#1"),
                bookId = BookId("c44"),
                title = "Astérix le Gaulois",
                authors = listOf("Albert Uderzo", "René Goscinny"),
                series = SeriesRef("Astérix", "1"),
                coverUrl = cover,
                detailCoverUrl = cover,
                startedOn = LocalDate.of(2026, 11, 20),
                endedOn = LocalDate.of(2026, 11, 22),
                outcome = ReadOutcome.Completed,
                readNumber = 1,
                progressPercent = null,
                sessions = 2,
                duration = Duration.ofSeconds(900),
                rating = null,
                highlightCount = 0,
                journalCount = 0,
                removed = false,
            ),
        )
    }

    @Test
    fun `a book read again is one row per read`() {
        routes.answer(
            HistoryRoute,
            items(
                item(1, "2026-11-20", 7, 44, completed = true),
                item(2, "2026-11-24", 7, 44, completed = true),
            ),
        )

        val result = log(routes.history())

        assertThat(records(result).map { it.id }).containsExactly(ReadId("c44#2"), ReadId("c44#1")).inOrder()
    }

    @Test
    fun `a lone book has no series`() {
        routes.answer(HistoryRoute, items(item(1, "2026-11-20", 451, 1857, completed = true)))

        val record = records(log(routes.history())).single()

        assertThat(record.bookId).isEqualTo(BookId("c1857"))
        assertThat(record.series).isNull()
    }

    @Test
    fun `a chapter no longer in its series is left out`() {
        routes.answer(
            HistoryRoute,
            items(item(1, "2026-11-20", 7, 999, completed = true), item(2, "2026-11-21", 7, 44, completed = true)),
        )

        val result = log(routes.history())

        assertThat(records(result).map { it.bookId }).containsExactly(BookId("c44"))
    }

    @Test
    fun `the reads of a series whose detail is not found are left out, the others shown`() {
        routes.answer(
            HistoryRoute,
            items(
                item(1, "2026-11-20", 43, 535, completed = true),
                item(2, "2026-11-21", 7, 44, completed = true),
            ),
        )

        val result = log(routes.history())

        assertThat(records(result).map { it.bookId }).containsExactly(BookId("c44"))
        assertThat((result as ApiResult.Success).value.total).isEqualTo(1)
    }

    @Test
    fun `a year keeps the reads ended in it`() {
        routes.answer(
            HistoryRoute,
            items(item(1, "2025-12-31", 7, 44, completed = true), item(2, "2026-01-02", 7, 55, completed = true)),
        )

        val result = log(routes.history(), period = HistoryPeriod(year = 2026))

        assertThat(records(result).map { it.bookId }).containsExactly(BookId("c55"))
        assertThat((result as ApiResult.Success).value.total).isEqualTo(1)
    }

    @Test
    fun `without finished reads asked there is nothing, and nothing is asked`() {
        val result = log(routes.history(), outcomes = setOf(ReadOutcome.Abandoned))

        assertThat(records(result)).isEmpty()
        assertThat(routes.requests()).isEmpty()
    }

    @Test
    fun `a page of the screen is taken from the reads, the total being every read`() {
        routes.answer(
            HistoryRoute,
            items(
                item(1, "2026-11-20", 7, 44, completed = true),
                item(2, "2026-11-21", 7, 55, completed = true),
                item(3, "2026-11-22", 7, 56, completed = true),
            ),
        )

        val result = log(routes.history(), offset = 1, limit = 1)

        assertThat(result).isInstanceOf(ApiResult.Success::class.java)
        val page = (result as ApiResult.Success).value
        assertThat(page.records.map { it.bookId }).containsExactly(BookId("c55"))
        assertThat(page.total).isEqualTo(3)
        assertThat(page.offset).isEqualTo(1)
        assertThat(page.limit).isEqualTo(1)
        assertThat(page.months).isNull()
    }

    @Test
    fun `the reads of a page go to the cache, for their detail`() {
        routes.answer(HistoryRoute, items(item(1, "2026-11-20", 7, 44, completed = true)))
        val cache = ReadRecordCache()

        val record = records(log(routes.history(cache = cache))).single()

        assertThat(cache.get(ReadId("c44#1"))).isEqualTo(record)
    }

    @Test
    fun `one load serves the next pages, and coming back to the first page loads again`() {
        routes.answer(
            HistoryRoute,
            items(item(1, "2026-11-20", 7, 44, completed = true), item(2, "2026-11-21", 7, 55, completed = true)),
        )
        val history = routes.history()

        log(history, offset = 0, limit = 1)
        val next = log(history, offset = 1, limit = 1)
        assertThat(records(next).map { it.bookId }).containsExactly(BookId("c44"))
        log(history, offset = 20, limit = 1)
        log(history, offset = 0, limit = 1)

        assertThat(historyRequests()).hasSize(2)
    }

    @Test
    fun `a failed history is the error of the call`() {
        routes.fail(HistoryRoute, 500)

        assertThat(log(routes.history())).isInstanceOf(ApiResult.Failure::class.java)
    }

    @Test
    fun `durations, sessions and rereads are shown, no summary, outcome, rating nor marks`() {
        assertThat(routes.history().capabilities).isEqualTo(
            HistoryCapabilities(
                summary = false,
                outcomes = false,
                rating = false,
                marks = false,
                durations = true,
                sessions = true,
                rereads = true,
            ),
        )
    }

    @Test
    fun `there is no summary, no marks and no rating`() = runBlocking {
        val history = routes.history()

        val summary = (history.summary(HistoryPeriod(null)) as ApiResult.Success).value
        assertThat(summary.years).isEmpty()
        assertThat(summary.records).isEmpty()
        assertThat(history.marks(ReadId("c44#1"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(history.setRating(ReadId("c44#1"), 4)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }
}

/** Kavita's answer of [items]. */
private fun items(vararg items: String): String = items.joinToString(",", prefix = "[", postfix = "]")

/** Kavita's `Pagination` header of a history of [total] sessions, by pages of 100. */
private fun pagination(total: Int): String =
    """{"currentPage":1,"itemsPerPage":100,"totalItems":$total,"totalPages":${(total + 99) / 100}}"""

/** The names of the test server's series, by id. */
private val SeriesNames = mapOf(7 to "Astérix", 43 to "Garfield", 451 to "11/22/63")

/** One real session of [series] on [day], reading [chapter] for [seconds], which it [completed] or not. */
@Suppress("LongParameterList") // Tests name every parameter they set.
private fun item(session: Int, day: String, series: Int, chapter: Int, completed: Boolean, seconds: Int = 60): String {
    val start = "${day}T10:00:00"
    val seriesName = SeriesNames.getValue(series)
    return """{"sessionId":$session,"isGeneratedSession":false,"startTimeUtc":"$start",""" +
        """"localDate":"${day}T00:00:00","seriesId":$series,"seriesName":"$seriesName","libraryId":1,""" +
        """"chapters":[{"chapterId":$chapter,"label":"x","startTimeUtc":"$start","durationSeconds":$seconds,""" +
        """"completed":$completed}]}"""
}
