package app.tfdn.lirelle.source.bookorbit.network

import com.google.common.truth.Truth.assertThat
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class DownloadPatienceTest {

    private val server = MockWebServer()
    private val client = OkHttpClient.Builder()
        .readTimeout(200, TimeUnit.MILLISECONDS)
        .addInterceptor(DownloadPatience(5.seconds))
        .build()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun slow() = MockResponse.Builder().body("book").headersDelay(700, TimeUnit.MILLISECONDS).build()

    private fun get(path: String) = client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    @Test
    fun `a book file is waited for, however long the server takes to prepare it`() {
        server.enqueue(slow())
        server.enqueue(slow())

        get("/api/v1/books/files/7/audioless-epub/download").use { assertThat(it.body.string()).isEqualTo("book") }
        get("/api/v1/books/files/7/serve").use { assertThat(it.body.string()).isEqualTo("book") }
    }

    @Test
    fun `an audio file is waited for too`() {
        server.enqueue(slow())

        get("/api/v1/audiobooks/3/assets/9/content").use { assertThat(it.body.string()).isEqualTo("book") }
    }

    @Test
    fun `the sync map of a read-along book is waited for, its small info is not`() {
        server.enqueue(slow())
        server.enqueue(slow())

        get("/api/v1/epub/3/media-overlay?fileId=4").use { assertThat(it.body.string()).isEqualTo("book") }
        assertThrows(SocketTimeoutException::class.java) { get("/api/v1/epub/3/info?fileId=4").close() }
    }

    @Test
    fun `a narration track is waited for, wherever it is in the book`() {
        server.enqueue(slow())
        server.enqueue(slow())
        server.enqueue(slow())

        get("/api/v1/epub/3/media-overlay/file/audio/part%201.mp3?fileId=4").use {
            assertThat(it.body.string()).isEqualTo("book")
        }
        get("/api/v1/epub/3/media-overlay/file/track.m4a?fileId=4").use {
            assertThat(it.body.string()).isEqualTo("book")
        }
        assertThrows(SocketTimeoutException::class.java) { get("/api/v1/x/media-overlay/file/track.m4a").close() }
    }

    @Test
    fun `only the content of an audio asset is waited for`() {
        server.enqueue(slow())
        server.enqueue(slow())

        assertThrows(SocketTimeoutException::class.java) { get("/api/v1/audiobooks/3/manifest").close() }
        assertThrows(SocketTimeoutException::class.java) { get("/api/v1/x/assets/y/content").close() }
    }

    @Test
    fun `any other answer keeps the ordinary time-out`() {
        server.enqueue(slow())

        assertThrows(SocketTimeoutException::class.java) { get("/api/v1/reading-progress?fileId=7").close() }
    }
}
