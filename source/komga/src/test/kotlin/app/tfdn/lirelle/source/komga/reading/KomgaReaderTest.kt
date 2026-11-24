package app.tfdn.lirelle.source.komga.reading

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.library.model.VolumeGroup
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.KomgaJson
import app.tfdn.lirelle.source.komga.testing.FakeProfileSession
import app.tfdn.lirelle.source.komga.testing.ReadVolumeJson
import app.tfdn.lirelle.source.komga.testing.ReadingBookJson
import app.tfdn.lirelle.source.komga.testing.UnreadBookJson
import app.tfdn.lirelle.source.komga.testing.bodyText
import app.tfdn.lirelle.source.komga.testing.komgaSession
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** A comic of Komga is one file, whose id is the book's. */
private val Album = FileId("0BK1A")

/** The formats of the album's five pages, as the page source lists them. */
private val Formats = listOf("image/jpeg", "image/webp", "image/jxl", "image/jpeg", "image/png")

/** When the device says it is now: with a part of a millisecond Komga is not sent. */
private val Now = Instant.parse("2026-11-23T09:30:00.250999Z")

/** When a page was reached, two days ago, for a position sent late. */
private val TwoDaysAgo = Instant.parse("2026-11-21T18:00:00Z")

/** What Komga keeps of another device's reading of the album, newer than [TwoDaysAgo] (`R2Progression`). */
private const val OtherDeviceJson = """{"modified":"2026-11-22T07:45:10.5Z","device":{"id":"0WEB",""" +
    """"name":"Komga web reader"},"locator":{"href":"https://komga.example/api/v1/books/0BK1A/pages/4",""" +
    """"type":"image/jpeg","locations":{"fragments":[],"position":4,"totalProgression":0.8}}}"""

/**
 * A Komga comic's place: read from the book's read progress, written as a timestamped progression (Komga keeps the
 * newer of two places), the book marked read at its last page, and the next volume of its series.
 */
class KomgaReaderTest {

    private val server = MockWebServer()
    private lateinit var url: String
    private lateinit var reader: KomgaReader
    private val answers = mutableListOf<Pair<String, (RecordedRequest) -> MockResponse>>()

