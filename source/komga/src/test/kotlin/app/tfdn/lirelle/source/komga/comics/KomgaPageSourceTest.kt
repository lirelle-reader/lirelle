package app.tfdn.lirelle.source.komga.comics

import app.tfdn.lirelle.core.comics.PageSink
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.KomgaJson
import app.tfdn.lirelle.source.komga.testing.enqueueJson
import app.tfdn.lirelle.source.komga.testing.serverUrl
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
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** A comic of Komga is a book, of one file: the file's id is the book's. */
private val Album = FileId("0BK1A")
private val Picture = ByteArray(200_000) { (it % 251).toByte() }

/**
 * What Komga lists of a book's pages (`PageDto`), numbered from 1, written by hand: a JPEG, a WebP, a JPEG XL, an
 * AVIF and a TIFF, each with a field the app does not read.
 */
private const val PagesJson = """[""" +
    """{"number":1,"fileName":"001.jpg","mediaType":"image/jpeg","width":1400,"height":2000,""" +
    """"sizeBytes":412000,"size":"402.3 KiB"},""" +
    """{"number":2,"fileName":"002.webp","mediaType":"image/webp","width":1400,"height":2000,""" +
    """"sizeBytes":208000,"size":"203.1 KiB"},""" +
    """{"number":3,"fileName":"003.jxl","mediaType":"image/jxl","width":1400,"height":2000,""" +
    """"sizeBytes":150000,"size":"146.5 KiB"},""" +
    """{"number":4,"fileName":"004.avif","mediaType":"image/avif","width":2800,"height":2000,""" +
    """"sizeBytes":180000,"size":"175.8 KiB"},""" +
    """{"number":5,"fileName":"005.tif","mediaType":"image/tiff","width":1400,"height":2000,""" +
    """"sizeBytes":900000,"size":"878.9 KiB"}]"""

/**
 * The pages of a Komga server's comics: numbered from 0 in the app and from 1 on Komga, asked as JPEG when the device
 * cannot draw them as they are, "not found" for a book Komga has not made ready or an account without the right to
 * read pages.
 */
class KomgaPageSourceTest {

