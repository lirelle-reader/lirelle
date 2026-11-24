package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.ProbeAnswer
import app.tfdn.lirelle.source.komga.testing.enqueueChallenge
import app.tfdn.lirelle.source.komga.testing.enqueueJson
import app.tfdn.lirelle.source.komga.testing.pathText
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Komga's servers told from any other by two answers given to anybody: its list of identity providers, a JSON
 * array, and its account route refusing a request without credentials with a Basic challenge. Anything less is
 * another server's: a web page, an empty answer, a refusal that is not Komga's.
 */
class KomgaProbeTest {

    private val server = MockWebServer()
    private lateinit var probe: KomgaProbe
    private lateinit var url: String

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        probe = KomgaProbe(KomgaApis(OkHttpClient(), KomgaJson, KomgaPublicApi::class.java), KomgaErrors())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun mine(address: String) = ApiResult.Success(ProbeAnswer.Mine(address))

    private val notMine = ApiResult.Success(ProbeAnswer.NotMine)

    private fun html(code: Int = 200) =
        MockResponse(code = code, headers = headersOf("Content-Type", "text/html"), body = "<html><body></body></html>")

    @Test
    fun `a Komga server is recognised by its providers and its Basic challenge`() = runBlocking {
        server.enqueueJson("""[{"name":"Authentik","registrationId":"authentik"}]""")
        server.enqueueChallenge()

        assertThat(probe.probe(url)).isEqualTo(mine(url))
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/oauth2/providers")
        val me = server.takeRequest()
        assertThat(me.pathText()).isEqualTo("/api/v2/users/me")
        assertThat(me.headers["Authorization"]).isNull()
    }

    @Test
    fun `a Komga server without any provider is recognised too`() = runBlocking {
        server.enqueueJson("[]")
        server.enqueueChallenge()

        assertThat(probe.probe(url)).isEqualTo(mine(url))
    }

    @Test
    fun `a plain web server is not Komga's, and is asked once`() = runBlocking {
        server.enqueue(html())

        assertThat(probe.probe(url)).isEqualTo(notMine)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a JSON answer that is not a list is not Komga's`() = runBlocking {
        server.enqueueJson("""{"product":"bookorbit"}""")

        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    @Test
    fun `a 404 on the providers is not Komga's`() = runBlocking {
        server.enqueue(html(code = 404))

        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    /** A server that refuses everything it does not know, as BookOrbit does, refuses the providers too. */
    @Test
    fun `a refusal of the providers is not Komga's`() = runBlocking {
        server.enqueueJson("""{"error":"Unauthorized"}""", code = 401)

        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    @Test
    fun `a list of providers with an account route that does not challenge for Basic is not Komga's`() =
        runBlocking {
            server.enqueueJson("[]")
            server.enqueueJson("""{"error":"Unauthorized"}""", code = 401, "WWW-Authenticate" to "Bearer")

            assertThat(probe.probe(url)).isEqualTo(notMine)
        }

    @Test
    fun `a list of providers with a 401 that challenges for nothing is not Komga's`() = runBlocking {
        server.enqueueJson("[]")
        server.enqueueJson("""{"error":"Unauthorized"}""", code = 401)

        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    @Test
    fun `a list of providers with an account route that answers a page is not Komga's`() = runBlocking {
        server.enqueueJson("[]")
        server.enqueue(html())

        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    @Test
    fun `an address with a base path that answers directly is the address typed`() = runBlocking {
        server.enqueueJson("[]")
        server.enqueueChallenge()

        assertThat(probe.probe("$url/komga")).isEqualTo(mine("$url/komga"))
        assertThat(server.takeRequest().pathText()).isEqualTo("/komga/api/v1/oauth2/providers")
        assertThat(server.takeRequest().pathText()).isEqualTo("/komga/api/v2/users/me")
    }

    @Test
    fun `a probe redirected to a base path answers with that path, and asks the account route there`() =
        runBlocking {
            server.enqueue(MockResponse(code = 308, headers = headersOf("Location", "/komga/api/v1/oauth2/providers")))
            server.enqueueJson("[]")
            server.enqueueChallenge()

            assertThat(probe.probe(url)).isEqualTo(mine("$url/komga"))
            server.takeRequest()
            server.takeRequest()
            assertThat(server.takeRequest().pathText()).isEqualTo("/komga/api/v2/users/me")
        }

    @Test
    fun `a probe redirected away from the providers is not Komga's`() = runBlocking {
        server.enqueue(MockResponse(code = 302, headers = headersOf("Location", "/login")))
        server.enqueueJson("[]")

        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    @Test
    fun `an unreachable server is reported as such, never sent`() = runBlocking {
        server.close()

        val answer = probe.probe(url)

        assertThat(answer).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
        assertThat((answer as ApiResult.Failure).mayHaveReachedServer).isFalse()
    }

    @Test
    fun `a failing server is a failure, not another kind's server`() = runBlocking {
        server.enqueueJson("""{"status":503}""", code = 503)

        assertThat(probe.probe(url)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `the address that answered keeps its base path, and never leaves https for cleartext`() {
        fun answered(base: String) = "$base/api/v1/oauth2/providers".toHttpUrl()

        assertThat(komgaAnsweringAddress("https://bd.example.com", answered("https://bd.example.com")))
            .isEqualTo("https://bd.example.com")
        assertThat(komgaAnsweringAddress("http://bd.example.com", answered("https://bd.example.com/komga")))
            .isEqualTo("https://bd.example.com/komga")
        assertThat(komgaAnsweringAddress("https://bd.example.com", answered("http://bd.example.com"))).isNull()
        assertThat(komgaAnsweringAddress("https://bd.example.com", "https://bd.example.com/login".toHttpUrl()))
            .isNull()
    }
}