    /** What the page source lists of the album's pages; how many times it was asked. */
    private var formats: () -> List<String> = { Formats }
    private var formatsAsked = 0

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val route = "${request.method} ${request.url.encodedPath}"
                return answers.lastOrNull { it.first == route }?.second?.invoke(request) ?: MockResponse(code = 404)
            }
        }
        server.start()
        url = server.serverUrl
        reader = KomgaReader(
            session = FakeProfileSession(komgaSession(url)),
            api = KomgaApis(OkHttpClient(), KomgaJson, KomgaReadingApi::class.java),
            errors = KomgaErrors(),
            pageFormats = {
                formatsAsked++
                formats()
            },
            deviceLabel = DeviceLabelProvider { "Galaxy Tab Test (Lirelle)" },
            now = { Now },
        )
    }

    @After
    fun tearDown() = server.close()

    private fun answer(route: String, code: Int = 200, body: String? = null) {
        answers += route to { _ ->
            if (body == null) {
                MockResponse(code = code)
            } else {
                MockResponse(code = code, headers = headersOf("Content-Type", "application/json"), body = body)
            }
        }
    }

    /** Every request the server had, in order. */
    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    private fun RecordedRequest.route() = "$method ${url.encodedPath}"

    /** What a progression of this device sends: the page's address, its format, its place in the album. */
    private fun progressionJson(modified: String, page: Int, type: String, total: String) =
        """{"modified":"$modified","device":{"id":"lirelle-p1","name":"Galaxy Tab Test (Lirelle)"},""" +
            """"locator":{"href":"$url/api/v1/books/0BK1A/pages/$page","type":"$type",""" +
            """"locations":{"fragments":[],"position":$page,"totalProgression":$total}}}"""

    private fun assertSameJson(actual: String, expected: String) =
        assertThat(KomgaJson.parseToJsonElement(actual)).isEqualTo(KomgaJson.parseToJsonElement(expected))

    // Reading the place.

    @Test
    fun `a book half read is at the page Komga holds, with the time it was read`(): Unit = runBlocking {
        answer("GET /api/v1/books/0BK1A", body = ReadingBookJson)

        assertThat(reader.position(Album)).isEqualTo(
            ApiResult.Success(
                ReadingPosition(
                    fileId = Album,
                    cfi = null,
                    percent = 25.0,
                    updatedAt = Instant.parse("2026-11-21T20:30:00Z"),
                    page = 12,
                ),
            ),
        )
    }

    @Test
    fun `the time of the place is when it was read, the progression's own, not when Komga wrote it`(): Unit =
        runBlocking {
            answer(
                "GET /api/v1/books/0BK1A",
                body = ReadingBookJson.replace(
                    """"readDate":"2026-11-21T20:30:00Z"""",
                    """"readDate":"2026-11-21T20:29:58.125Z"""",
                ),
            )

            val position = (reader.position(Album) as ApiResult.Success).value

            assertThat(position.updatedAt).isEqualTo(Instant.parse("2026-11-21T20:29:58.125Z"))
        }

    @Test
    fun `a book never opened is at its start, with no page and no time`(): Unit = runBlocking {
        answer("GET /api/v1/books/0BK2B", body = UnreadBookJson)

        assertThat(reader.position(FileId("0BK2B")))
            .isEqualTo(ApiResult.Success(ReadingPosition(FileId("0BK2B"), cfi = null, percent = 0.0, updatedAt = null)))
    }

    @Test
    fun `a book marked read is at its last page, whatever page Komga kept`(): Unit = runBlocking {
        answer(
            "GET /api/v1/books/0BK4D",
            body = ReadVolumeJson.replace(""""page":40,"completed":true""", """"page":7,"completed":true"""),
        )

        val position = (reader.position(FileId("0BK4D")) as ApiResult.Success).value

        assertThat(position.page).isEqualTo(40)
        assertThat(position.percent).isEqualTo(100.0)
        assertThat(position.updatedAt).isEqualTo(Instant.parse("2026-10-31T23:30:00Z"))
    }

    @Test
    fun `the place of a book gone, or one the account may not see, is not found`(): Unit = runBlocking {
        answer("GET /api/v1/books/0BK1A", code = 403)

        assertThat(reader.position(Album)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(reader.position(FileId("0BKGONE"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    // Writing the place.

    @Test
    fun `the page the reader is at goes to Komga as a progression of this device, made now`(): Unit = runBlocking {
        answer("PUT /api/v1/books/0BK1A/progression", code = 204)

        val saved = reader.savePosition(Album, cfi = null, percent = 60.0, page = 3)

        assertThat(saved).isEqualTo(
            ApiResult.Success(SavedPosition(SaveOutcome.Saved, Instant.parse("2026-11-23T09:30:00.250Z"))),
        )
        val sent = requests().single()
        assertThat(sent.route()).isEqualTo("PUT /api/v1/books/0BK1A/progression")
        assertThat(sent.headers["Content-Type"]).startsWith("application/json")
        assertSameJson(sent.bodyText(), progressionJson("2026-11-23T09:30:00.250Z", 3, "image/jxl", "0.6"))
    }

    @Test
    fun `a page sent late carries the time it was reached`(): Unit = runBlocking {
        answer("PUT /api/v1/books/0BK1A/progression", code = 204)

        val saved = reader.savePosition(Album, cfi = null, percent = 20.0, editedAt = TwoDaysAgo, page = 1)

        assertThat(saved).isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, TwoDaysAgo)))
        assertSameJson(requests().single().bodyText(), progressionJson("2026-11-21T18:00:00Z", 1, "image/jpeg", "0.2"))
    }

    @Test
    fun `a page sent late after a newer one of another device is set aside, Komga keeping its own`(): Unit =
        runBlocking {
            answer("PUT /api/v1/books/0BK1A/progression", code = 409)
            answer("GET /api/v1/books/0BK1A/progression", body = OtherDeviceJson)

            val saved = reader.savePosition(Album, cfi = null, percent = 20.0, editedAt = TwoDaysAgo, page = 1)

            assertThat(saved).isEqualTo(
                ApiResult.Success(SavedPosition(SaveOutcome.Superseded, Instant.parse("2026-11-22T07:45:10.500Z"))),
            )
            assertThat(requests().map { it.route() }).containsExactly(
                "PUT /api/v1/books/0BK1A/progression",
                "GET /api/v1/books/0BK1A/progression",
            ).inOrder()
        }

    @Test
    fun `a page set aside is so even when Komga's own place cannot be read`(): Unit = runBlocking {
        answer("PUT /api/v1/books/0BK1A/progression", code = 409)
        answer("GET /api/v1/books/0BK1A/progression", code = 500)

        assertThat(reader.savePosition(Album, cfi = null, percent = 20.0, editedAt = TwoDaysAgo, page = 1))
            .isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Superseded, storedAt = null)))

        answer("GET /api/v1/books/0BK1A/progression", code = 204)
        assertThat(reader.savePosition(Album, cfi = null, percent = 20.0, editedAt = TwoDaysAgo, page = 1))
            .isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Superseded, storedAt = null)))
    }

    @Test
    fun `the page the reader is at lands even after a place Komga dates later, sent just after it`(): Unit =
        runBlocking {
            // Another device's clock, or the server's, ahead of this one's.
            val later = OtherDeviceJson.replace("2026-11-22T07:45:10.5Z", "2026-11-23T09:30:04.5Z")
            var puts = 0
            answers += "PUT /api/v1/books/0BK1A/progression" to { _ ->
                MockResponse(code = if (puts++ == 0) 409 else 204)
            }
            answer("GET /api/v1/books/0BK1A/progression", body = later)

            val saved = reader.savePosition(Album, cfi = null, percent = 40.0, page = 2)

            assertThat(saved).isEqualTo(
                ApiResult.Success(SavedPosition(SaveOutcome.Saved, Instant.parse("2026-11-23T09:30:04.501Z"))),
            )
            val sent = requests()
            assertThat(sent.map { it.route() }).containsExactly(
                "PUT /api/v1/books/0BK1A/progression",
                "GET /api/v1/books/0BK1A/progression",
                "PUT /api/v1/books/0BK1A/progression",
            ).inOrder()
            assertSameJson(sent.last().bodyText(), progressionJson("2026-11-23T09:30:04.501Z", 2, "image/webp", "0.4"))
        }

    @Test
    fun `the page the reader is at, set aside, is not lost when Komga cannot say what it holds`(): Unit =
        runBlocking {
            answer("PUT /api/v1/books/0BK1A/progression", code = 409)
            answer("GET /api/v1/books/0BK1A/progression", code = 500)

            // The save fails: the outbox tries again later.
            assertThat(reader.savePosition(Album, cfi = null, percent = 40.0, page = 2))
                .isEqualTo(ApiResult.Failure(ApiError.ServerFailed))

            answer("GET /api/v1/books/0BK1A/progression", code = 204)
            assertThat(reader.savePosition(Album, cfi = null, percent = 40.0, page = 2))
                .isEqualTo(ApiResult.Failure(ApiError.Unexpected))
            // Nothing sent again blindly.
            assertThat(requests().map { it.route() }).containsExactly(
                "PUT /api/v1/books/0BK1A/progression",
                "GET /api/v1/books/0BK1A/progression",
                "PUT /api/v1/books/0BK1A/progression",
                "GET /api/v1/books/0BK1A/progression",
            ).inOrder()
        }

    @Test
    fun `the page the reader is at is sent once more only, a newer place set aside again being kept`(): Unit =
        runBlocking {
            val later = OtherDeviceJson.replace("2026-11-22T07:45:10.5Z", "2026-11-23T09:30:04.5Z")
            answer("PUT /api/v1/books/0BK1A/progression", code = 409)
            answer("GET /api/v1/books/0BK1A/progression", body = later)

            val saved = reader.savePosition(Album, cfi = null, percent = 40.0, page = 2)

            assertThat(saved).isEqualTo(
                ApiResult.Success(SavedPosition(SaveOutcome.Superseded, Instant.parse("2026-11-23T09:30:04.500Z"))),
            )
            assertThat(requests().map { it.route() }).containsExactly(
                "PUT /api/v1/books/0BK1A/progression",
                "GET /api/v1/books/0BK1A/progression",
                "PUT /api/v1/books/0BK1A/progression",
                "GET /api/v1/books/0BK1A/progression",
            ).inOrder()
        }

    @Test
    fun `this device's own page, which Komga dates otherwise, is saved and not sent again`(): Unit = runBlocking {
        answer("PUT /api/v1/books/0BK1A/progression", code = 409)
        answer(
            "GET /api/v1/books/0BK1A/progression",
            body = progressionJson("2026-11-23T09:30:07Z", 2, "image/webp", "0.4"),
        )

        val saved = reader.savePosition(Album, cfi = null, percent = 40.0, page = 2)

        assertThat(saved).isEqualTo(
            ApiResult.Success(SavedPosition(SaveOutcome.Saved, Instant.parse("2026-11-23T09:30:07Z"))),
        )
        assertThat(requests().map { it.route() }).containsExactly(
            "PUT /api/v1/books/0BK1A/progression",
            "GET /api/v1/books/0BK1A/progression",
        ).inOrder()
    }

    @Test
    fun `a place of this device at another page is not the one sent`(): Unit = runBlocking {
        answer("PUT /api/v1/books/0BK1A/progression", code = 409)
        answer(
            "GET /api/v1/books/0BK1A/progression",
            body = progressionJson("2026-11-21T18:00:00Z", 3, "image/jxl", "0.6"),
        )

        val saved = reader.savePosition(Album, cfi = null, percent = 20.0, editedAt = TwoDaysAgo, page = 1)

        assertThat(saved).isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Superseded, TwoDaysAgo)))
    }

    @Test
    fun `a page Komga already holds from this device, its answer lost on the way, counts as saved`(): Unit =
        runBlocking {
            answer("PUT /api/v1/books/0BK1A/progression", code = 409)
            answer(
                "GET /api/v1/books/0BK1A/progression",
                body = progressionJson("2026-11-21T18:00:00Z", 1, "image/jpeg", "0.2"),
            )

            val saved = reader.savePosition(Album, cfi = null, percent = 20.0, editedAt = TwoDaysAgo, page = 1)

            assertThat(saved).isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, TwoDaysAgo)))
        }

    @Test
    fun `the last page goes to Komga as the end of the album, which marks it read, and nothing else is sent`(): Unit =
        runBlocking {
            answer("PUT /api/v1/books/0BK1A/progression", code = 204)

            val saved = reader.savePosition(Album, cfi = null, percent = 100.0, page = 5)

            assertThat(saved).isEqualTo(
                ApiResult.Success(SavedPosition(SaveOutcome.Saved, Instant.parse("2026-11-23T09:30:00.250Z"))),
            )
            val sent = requests().single()
            assertThat(sent.route()).isEqualTo("PUT /api/v1/books/0BK1A/progression")
            assertSameJson(sent.bodyText(), progressionJson("2026-11-23T09:30:00.250Z", 5, "image/png", "1.0"))
        }

    @Test
    fun `a last spread of two pages, whose first page the reader gives, goes as the album's last page`(): Unit =
        runBlocking {
            answer("PUT /api/v1/books/0BK1A/progression", code = 204)

            val saved = reader.savePosition(Album, cfi = null, percent = 100.0, page = 4)

            // The time the app remembers is the progression's own: Komga dates the place with it.
            assertThat(saved).isEqualTo(
                ApiResult.Success(SavedPosition(SaveOutcome.Saved, Instant.parse("2026-11-23T09:30:00.250Z"))),
            )
            assertSameJson(
                requests().single().bodyText(),
                progressionJson("2026-11-23T09:30:00.250Z", 5, "image/png", "1.0"),
            )
        }

    @Test
    fun `the end of the album sent late after a newer place is set aside like any other`(): Unit = runBlocking {
        answer("PUT /api/v1/books/0BK1A/progression", code = 409)
        answer("GET /api/v1/books/0BK1A/progression", body = OtherDeviceJson)

        assertThat(reader.savePosition(Album, cfi = null, percent = 100.0, editedAt = TwoDaysAgo, page = 4))
            .isEqualTo(
                ApiResult.Success(SavedPosition(SaveOutcome.Superseded, Instant.parse("2026-11-22T07:45:10.500Z"))),
            )
        assertThat(requests().map { it.route() }).containsExactly(
            "PUT /api/v1/books/0BK1A/progression",
            "GET /api/v1/books/0BK1A/progression",
        ).inOrder()
    }

    @Test
    fun `a place Komga refuses says why, as its other calls do`(): Unit = runBlocking {
        answer("PUT /api/v1/books/0BK1A/progression", code = 404)
        assertThat(reader.savePosition(Album, cfi = null, percent = 20.0, page = 1))
            .isEqualTo(ApiResult.Failure(ApiError.NotFound))

        answer("PUT /api/v1/books/0BK1A/progression", code = 503)
        assertThat(reader.savePosition(Album, cfi = null, percent = 20.0, page = 1))
            .isEqualTo(ApiResult.Failure(ApiError.ServerFailed))

        answer("PUT /api/v1/books/0BK1A/progression", code = 400)
        assertThat(reader.savePosition(Album, cfi = null, percent = 20.0, page = 1))
            .isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    @Test
    fun `a page past the album's end is sent as its last, which Komga takes`(): Unit = runBlocking {
        answer("PUT /api/v1/books/0BK1A/progression", code = 204)

        reader.savePosition(Album, cfi = null, percent = 60.0, page = 9)

        assertSameJson(
            requests().single().bodyText(),
            progressionJson("2026-11-23T09:30:00.250Z", 5, "image/png", "1.0"),
        )
    }

    @Test
    fun `a place whose pages cannot be listed is not sent, and says why`(): Unit = runBlocking {
        formats = { throw IOException("No network") }

        assertThat(reader.savePosition(Album, cfi = null, percent = 20.0, page = 1))
            .isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a place of an album Komga analyses again is not sent, and fails as one to try again later`(): Unit =
        runBlocking {
            formats = { throw HttpException(Response.error<Any>(404, "".toResponseBody())) }
            answer("GET /api/v1/books/0BK1A", body = ReadingBookJson.replace(""""READY"""", """"OUTDATED""""))

            assertThat(reader.savePosition(Album, cfi = null, percent = 20.0, page = 1))
                .isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
            assertThat(requests().map { it.route() }).containsExactly("GET /api/v1/books/0BK1A")
        }

    @Test
    fun `a place of an album Komga could not analyse fails as one to try again later`(): Unit = runBlocking {
        formats = { throw HttpException(Response.error<Any>(404, "".toResponseBody())) }
        answer("GET /api/v1/books/0BK1A", body = ReadingBookJson.replace(""""READY"""", """"ERROR""""))

        assertThat(reader.savePosition(Album, cfi = null, percent = 20.0, page = 1))
            .isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `a place of an album gone from Komga is not found, and given up`(): Unit = runBlocking {
        formats = { throw HttpException(Response.error<Any>(404, "".toResponseBody())) }

        assertThat(reader.savePosition(Album, cfi = null, percent = 20.0, page = 1))
            .isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(requests().map { it.route() }).containsExactly("GET /api/v1/books/0BK1A")
    }

    @Test
    fun `a place of a ready album Komga lists no page of is not found`(): Unit = runBlocking {
        formats = { emptyList() }
        answer("GET /api/v1/books/0BK1A", body = ReadingBookJson)

        assertThat(reader.savePosition(Album, cfi = null, percent = 20.0, page = 1))
            .isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `a place with no page is not one of a comic, and Komga is not asked`(): Unit = runBlocking {
        assertThat(reader.savePosition(Album, cfi = "epubcfi(/6/4)", percent = 20.0))
            .isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(formatsAsked).isEqualTo(0)
    }

    // The next volume.

    @Test
    fun `the next volume is the series' next book on Komga`(): Unit = runBlocking {
        answer("GET /api/v1/books/0BK1A/next", body = UnreadBookJson)

        assertThat(reader.nextVolume(SeriesId("0SR1A"), BookId("0BK1A"), VolumeGroup.Comics))
            .isEqualTo(ApiResult.Success(NextVolume(BookId("0BK2B"), title = "Tidewater 04", seriesIndex = "4")))
    }

    @Test
    fun `the last volume of a series has none after it`(): Unit = runBlocking {
        answer("GET /api/v1/books/0BK2B/next", code = 404)
        answer("GET /api/v1/books/0BK3C/next", code = 500)

        assertThat(reader.nextVolume(SeriesId("0SR1A"), BookId("0BK2B"), VolumeGroup.Comics))
            .isEqualTo(ApiResult.Success(null))
        assertThat(reader.nextVolume(SeriesId("0SR2B"), BookId("0BK3C"), VolumeGroup.Comics))
            .isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    // What Komga does not do.

    @Test
    fun `no narration, session, finished reading, rating nor whole file, and Komga is not asked`(): Unit =
        runBlocking {
            val session = ReadingSessionReport("s1", TwoDaysAgo, Now, 600, 10.0, 60.0)

            assertThat(reader.saveNarration(Album, NarrationPlace(1.0, "a.html#p1", 0), 10.0))
                .isEqualTo(ApiResult.Failure(ApiError.NotFound))
            assertThat(reader.saveSession(Album, session)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
            assertThat(reader.finishedReading(BookId("0BK1A"))).isEqualTo(ApiResult.Success(null))
            assertThat(reader.rate(ReadId("1"), 4)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
            assertThat(reader.fileUrl(Album, audioless = false)).isNull()
            assertThat(reader.download(Album, audioless = false, ByteArrayOutputStream()) {})
                .isEqualTo(ApiResult.Failure(ApiError.NotFound))
            assertThat(server.requestCount).isEqualTo(0)
        }
}
