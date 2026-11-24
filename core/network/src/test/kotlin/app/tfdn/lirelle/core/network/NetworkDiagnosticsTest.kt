package app.tfdn.lirelle.core.network

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.ConnectException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class NetworkDiagnosticsTest {

    private val server = MockWebServer()
    private val told = mutableListOf<String>()
    private val log = NetworkLog { told += it }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun search() = Request.Builder().url(server.url("/api/v1/search?q=my+secret+words")).build()

    @Test
    fun `a request that goes through tells nothing`() {
        server.enqueue(MockResponse(code = 200))
        val client = OkHttpClient.Builder().addInterceptor(FailureLoggingInterceptor { log }).build()

        client.newCall(search()).execute().use { assertThat(it.code).isEqualTo(200) }

        assertThat(told).isEmpty()
    }

    @Test
    fun `a request whose sending failed is told, without its query, and the same failure goes on`() {
        val failure = IOException("unexpected end of stream?q=my+secret+words", ConnectException("refused"))
        val client = OkHttpClient.Builder()
            .addInterceptor(FailureLoggingInterceptor { log })
            .addInterceptor { throw failure }
            .build()

        val thrown = assertThrows(IOException::class.java) { client.newCall(search()).execute() }

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(told).containsExactly(
            "GET /api/v1/search failed: IOException: unexpected end of stream, causes [ConnectException]",
        )
        assertThat(told.single()).doesNotContain("secret")
    }

    @Test
    fun `nothing is told unless a build says where`() {
        assertThat(NetworkDiagnostics.log).isSameInstanceAs(NetworkLog.None)
    }
}
