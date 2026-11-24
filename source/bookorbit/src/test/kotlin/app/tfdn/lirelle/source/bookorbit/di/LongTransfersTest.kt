package app.tfdn.lirelle.source.bookorbit.di

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.library.DefaultReadAlongRepository
import app.tfdn.lirelle.source.bookorbit.library.DefaultReaderRepository
import app.tfdn.lirelle.source.bookorbit.library.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.library.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import app.tfdn.lirelle.source.bookorbit.network.DownloadPatience
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val OrdinaryMillis = 200L
private const val PreparingMillis = 700L

/**
 * BookOrbit's long transfers are waited for, through the clients the source builds: the reader's file and the
 * read-along map, on the bound long-transfer interceptor, which is BookOrbit's patience.
 */
class LongTransfersTest {

    private val server = MockWebServer()

    /** The authenticated client, with an ordinary time-out shorter than the server takes to prepare a file. */
    private val client = OkHttpClient.Builder().readTimeout(OrdinaryMillis, TimeUnit.MILLISECONDS).build()
    private val patience = DownloadPatience(5.seconds)

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun slow(body: String) = MockResponse.Builder()
        .addHeader("Content-Type", "application/json")
        .body(body)
        .headersDelay(PreparingMillis, TimeUnit.MILLISECONDS)
        .build()

    private fun readAlong() = DefaultReadAlongRepository(
        api = ReadAlongModule.readAlongApi(client, ApiJson, patience),
        errors = ErrorMapper(ApiJson),
        serverUrl = { server.serverUrl },
    )

    @Test
    fun `the long-transfer interceptor the source binds is BookOrbit's patience`() {
        assertThat(BookOrbitNetworkProfileModule.longTransfer()).isInstanceOf(DownloadPatience::class.java)
    }

    @Test
    fun `the reader's file is waited for, however long the server takes to prepare it`() = runBlocking {
        server.enqueue(slow("book"))
        val reader = DefaultReaderRepository(
            api = BookOrbitLibraryModule.readerApi(client, ApiJson, patience),
            errors = ErrorMapper(ApiJson),
            serverUrl = { server.serverUrl },
        )
        val sink = ByteArrayOutputStream()

        val result = reader.download(FileId("7"), audioless = true, sink) {}

        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        assertThat(sink.toString(Charsets.UTF_8)).isEqualTo("book")
    }

    @Test
    fun `the read-along map is waited for, as the server builds it on its first request`() = runBlocking {
        server.enqueue(slow(Fixtures.recorded("readalong-map-gap")))

        val playlist = readAlong().playlist(BookId("3"), FileId("4"))

        assertThat(playlist).isInstanceOf(ApiResult.Success::class.java)
    }

    @Test
    fun `the read-along book's small info keeps the ordinary time-out`() = runBlocking {
        server.enqueue(slow(Fixtures.recorded("readalong-info-gap")))

        val spine = readAlong().spine(BookId("3"), FileId("4"))

        assertThat(spine).isInstanceOf(ApiResult.Failure::class.java)
    }
}
