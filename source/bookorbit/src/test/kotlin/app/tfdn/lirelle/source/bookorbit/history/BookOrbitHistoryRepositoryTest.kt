package app.tfdn.lirelle.source.bookorbit.history

import app.tfdn.lirelle.core.history.ReadRecordCache
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class BookOrbitHistoryRepositoryTest {

    private val server = MockWebServer()
    private val cache = ReadRecordCache()
    private var signedIn = true
    private lateinit var repository: BookOrbitHistoryRepository

    @Before
    fun setUp() {
        server.start()
        repository = BookOrbitHistoryRepository(
            api = ApiProvider(OkHttpClient(), ApiJson, HistoryApi::class.java),
            errors = ErrorMapper(ApiJson),
            serverUrl = { if (signedIn) server.url("/").toString().trimEnd('/') else null },
            cache = cache,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse(code = code, headers = headersOf("Content-Type", "application/json"), body = body))
    }

    private val page = """{"items":[{"id":7,"bookId":3,"title":"Dune","authors":["Frank Herbert"],
        "outcome":"completed","readNumber":1,"endedOn":"2026-03-04"}],"offset":0,"limit":30,"total":1,
        "months":[{"month":"2026-03","reads":1}]}"""

    private val empty = """{"items":[],"offset":0,"limit":30,"total":0}"""

    private fun <T> ApiResult<T>.value(): T = (this as ApiResult.Success).value

    @Test
    fun `completed only sends no outcomes parameter`() = runBlocking {
        enqueue(empty)

        repository.log(HistoryPeriod(2026), setOf(ReadOutcome.Completed), true, 30, 30)

        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/api/v1/reading-history")
        assertThat(request.url.queryParameter("outcomes")).isNull()
        assertThat(request.url.queryParameter("year")).isEqualTo("2026")
        assertThat(request.url.queryParameter("sort")).isEqualTo("newest")
        assertThat(request.url.queryParameter("offset")).isEqualTo("30")
        assertThat(request.url.queryParameter("limit")).isEqualTo("30")
    }

    @Test
    fun `several outcomes are joined in order`() = runBlocking {
        enqueue(empty)

        repository.log(HistoryPeriod(null), setOf(ReadOutcome.Completed, ReadOutcome.Skimmed), true, 0, 30)

        val request = server.takeRequest()
        assertThat(request.url.queryParameter("outcomes")).isEqualTo("completed,skimmed")
        assertThat(request.url.queryParameter("year")).isNull()
    }

    @Test
    fun `oldest first sends sort oldest`() = runBlocking {
        enqueue(empty)

        repository.log(HistoryPeriod(null), setOf(ReadOutcome.Completed), false, 0, 30)

        assertThat(server.takeRequest().url.queryParameter("sort")).isEqualTo("oldest")
    }

    @Test
    fun `a page maps and fills the cache`() = runBlocking {
        enqueue(page)

        val result = repository.log(HistoryPeriod(null), setOf(ReadOutcome.Completed), true, 0, 30).value()

        assertThat(result.records.single().title).isEqualTo("Dune")
        assertThat(result.months).hasSize(1)
        assertThat(cache.get(ReadId("7"))?.title).isEqualTo("Dune")
    }

    @Test
    fun `a summary maps`() = runBlocking {
        enqueue("""{"years":[{"year":2026,"reads":4}],"reads":4,"books":3,"pages":900,"seconds":3600,"daysRead":2}""")

        val summary = repository.summary(HistoryPeriod(2026)).value()

        assertThat(summary.years).containsExactly(2026)
        assertThat(summary.totals.reads).isEqualTo(4)
        assertThat(server.takeRequest().url.queryParameter("year")).isEqualTo("2026")
    }

    @Test
    fun `a rating is sent and its answer mapped`() = runBlocking {
        enqueue("""{"rating":4,"bookRatingChanged":true}""")

        val result = repository.setRating(ReadId("7"), 4).value()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PATCH")
        assertThat(request.url.encodedPath).isEqualTo("/api/v1/reading-history/7/rating")
        assertThat(request.body?.utf8()).isEqualTo("""{"rating":4}""")
        assertThat(result.rating).isEqualTo(4)
        assertThat(result.bookRatingChanged).isTrue()
    }

    @Test
    fun `clearing a rating sends null`() = runBlocking {
        enqueue("""{"rating":null,"bookRatingChanged":false}""")

        val result = repository.setRating(ReadId("7"), null).value()

        assertThat(server.takeRequest().body?.utf8()).isEqualTo("""{"rating":null}""")
        assertThat(result.rating).isNull()
    }

    @Test
    fun `marks map`() = runBlocking {
        enqueue("""{"highlights":[{"id":1,"text":"A line"}],"highlightCount":1,"journal":[],"journalCount":0}""")

        val marks = repository.marks(ReadId("7")).value()

        assertThat(marks.highlights.single().text).isEqualTo("A line")
        assertThat(marks.highlights.single().id).isEqualTo(MarkId("1"))
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/api/v1/reading-history/7/marks")
    }

    @Test
    fun `signed out fails without a request`() = runBlocking {
        signedIn = false

        val result = repository.summary(HistoryPeriod(null))

        assertThat(result).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a server error is a failure`() = runBlocking {
        enqueue("{}", code = 500)

        val result = repository.summary(HistoryPeriod(null))

        assertThat(result).isInstanceOf(ApiResult.Failure::class.java)
    }
}
