package app.tfdn.lirelle.source.kavita.comics

import app.tfdn.lirelle.core.comics.PageSink
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import app.tfdn.lirelle.source.kavita.testing.KavitaRoutes
import app.tfdn.lirelle.source.kavita.testing.TheKey
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** A chapter of the test server: library 1, series 7, volume 7, chapter 44, file 44. */
private val Album = FileId("1-7-7-44-44")
private val Picture = ByteArray(200_000) { (it % 251).toByte() }

/** The pages of a Kavita server's comics: numbered from 0 as in the app, asked with the key in their address. */
class KavitaPageSourceTest {

    private val server = MockWebServer()
    private lateinit var routes: KavitaRoutes
    private lateinit var source: KavitaPageSource

    private val written = ByteArrayOutputStream()
    private val sink = PageSink { written }

    @Before
    fun setUp() {
        server.start()
        routes = KavitaRoutes(server)
        source = KavitaPageSource(routes.readingApi(), KavitaErrors()) { routes.serverUrl }
    }

    @After
    fun tearDown() = server.close()

    private fun page(index: Int = 0, kind: PagedKind = PagedKind.Comic, file: FileId = Album) =
        runBlocking { source.page(kind, file, index, sink) }

    @Test
    fun `only comics are read by pages`() {
        assertThat(source.servesByPages(PagedKind.Comic)).isTrue()
        assertThat(source.servesByPages(PagedKind.Pdf)).isFalse()
        assertThat(source.servesByPages(PagedKind.Djvu)).isFalse()
    }

    @Test
    fun `the page count is the chapter's`() {
        routes.answer("GET /api/Reader/chapter-info", """{"pages":53}""")

        val count = runBlocking { source.pageCount(PagedKind.Comic, Album) }

        assertThat(count).isEqualTo(ApiResult.Success(53))
        val request = routes.requests().single()
        assertThat(request.url.queryParameter("chapterId")).isEqualTo("44")
    }

    @Test
    fun `a page is written into the sink, asked with the key in its address`() {
        routes.answer("GET /api/Reader/image") {
            MockResponse.Builder().addHeader("Content-Type", "image/jpeg").body(Buffer().write(Picture)).build()
        }

        val result = page(index = 0)

        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        assertThat(written.toByteArray()).isEqualTo(Picture)
        val request = routes.requests().single()
        assertThat(request.url.queryParameter("chapterId")).isEqualTo("44")
        assertThat(request.url.queryParameter("page")).isEqualTo("0")
        assertThat(request.url.queryParameter("apiKey")).isEqualTo(TheKey)
    }

    @Test
    fun `a sink that cannot be closed is the device's failure, not the network's`() {
        routes.answer("GET /api/Reader/image") {
            MockResponse.Builder().addHeader("Content-Type", "image/jpeg").body(Buffer().write(Picture)).build()
        }
        val closing = PageSink {
            object : OutputStream() {
                override fun write(b: Int) = Unit

                override fun close() = throw IOException("cannot close")
            }
        }

        val result = runBlocking { source.page(PagedKind.Comic, Album, 0, closing) }

        assertThat(result).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    @Test
    fun `a sink that cannot be opened is the device's failure, not the network's`() {
        routes.answer("GET /api/Reader/image") {
            MockResponse.Builder().addHeader("Content-Type", "image/jpeg").body(Buffer().write(Picture)).build()
        }

        val result = runBlocking { source.page(PagedKind.Comic, Album, 0, { throw IOException("full") }) }

        assertThat(result).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    @Test
    fun `a failed copy is told rather than the close that follows it`() {
        routes.answer("GET /api/Reader/image") {
            MockResponse.Builder().addHeader("Content-Type", "image/jpeg").body(Buffer().write(Picture)).build()
        }
        val failing = PageSink {
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("full")

                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("full")

                override fun close() = throw IOException("cannot close")
            }
        }

        val result = runBlocking { source.page(PagedKind.Comic, Album, 0, failing) }

        assertThat(result).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    @Test
    fun `a page Kavita does not have is not found`() {
        routes.fail("GET /api/Reader/image", 404)

        assertThat(page(index = 3)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
    }

    @Test
    fun `a key refused ends the session`() {
        routes.fail("GET /api/Reader/image", 401)

        assertThat(page()).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
    }

    @Test
    fun `a page before the first is refused without asking`() {
        assertThat(page(index = -1)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a file that is not Kavita's is not found, and nothing is asked`() {
        val other = FileId("0BK1A")

        assertThat(page(file = other)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(runBlocking { source.pageCount(PagedKind.Comic, other) })
            .isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a book that is not a comic has no page`() {
        assertThat(page(kind = PagedKind.Pdf)).isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(runBlocking { source.pageCount(PagedKind.Pdf, Album) })
            .isEqualTo(ApiResult.Failure(ApiError.NotFound))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `the address of a page has no key, the client adds it`() {
        assertThat(source.pageUrl(PagedKind.Comic, Album, 3))
            .isEqualTo(routes.urlOf("/api/Reader/image", "chapterId=44&page=3"))
        assertThat(source.pageUrl(PagedKind.Pdf, Album, 3)).isNull()
    }
}
