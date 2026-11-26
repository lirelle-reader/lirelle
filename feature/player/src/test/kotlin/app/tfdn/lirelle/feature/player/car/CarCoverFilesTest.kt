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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
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
    val asked: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /** One permit per request that arrived. */
    val arrived = Semaphore(0)

    /** What every answer waits for, when there is one. */
    @Volatile
    var hold: CountDownLatch? = null

    /** What the answer to one address waits for. */
    val holds = ConcurrentHashMap<String, CountDownLatch>()

    /**
     * The server sends its answer a few bytes at a time, for ever: the thread that reads it is not woken by an
     * interrupt, as one that waits on a socket is not, and only the call's cancellation ends the request.
     */
    @Volatile
    var drips = false

    /** How many requests ended because their call was cancelled. */
    val cancelled = AtomicInteger()

    /** The answer's code; null fails the connection. */
    var code: Int? = 200

    /** How long an answer takes. */
    var delayMillis = 0L

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val url = chain.request().url.toString()
                asked += url
                arrived.release()
                hold?.await()
                holds[url]?.await()
                if (drips) drip(chain.call())
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

    /** @throws IOException once [call] is cancelled, or after a time no test waits for. */
    private fun drip(call: Call): Nothing {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(DripSeconds)
        while (!call.isCanceled() && System.nanoTime() < end) {
            try {
                Thread.sleep(5)
            } catch (_: InterruptedException) {
                // A socket's read does not hear it.
            }
        }
        if (call.isCanceled()) cancelled.incrementAndGet()
        throw IOException("Canceled")
    }

    private companion object {
        const val DripSeconds = 20L
    }
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

    /** The clock that does not jump, as the test sets it. */
    private var now = 0L

    private fun files(bound: Int = 200, atOnce: Int = 4) =
        CarCoverFiles(kept, urls, library, server.client, cache, CarCoverFetches({ now }, atOnce), bound)

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
    fun `a fetch that ends after the cover changed leaves the newer cover, which is being served`() =
        runBlocking<Unit> {
            val files = files()
            val older = CountDownLatch(1).also { server.holds["https://books.example/c/1?v=1"] = it }
            urls.remember(BookId("1"), "https://books.example/c/1?v=1")
            val first = async(Dispatchers.IO) { files.file(BookId("1")) }
            assertThat(server.arrived.tryAcquire(5, TimeUnit.SECONDS)).isTrue()

            urls.remember(BookId("1"), "https://books.example/c/1?v=2")
            val newer = files.file(BookId("1"))
            older.countDown()
            first.await()

            assertThat(newer?.isFile).isTrue()
            assertThat(files.file(BookId("1"))).isEqualTo(newer)
            assertThat(server.asked).hasSize(2)
        }

    @Test
    fun `a book that was not listed since the app started shows the cover kept before, else its page's`() = runTest {
        library.bookResult = ApiResult.Success(sampleBookDetail("7").copy(coverUrl = "https://books.example/c/7"))

        val fetched = files().file(BookId("7"))
        assertThat(fetched?.readText()).isEqualTo("https://books.example/c/7")
        assertThat(library.calls).containsExactly("book 7")

        // Another start of the app: nothing is remembered, the file is there.
        val again = CarCoverFiles(kept, CarCoverUrls(), library, server.client, cache, CarCoverFetches({ now }, 4))
            .file(BookId("7"))
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
    fun `a cover that could not be had is not asked again for a minute, then is`() = runTest {
        urls.remember(BookId("1"), "https://books.example/c/1")
        val files = files()
        server.code = null
        assertThat(files.file(BookId("1"))).isNull()
        server.code = 200

        now = 59_000
        assertThat(files.file(BookId("1"))).isNull()
        assertThat(server.asked).hasSize(1)

        now = 60_000
        assertThat(files.file(BookId("1"))).isNotNull()
        assertThat(server.asked).hasSize(2)
    }

    @Test
    fun `a book whose page cannot be had is not asked again for a minute either`() = runTest {
        library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val files = files()

        assertThat(files.file(BookId("9"))).isNull()
        assertThat(files.file(BookId("9"))).isNull()

        assertThat(library.calls).containsExactly("book 9")
    }

    @Test
    fun `as many covers as may are fetched at once - one more has none, and is fetched when asked again`() =
        runBlocking<Unit> {
            urls.remember(BookId("1"), "https://books.example/c/1")
            urls.remember(BookId("2"), "https://books.example/c/2")
            val files = files(atOnce = 1)
            val answer = CountDownLatch(1).also { server.hold = it }

            val first = async(Dispatchers.IO) { files.file(BookId("1")) }
            assertThat(server.arrived.tryAcquire(5, TimeUnit.SECONDS)).isTrue()
            assertThat(files.file(BookId("2"))).isNull()
            assertThat(server.asked).hasSize(1)
            answer.countDown()

            assertThat(first.await()).isNotNull()
            // Turned away, not failed: nothing is remembered of it.
            assertThat(files.file(BookId("2"))?.readText()).isEqualTo("https://books.example/c/2")
        }

    @Test
    fun `a request given up cancels its call - a server that sends a few bytes at a time holds no thread`() =
        runBlocking<Unit> {
            urls.remember(BookId("1"), "https://books.example/c/1")
            server.drips = true
            val started = System.nanoTime()

            val cover = withTimeoutOrNull(100) { files().file(BookId("1")) }

            assertThat(cover).isNull()
            assertThat(server.cancelled.get()).isEqualTo(1)
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(10)
            assertThat(cache.listFiles().orEmpty()).isEmpty()
        }

    @Test
    fun `two requests of the same cover at once are both served it, and nothing half-written is left`() =
        runBlocking<Unit> {
            urls.remember(BookId("1"), "https://books.example/c/1")
            val files = files()
            val answer = CountDownLatch(1).also { server.hold = it }

            val both = List(2) { async(Dispatchers.IO) { files.file(BookId("1")) } }
            assertThat(server.arrived.tryAcquire(2, 5, TimeUnit.SECONDS)).isTrue()
            answer.countDown()

            val served = both.awaitAll()
            assertThat(served.map { it?.readText() })
                .containsExactly("https://books.example/c/1", "https://books.example/c/1")
            assertThat(cache.listFiles()!!.toList()).containsExactly(served.first())
        }

    @Test
    fun `what a fetch that never ended left behind goes with the next cover fetched, not one being written`() =
        runTest {
            cache.mkdirs()
            val left = File(cache, "left.part").apply { writeText("half") }
            val written = File(cache, "written.part").apply { writeText("half") }
            left.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1_000L)
            urls.remember(BookId("1"), "https://books.example/c/1")

            val cover = files().file(BookId("1"))

            assertThat(cache.listFiles()!!.toList()).containsExactly(cover, written)
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
