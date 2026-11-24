package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.library.model.FinishedReading
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.library.testing.bodyText
import app.tfdn.lirelle.source.bookorbit.library.testing.enqueueJson
import app.tfdn.lirelle.source.bookorbit.library.testing.enqueueRecorded
import app.tfdn.lirelle.source.bookorbit.library.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.library.testing.target
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

// The answers are written from the shapes the server gives on its reader routes.
class DefaultReaderRepositoryTest {

    private val server = MockWebServer()
    private var signedIn = true
    private lateinit var repository: DefaultReaderRepository

    @Before
    fun setUp() {
        server.start()
        repository = DefaultReaderRepository(
            api = ApiProvider(OkHttpClient(), ApiJson, ReaderApi::class.java),
            errors = ErrorMapper(ApiJson),
            serverUrl = { if (signedIn) server.serverUrl else null },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun entry(cfi: String?, percentage: Double, updatedAt: String?, textUpdatedAt: String?): String {
        fun quoted(value: String?) = value?.let { "\"$it\"" } ?: "null"
        return """{"fileId":12,"percentage":$percentage,"updatedAt":${quoted(updatedAt)},
            "text":{"cfi":${quoted(cfi)},"page":null,"updatedAt":${quoted(textUpdatedAt)}},
            "narration":{"seconds":null,"fragment":null,"sectionIndex":null,"percentage":null,"updatedAt":null},
            "kobo":{"source":null,"type":null,"value":null,"contentPercent":null},"koreader":null}"""
    }

    // --- position, read

    @Test
    fun `the position of a file`() = runBlocking {
        val cfi = "epubcfi(/6/8!/4/2/1:0)"
        server.enqueueJson(
            """{"items":[${entry(cfi, 42.5, "2026-11-10T08:00:00.000Z", "2026-11-09T07:00:00.000Z")}]}""",
        )

        val result = repository.position(FileId("12"))

        assertThat(result).isEqualTo(
            ApiResult.Success(ReadingPosition(FileId("12"), cfi, 42.5, Instant.parse("2026-11-09T07:00:00Z"))),
        )
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.target()).isEqualTo("/api/v1/reading-progress?fileId=12")
    }

    @Test
    fun `a position without a place keeps its percentage and a null place`() = runBlocking {
        server.enqueueJson("""{"items":[${entry(null, 63.0, "2026-11-10T08:00:00.000Z", null)}]}""")

        val result = repository.position(FileId("12"))

        assertThat(result).isEqualTo(
            ApiResult.Success(ReadingPosition(FileId("12"), null, 63.0, Instant.parse("2026-11-10T08:00:00Z"))),
        )
    }

    @Test
    fun `a file never opened is at the start, with no date`() = runBlocking {
        server.enqueueJson("""{"items":[${entry(null, 0.0, null, null)}]}""")
        server.enqueueJson("""{"items":[]}""")

        val unread = ReadingPosition(FileId("12"), null, 0.0, null)
        assertThat(repository.position(FileId("12"))).isEqualTo(ApiResult.Success(unread))
        assertThat(repository.position(FileId("12"))).isEqualTo(ApiResult.Success(unread))
    }

    @Test
    fun `the position of another file in the answer is not taken`() = runBlocking {
        server.enqueueJson("""{"items":[{"fileId":13,"percentage":80,"updatedAt":"2026-11-10T08:00:00.000Z"}]}""")

        assertThat(repository.position(FileId("12")))
            .isEqualTo(ApiResult.Success(ReadingPosition(FileId("12"), null, 0.0, null)))
    }

    // --- position, write

    @Test
    fun `saving a position sends one text write and nothing else`() = runBlocking {
        server.enqueueJson(
            """{"results":[{"fileId":12,"status":"saved","progress":${entry("x", 42.5, null, null)}}]}""",
        )

        val result = repository.savePosition(FileId("12"), "epubcfi(/6/8!/4/2/1:0)", 42.5)

        assertThat(result).isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved)))
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.target()).isEqualTo("/api/v1/reading-progress")
        assertThat(json(request.bodyText())).isEqualTo(
            json(
                """{"writes":[
                    {"fileId":12,"kind":"text","percentage":42.5,"text":{"cfi":"epubcfi(/6/8!/4/2/1:0)"}}]}""",
            ),
        )
    }

    @Test
    fun `a position sent late says when it was made, as a strict UTC instant`() = runBlocking {
        server.enqueueJson("""{"results":[{"fileId":12,"status":"saved"}]}""")
        server.enqueueJson("""{"results":[{"fileId":12,"status":"saved"}]}""")

        repository.savePosition(
            FileId("12"),
            "epubcfi(/6/8!/4/2/1:0)",
            42.5,
            editedAt = Instant.parse("2026-11-14T09:58:07Z"),
        )
        repository.savePosition(FileId("12"), null, 10.0, editedAt = Instant.parse("2026-11-14T09:58:07.250Z"))

        assertThat(json(server.takeRequest().bodyText())).isEqualTo(
            json(
                """{"writes":[{"fileId":12,"kind":"text","percentage":42.5,
                    "text":{"cfi":"epubcfi(/6/8!/4/2/1:0)"},"editedAt":"2026-11-14T09:58:07Z"}]}""",
            ),
        )
        assertThat(json(server.takeRequest().bodyText())).isEqualTo(
            json(
                """{"writes":[
                    {"fileId":12,"kind":"text","percentage":10.0,"text":{},"editedAt":"2026-11-14T09:58:07.250Z"}]}""",
            ),
        )
    }

    @Test
    fun `a position without a place is sent with an empty text part`() = runBlocking {
        server.enqueueJson("""{"results":[{"fileId":12,"status":"saved"}]}""")

        repository.savePosition(FileId("12"), null, 10.0)

        assertThat(json(server.takeRequest().bodyText()))
            .isEqualTo(json("""{"writes":[{"fileId":12,"kind":"text","percentage":10.0,"text":{}}]}"""))
    }

    @Test
    fun `a percentage is kept within what the server accepts`() = runBlocking {
        server.enqueueJson("""{"results":[{"fileId":12,"status":"saved"}]}""")
        server.enqueueJson("""{"results":[{"fileId":12,"status":"saved"}]}""")

        repository.savePosition(FileId("12"), "a", 100.4)
        repository.savePosition(FileId("12"), "a", -1.0)

        assertThat(server.takeRequest().bodyText()).contains(""""percentage":100.0""")
        assertThat(server.takeRequest().bodyText()).contains(""""percentage":0.0""")
    }

    @Test
    fun `a write the server set aside is reported as superseded`() = runBlocking {
        server.enqueueJson(
            """{"results":[{"fileId":12,"status":"superseded","progress":${entry("y", 50.0, null, null)}}]}""",
        )

        assertThat(repository.savePosition(FileId("12"), "x", 42.5))
            .isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Superseded)))
    }

    @Test
    fun `the answer to a saved position says when the server stored it, by its own clock`() = runBlocking {
        val general = "2026-11-14T10:10:00.000Z"
        val text = "2026-11-14T10:10:00.250Z"
        fun answer(status: String, textAt: String?) =
            """{"results":[{"fileId":12,"status":"$status","progress":${entry("x", 42.5, general, textAt)}}]}"""
        server.enqueueJson(answer("saved", text))
        server.enqueueJson(answer("saved", null))
        server.enqueueJson(answer("superseded", null))

        assertThat(repository.savePosition(FileId("12"), "x", 42.5))
            .isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, Instant.parse(text))))
        assertThat(repository.savePosition(FileId("12"), "x", 42.5))
            .isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, Instant.parse(general))))
        assertThat(repository.savePosition(FileId("12"), "x", 42.5))
            .isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Superseded, Instant.parse(general))))
    }

    // --- narration

    @Test
    fun `a position with narration reads it, with its own date`() = runBlocking {
        server.enqueueRecorded("readalong-progress-narration")

        val result = repository.position(FileId("6")) as ApiResult.Success

        assertThat(result.value.narration).isEqualTo(
            NarrationPlace(19412.16, "OEBPS/Text/section111.xhtml#p014-s182", 27),
        )
        assertThat(result.value.narrationUpdatedAt).isEqualTo(Instant.parse("2026-11-15T18:47:13.498Z"))
        assertThat(result.value.cfi).isEqualTo("epubcfi(/6/56!/4/2/2/2/8/68/2[p014-s182]/1:0)")
    }

    @Test
    fun `a narration without seconds starts at 0, one without a place is not a narration`() = runBlocking {
        fun item(narration: String) = """{"items":[{"fileId":12,"percentage":5,"narration":$narration}]}"""
        server.enqueueJson(
            item("""{"seconds":null,"fragment":"a.xhtml#s1","sectionIndex":2,"percentage":5,"updatedAt":null}"""),
        )
        server.enqueueJson(item("""{"seconds":3.0,"fragment":null,"sectionIndex":2,"percentage":5,"updatedAt":null}"""))
        server.enqueueJson(item("""{"seconds":3.0,"fragment":"a#b","sectionIndex":null,"percentage":5}"""))

        assertThat((repository.position(FileId("12")) as ApiResult.Success).value.narration)
            .isEqualTo(NarrationPlace(0.0, "a.xhtml#s1", 2))
        assertThat((repository.position(FileId("12")) as ApiResult.Success).value.narration).isNull()
        assertThat((repository.position(FileId("12")) as ApiResult.Success).value.narration).isNull()
    }

    @Test
    fun `a position without narration reads as before`() = runBlocking {
        server.enqueueJson("""{"items":[{"fileId":12,"percentage":5}]}""")

        val position = (repository.position(FileId("12")) as ApiResult.Success).value

        assertThat(position.narration).isNull()
        assertThat(position.narrationUpdatedAt).isNull()
    }

    @Test
    fun `a narration write has the narration and no text`() = runBlocking {
        server.enqueueRecorded("readalong-narration-write")

        val result = repository.saveNarration(
            FileId("6"),
            NarrationPlace(19412.16, "OEBPS/Text/section111.xhtml#p014-s182", 27),
            50.0,
        )

        // The narration's own time: the one the server compares a late narration with.
        assertThat(result).isEqualTo(
            ApiResult.Success(SavedPosition(SaveOutcome.Saved, Instant.parse("2026-11-15T18:47:13.498Z"))),
        )
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.target()).isEqualTo("/api/v1/reading-progress")
        assertThat(json(request.bodyText())).isEqualTo(
            json(
                """{"writes":[{"fileId":6,"kind":"narration","percentage":50.0,
                    "narration":{"seconds":19412.16,"fragment":"OEBPS/Text/section111.xhtml#p014-s182",
                    "sectionIndex":27}}]}""",
            ),
        )
    }

    @Test
    fun `a late narration write says when it was made, and a superseded one is reported so`() = runBlocking {
        server.enqueueJson("""{"results":[{"fileId":6,"status":"superseded"}]}""")

        val result = repository.saveNarration(
            FileId("6"),
            NarrationPlace(1.0, "a.xhtml#s", 0),
            120.0,
            editedAt = Instant.parse("2026-11-14T09:58:07Z"),
        )

        assertThat(result).isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Superseded)))
        assertThat(json(server.takeRequest().bodyText())).isEqualTo(
            json(
                """{"writes":[{"fileId":6,"kind":"narration","percentage":100.0,
                    "narration":{"seconds":1.0,"fragment":"a.xhtml#s","sectionIndex":0},
                    "editedAt":"2026-11-14T09:58:07Z"}]}""",
            ),
        )
    }

    @Test
    fun `a narration stored without a time of its own is stamped with the position's`() = runBlocking {
        server.enqueueJson(
            """{"results":[{"fileId":6,"status":"saved","progress":{"fileId":6,"percentage":50,
                "updatedAt":"2026-11-15T18:47:13.499Z","narration":{"seconds":1.0}}}]}""",
        )

        val result = repository.saveNarration(FileId("6"), NarrationPlace(1.0, "a.xhtml#s", 0), 50.0)

        assertThat(result).isEqualTo(
            ApiResult.Success(SavedPosition(SaveOutcome.Saved, Instant.parse("2026-11-15T18:47:13.499Z"))),
        )
    }

    @Test
    fun `a narration write fails like the other calls`() = runBlocking {
        signedIn = false

        assertThat(repository.saveNarration(FileId("6"), NarrationPlace(1.0, "a#b", 0), 1.0))
            .isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
    }

    // --- sessions

    private val session = ReadingSessionReport(
        sessionId = "0b0e7a52-3c56-4f0e-9a43-1f2d3c4b5a69",
        startedAt = Instant.parse("2026-11-14T10:00:00Z"),
        endedAt = Instant.parse("2026-11-14T10:20:00Z"),
        activeSeconds = 900,
        progressDelta = 3.5,
        endPercent = 46.0,
    )

    @Test
    fun `a session is sent as an android one, without a session type`() = runBlocking {
        server.enqueue(MockResponse(code = 204))

        val result = repository.saveSession(FileId("12"), session)

        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.target()).isEqualTo("/api/v1/books/files/12/sessions")
        assertThat(json(request.bodyText())).isEqualTo(
            json(
                """{"sessionId":"0b0e7a52-3c56-4f0e-9a43-1f2d3c4b5a69","startedAt":"2026-11-14T10:00:00Z",
                    "endedAt":"2026-11-14T10:20:00Z","durationSeconds":900,"progressDelta":3.5,"endProgress":46.0,
                    "source":"android"}""",
            ),
        )
    }

    @Test
    fun `a session without progress leaves those fields out`() = runBlocking {
        server.enqueue(MockResponse(code = 204))

        repository.saveSession(FileId("12"), session.copy(progressDelta = null, endPercent = null))

        assertThat(json(server.takeRequest().bodyText())).isEqualTo(
            json(
                """{"sessionId":"0b0e7a52-3c56-4f0e-9a43-1f2d3c4b5a69","startedAt":"2026-11-14T10:00:00Z",
                    "endedAt":"2026-11-14T10:20:00Z","durationSeconds":900,"source":"android"}""",
            ),
        )
    }

    // --- readings and rating

    @Test
    fun `the finished reading is the newest one with an outcome`() = runBlocking {
        server.enqueueJson(
            """{"items":[{"id":31,"bookId":5,"outcome":null},{"id":27,"bookId":5,"outcome":"completed"},
                {"id":9,"bookId":5,"outcome":"abandoned"}],"page":1,"pageSize":20,"total":3}""",
        )

        assertThat(repository.finishedReadingId(BookId("5"))).isEqualTo(ApiResult.Success(ReadId("27")))
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.target()).isEqualTo("/api/v1/books/5/reading-attempts")
    }

    @Test
    fun `the newest finished reading is the first the server lists, whatever its id`() = runBlocking {
        // BookOrbit lists them by id descending, newest first, so it never sends id 4 before id 12: this order pins
        // the contract, the server's order wins over the ids, which are never compared.
        server.enqueueJson(
            """{"items":[{"id":31,"bookId":5,"outcome":null},{"id":4,"bookId":5,"outcome":"completed"},
                {"id":12,"bookId":5,"outcome":"abandoned"}],"page":1,"pageSize":20,"total":3}""",
        )

        assertThat(repository.finishedReadingId(BookId("5"))).isEqualTo(ApiResult.Success(ReadId("4")))
    }

    @Test
    fun `a book with no finished reading has no reading to rate`() = runBlocking {
        server.enqueueJson("""{"items":[{"id":31,"bookId":5,"outcome":null}],"page":1,"pageSize":20,"total":1}""")
        server.enqueueJson("""{"items":[],"page":1,"pageSize":20,"total":0}""")

        assertThat(repository.finishedReadingId(BookId("5"))).isEqualTo(ApiResult.Success(null))
        assertThat(repository.finishedReadingId(BookId("5"))).isEqualTo(ApiResult.Success(null))
    }

    @Test
    fun `the newest finished reading comes with when it was last written and its rating`() = runBlocking<Unit> {
        server.enqueueJson(
            """{"items":[{"id":27,"bookId":5,"outcome":"completed","rating":4,"endedOn":"2026-11-14",""" +
                """"updatedAt":"2026-11-14T21:10:00.000Z"},{"id":31,"bookId":5,"outcome":null},""" +
                """{"id":9,"bookId":5,"outcome":"abandoned"}],"page":1,"pageSize":20,"total":3}""",
        )

        assertThat(repository.finishedReading(BookId("5")))
            .isEqualTo(ApiResult.Success(FinishedReading(ReadId("27"), Instant.parse("2026-11-14T21:10:00Z"), 4)))
    }

    @Test
    fun `a finished reading without a time of its own ends with its day, and without stars has no rating`() =
        runBlocking<Unit> {
            server.enqueueJson(
                """{"items":[{"id":27,"bookId":5,"outcome":"completed","rating":null,"endedOn":"2026-11-14"}],""" +
                    """"page":1,"pageSize":20,"total":1}""",
            )
            server.enqueueRecorded("reader-attempts")

            val endOfDay = Instant.parse("2026-11-15T00:00:00Z")
            assertThat(repository.finishedReading(BookId("5")))
                .isEqualTo(ApiResult.Success(FinishedReading(ReadId("27"), endOfDay, null)))
            val recorded = Instant.parse("2026-11-14T07:45:03.015Z")
            assertThat(repository.finishedReading(BookId("3")))
                .isEqualTo(ApiResult.Success(FinishedReading(ReadId("2"), recorded, null)))
        }

    @Test
    fun `rating a reading`() = runBlocking {
        server.enqueueJson("""{"rating":4,"bookRatingChanged":true}""")

        assertThat(repository.rate(ReadId("27"), 4)).isEqualTo(ApiResult.Success(Unit))

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PATCH")
        assertThat(request.target()).isEqualTo("/api/v1/reading-history/27/rating")
        assertThat(json(request.bodyText())).isEqualTo(json("""{"rating":4}"""))
    }

    @Test
    fun `removing a rating sends an explicit null`() = runBlocking {
        server.enqueueJson("""{"rating":null,"bookRatingChanged":true}""")

        repository.rate(ReadId("27"), null)

        assertThat(json(server.takeRequest().bodyText())).isEqualTo(json("""{"rating":null}"""))
    }

    // --- next volume

    @Test
    fun `the next volume of a series`() = runBlocking {
        server.enqueueJson("""{"next":{"bookId":7,"fileId":21,"format":"epub","title":"Book 2","seriesIndex":"2"}}""")

        assertThat(repository.nextVolume(SeriesId("3"), BookId("5")))
            .isEqualTo(ApiResult.Success(NextVolume(BookId("7"), "Book 2", "2")))
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/series/3/books/5/next?formatGroup=epub")
    }

    @Test
    fun `the last volume has no next one`() = runBlocking {
        server.enqueueJson("""{"next":null}""")

        assertThat(repository.nextVolume(SeriesId("3"), BookId("5"))).isEqualTo(ApiResult.Success(null))
    }

    // --- what the server really answered (recorded with the test account)

    @Test
    fun `recorded - a file never opened`() = runBlocking {
        server.enqueueRecorded("reader-progress-none")

        assertThat(repository.position(FileId("1")))
            .isEqualTo(ApiResult.Success(ReadingPosition(FileId("1"), null, 0.0, null)))
    }

    @Test
    fun `recorded - a position after a write`() = runBlocking {
        server.enqueueRecorded("reader-progress")

        assertThat(repository.position(FileId("1"))).isEqualTo(
            ApiResult.Success(
                ReadingPosition(FileId("1"), "epubcfi(/6/8!/4/2/1:0)", 30.0, Instant.parse("2026-11-14T07:45:01.809Z")),
            ),
        )
    }

    @Test
    fun `recorded - the answer to a saved position`() = runBlocking {
        server.enqueueRecorded("reader-progress-saved")

        assertThat(repository.savePosition(FileId("1"), "epubcfi(/6/8!/4/2/1:0)", 30.0))
            .isEqualTo(ApiResult.Success(SavedPosition(SaveOutcome.Saved, Instant.parse("2026-11-14T07:45:01.809Z"))))
    }

    @Test
    fun `recorded - a finished reading and its rating`() = runBlocking {
        server.enqueueRecorded("reader-attempts")
        server.enqueueRecorded("reader-rating")

        assertThat(repository.finishedReadingId(BookId("3"))).isEqualTo(ApiResult.Success(ReadId("2")))
        assertThat(repository.rate(ReadId("2"), 4)).isEqualTo(ApiResult.Success(Unit))
    }

    @Test
    fun `recorded - the next volume, and none`() = runBlocking {
        server.enqueueRecorded("reader-next-volume")
        server.enqueueRecorded("reader-next-volume-none")

        assertThat(repository.nextVolume(SeriesId("3"), BookId("5")))
            .isEqualTo(ApiResult.Success(NextVolume(BookId("4"), "Book 1", "3")))
        assertThat(repository.nextVolume(SeriesId("3"), BookId("4"))).isEqualTo(ApiResult.Success(null))
    }

    // --- file urls

    @Test
    fun `a file's urls are built from the session's server`() {
        assertThat(repository.fileUrl(FileId("12"), audioless = false))
            .isEqualTo("${server.serverUrl}/api/v1/books/files/12/serve")
        assertThat(repository.fileUrl(FileId("12"), audioless = true))
            .isEqualTo("${server.serverUrl}/api/v1/books/files/12/audioless-epub/download")
    }

    @Test
    fun `signed out, a file has no url`() {
        signedIn = false

        assertThat(repository.fileUrl(FileId("12"), audioless = false)).isNull()
    }

    // --- download

    private fun bytes(count: Int) = ByteArray(count) { (it % 251).toByte() }

    private fun fileResponse(content: ByteArray) = MockResponse.Builder()
        .addHeader("Content-Type", "application/epub+zip")
        .body(Buffer().write(content))

    @Test
    fun `a file is downloaded from the serve route into the sink, with its progress`() = runBlocking {
        val content = bytes(300_000)
        server.enqueue(fileResponse(content).build())
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<DownloadProgress>()

        val result = repository.download(FileId("12"), audioless = false, sink) { progress += it }

        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        assertThat(sink.toByteArray()).isEqualTo(content)
        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/books/files/12/serve")
        assertThat(progress.first()).isEqualTo(DownloadProgress(0, 300_000))
        assertThat(progress.last()).isEqualTo(DownloadProgress(300_000, 300_000))
        assertThat(progress.size).isGreaterThan(2)
        assertThat(progress.map { it.bytesRead }).isInOrder()
    }

    @Test
    fun `the audio-less variant comes from its own route`() = runBlocking {
        server.enqueue(fileResponse(bytes(10)).build())

        repository.download(FileId("12"), audioless = true, ByteArrayOutputStream()) {}

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/books/files/12/audioless-epub/download")
    }

    @Test
    fun `a body of unknown length reports no total`() = runBlocking {
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(bytes(5_000)), 1_000).build())
        val progress = mutableListOf<DownloadProgress>()

        repository.download(FileId("12"), audioless = false, ByteArrayOutputStream()) { progress += it }

        assertThat(progress.last()).isEqualTo(DownloadProgress(5_000, null))
    }

    /**
     * The server cuts the connection half-way. A body read whole before being handed over would
     * fail before the first byte reaches the sink; a streamed one has already written its start.
     */
    @Test
    fun `the body is streamed to the sink, not held in memory first`() = runBlocking {
        val total = 4_000_000
        server.enqueue(fileResponse(bytes(total)).onResponseBody(SocketEffect.ShutdownConnection).build())
        val sink = ByteArrayOutputStream()

        val result = repository.download(FileId("12"), audioless = false, sink) {}

        assertThat(result).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
        assertThat(sink.size()).isGreaterThan(0)
        assertThat(sink.size()).isLessThan(total)
    }

    @Test
    fun `a refused download is a failure and writes nothing`() = runBlocking {
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 409))
        server.enqueue(MockResponse(code = 401))
        val sink = ByteArrayOutputStream()

        assertThat(repository.download(FileId("12"), false, sink) {}).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(repository.download(FileId("12"), true, sink) {}).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
        assertThat(repository.download(FileId("12"), false, sink) {})
            .isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(sink.size()).isEqualTo(0)
    }

    @Test
    fun `a sink that cannot be written is not a network failure`() = runBlocking {
        server.enqueue(fileResponse(bytes(1_000)).build())
        val full = object : OutputStream() {
            override fun write(b: Int) = throw IOException("No space left on device")
        }

        assertThat(repository.download(FileId("12"), false, full) {}).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    @Test
    fun `a cancelled download stops and leaves the sink as it is`() = runBlocking {
        server.enqueue(fileResponse(bytes(2_000_000)).throttleBody(16_384, 20, TimeUnit.MILLISECONDS).build())
        val started = CompletableDeferred<Unit>()
        var closed = false
        val sink = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
            }
        }

        val job = launch(Dispatchers.Default) {
            repository.download(FileId("12"), audioless = false, sink) { if (it.bytesRead > 0) started.complete(Unit) }
        }
        started.await()
        job.cancelAndJoin()

        assertThat(job.isCancelled).isTrue()
        assertThat(sink.size()).isGreaterThan(0)
        assertThat(sink.size()).isLessThan(2_000_000)
        assertThat(closed).isFalse()
    }

    // --- failures

    @Test
    fun `failures are mapped like the rest of the library`() = runBlocking {
        server.enqueue(MockResponse(code = 401))
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 500))

        assertThat(repository.position(FileId("12"))).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(repository.rate(ReadId("27"), 4)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(repository.saveSession(FileId("12"), session)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `nothing is asked when signed out`() = runBlocking {
        signedIn = false

        assertThat(repository.savePosition(FileId("12"), "x", 1.0))
            .isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(repository.download(FileId("12"), false, ByteArrayOutputStream()) {})
            .isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `an unreachable server is reported as such`() = runBlocking {
        server.close()

        assertThat(repository.position(FileId("12"))).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
    }
}