    private val server = MockWebServer()
    private lateinit var source: KomgaPageSource
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
        url = server.serverUrl
        source = KomgaPageSource(
            api = KomgaApis(OkHttpClient(), KomgaJson, KomgaPagesApi::class.java),
            errors = KomgaErrors(),
            serverUrl = { url },
        )
    }

    @After
    fun tearDown() = server.close()

    private fun picture(bytes: ByteArray = Picture) =
        MockResponse.Builder().addHeader("Content-Type", "image/jpeg").body(Buffer().write(bytes))

    /** Every request the server had, in order. */
    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    /** The path and query as sent, for example `/api/v1/books/0BK1A/pages/3?convert=jpeg`. */
    private fun RecordedRequest.target(): String = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")

    @Test
    fun `a comic is served by pages, and a PDF and a DjVu are not`() {
        assertThat(source.servesByPages(PagedKind.Comic)).isTrue()
        assertThat(source.servesByPages(PagedKind.Pdf)).isFalse()
        assertThat(source.servesByPages(PagedKind.Djvu)).isFalse()
    }

    @Test
    fun `the count is the number of pages Komga lists of the book`(): Unit = runBlocking {
        server.enqueueJson(PagesJson)

        assertThat(source.pageCount(PagedKind.Comic, Album)).isEqualTo(ApiResult.Success(5))

        assertThat(requests().map { it.target() }).containsExactly("/api/v1/books/0BK1A/pages")
    }

    @Test
    fun `a book not ready on Komga, or one the account may not see, is not found`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 403))
        server.enqueue(MockResponse(code = 500))

        assertThat(source.pageCount(PagedKind.Comic, Album)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(source.pageCount(PagedKind.Comic, Album)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(source.pageCount(PagedKind.Comic, Album)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `a PDF and a DjVu have no pages here, and Komga is not asked`(): Unit = runBlocking {
        assertThat(source.pageCount(PagedKind.Pdf, Album)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(source.page(PagedKind.Djvu, Album, 0, sink)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(source.pageUrl(PagedKind.Pdf, Album, 0)).isNull()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(opened.get()).isEqualTo(0)
    }

    @Test
    fun `a page's bytes are written into the sink, the app's page 0 being Komga's page 1`(): Unit = runBlocking {
        server.enqueueJson(PagesJson)
        server.enqueue(picture().build())
        server.enqueue(picture(byteArrayOf(1, 2, 3)).build())

        assertThat(source.pageCount(PagedKind.Comic, Album)).isEqualTo(ApiResult.Success(5))
        assertThat(source.page(PagedKind.Comic, Album, 0, sink)).isEqualTo(ApiResult.Success(Unit))
        assertThat(written.toByteArray()).isEqualTo(Picture)
        written.reset()
        assertThat(source.page(PagedKind.Comic, Album, 1, sink)).isEqualTo(ApiResult.Success(Unit))
        assertThat(written.toByteArray()).isEqualTo(byteArrayOf(1, 2, 3))

        // A JPEG and a WebP are drawn as they are: no conversion asked.
        assertThat(requests().map { it.target() }).containsExactly(
            "/api/v1/books/0BK1A/pages",
            "/api/v1/books/0BK1A/pages/1",
            "/api/v1/books/0BK1A/pages/2",
        ).inOrder()
    }

    @Test
    fun `a page the device cannot draw as it is is asked as a JPEG, an AVIF as it is`(): Unit = runBlocking {
        server.enqueueJson(PagesJson)
        repeat(3) { server.enqueue(picture().build()) }

        assertThat(source.pageCount(PagedKind.Comic, Album)).isEqualTo(ApiResult.Success(5))
        assertThat(source.page(PagedKind.Comic, Album, 2, sink)).isEqualTo(ApiResult.Success(Unit))
        assertThat(source.page(PagedKind.Comic, Album, 3, sink)).isEqualTo(ApiResult.Success(Unit))
        assertThat(source.page(PagedKind.Comic, Album, 4, sink)).isEqualTo(ApiResult.Success(Unit))

        assertThat(requests().drop(1).map { it.target() }).containsExactly(
            "/api/v1/books/0BK1A/pages/3?convert=jpeg",
            "/api/v1/books/0BK1A/pages/4",
            "/api/v1/books/0BK1A/pages/5?convert=jpeg",
        ).inOrder()
    }

    @Test
    fun `a page asked before the count lists the pages once, to know their formats`(): Unit = runBlocking {
        server.enqueueJson(PagesJson)
        server.enqueue(picture().build())
        server.enqueue(picture().build())

        assertThat(source.page(PagedKind.Comic, Album, 2, sink)).isEqualTo(ApiResult.Success(Unit))
        assertThat(source.page(PagedKind.Comic, Album, 0, sink)).isEqualTo(ApiResult.Success(Unit))

        assertThat(requests().map { it.target() }).containsExactly(
            "/api/v1/books/0BK1A/pages",
            "/api/v1/books/0BK1A/pages/3?convert=jpeg",
            "/api/v1/books/0BK1A/pages/1",
        ).inOrder()
    }

    @Test
    fun `the count and pages asked together at an opening list the pages once`(): Unit = runBlocking {
        val listed = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath == "/api/v1/books/0BK1A/pages") {
                    listed.incrementAndGet()
                    // Long enough for every caller to be waiting on the one listing.
                    MockResponse.Builder().addHeader("Content-Type", "application/json").body(PagesJson)
                        .headersDelay(500, TimeUnit.MILLISECONDS).build()
                } else {
                    picture(byteArrayOf(7)).build()
                }
        }
        val count = async(Dispatchers.IO) { source.pageCount(PagedKind.Comic, Album) }
        val pages = List(3) { index ->
            async(Dispatchers.IO) { source.page(PagedKind.Comic, Album, index, PageSink { ByteArrayOutputStream() }) }
        }

        assertThat(count.await()).isEqualTo(ApiResult.Success(5))
        pages.forEach { assertThat(it.await()).isEqualTo(ApiResult.Success(Unit)) }
        assertThat(listed.get()).isEqualTo(1)
    }

    @Test
    fun `a count asked at a later opening lists the pages again, in case the book changed`(): Unit = runBlocking {
        server.enqueueJson(PagesJson)
        // Its last page taken out of the book meanwhile.
        server.enqueueJson(PagesJson.substringBeforeLast(",{") + "]")

        assertThat(source.pageCount(PagedKind.Comic, Album)).isEqualTo(ApiResult.Success(5))
        assertThat(source.pageCount(PagedKind.Comic, Album)).isEqualTo(ApiResult.Success(4))
    }

    @Test
    fun `the formats the reading writes places with are those kept, listed once when none are`(): Unit =
        runBlocking {
            server.enqueueJson(PagesJson)

            assertThat(source.formats(Album)).hasSize(5)
            assertThat(source.formats(Album)[2]).isEqualTo("image/jxl")
            assertThat(requests().map { it.target() }).containsExactly("/api/v1/books/0BK1A/pages")
        }

    @Test
    fun `a page past the end is not found, and not asked`(): Unit = runBlocking {
        server.enqueueJson(PagesJson)

        assertThat(source.page(PagedKind.Comic, Album, 5, sink)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(source.page(PagedKind.Comic, Album, -1, sink)).isEqualTo(ApiResult.Failure(ApiError.NotFound))

        assertThat(requests().map { it.target() }).containsExactly("/api/v1/books/0BK1A/pages")
        assertThat(opened.get()).isEqualTo(0)
    }

    @Test
    fun `a page of a book not ready, refused, or failing opens nothing and says why`(): Unit = runBlocking {
        server.enqueueJson(PagesJson)
        // Komga no longer has the book ready, then the account lacks the right to read pages, then it fails.
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 403))
        server.enqueue(MockResponse(code = 500))

        assertThat(source.page(PagedKind.Comic, Album, 0, sink)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(source.page(PagedKind.Comic, Album, 0, sink)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(source.page(PagedKind.Comic, Album, 0, sink)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
        assertThat(opened.get()).isEqualTo(0)
    }

    @Test
    fun `a page whose list cannot be had says why the list could not`(): Unit = runBlocking {
        server.enqueue(MockResponse(code = 404))

        assertThat(source.page(PagedKind.Comic, Album, 0, sink)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(opened.get()).isEqualTo(0)
    }

    @Test
    fun `a sink the device cannot open, or write, is the device's failure`(): Unit = runBlocking {
        server.enqueueJson(PagesJson)
        server.enqueue(picture().build())
        server.enqueue(picture().build())
        val unopened = PageSink { throw IOException("No folder") }
        val full = PageSink {
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("ENOSPC")
            }
        }

        assertThat(source.page(PagedKind.Comic, Album, 0, unopened)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        assertThat(source.page(PagedKind.Comic, Album, 0, full)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    @Test
    fun `a connection lost on the way is the server out of reach`(): Unit = runBlocking {
        server.enqueueJson(PagesJson)
        server.enqueue(picture(ByteArray(4_000_000)).onResponseBody(SocketEffect.ShutdownConnection).build())

        assertThat(source.page(PagedKind.Comic, Album, 0, sink))
            .isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
    }

    @Test
    fun `a fetch given up stops waiting for the bytes of its picture`(): Unit = runBlocking {
        server.enqueueJson(PagesJson)
        server.enqueue(picture().bodyDelay(20, TimeUnit.SECONDS).build())
        val begun = CompletableDeferred<Unit>()
        val waiting = PageSink {
            begun.complete(Unit)
            written
        }

        val fetch = async(Dispatchers.IO) { source.page(PagedKind.Comic, Album, 0, waiting) }
        withTimeout(5.seconds) { begun.await() }

        // Long before the server sends the picture.
        withTimeout(5.seconds) { fetch.cancelAndJoin() }
        assertThat(fetch.isCancelled).isTrue()
        assertThat(written.size()).isEqualTo(0)
    }

    @Test
    fun `a fetch given up before the server answers stops waiting, and opens nothing`(): Unit = runBlocking {
        server.enqueueJson(PagesJson)
        server.enqueue(picture().headersDelay(20, TimeUnit.SECONDS).build())

        val fetch = async(Dispatchers.IO) { source.page(PagedKind.Comic, Album, 0, sink) }
        // The list, then the page, which the server holds its answer to.
        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "The pages were not listed" }
        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "The page was not asked for" }

        withTimeout(5.seconds) { fetch.cancelAndJoin() }
        assertThat(fetch.isCancelled).isTrue()
        assertThat(opened.get()).isEqualTo(0)
    }

    @Test
    fun `a page's address is Komga's page, numbered from 1, as a JPEG when the device needs one`(): Unit =
        runBlocking {
            // Before the formats are known, the page as it is.
            assertThat(source.pageUrl(PagedKind.Comic, Album, 2)).isEqualTo("$url/api/v1/books/0BK1A/pages/3")

            server.enqueueJson(PagesJson)
            assertThat(source.pageCount(PagedKind.Comic, Album)).isEqualTo(ApiResult.Success(5))

            assertThat(source.pageUrl(PagedKind.Comic, Album, 0)).isEqualTo("$url/api/v1/books/0BK1A/pages/1")
            assertThat(source.pageUrl(PagedKind.Comic, Album, 2))
                .isEqualTo("$url/api/v1/books/0BK1A/pages/3?convert=jpeg")
            assertThat(source.pageUrl(PagedKind.Djvu, Album, 0)).isNull()
        }

    @Test
    fun `a page's address keeps the base path of a server behind one`() {
        url = "https://media.example.com/komga"

        assertThat(source.pageUrl(PagedKind.Comic, Album, 0))
            .isEqualTo("https://media.example.com/komga/api/v1/books/0BK1A/pages/1")
    }

    @Test
    fun `a page's format is read whatever its case and parameters`() {
        assertThat(needsJpeg("IMAGE/JXL")).isTrue()
        assertThat(needsJpeg("image/jpeg; q=0.9")).isFalse()
        assertThat(needsJpeg("image/png")).isFalse()
        assertThat(needsJpeg("image/gif")).isFalse()
        assertThat(needsJpeg("image/heif")).isFalse()
        assertThat(needsJpeg("")).isTrue()
    }
}
