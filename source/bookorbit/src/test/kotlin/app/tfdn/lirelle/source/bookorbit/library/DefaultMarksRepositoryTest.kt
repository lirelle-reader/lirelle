package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.library.model.HighlightStyle
import app.tfdn.lirelle.core.library.model.MarkOrigin
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.library.model.PositionStatus
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.library.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.library.testing.bodyText
import app.tfdn.lirelle.source.bookorbit.library.testing.enqueueJson
import app.tfdn.lirelle.source.bookorbit.library.testing.enqueueRecorded
import app.tfdn.lirelle.source.bookorbit.library.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.library.testing.target
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class DefaultMarksRepositoryTest {

    private val server = MockWebServer()
    private var signedIn = true
    private lateinit var repository: DefaultMarksRepository

    @Before
    fun setUp() {
        server.start()
        repository = DefaultMarksRepository(
            api = ApiProvider(OkHttpClient(), ApiJson, MarksApi::class.java),
            errors = ErrorMapper(ApiJson),
            serverUrl = { if (signedIn) server.serverUrl else null },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun <T> ApiResult<T>.value(): T = (this as ApiResult.Success).value

    private val newHighlight =
        NewHighlight("epubcfi(/6/8!/4/2,/1:0,/1:24)", FileId("4"), "Highlighted text 1", "#FACC15", null, null)

    // --- bookmarks

    @Test
    fun `bookmarks are asked in the file being read`() = runBlocking {
        server.enqueueRecorded("marks-bookmarks-empty")

        assertThat(repository.bookmarks(BookId("2"), FileId("4")).value()).isEmpty()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.target()).isEqualTo("/api/v1/books/2/bookmarks?readerFileId=4")
    }

    @Test
    fun `a recorded list of bookmarks maps`() = runBlocking {
        server.enqueueRecorded("marks-bookmarks")

        val items = repository.bookmarks(BookId("2"), FileId("4")).value()

        assertThat(items).isNotEmpty()
        assertThat(items.first().origin).isEqualTo(MarkOrigin.Web)
        assertThat(items.first().createdAt).isNotEqualTo(Instant.EPOCH)
    }

    @Test
    fun `device bookmarks keep their note, origin and missing place`() = runBlocking {
        server.enqueueJson(Fixtures.read("handmade/marks-device-bookmarks.json"))

        val items = repository.bookmarks(BookId("2"), FileId("4")).value()

        assertThat(items[0].origin).isEqualTo(MarkOrigin.Kobo)
        assertThat(items[0].note).startsWith("Kobo note")
        assertThat(items[1].origin).isEqualTo(MarkOrigin.KoReader)
        assertThat(items[1].cfi).isNull()
    }

    @Test
    fun `adding a bookmark sends the place, the title and the file`() = runBlocking {
        server.enqueueRecorded("marks-bookmark-added")

        val bookmark = repository.addBookmark(BookId("2"), FileId("4"), "epubcfi(/6/8!/4/2/1:0)", "Chapter 1").value()

        assertThat(bookmark.id).isEqualTo(MarkId("1"))
        assertThat(bookmark.createdAt).isEqualTo(Instant.parse("2026-11-14T13:16:59.739Z"))
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.target()).isEqualTo("/api/v1/books/2/bookmarks")
        assertThat(json(request.bodyText())).isEqualTo(
            json("""{"cfi":"epubcfi(/6/8!/4/2/1:0)","title":"Chapter 1","bookFileId":4}"""),
        )
    }

    @Test
    fun `a bookmark title is trimmed and cut to 500`() = runBlocking {
        server.enqueueRecorded("marks-bookmark-added")

        repository.addBookmark(BookId("2"), FileId("4"), "epubcfi(/6/8)", "  " + "a".repeat(600) + "  ")

        assertThat(sentTitle()).isEqualTo("a".repeat(500))
    }

    private fun sentTitle() = (json(server.takeRequest().bodyText()) as JsonObject)["title"]!!.jsonPrimitive.content

    @Test
    fun `a title is not cut in the middle of a surrogate pair`() = runBlocking {
        server.enqueueRecorded("marks-bookmark-added")

        repository.addBookmark(BookId("2"), FileId("4"), "epubcfi(/6/8)", "a".repeat(499) + "\uD83D\uDE00")

        assertThat(sentTitle()).isEqualTo("a".repeat(499))
    }

    @Test
    fun `a title is trimmed again after the cut`() = runBlocking {
        server.enqueueRecorded("marks-bookmark-added")

        repository.addBookmark(BookId("2"), FileId("4"), "epubcfi(/6/8)", "a".repeat(498) + "  b")

        assertThat(sentTitle()).isEqualTo("a".repeat(498))
    }

    @Test
    fun `the date of a highlight is when it was made`() = runBlocking {
        server.enqueueJson(Fixtures.read("handmade/marks-device-highlights.json"))

        val first = repository.highlights(BookId("2"), FileId("4")).value().first()

        assertThat(first.createdAt).isEqualTo(Instant.parse("2026-11-02T19:38:12Z"))
    }

    @Test
    fun `an empty bookmark title is refused without a request`() = runBlocking {
        val result = repository.addBookmark(BookId("2"), FileId("4"), "epubcfi(/6/8)", "   ")

        assertThat(result).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `deleting a bookmark maps the empty answer to Unit`() = runBlocking {
        server.enqueue(MockResponse(code = 204))

        assertThat(repository.deleteBookmark(BookId("2"), MarkId("1"))).isEqualTo(ApiResult.Success(Unit))

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.target()).isEqualTo("/api/v1/books/2/bookmarks/1")
    }

    @Test
    fun `a bookmark that is not there is NotFound`() = runBlocking {
        server.enqueueJson("""{"message":"gone"}""", code = 404)

        assertThat(repository.deleteBookmark(BookId("2"), MarkId("9"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    // --- highlights

    @Test
    fun `highlights are asked in the file being read`() = runBlocking {
        server.enqueueRecorded("marks-highlights-empty")

        assertThat(repository.highlights(BookId("2"), FileId("4")).value()).isEmpty()

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/books/2/annotations?readerFileId=4")
    }

    @Test
    fun `a recorded highlight maps`() = runBlocking {
        server.enqueueRecorded("marks-highlight-changed")
        server.enqueueRecorded("marks-highlights")

        val one = repository.changeHighlight(BookId("2"), MarkId("3"), note = "Note 2").value()
        assertThat(one.cfi).isEqualTo("epubcfi(/6/8!/4/2,/1:0,/1:24)")
        assertThat(one.color).isEqualTo("#38BDF8")
        assertThat(one.style).isEqualTo(HighlightStyle.Highlight)
        assertThat(one.note).isEqualTo("Note 2")
        assertThat(one.chapterTitle).isEqualTo("Chapter 1")
        assertThat(one.chapterIndex).isNull()
        assertThat(one.positionStatus).isEqualTo(PositionStatus.Exact)
        assertThat(one.createdAt).isEqualTo(Instant.parse("2026-11-14T13:17:00.562Z"))
        assertThat(one.updatedAt).isEqualTo(Instant.parse("2026-11-14T13:17:00.729Z"))
        assertThat(repository.highlights(BookId("2"), FileId("4")).value()).isNotEmpty()
    }

    @Test
    fun `device highlights map their odd values`() = runBlocking {
        server.enqueueJson(Fixtures.read("handmade/marks-device-highlights.json"))

        val (unplaced, kobo, odd) = repository.highlights(BookId("2"), FileId("4")).value()

        assertThat(unplaced.cfi).isNull()
        assertThat(unplaced.positionStatus).isEqualTo(PositionStatus.Failed)
        assertThat(unplaced.origin).isEqualTo(MarkOrigin.KoReader)
        assertThat(unplaced.chapterIndex).isEqualTo(4)
        assertThat(kobo.style).isEqualTo(HighlightStyle.Squiggly)
        assertThat(kobo.origin).isEqualTo(MarkOrigin.Kobo)
        assertThat(kobo.positionStatus).isEqualTo(PositionStatus.Repaired)
        assertThat(kobo.updatedAt).isNull()
        assertThat(odd.color).isEqualTo("yellow")
        assertThat(odd.origin).isEqualTo(MarkOrigin.Web)
        assertThat(odd.style).isEqualTo(HighlightStyle.Invert)
        assertThat(odd.positionStatus).isNull()
    }

    @Test
    fun `an unknown style and status map to the defaults`() = runBlocking {
        server.enqueueJson(
            """[{"id":1,"cfi":"c","text":"t","color":"#FACC15","style":"glow","origin":"x",
            "positionStatus":"odd","createdAt":"2026-11-14T13:17:00.562Z"}]""",
        )

        val item = repository.highlights(BookId("2"), FileId("4")).value().single()

        assertThat(item.style).isEqualTo(HighlightStyle.Highlight)
        assertThat(item.positionStatus).isNull()
    }

    @Test
    fun `a colour that is not a hex is kept as is`() = runBlocking {
        server.enqueueJson(
            """[{"id":1,"cfi":"c","text":"t","color":"chartreuse","createdAt":"2026-11-14T13:17:00Z"}]""",
        )

        assertThat(repository.highlights(BookId("2"), FileId("4")).value().single().color).isEqualTo("chartreuse")
    }

    @Test
    fun `adding a highlight omits a null note and chapter title`() = runBlocking {
        server.enqueueRecorded("marks-highlight-added")

        repository.addHighlight(BookId("2"), newHighlight).value()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.target()).isEqualTo("/api/v1/books/2/annotations")
        assertThat(json(request.bodyText())).isEqualTo(
            json(
                """{"cfi":"epubcfi(/6/8!/4/2,/1:0,/1:24)","bookFileId":4,"text":"Highlighted text 1",
                "color":"#FACC15","style":"highlight"}""",
            ),
        )
    }

    @Test
    fun `adding a highlight sends its note and chapter title when given`() = runBlocking {
        server.enqueueRecorded("marks-highlight-added")

        repository.addHighlight(BookId("2"), newHighlight.copy(note = "n", chapterTitle = "Chapter 1")).value()

        assertThat(json(server.takeRequest().bodyText())).isEqualTo(
            json(
                """{"cfi":"epubcfi(/6/8!/4/2,/1:0,/1:24)","bookFileId":4,"text":"Highlighted text 1",
                "color":"#FACC15","style":"highlight","note":"n","chapterTitle":"Chapter 1"}""",
            ),
        )
    }

    @Test
    fun `a chapter title is cut to 500, never in the middle of a surrogate pair`() = runBlocking {
        server.enqueueRecorded("marks-highlight-added")
        server.enqueueRecorded("marks-highlight-added")

        repository.addHighlight(BookId("2"), newHighlight.copy(chapterTitle = "c".repeat(600))).value()
        repository.addHighlight(BookId("2"), newHighlight.copy(chapterTitle = "c".repeat(499) + "\uD83D\uDE00")).value()

        val sent = List(2) {
            (json(server.takeRequest().bodyText()) as JsonObject)["chapterTitle"]!!.jsonPrimitive.content
        }
        assertThat(sent).containsExactly("c".repeat(500), "c".repeat(499)).inOrder()
    }

    @Test
    fun `a patch sends only what is given`() = runBlocking {
        server.enqueueRecorded("marks-highlight-changed")
        server.enqueueRecorded("marks-highlight-changed")

        repository.changeHighlight(BookId("2"), MarkId("3"), color = "#38BDF8")
        repository.changeHighlight(BookId("2"), MarkId("3"), note = "Note 2")

        val first = server.takeRequest()
        assertThat(first.method).isEqualTo("PATCH")
        assertThat(first.target()).isEqualTo("/api/v1/books/2/annotations/3")
        assertThat(json(first.bodyText())).isEqualTo(json("""{"color":"#38BDF8"}"""))
        assertThat(json(server.takeRequest().bodyText())).isEqualTo(json("""{"note":"Note 2"}"""))
    }

    @Test
    fun `clearing a note sends a null note`() = runBlocking {
        server.enqueueRecorded("marks-highlight-note-cleared")

        val item = repository.changeHighlight(BookId("2"), MarkId("3"), clearNote = true).value()

        assertThat(item.note).isNull()
        assertThat(json(server.takeRequest().bodyText())).isEqualTo(json("""{"note":null}"""))
    }

    @Test
    fun `deleting and restoring a highlight map the answers to Unit`() = runBlocking {
        server.enqueue(MockResponse(code = 204))
        server.enqueueRecorded("marks-restored")

        assertThat(repository.deleteHighlight(BookId("2"), MarkId("3"))).isEqualTo(ApiResult.Success(Unit))
        assertThat(repository.restoreHighlight(MarkId("3"))).isEqualTo(ApiResult.Success(Unit))

        val delete = server.takeRequest()
        assertThat(delete.method).isEqualTo("DELETE")
        assertThat(delete.target()).isEqualTo("/api/v1/books/2/annotations/3")
        val restore = server.takeRequest()
        assertThat(restore.method).isEqualTo("POST")
        assertThat(restore.target()).isEqualTo("/api/v1/annotations/3/restore")
    }

    @Test
    fun `a highlight that is not there is NotFound`() = runBlocking {
        server.enqueueJson("""{"message":"gone"}""", code = 404)

        val result = repository.changeHighlight(BookId("2"), MarkId("9"), color = "#FACC15")

        assertThat(result).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `signed out is an expired session, without a request`() = runBlocking {
        signedIn = false

        assertThat(repository.bookmarks(BookId("2"), FileId("4"))).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(server.requestCount).isEqualTo(0)
    }
}
