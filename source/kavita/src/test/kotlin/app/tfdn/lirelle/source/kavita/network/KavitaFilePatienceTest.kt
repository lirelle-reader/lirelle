package app.tfdn.lirelle.source.kavita.network

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

/** Kavita's whole files are waited for longer than an ordinary answer; its other answers are not. */
class KavitaFilePatienceTest {

    private val server = MockWebServer()
    private val client = OkHttpClient.Builder()
        .readTimeout(200, TimeUnit.MILLISECONDS)
        .addInterceptor(KavitaFilePatience(5.seconds))
        .build()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun slow() = MockResponse.Builder().body("book").headersDelay(700, TimeUnit.MILLISECONDS).build()

    private fun get(path: String) = client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    @Test
    fun `a chapter's file and a PDF are waited for, however long Kavita takes to prepare them`() {
        server.enqueue(slow())
        server.enqueue(slow())

        get("/api/Download/chapter?chapterId=44").use { assertThat(it.body.string()).isEqualTo("book") }
        get("/api/Reader/pdf?chapterId=44&apiKey=k").use { assertThat(it.body.string()).isEqualTo("book") }
    }

    @Test
    fun `a page and any other answer keep the ordinary time-out`() {
        server.enqueue(slow())
        server.enqueue(slow())

        assertThrows(SocketTimeoutException::class.java) { get("/api/Reader/image?chapterId=44&page=0").close() }
        assertThrows(SocketTimeoutException::class.java) { get("/api/Reader/get-progress?chapterId=44").close() }
    }

    @Test
    fun `a server under a path of its own is waited for too`() {
        server.enqueue(slow())

        get("/kavita/api/Download/chapter?chapterId=44").use { assertThat(it.body.string()).isEqualTo("book") }
    }
}
