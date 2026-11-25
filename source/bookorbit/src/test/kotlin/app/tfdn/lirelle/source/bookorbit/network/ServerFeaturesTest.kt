package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.source.bookorbit.network.testing.enqueueJson
import app.tfdn.lirelle.source.bookorbit.network.testing.pathText
import app.tfdn.lirelle.source.bookorbit.network.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class ServerFeaturesTest {

    private val server = MockWebServer()
    private var signedIn = true
    private lateinit var features: ServerFeatures

    @Before
    fun setUp() {
        server.start()
        features = ServerFeatures(
            api = ApiProvider(OkHttpClient(), ApiJson, AccountApi::class.java),
            errors = ErrorMapper(ApiJson),
            serverUrl = { if (signedIn) server.serverUrl else null },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun appInfo(vararg names: String) =
        """{"version":"v3.10.0","features":[${names.joinToString(",") { "\"$it\"" }}]}"""

    @Test
    fun `a feature the server announces is there, another is not`() = runBlocking {
        server.enqueueJson(appInfo("book-links", "book-editions"))

        assertThat(features.has(ServerFeatures.BookEditions)).isTrue()
        assertThat(features.has("some-later-feature")).isFalse()
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/app-info")
    }

    @Test
    fun `a server before 3_10 does not group editions`() = runBlocking {
        server.enqueueJson(appInfo("book-links", "reading-history"))

        assertThat(features.has(ServerFeatures.BookEditions)).isFalse()
    }

    @Test
    fun `a server that lists no features has none`() = runBlocking {
        server.enqueueJson("""{"version":"v3.4.0"}""")

        assertThat(features.has(ServerFeatures.BookEditions)).isFalse()
    }

    @Test
    fun `the server is asked once`() = runBlocking {
        server.enqueueJson(appInfo("book-editions"))

        features.has(ServerFeatures.BookEditions)
        features.has(ServerFeatures.BookEditions)
        features.has("book-links")

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `two questions at once ask the server once`() = runBlocking {
        val slow = MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body(appInfo("book-editions"))
            .headersDelay(300, TimeUnit.MILLISECONDS)
            .build()
        // Two answers ready: a second request would be answered, and counted.
        server.enqueue(slow)
        server.enqueue(slow)

        val answers = List(2) { async(Dispatchers.IO) { features.has(ServerFeatures.BookEditions) } }.awaitAll()

        assertThat(answers).containsExactly(true, true)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a failed read has no feature, and the server is asked again`() = runBlocking {
        server.enqueueJson("""{"statusCode":500,"message":"Internal server error"}""", code = 500)
        server.enqueueJson(appInfo("book-editions"))

        assertThat(features.has(ServerFeatures.BookEditions)).isFalse()
        assertThat(features.has(ServerFeatures.BookEditions)).isTrue()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `without a session nothing is asked`() = runBlocking {
        signedIn = false

        assertThat(features.has(ServerFeatures.BookEditions)).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }
}
