package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.ProbeAnswer
import app.tfdn.lirelle.source.kavita.testing.OidcOffJson
import app.tfdn.lirelle.source.kavita.testing.enqueueHealthy
import app.tfdn.lirelle.source.kavita.testing.enqueueJson
import app.tfdn.lirelle.source.kavita.testing.enqueueText
import app.tfdn.lirelle.source.kavita.testing.oidcOnJson
import app.tfdn.lirelle.source.kavita.testing.pathText
import app.tfdn.lirelle.source.kavita.testing.serverUrl
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
 * Kavita's servers told from any other by two answers given to anybody: its health, the plain text `Ok`, and what
 * it says of its identity provider, an object answered even when none is set up. Anything less is another
 * server's: a web page, a health told in other words, settings of another shape.
 */
class KavitaProbeTest {

    private val server = MockWebServer()
    private lateinit var probe: KavitaProbe
    private lateinit var url: String

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        probe = KavitaProbe(KavitaApis(OkHttpClient(), KavitaJson, KavitaPublicApi::class.java), KavitaErrors())
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
    fun `a Kavita server without a provider is recognised by its health and its provider's settings`() = runBlocking {
        server.enqueueHealthy()
        server.enqueueJson(OidcOffJson)

        assertThat(probe.probe(url)).isEqualTo(mine(url))
        val health = server.takeRequest()
        assertThat(health.method).isEqualTo("GET")
        assertThat(health.pathText()).isEqualTo("/api/Health")
        val settings = server.takeRequest()
        assertThat(settings.pathText()).isEqualTo("/api/Settings/oidc")
        assertThat(settings.headers["Authorization"]).isNull()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a Kavita server with a provider is recognised too`() = runBlocking {
        server.enqueueHealthy()
        server.enqueueJson(oidcOnJson(passwordsDisabled = true))

        assertThat(probe.probe(url)).isEqualTo(mine(url))
    }

    @Test
    fun `a health followed by a line break is Kavita's`() = runBlocking {
        server.enqueueText("Ok\n")
        server.enqueueJson(OidcOffJson)

        assertThat(probe.probe(url)).isEqualTo(mine(url))
    }

    @Test
    fun `a plain web server is not Kavita's, and is asked once`() = runBlocking {
        server.enqueue(html())

        assertThat(probe.probe(url)).isEqualTo(notMine)
        assertThat(server.requestCount).isEqualTo(1)
    }

    /** A page that answers every address, as a single-page app does: far longer than any health. */
    @Test
    fun `a long answer about health is not Kavita's`() = runBlocking {
        server.enqueueText("Ok" + " ".repeat(100_000))

        assertThat(probe.probe(url)).isEqualTo(notMine)
        assertThat(server.requestCount).isEqualTo(1)
    }

    /** Komga has no such routes: it answers them with its error, or with its web interface. */
    @Test
    fun `a server that answers as Komga does is not Kavita's`() = runBlocking {
        server.enqueueJson("""{"status":404,"error":"Not Found","path":"/api/Health"}""", code = 404)
        assertThat(probe.probe(url)).isEqualTo(notMine)

        server.enqueueJson("""{"status":401,"error":"Unauthorized"}""", code = 401, "WWW-Authenticate" to "Basic")
        assertThat(probe.probe(url)).isEqualTo(notMine)

        assertThat(server.requestCount).isEqualTo(2)
    }

    /** BookOrbit refuses everything it does not know, and says its health as JSON. */
    @Test
    fun `a server that answers as BookOrbit does is not Kavita's`() = runBlocking {
        server.enqueueJson("""{"error":"Unauthorized"}""", code = 401)
        assertThat(probe.probe(url)).isEqualTo(notMine)

        server.enqueueJson("""{"product":"bookorbit","status":"ok"}""")
        assertThat(probe.probe(url)).isEqualTo(notMine)

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a health told in other words is not Kavita's`() = runBlocking {
        listOf("\"OK\"", """{"status":"UP"}""", "OK", "ok", "Healthy", "").forEach { health ->
            server.enqueueJson(health)

            assertThat(probe.probe(url)).isEqualTo(notMine)
        }
        assertThat(server.requestCount).isEqualTo(6)
    }

    @Test
    fun `a health with settings of another shape is not Kavita's`() = runBlocking {
        val others = listOf(
            "[]",
            "{}",
            """{"enabled":"false","disablePasswordAuthentication":false,"providerName":"x"}""",
            """{"enabled":false,"providerName":"x"}""",
            """{"enabled":false,"disablePasswordAuthentication":false,"providerName":null}""",
            """{"enabled":false,"disablePasswordAuthentication":false,"providerName":1}""",
            "Ok",
        )
        others.forEach { settings ->
            server.enqueueHealthy()
            server.enqueueJson(settings)

            assertThat(probe.probe(url)).isEqualTo(notMine)
        }
    }

    @Test
    fun `a health with settings that are refused, absent or a page is not Kavita's`() = runBlocking {
        listOf(html(), html(code = 404), MockResponse(code = 401, headers = headersOf("WWW-Authenticate", "Bearer")))
            .forEach { settings ->
                server.enqueueHealthy()
                server.enqueue(settings)

                assertThat(probe.probe(url)).isEqualTo(notMine)
            }
    }

    @Test
    fun `an address with a base path that answers directly is the address typed`() = runBlocking {
        server.enqueueHealthy()
        server.enqueueJson(OidcOffJson)

        assertThat(probe.probe("$url/kavita")).isEqualTo(mine("$url/kavita"))
        assertThat(server.takeRequest().pathText()).isEqualTo("/kavita/api/Health")
        assertThat(server.takeRequest().pathText()).isEqualTo("/kavita/api/Settings/oidc")
    }

    @Test
    fun `a probe redirected to a base path answers with that path, and asks the settings there`() = runBlocking {
        server.enqueue(MockResponse(code = 308, headers = headersOf("Location", "/kavita/api/Health")))
        server.enqueueHealthy()
        server.enqueueJson(OidcOffJson)

        assertThat(probe.probe(url)).isEqualTo(mine("$url/kavita"))
        server.takeRequest()
        server.takeRequest()
        assertThat(server.takeRequest().pathText()).isEqualTo("/kavita/api/Settings/oidc")
    }

    @Test
    fun `a probe redirected away from the health is not Kavita's`() = runBlocking {
        server.enqueue(MockResponse(code = 302, headers = headersOf("Location", "/login")))
        server.enqueueHealthy()

        assertThat(probe.probe(url)).isEqualTo(notMine)
        assertThat(server.requestCount).isEqualTo(2)
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
        server.enqueueText("System.Exception: boom\n   at API.Startup()", code = 500)
        assertThat(probe.probe(url)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))

        server.enqueueHealthy()
        server.enqueueText("Bad gateway", code = 502)
        assertThat(probe.probe(url)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `the address that answered keeps its base path, and never leaves https for cleartext`() {
        fun answered(base: String) = "$base/api/Health".toHttpUrl()

        assertThat(kavitaAnsweringAddress("https://bd.example.com", answered("https://bd.example.com")))
            .isEqualTo("https://bd.example.com")
        assertThat(kavitaAnsweringAddress("http://bd.example.com", answered("https://bd.example.com/kavita")))
            .isEqualTo("https://bd.example.com/kavita")
        assertThat(kavitaAnsweringAddress("https://bd.example.com", answered("http://bd.example.com"))).isNull()
        assertThat(kavitaAnsweringAddress("https://bd.example.com", "https://bd.example.com/login".toHttpUrl()))
            .isNull()
    }
}
