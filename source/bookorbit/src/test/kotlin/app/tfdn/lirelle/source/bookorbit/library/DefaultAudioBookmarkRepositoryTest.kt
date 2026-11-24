package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.core.library.model.BookmarkCreateResult
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class DefaultAudioBookmarkRepositoryTest {

    private val server = MockWebServer()
    private var signedIn = true
    private lateinit var repository: DefaultAudioBookmarkRepository

    private val clientId = "00000000-0000-4000-8000-000000000001"
    private val bookmark = AudioBookmark(
        id = clientId,
        bookId = BookId("5"),
        positionMs = 600_000,
        chapterId = null,
        title = "Bookmark 1",
        note = null,
        createdAt = null,
        updatedAt = null,
    )

    @Before
    fun setUp() {
        server.start()
        repository = DefaultAudioBookmarkRepository(
            api = ApiProvider(OkHttpClient(), ApiJson, AudioBookmarkApi::class.java),
            errors = ErrorMapper(ApiJson),
            serverUrl = { if (signedIn) server.serverUrl else null },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun <T> ApiResult<T>.value(): T = (this as ApiResult.Success).value

    private fun wrapped(name: String): Pair<String, Int> = Json.parseToJsonElement(Fixtures.recorded(name)).jsonObject
        .let { it.getValue("body").toString() to (it.getValue("status") as JsonPrimitive).content.toInt() }

    private fun sentBody() = Json.parseToJsonElement(server.takeRequest().bodyText()).jsonObject.toMap()

    // --- create

    @Test
    fun `a new bookmark sends its client id, whole-book position and title, and answers the stored one`() =
        runBlocking {
            server.enqueueRecorded("audiobookmarks-created")

            val created = repository.create(BookId("5"), bookmark).value() as BookmarkCreateResult.Created

            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.target()).isEqualTo("/api/v1/audiobooks/5/bookmarks")
            assertThat(Json.parseToJsonElement(request.bodyText()).jsonObject.toMap()).containsExactly(
                "clientId", JsonPrimitive(clientId),
                "positionMs", JsonPrimitive(600_000),
                "title", JsonPrimitive("Bookmark 1"),
            )
            assertThat(created.bookmark).isEqualTo(
                bookmark.copy(
                    createdAt = Instant.parse("2026-11-15T18:47:15.089Z"),
                    updatedAt = Instant.parse("2026-11-15T18:47:15.089Z"),
                ),
            )
        }

    @Test
    fun `a known chapter and a typed note are sent, a blank note is not`() = runBlocking<Unit> {
        server.enqueueRecorded("audiobookmarks-created")
        server.enqueueRecorded("audiobookmarks-created")

        repository.create(BookId("5"), bookmark.copy(chapterId = "ch_1", note = "Note 1"))
        val first = sentBody()
        repository.create(BookId("5"), bookmark.copy(note = "  "))
        val second = sentBody()

        assertThat(first["chapterId"]).isEqualTo(JsonPrimitive("ch_1"))
        assertThat(first["note"]).isEqualTo(JsonPrimitive("Note 1"))
        assertThat(second.keys).containsExactly("clientId", "positionMs", "title")
    }

    @Test
    fun `a title is trimmed and cut to the server's limit`() = runBlocking<Unit> {
        server.enqueueRecorded("audiobookmarks-created")

        repository.create(BookId("5"), bookmark.copy(title = "  " + "a".repeat(600)))

        assertThat(sentBody()["title"]).isEqualTo(JsonPrimitive("a".repeat(500)))
    }

    @Test
    fun `a blank title is refused without a request`() = runBlocking {
        assertThat(repository.create(BookId("5"), bookmark.copy(title = " ")))
            .isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `another bookmark at the same second is its own answer, not a failure`() = runBlocking {
        val (body, status) = wrapped("audiobookmarks-same-second")
        server.enqueueJson(body, status)

        assertThat(repository.create(BookId("5"), bookmark))
            .isEqualTo(ApiResult.Success(BookmarkCreateResult.SameSecond))
    }

    @Test
    fun `any other refusal of a new bookmark is a failure`() = runBlocking {
        server.enqueue(MockResponse(code = 400))
        server.enqueue(MockResponse(code = 500))
        server.enqueue(MockResponse(code = 404))

        assertThat(repository.create(BookId("5"), bookmark)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        assertThat(repository.create(BookId("5"), bookmark)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
        assertThat(repository.create(BookId("5"), bookmark)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `an empty answer to a new bookmark is unexpected`() = runBlocking {
        server.enqueue(MockResponse(code = 201))

        assertThat(repository.create(BookId("5"), bookmark)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    // --- list

    @Test
    fun `the list is in position order`() = runBlocking {
        server.enqueueRecorded("audiobookmarks-list")

        val list = repository.list(BookId("5")).value()

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/audiobooks/5/bookmarks")
        assertThat(list.map { it.positionMs }).containsExactly(120_000L, 600_000L).inOrder()
        assertThat(list.first().note).isEqualTo("Note 1")
        assertThat(list.last().note).isNull()
    }

    @Test
    fun `bookmarks the server gives out of order are put in order, by position then creation`() = runBlocking {
        server.enqueueJson(
            """[
              {"id":"c","bookId":5,"positionMs":9000,"title":"c","createdAt":"2026-11-15T10:00:02Z"},
              {"id":"b","bookId":5,"positionMs":9000,"title":"b","createdAt":"2026-11-15T10:00:01Z"},
              {"id":"a","bookId":5,"positionMs":1000,"title":"a"}
            ]""",
        )

        assertThat(repository.list(BookId("5")).value().map { it.id }).containsExactly("a", "b", "c").inOrder()
    }

    // --- edit, delete

    @Test
    fun `an edit sends the title and the note, and answers the bookmark`() = runBlocking {
        server.enqueueRecorded("audiobookmarks-edited")

        val edited = repository.edit(BookId("5"), clientId, "Bookmark 2", "Note 1").value()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PATCH")
        assertThat(request.target()).isEqualTo("/api/v1/audiobooks/5/bookmarks/$clientId")
        assertThat(Json.parseToJsonElement(request.bodyText()).jsonObject.toMap()).containsExactly(
            "title", JsonPrimitive("Bookmark 2"),
            "note", JsonPrimitive("Note 1"),
        )
        assertThat(edited.title).isEqualTo("Bookmark 2")
        assertThat(edited.note).isEqualTo("Note 1")
        assertThat(edited.updatedAt).isEqualTo(Instant.parse("2026-11-15T18:47:15.960Z"))
    }

    @Test
    fun `an edit without a note clears it`() = runBlocking<Unit> {
        server.enqueueRecorded("audiobookmarks-edited")
        server.enqueueRecorded("audiobookmarks-edited")

        repository.edit(BookId("5"), clientId, "Bookmark 2", null)
        val cleared = sentBody()
        repository.edit(BookId("5"), clientId, "Bookmark 2", " ")
        val blank = sentBody()

        assertThat(cleared["note"]).isEqualTo(JsonNull)
        assertThat(blank["note"]).isEqualTo(JsonNull)
    }

    @Test
    fun `a delete answers nothing`() = runBlocking {
        server.enqueue(MockResponse(code = 204))

        val result = repository.delete(BookId("5"), clientId)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.target()).isEqualTo("/api/v1/audiobooks/5/bookmarks/$clientId")
        assertThat(result).isEqualTo(ApiResult.Success(Unit))
    }

    @Test
    fun `a bookmark gone from the server is not found`() = runBlocking {
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 404))

        assertThat(repository.edit(BookId("5"), clientId, "t", null)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(repository.delete(BookId("5"), clientId)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    // --- session, network

    @Test
    fun `signed out, a call fails as an expired session`() = runBlocking {
        signedIn = false

        assertThat(repository.list(BookId("5"))).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(repository.create(BookId("5"), bookmark)).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
    }

    @Test
    fun `an unreachable server is reported as such`() = runBlocking {
        server.close()

        assertThat(repository.list(BookId("5"))).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
        assertThat(repository.create(BookId("5"), bookmark)).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
    }
}
