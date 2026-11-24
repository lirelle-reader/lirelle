package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.library.testing.enqueueJson
import app.tfdn.lirelle.source.bookorbit.library.testing.enqueueRecorded
import app.tfdn.lirelle.source.bookorbit.library.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.library.testing.target
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class DefaultReadAlongRepositoryTest {

    private val server = MockWebServer()
    private var signedIn = true
    private lateinit var repository: DefaultReadAlongRepository

    @Before
    fun setUp() {
        server.start()
        repository = DefaultReadAlongRepository(
            api = ApiProvider(OkHttpClient(), ApiJson, ReadAlongApi::class.java),
            errors = ErrorMapper(ApiJson),
            serverUrl = { if (signedIn) server.serverUrl else null },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun <T> ApiResult<T>.value(): T = (this as ApiResult.Success).value

    /** Runs what it is given at once, counting it: where the work was sent. */
    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatched = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            block.run()
        }
    }

    @Test
    fun `the answers are turned into the model off the caller's thread`() = runBlocking {
        val compute = CountingDispatcher()
        val offMain = DefaultReadAlongRepository(
            api = ApiProvider(OkHttpClient(), ApiJson, ReadAlongApi::class.java),
            errors = ErrorMapper(ApiJson),
            serverUrl = { server.serverUrl },
            compute = compute,
        )
        server.enqueueRecorded("readalong-map-gap")

        offMain.playlist(BookId("3"), FileId("4")).value()

        assertThat(compute.dispatched).isGreaterThan(0)
    }

    @Test
    fun `the playlist of a recorded map has times in milliseconds, labels and its resources`() = runBlocking {
        server.enqueueRecorded("readalong-map-gap")

        val playlist = repository.playlist(BookId("3"), FileId("4")).value()

        val request = server.takeRequest()
        assertThat(request.target()).isEqualTo("/api/v1/epub/3/media-overlay?fileId=4")
        assertThat(playlist.bookId).isEqualTo(BookId("3"))
        assertThat(playlist.fileId).isEqualTo(FileId("4"))
        assertThat(playlist.durationMs).isEqualTo(29_575_120)
        assertThat(playlist.items).hasSize(640)
        val first = playlist.items.first()
        assertThat(first.sectionIndex).isEqualTo(4)
        assertThat(first.textHref).isEqualTo("OEBPS/Text/section004.xhtml")
        assertThat(first.fragment).isEqualTo("p004-s0")
        assertThat(first.audioHref).isEqualTo("OEBPS/Audio/track001.mp4")
        assertThat(first.clipBeginMs).isEqualTo(0)
        assertThat(first.clipEndMs).isEqualTo(24_300)
        assertThat(playlist.sections.map { it.label }.take(2)).containsExactly("Chapter 4", "Chapter 5").inOrder()
        assertThat(playlist.sections[1].index).isEqualTo(6)
        assertThat(playlist.sections[1].href).isEqualTo("OEBPS/Text/section005.xhtml")
        assertThat(playlist.resources.first().href).isEqualTo("OEBPS/Audio/track001.mp4")
        assertThat(playlist.resources.first().mediaType).isEqualTo("audio/mp4")
        assertThat(playlist.resources.first().sizeBytes).isEqualTo(110_643_109)
    }

    @Test
    fun `a sentence without a clip end or a fragment, and a section without a label, read as null`() = runBlocking {
        server.enqueueJson(
            """{"bookId":1,"fileId":2,"durationSeconds":null,
            "items":[{"index":0,"sectionIndex":1,"smilHref":"s.smil","textHref":"t.xhtml","textFragment":null,
              "audioHref":"a.mp3","audioMimeType":"audio/mpeg","clipBeginSeconds":1.5,"clipEndSeconds":null,
              "durationSeconds":0,"label":null}],
            "sections":[{"index":1,"href":"t.xhtml","label":null,"smilHref":"s.smil","startSeconds":0,
              "durationSeconds":1}],
            "resources":[{"href":"a.mp3","mediaType":"audio/mpeg"}]}""",
        )

        val playlist = repository.playlist(BookId("1"), FileId("2")).value()

        assertThat(playlist.durationMs).isNull()
        assertThat(playlist.items.single().clipBeginMs).isEqualTo(1500)
        assertThat(playlist.items.single().clipEndMs).isNull()
        assertThat(playlist.items.single().fragment).isNull()
        assertThat(playlist.sections.single().label).isNull()
        assertThat(playlist.resources.single().sizeBytes).isNull()
    }

    @Test
    fun `every recorded map reads`() = runBlocking {
        for (name in listOf("m4b", "mp3", "gap", "unclipped", "after")) {
            server.enqueueRecorded("readalong-map-$name")
            assertThat(repository.playlist(BookId("1"), FileId("2"))).isInstanceOf(ApiResult.Success::class.java)
        }
    }

    @Test
    fun `every recorded info reads into a spine`() = runBlocking {
        for (name in listOf("m4b", "mp3", "gap", "unclipped", "after")) {
            server.enqueueRecorded("readalong-info-$name")
            assertThat(repository.spine(BookId("1"), FileId("2")).value()).isNotEmpty()
        }
    }

    @Test
    fun `the spine has the place of each file and its size from the manifest`() = runBlocking {
        server.enqueueRecorded("readalong-info-gap")

        val spine = repository.spine(BookId("3"), FileId("4")).value()

        assertThat(server.takeRequest().target()).isEqualTo("/api/v1/epub/3/info?fileId=4")
        assertThat(spine).hasSize(33)
        assertThat(spine.map { it.index }).containsExactlyElementsIn(0..32).inOrder()
        assertThat(spine.first().href).isEqualTo("OEBPS/Text/section058.xhtml")
        assertThat(spine.first().sizeBytes).isEqualTo(623)
    }

    @Test
    fun `a spine entry missing from the manifest has no size`() = runBlocking {
        server.enqueueJson(
            """{"spine":[{"idref":"x","href":"a.xhtml","mediaType":"application/xhtml+xml","linear":true}],
            "manifest":[{"id":"y","href":"b","mediaType":"m","size":5}],"toc":[]}""",
        )

        assertThat(repository.spine(BookId("1"), FileId("2")).value().single().sizeBytes).isNull()
    }

    @Test
    fun `a track url encodes each segment once and keeps the slashes`() {
        assertThat(repository.trackUrl(BookId("3"), FileId("4"), "OEBPS/Audio/00001-00001.mp4"))
            .isEqualTo("${server.serverUrl}/api/v1/epub/3/media-overlay/file/OEBPS/Audio/00001-00001.mp4?fileId=4")
        assertThat(repository.trackUrl(BookId("3"), FileId("4"), "OEBPS/Audio/a b%c é.mp3"))
            .isEqualTo(
                "${server.serverUrl}/api/v1/epub/3/media-overlay/file/OEBPS/Audio/a%20b%25c%20%C3%A9.mp3?fileId=4",
            )
    }

    @Test
    fun `there is no track url when signed out`() {
        signedIn = false

        assertThat(repository.trackUrl(BookId("3"), FileId("4"), "a.mp3")).isNull()
    }

    @Test
    fun `signed out, the calls fail as an expired session`() = runBlocking {
        signedIn = false

        assertThat(repository.playlist(BookId("1"), FileId("2"))).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(repository.spine(BookId("1"), FileId("2"))).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
    }

    @Test
    fun `a server error is mapped like the rest of the library`() = runBlocking {
        server.enqueueJson("""{"message":"boom"}""", code = 500)

        assertThat(repository.playlist(BookId("1"), FileId("2"))).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }
}
