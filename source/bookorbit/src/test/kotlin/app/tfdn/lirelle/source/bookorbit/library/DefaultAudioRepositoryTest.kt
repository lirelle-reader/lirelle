package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class DefaultAudioRepositoryTest {

    private val server = MockWebServer()
    private var signedIn = true
    private lateinit var repository: DefaultAudioRepository

    private val revision = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val write = PlaybackWrite(
        assetId = "aud_2",
        positionMs = 600_000,
        capturedAt = Instant.parse("2026-11-15T09:00:00Z"),
        operationId = "6f1b8f0e-0000-4000-8000-000000000001",
        baseVersion = null,
        contentVersion = revision,
    )

    @Before
    fun setUp() {
        server.start()
        repository = DefaultAudioRepository(
            api = ApiProvider(OkHttpClient(), ApiJson, AudioApi::class.java),
            errors = ErrorMapper(ApiJson),
            serverUrl = { if (signedIn) server.serverUrl else null },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun <T> ApiResult<T>.value(): T = (this as ApiResult.Success).value

    private fun wrapped(name: String): String = Json.parseToJsonElement(Fixtures.recorded(name)).jsonObject.let {
        it.getValue("body").toString()
    }

    private fun status(name: String): Int =
        (Json.parseToJsonElement(Fixtures.recorded(name)).jsonObject.getValue("status") as JsonPrimitive)
            .content.toInt()

    // --- manifest

    @Test
    fun `the manifest of a single-file book`() = runBlocking {
        server.enqueueRecorded("audio-manifest-m4b")

        val manifest = repository.manifest(BookId("1")).value()

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/audiobooks/1/manifest")
        assertThat(manifest.bookId).isEqualTo(BookId("1"))
        assertThat(manifest.title).isEqualTo("Book 1")
        assertThat(manifest.narrators).containsExactly("Narrator 1")
        assertThat(manifest.hasCover).isTrue()
        assertThat(manifest.contentVersion).isEqualTo(revision)
        assertThat(manifest.assets.single().fileId).isEqualTo(FileId("3"))
        assertThat(manifest.chapters.first().assetOffsetMs).isEqualTo(0)
        assertThat(manifest.chapters.map { it.sequence }).isInOrder()
        assertThat(manifest.totalDurationMs).isEqualTo(38_190_000)
    }

    @Test
    fun `the manifest of an mp3 book has one asset per file and chapters in them`() = runBlocking {
        server.enqueueRecorded("audio-manifest-mp3")

        val manifest = repository.manifest(BookId("48")).value()

        assertThat(manifest.assets.map { it.format }.distinct()).containsExactly("mp3")
        assertThat(manifest.assets.map { it.sequence }).isInOrder()
        assertThat(manifest.chapters.map { it.assetId }.toSet()).isEqualTo(manifest.assets.map { it.assetId }.toSet())
        assertThat(manifest.totalDurationMs).isEqualTo(51_750_000)
    }

    @Test
    fun `a duration the server does not know, or gives as zero, is unknown`() = runBlocking {
        val body = Json.parseToJsonElement(Fixtures.recorded("audio-manifest-mp3")).jsonObject
        val assets = body.getValue("assets").jsonArray.mapIndexed { index, asset ->
            val duration = when (index) {
                0 -> JsonNull
                1 -> JsonPrimitive(0)
                else -> asset.jsonObject.getValue("durationMs")
            }
            JsonObject(asset.jsonObject + ("durationMs" to duration))
        }
        server.enqueueJson(JsonObject(body + ("assets" to JsonArray(assets))).toString())

        val durations = repository.manifest(BookId("48")).value().assets.map { it.durationMs }

        assertThat(durations.take(2)).containsExactly(null, null)
        assertThat(durations.drop(2).all { it != null && it > 0 }).isTrue()
    }

    @Test
    fun `a book without audio is not found`() = runBlocking {
        server.enqueue(MockResponse(code = 404))

        assertThat(repository.manifest(BookId("1"))).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `signed out, a call fails as an expired session`() = runBlocking {
        signedIn = false

        assertThat(repository.manifest(BookId("1"))).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(repository.assetUrl(BookId("1"), "aud_2")).isNull()
    }

    @Test
    fun `an unreachable server is reported as such`() = runBlocking {
        server.close()

        assertThat(repository.playbackState(BookId("1"))).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
    }

    @Test
    fun `an asset url is built from the session's server`() {
        assertThat(repository.assetUrl(BookId("1"), "aud_2"))
            .isEqualTo("${server.serverUrl}/api/v1/audiobooks/1/assets/aud_2/content")
    }

    // --- playback state, read

    @Test
    fun `no state, as recorded, is null`() = runBlocking {
        server.enqueueJson(wrapped("audio-playback-none"))

        assertThat(repository.playbackState(BookId("1")).value()).isNull()
    }

    @Test
    fun `an empty body is no state either`() = runBlocking {
        server.enqueue(MockResponse(code = 200))

        assertThat(repository.playbackState(BookId("1")).value()).isNull()
    }

    @Test
    fun `a stored state`() = runBlocking {
        server.enqueueRecorded("audio-playback")

        val state = repository.playbackState(BookId("1")).value()!!

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/audiobooks/1/playback-state")
        assertThat(state.assetId).isEqualTo("aud_2")
        assertThat(state.positionMs).isEqualTo(900_000)
        // BookOrbit's revision 2, as the version the app compares for equality only.
        assertThat(state.version).isEqualTo("2")
        assertThat(state.completed).isFalse()
        assertThat(state.capturedAt).isEqualTo(Instant.parse("2026-11-15T09:00:00Z"))
        assertThat(state.contentVersion).isEqualTo(revision)
    }

    // --- playback state, write

    @Test
    fun `a write over no version creates the state with all six fields, and answers it`() = runBlocking {
        server.enqueueRecorded("audio-playback-created")

        val result = repository.savePlaybackState(BookId("1"), write).value() as PlaybackWriteResult.Saved

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.target()).isEqualTo("/api/v1/audiobooks/1/playback-state")
        assertThat(Json.parseToJsonElement(request.bodyText())).isEqualTo(
            JsonObject(
                mapOf(
                    "assetId" to JsonPrimitive("aud_2"),
                    "positionMs" to JsonPrimitive(600_000),
                    "capturedAt" to JsonPrimitive("2026-11-15T09:00:00Z"),
                    "operationId" to JsonPrimitive(write.operationId),
                    "baseRevision" to JsonPrimitive(0),
                    "manifestRevision" to JsonPrimitive(revision),
                ),
            ),
        )
        assertThat(result.state.version).isEqualTo("1")
        assertThat(result.state.positionMs).isEqualTo(600_000)
    }

    @Test
    fun `a write names the version it was made over as its base revision`() = runBlocking {
        server.enqueueRecorded("audio-playback-created")

        repository.savePlaybackState(BookId("1"), write.copy(baseVersion = "7"))

        val body = Json.parseToJsonElement(server.takeRequest().bodyText()).jsonObject
        assertThat(body.getValue("baseRevision")).isEqualTo(JsonPrimitive(7))
    }

    @Test
    fun `a write made over the state read from the server is resent on the server's revision`() = runBlocking {
        // After an overtaken write, the app reads the state and makes the write again over its version.
        server.enqueueRecorded("audio-playback")
        server.enqueueRecorded("audio-playback-created")
        val read = repository.playbackState(BookId("1")).value()!!
        server.takeRequest()

        repository.savePlaybackState(BookId("1"), write.copy(baseVersion = read.version))

        val body = Json.parseToJsonElement(server.takeRequest().bodyText()).jsonObject
        assertThat(body.getValue("baseRevision")).isEqualTo(JsonPrimitive(2))
    }

    @Test
    fun `a version that is not a BookOrbit revision is a bug, never sent`() = runBlocking {
        assertThat(repository.savePlaybackState(BookId("1"), write.copy(baseVersion = "v7")))
            .isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a stale revision, a 409, is overtaken, not a failure`() = runBlocking {
        server.enqueueJson(wrapped("audio-playback-conflict"), status("audio-playback-conflict"))

        assertThat(repository.savePlaybackState(BookId("1"), write))
            .isEqualTo(ApiResult.Success(PlaybackWriteResult.Overtaken))
    }

    @Test
    fun `a changed manifest, a 412, is a content change`() = runBlocking {
        server.enqueueJson(wrapped("audio-playback-manifest-changed"), status("audio-playback-manifest-changed"))

        assertThat(repository.savePlaybackState(BookId("1"), write))
            .isEqualTo(ApiResult.Success(PlaybackWriteResult.ContentChanged))
    }

    @Test
    fun `any other refusal of a write is a failure`() = runBlocking {
        server.enqueue(MockResponse(code = 400))
        server.enqueue(MockResponse(code = 500))
        server.enqueue(MockResponse(code = 401))

        assertThat(repository.savePlaybackState(BookId("1"), write)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        assertThat(repository.savePlaybackState(BookId("1"), write)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
        assertThat(repository.savePlaybackState(BookId("1"), write))
            .isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
    }

    @Test
    fun `an empty answer to a write is unexpected, neither saved nor a conflict`() = runBlocking {
        server.enqueue(MockResponse(code = 200))

        assertThat(repository.savePlaybackState(BookId("1"), write)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    // --- sessions, next volume

    private fun report(activeSeconds: Int, delta: Double?, end: Double?) = ReadingSessionReport(
        sessionId = "s-1",
        startedAt = Instant.parse("2026-11-15T09:00:00Z"),
        endedAt = Instant.parse("2026-11-15T09:10:00Z"),
        activeSeconds = activeSeconds,
        progressDelta = delta,
        endPercent = end,
    )

    @Test
    fun `a session without progress leaves both fields out`() = runBlocking<Unit> {
        server.enqueue(MockResponse(code = 204))

        repository.saveListeningSession(FileId("3"), report(60, delta = null, end = null))

        val body = Json.parseToJsonElement(server.takeRequest().bodyText()).jsonObject
        assertThat(body.keys).containsExactly(
            "sessionId", "startedAt", "endedAt", "durationSeconds", "sessionType", "source",
        )
    }

    @Test
    fun `a session clamps the end percentage and a negative active time`() = runBlocking<Unit> {
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 204))

        repository.saveListeningSession(FileId("3"), report(-5, delta = 0.0, end = 140.0))
        repository.saveListeningSession(FileId("3"), report(5, delta = 0.0, end = -3.0))

        val first = Json.parseToJsonElement(server.takeRequest().bodyText()).jsonObject
        val second = Json.parseToJsonElement(server.takeRequest().bodyText()).jsonObject
        assertThat(first.getValue("durationSeconds")).isEqualTo(JsonPrimitive(0))
        assertThat(first.getValue("endProgress")).isEqualTo(JsonPrimitive(100.0))
        assertThat(second.getValue("endProgress")).isEqualTo(JsonPrimitive(0.0))
    }

    @Test
    fun `a listening session carries its type and the source, and the 2a fields`() = runBlocking<Unit> {
        server.enqueue(MockResponse(code = 204))

        val result = repository.saveListeningSession(
            FileId("3"),
            ReadingSessionReport(
                sessionId = "s-1",
                startedAt = Instant.parse("2026-11-15T09:00:00Z"),
                endedAt = Instant.parse("2026-11-15T09:10:00Z"),
                activeSeconds = 590,
                progressDelta = 1.5,
                endPercent = 12.0,
            ),
        )

        val request = server.takeRequest()
        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        assertThat(request.target()).isEqualTo("/api/v1/books/files/3/sessions")
        assertThat(Json.parseToJsonElement(request.bodyText()).jsonObject.toMap()).containsExactly(
            "sessionId", JsonPrimitive("s-1"),
            "startedAt", JsonPrimitive("2026-11-15T09:00:00Z"),
            "endedAt", JsonPrimitive("2026-11-15T09:10:00Z"),
            "durationSeconds", JsonPrimitive(590),
            "progressDelta", JsonPrimitive(1.5),
            "endProgress", JsonPrimitive(12.0),
            "sessionType", JsonPrimitive("listen"),
            "source", JsonPrimitive("android"),
        )
    }

    @Test
    fun `the next audio volume asks for the audio format group`() = runBlocking {
        server.enqueueRecorded("audio-next-volume")

        val next = repository.nextAudioVolume(SeriesId("170"), BookId("1")).value()

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/series/170/books/1/next?formatGroup=audio")
        assertThat(next).isEqualTo(NextVolume(BookId("173"), "Book 87", "2"))
    }

    @Test
    fun `no next audio volume is null`() = runBlocking {
        server.enqueueJson("""{"next":null}""")

        assertThat(repository.nextAudioVolume(SeriesId("170"), BookId("1")).value()).isNull()
    }
}
