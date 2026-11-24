package app.tfdn.lirelle.source.bookorbit.comics

import app.tfdn.lirelle.core.comics.PageSink
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

private val AlbumFile = FileId("7")
private val Picture = ByteArray(200_000) { (it % 251).toByte() }

/** The pages of a BookOrbit server's comics and DjVus: under the routes of each, `cbz` and `djvu`. */
class BookOrbitPageSourceTest {

    private val server = MockWebServer()
    private var signedIn = true
    private lateinit var source: BookOrbitPageSource
    private lateinit var url: String

    /** What the sink was given, and how many times it was opened. */
    private val written = ByteArrayOutputStream()
    private val opened = AtomicInteger()
    private val sink = PageSink {
        opened.incrementAndGet()
        written
    }

    @Before
    fun setUp() {
        server.start()
        url = server.url("/").toString().trimEnd('/')
        source = BookOrbitPageSource(
            api = ApiProvider(OkHttpClient(), ApiJson, ComicsApi::class.java),
            errors = ErrorMapper(ApiJson),
            serverUrl = { url.takeIf { signedIn } },
        )
    }

    @After
    fun tearDown() = server.close()

    private fun picture(bytes: ByteArray = Picture) =
        MockResponse.Builder().addHeader("Content-Type", "image/jpeg").body(Buffer().write(bytes))

    private fun json(body: String, code: Int) =
        MockResponse(code = code, headers = headersOf("Content-Type", "application/json"), body = body)

    private fun paths(): List<String> = List(server.requestCount) { server.takeRequest().url.encodedPath }

    @Test
    fun `a comic and a DjVu are served by pages, and a PDF is not`() {
        assertThat(source.servesByPages(PagedKind.Comic)).isTrue()
        assertThat(source.servesByPages(PagedKind.Djvu)).isTrue()
        assertThat(source.servesByPages(PagedKind.Pdf)).isFalse()
    }

    @Test
    fun `the count is asked through the routes of the book's kind`(): Unit = runBlocking {
        server.enqueue(json("""{"pageCount":8}""", 200))
        server.enqueue(json("""{"pageCount":3}""", 200))

        assertThat(source.pageCount(PagedKind.Comic, AlbumFile)).isEqualTo(ApiResult.Success(8))
        assertThat(source.pageCount(PagedKind.Djvu, FileId("9"))).isEqualTo(ApiResult.Success(3))

        assertThat(paths()).containsExactly("/api/v1/cbz/files/7/pages", "/api/v1/djvu/files/9/pages").inOrder()
    }

    @Test
    fun `a PDF has no count of pages on the server, and a count that fails says why`(): Unit = runBlocking {
        assertThat(source.pageCount(PagedKind.Pdf, AlbumFile)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(server.requestCount).isEqualTo(0)

        server.enqueue(MockResponse(code = 500))

        assertThat(source.pageCount(PagedKind.Comic, AlbumFile)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `with no session nothing is asked, and the session is said to have ended`(): Unit = runBlocking {
        signedIn = false

        assertThat(source.pageCount(PagedKind.Comic, AlbumFile)).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(source.page(PagedKind.Comic, AlbumFile, 0, sink))
            .isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(source.pageUrl(PagedKind.Comic, AlbumFile, 0)).isNull()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(opened.get()).isEqualTo(0)
    }

    @Test
    fun `a page's bytes are written into the sink, through the routes of the book's kind`(): Unit = runBlocking {
        server.enqueue(picture().build())
        server.enqueue(picture(byteArrayOf(1, 2, 3)).build())

        assertThat(source.page(PagedKind.Djvu, AlbumFile, 3, sink)).isEqualTo(ApiResult.Success(Unit))
        assertThat(written.toByteArray()).isEqualTo(Picture)
        written.reset()
        assertThat(source.page(PagedKind.Comic, AlbumFile, 0, sink)).isEqualTo(ApiResult.Success(Unit))
        assertThat(written.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3))

        assertThat(paths()).containsExactly("/api/v1/djvu/files/7/pages/3", "/api/v1/cbz/files/7/pages/0").inOrder()
    }

    @Test
    fun `a refusal opens nothing, a page past the end not found, a failure the server's`(): Unit =
        runBlocking {
            server.enqueue(json("""{"statusCode":404,"message":"Page not found"}""", 404))
            server.enqueue(MockResponse(code = 500))

            assertThat(source.page(PagedKind.Comic, AlbumFile, 8, sink)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
            assertThat(source.page(PagedKind.Comic, AlbumFile, 1, sink))
                .isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
            assertThat(opened.get()).isEqualTo(0)
        }

    @Test
    fun `a PDF's page is not asked, the server serving a PDF whole`(): Unit = runBlocking {
        assertThat(source.page(PagedKind.Pdf, AlbumFile, 0, sink)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a sink the device cannot open, or write, is the device's failure`(): Unit = runBlocking {
        server.enqueue(picture().build())
        server.enqueue(picture().build())
        val unopened = PageSink { throw IOException("No folder") }
        val full = PageSink {
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("ENOSPC")
            }
        }

        assertThat(source.page(PagedKind.Comic, AlbumFile, 0, unopened))
            .isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        assertThat(source.page(PagedKind.Comic, AlbumFile, 0, full)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    @Test
    fun `a connection lost on the way is the server out of reach`(): Unit = runBlocking {
        server.enqueue(picture(ByteArray(4_000_000)).onResponseBody(SocketEffect.ShutdownConnection).build())

        assertThat(source.page(PagedKind.Comic, AlbumFile, 0, sink))
            .isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
    }

    @Test
    fun `a fetch given up stops waiting for the bytes of its picture`(): Unit = runBlocking {
        server.enqueue(picture().bodyDelay(20, TimeUnit.SECONDS).build())
        val begun = CompletableDeferred<Unit>()
        val waiting = PageSink {
            begun.complete(Unit)
            written
        }

        val fetch = async(Dispatchers.IO) { source.page(PagedKind.Comic, AlbumFile, 0, waiting) }
        withTimeout(5.seconds) { begun.await() }

        // Long before the server sends the picture.
        withTimeout(5.seconds) { fetch.cancelAndJoin() }
        assertThat(fetch.isCancelled).isTrue()
        assertThat(written.size()).isEqualTo(0)
    }

    @Test
    fun `a fetch given up before the server answers stops waiting, and opens nothing`(): Unit = runBlocking {
        server.enqueue(picture().headersDelay(20, TimeUnit.SECONDS).build())

        val fetch = async(Dispatchers.IO) { source.page(PagedKind.Comic, AlbumFile, 0, sink) }
        // The request has reached the server, which holds its answer.
        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "The page was not asked for" }

        withTimeout(5.seconds) { fetch.cancelAndJoin() }
        assertThat(fetch.isCancelled).isTrue()
        assertThat(opened.get()).isEqualTo(0)
    }

    @Test
    fun `a page's address is under the routes of the book's kind, and a PDF's page has none`() {
        assertThat(source.pageUrl(PagedKind.Comic, AlbumFile, 3)).isEqualTo("$url/api/v1/cbz/files/7/pages/3")
        assertThat(source.pageUrl(PagedKind.Djvu, FileId("31"), 0)).isEqualTo("$url/api/v1/djvu/files/31/pages/0")
        assertThat(source.pageUrl(PagedKind.Pdf, AlbumFile, 0)).isNull()
    }
}
