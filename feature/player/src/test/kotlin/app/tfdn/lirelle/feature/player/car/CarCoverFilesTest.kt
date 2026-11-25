package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.feature.player.service.FakeKeptBookData
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A server of covers: it answers each address with the address itself as the picture, and notes what it is asked. */
class CoverServer {
    val asked = mutableListOf<String>()

    /** The answer's code; null fails the connection. */
    var code: Int? = 200

    /** How long an answer takes. */
    var delayMillis = 0L

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val url = chain.request().url.toString()
                asked += url
                if (delayMillis > 0) Thread.sleep(delayMillis)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code ?: throw IOException("No connection"))
                    .message("Answer")
                    .body(url.toResponseBody("image/png".toMediaType()))
                    .build()
            },
        )
        .build()
}

/** Where the covers the car asks come from: the download's, else the server's, kept in a bounded folder. */
class CarCoverFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val kept = FakeKeptBookData()
    private val urls = CarCoverUrls()
    private val library = FakeLibraryRepository()
    private val server = CoverServer()
    private val cache by lazy { File(folder.root, "car-covers") }

    private fun files(bound: Int = 200) = CarCoverFiles(kept, urls, library, server.client, cache, bound)

    private fun keptFile(name: String) = folder.newFile(name).apply { writeText(name) }

    @Test
    fun `a downloaded book's cover is the kept one, the audiobook's own first, and the server is asked nothing`() =
        runTest {
            kept.covers[BookId("1")] = keptFile("cover-1")
            kept.covers[BookId("2")] = keptFile("cover-2")
            kept.audioCovers[BookId("2")] = keptFile("audio-cover-2")
            urls.remember(BookId("1"), "https://books.example/c/1?v=1")

            assertThat(files().file(BookId("1"))?.readText()).isEqualTo("cover-1")
            assertThat(files().file(BookId("2"))?.readText()).isEqualTo("audio-cover-2")
            assertThat(server.asked).isEmpty()
            assertThat(library.calls).isEmpty()
        }

    @Test
    fun `the server's cover is fetched at the address the book was listed with - a second call fetches nothing`() =
        runTest {
            urls.remember(BookId("1"), "https://books.example/c/1?v=1")
            val files = files()

            val first = files.file(BookId("1"))
            val second = files.file(BookId("1"))

            assertThat(first?.readText()).isEqualTo("https://books.example/c/1?v=1")
            assertThat(second).isEqualTo(first)
            assertThat(first?.parentFile).isEqualTo(cache)
            assertThat(server.asked).containsExactly("https://books.example/c/1?v=1")
            // The address was known: the book's page is not asked.
            assertThat(library.calls).isEmpty()
        }

    @Test
    fun `a cover that changed on the server is fetched again, and the old one goes`() = runTest {
        val files = files()
        urls.remember(BookId("1"), "https://books.example/c/1?v=1")
        files.file(BookId("1"))

        urls.remember(BookId("1"), "https://books.example/c/1?v=2")
        val newer = files.file(BookId("1"))

        assertThat(newer?.readText()).isEqualTo("https://books.example/c/1?v=2")
        assertThat(cache.listFiles()!!.toList()).containsExactly(newer)
    }

    @Test
    fun `a book that was not listed since the app started shows the cover kept before, else its page's`() = runTest {
        library.bookResult = ApiResult.Success(sampleBookDetail("7").copy(coverUrl = "https://books.example/c/7"))

        val fetched = files().file(BookId("7"))
        assertThat(fetched?.readText()).isEqualTo("https://books.example/c/7")
        assertThat(library.calls).containsExactly("book 7")

        // Another start of the app: nothing is remembered, the file is there.
        val again = CarCoverFiles(kept, CarCoverUrls(), library, server.client, cache).file(BookId("7"))
        assertThat(again).isEqualTo(fetched)
        assertThat(library.calls).containsExactly("book 7")
        assertThat(server.asked).hasSize(1)
    }

    @Test
    fun `a book without a cover, a failing fetch and a refused one give nothing, and leave no file`() = runTest {
        urls.remember(BookId("1"), null)
        urls.remember(BookId("2"), "https://books.example/c/2")
        library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)

        assertThat(files().file(BookId("1"))).isNull()
        assertThat(files().file(BookId("9"))).isNull()
        server.code = null
        assertThat(files().file(BookId("2"))).isNull()
        server.code = 404
        assertThat(files().file(BookId("2"))).isNull()
        assertThat(cache.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `the folder is bounded - past the bound the covers served longest ago go`() = runTest {
        val files = files(bound = 3)
        (1..3).forEach { urls.remember(BookId("$it"), "https://books.example/c/$it") }
        val served = (1..3).map { files.file(BookId("$it"))!! }
        // Served at times a file system tells apart, the first one again last.
        served.forEachIndexed { index, file -> file.setLastModified(1_000_000L + index * 10_000) }
        files.file(BookId("1"))

        urls.remember(BookId("4"), "https://books.example/c/4")
        val fourth = files.file(BookId("4"))

        assertThat(cache.listFiles()!!.toList()).containsExactly(served[0], served[2], fourth)
    }

    @Test
    fun `ids of any text are one file each`() = runTest {
        urls.remember(BookId("a/../b"), "https://books.example/c/x")

        val file = files().file(BookId("a/../b"))

        assertThat(file?.parentFile).isEqualTo(cache)
    }
}
