package app.tfdn.lirelle.source.komga.history

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
import app.tfdn.lirelle.core.network.newAuthenticatedClient
import app.tfdn.lirelle.source.komga.library.KomgaLibraryApi
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.KomgaJson
import app.tfdn.lirelle.source.komga.network.KomgaKeyAuthorizer
import app.tfdn.lirelle.source.komga.testing.FakeProfileSession
import app.tfdn.lirelle.source.komga.testing.ReadOneShotJson
import app.tfdn.lirelle.source.komga.testing.ReadVolumeJson
import app.tfdn.lirelle.source.komga.testing.TheKey
import app.tfdn.lirelle.source.komga.testing.bodyText
import app.tfdn.lirelle.source.komga.testing.bookJsonWithId
import app.tfdn.lirelle.source.komga.testing.komgaSession
import app.tfdn.lirelle.source.komga.testing.pageJson
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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

private val All = HistoryPeriod(null)
private val Finished = setOf(ReadOutcome.Completed)

/**
 * A Komga profile's history: the books the account read to the end, the most recently read first, each once with
 * the date it was finished. Komga keeps one reading per book: no summary, outcome, rating, marks, time, sessions nor
 * rereads.
 */
class KomgaHistoryTest {

    private val server = MockWebServer()
    private lateinit var session: FakeProfileSession
    private lateinit var url: String
    private val cache = ReadRecordCache()
    private var zone: ZoneId = ZoneOffset.UTC
    private lateinit var history: KomgaHistory
    private val answers = mutableMapOf<String, (RecordedRequest) -> MockResponse>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                answers["${request.method} ${request.url.encodedPath}"]?.invoke(request) ?: MockResponse(code = 404)
        }
        server.start()
        url = server.serverUrl
        session = FakeProfileSession(komgaSession(url))
        // The profile's own authenticated client, its key and what a refusal of it does included.
        val client = newAuthenticatedClient(OkHttpClient(), KomgaKeyAuthorizer(session))
        history = KomgaHistory(
            session,
            KomgaApis(client, KomgaJson, KomgaLibraryApi::class.java),
            KomgaErrors(),
            cache,
            zone = { zone },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun answerBooks(body: String) {
        val json = headersOf("Content-Type", "application/json")
        answers["POST /api/v1/books/list"] = { MockResponse(code = 200, headers = json, body = body) }
    }

    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    private fun ApiResult<ReadPage>.page() = (this as ApiResult.Success).value

    /** A thumbnail's URL, unversioned: Komga keeps an item's `lastModified` when its thumbnail changes. */
    private fun cover(id: String) = "$url/api/v1/books/$id/thumbnail"

    /** The entry of [ReadVolumeJson], finished on [endedOn]. */
    private fun volumeRead(endedOn: LocalDate) = ReadRecord(
        id = ReadId("0BK4D"),
        bookId = BookId("0BK4D"),
        title = "First Tide",
        authors = listOf("Writer One", "Artist Two"),
        series = SeriesRef("Tidewater", "1"),
        coverUrl = cover("0BK4D"),
        detailCoverUrl = cover("0BK4D"),
        startedOn = null,
        endedOn = endedOn,
        outcome = ReadOutcome.Completed,
        readNumber = null,
        progressPercent = null,
        sessions = null,
        duration = null,
        rating = null,
        highlightCount = 0,
        journalCount = 0,
        removed = false,
    )

    @Test
    fun `Komga's history shows the books read and their dates, nothing more`() {
        assertThat(history.capabilities).isEqualTo(HistoryCapabilities(false, false, false, false, false, false, false))
    }

    @Test
    fun `the history is the books read, still in the library, the most recently read first`() = runBlocking<Unit> {
        answerBooks(pageJson(ReadOneShotJson, ReadVolumeJson, total = 45))

        val page = history.log(All, Finished, newestFirst = true, offset = 0, limit = 30).page()

        assertThat(page.records.map { it.id }).containsExactly(ReadId("0BK3C"), ReadId("0BK4D")).inOrder()
        assertThat(page.total).isEqualTo(45)
        assertThat(page.offset).isEqualTo(0)
        assertThat(page.limit).isEqualTo(30)
        // No month totals: the screen counts the reads of each month from its rows.
        assertThat(page.months).isNull()
        val asked = requests().single()
        assertThat(Json.parseToJsonElement(asked.bodyText())).isEqualTo(
            Json.parseToJsonElement(
                """{"condition":{"allOf":[{"deleted":{"operator":"isFalse"}},""" +
                    """{"readStatus":{"operator":"is","value":"READ"}}]}}""",
            ),
        )
        assertThat(asked.url.queryParameter("page")).isEqualTo("0")
        assertThat(asked.url.queryParameter("size")).isEqualTo("30")
        // The series, then the volumes' order break a tie between books marked read together, so the pages stay
        // stable.
        assertThat(asked.url.queryParameterValues("sort"))
            .containsExactly("readProgress.readDate,desc", "series,desc", "metadata.numberSort,desc").inOrder()
        assertThat(asked.headers["X-API-Key"]).isEqualTo(TheKey)
    }

    @Test
    fun `an entry is one book with the day it was finished, its series and its cover`() = runBlocking<Unit> {
        answerBooks(pageJson(ReadVolumeJson, ReadOneShotJson))

        val records = history.log(All, Finished, newestFirst = true, offset = 0, limit = 30).page().records

        assertThat(records.first()).isEqualTo(volumeRead(LocalDate.of(2026, 10, 31)))
        // A one-shot is no series in the app.
        assertThat(records.last().series).isNull()
        assertThat(records.last().endedOn).isEqualTo(LocalDate.of(2026, 11, 1))
    }

    @Test
    fun `the day a book was finished is the device's day`() = runBlocking<Unit> {
        answerBooks(pageJson(ReadVolumeJson))
        zone = ZoneId.of("Europe/Paris")

        val record = history.log(All, Finished, newestFirst = true, offset = 0, limit = 30).page().records.single()

        assertThat(record.endedOn).isEqualTo(LocalDate.of(2026, 11, 1))
    }

    @Test
    fun `the next pages follow, and the oldest first when asked`() = runBlocking<Unit> {
        answerBooks(pageJson(ReadVolumeJson, total = 45))

        val next = history.log(All, Finished, newestFirst = true, offset = 30, limit = 30).page()
        history.log(All, Finished, newestFirst = false, offset = 0, limit = 30)

        assertThat(next.offset).isEqualTo(30)
        val asked = requests()
        assertThat(asked.map { it.url.queryParameter("page") }).containsExactly("1", "0").inOrder()
        assertThat(asked.last().url.queryParameterValues("sort"))
            .containsExactly("readProgress.readDate,asc", "series,asc", "metadata.numberSort,asc").inOrder()
    }

    @Test
    fun `a page keeps every book the server sent, so the next offset follows it`() = runBlocking<Unit> {
        // The server already left the trash out: a book it still marks deleted is not dropped here, or the page
        // would come shorter and the next offset would point back inside it.
        val deleted = bookJsonWithId("0BKZZ").replace("\"deleted\":false", "\"deleted\":true")
        answerBooks(pageJson(bookJsonWithId("0BKA1"), deleted, total = 2))

        val page = history.log(All, Finished, newestFirst = true, offset = 0, limit = 30).page()

        assertThat(page.records.map { it.id.value }).containsExactly("0BKA1", "0BKZZ").inOrder()
    }

    @Test
    fun `an offset inside a page asks that page, without the books already shown`() = runBlocking<Unit> {
        val ids = (0 until 30).map { "0BKP$it" }
        answerBooks(pageJson(*ids.map(::bookJsonWithId).toTypedArray(), total = 60))

        val page = history.log(All, Finished, newestFirst = true, offset = 45, limit = 30).page()

        assertThat(page.records.map { it.id.value }).isEqualTo(ids.drop(15))
        assertThat(requests().single().url.queryParameter("page")).isEqualTo("1")
    }

    @Test
    fun `every book read is finished, a year cannot be asked, and neither is asked of the server`() =
        runBlocking<Unit> {
            val abandoned = history.log(All, setOf(ReadOutcome.Abandoned), true, offset = 0, limit = 30).page()
            val year = history.log(HistoryPeriod(2026), Finished, true, offset = 0, limit = 30).page()

            assertThat(abandoned.records).isEmpty()
            assertThat(abandoned.total).isEqualTo(0)
            assertThat(year.records).isEmpty()
            assertThat(server.requestCount).isEqualTo(0)
        }

    @Test
    fun `the entries listed are kept for their detail`() = runBlocking<Unit> {
        answerBooks(pageJson(ReadVolumeJson))

        history.log(All, Finished, newestFirst = true, offset = 0, limit = 30)

        assertThat(cache.get(ReadId("0BK4D"))).isEqualTo(volumeRead(LocalDate.of(2026, 10, 31)))
    }

    @Test
    fun `no summary, no marks and no rating are asked of the server`() = runBlocking<Unit> {
        val summary = (history.summary(All) as ApiResult.Success).value

        assertThat(summary.years).isEmpty()
        assertThat(summary.totals.reads).isEqualTo(0)
        assertThat(history.marks(ReadId("0BK4D"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(history.setRating(ReadId("0BK4D"), 4)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a revoked key fails the history and marks the profile`() = runBlocking<Unit> {
        answers["POST /api/v1/books/list"] = { MockResponse(code = 401) }

        val answer = history.log(All, Finished, newestFirst = true, offset = 0, limit = 30)

        assertThat(answer).isInstanceOf(ApiResult.Failure::class.java)
        assertThat(session.signInNeeded.value).isTrue()
    }
}
