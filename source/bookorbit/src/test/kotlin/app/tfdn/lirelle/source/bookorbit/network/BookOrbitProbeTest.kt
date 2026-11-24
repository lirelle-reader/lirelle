package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.ProbeAnswer
import app.tfdn.lirelle.source.bookorbit.network.testing.enqueueFixture
import app.tfdn.lirelle.source.bookorbit.network.testing.enqueueJson
import app.tfdn.lirelle.source.bookorbit.network.testing.pathText
import app.tfdn.lirelle.source.bookorbit.network.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * BookOrbit's servers told from any other by their identity route: what was the check of the address before the
 * app knew several kinds. Remembering the address is the app's ([app.tfdn.lirelle.core.network.AuthRepository]).
 */
class BookOrbitProbeTest {

    private val server = MockWebServer()
    private lateinit var probe: BookOrbitProbe
    private lateinit var url: String

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        probe = BookOrbitProbe(ApiProvider(OkHttpClient(), ApiJson, PublicApi::class.java), ErrorMapper(ApiJson))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun mine(address: String) = ApiResult.Success(ProbeAnswer.Mine(address))

    private val notMine = ApiResult.Success(ProbeAnswer.NotMine)

    @Test
    fun `a BookOrbit server is recognised at its address`() = runBlocking {
        server.enqueueFixture("server-identity.json")

        assertThat(probe.probe(url)).isEqualTo(mine(url))
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/server-identity")
    }

    @Test
    fun `a server that reports another product is not BookOrbit's`() = runBlocking {
        server.enqueueJson("""{"product":"something-else","protocolVersion":1}""")

        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    @Test
    fun `a web page instead of JSON is not BookOrbit's`() = runBlocking {
        server.enqueue(
            MockResponse(code = 200, headers = headersOf("Content-Type", "text/html"), body = "<html></html>"),
        )

        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    @Test
    fun `a 404 on the identity route is not BookOrbit's`() = runBlocking {
        server.enqueueJson("""{"statusCode":404}""", code = 404)

        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    /** Another kind's server, which refuses what it does not know as Komga does. */
    @Test
    fun `a 401 on the identity route is not BookOrbit's`() = runBlocking {
        server.enqueueJson("""{"error":"Unauthorized"}""", code = 401)

        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    @Test
    fun `an unreachable server is reported as such`() = runBlocking {
        server.close()

        assertThat(probe.probe(url)).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
    }

    @Test
    fun `an address with a sub-path that answers directly is the address typed`() = runBlocking {
        server.enqueueFixture("server-identity.json")

        assertThat(probe.probe("$url/books")).isEqualTo(mine("$url/books"))
        assertThat(server.takeRequest().pathText()).isEqualTo("/books/api/v1/server-identity")
    }

    // A reverse proxy that sends http to https: later POSTs to the typed address would be
    // turned into GETs, or carry the password in cleartext before being redirected.
    @Test
    fun `a probe redirected to another server answers with the address that answered`() = runBlocking {
        val redirectTarget = MockWebServer()
        redirectTarget.start()
        try {
            server.enqueue(redirect(redirectTarget.url("/api/v1/server-identity").toString()))
            redirectTarget.enqueueFixture("server-identity.json")

            assertThat(probe.probe(url)).isEqualTo(mine(redirectTarget.serverUrl))

            // The client followed the redirect: each server saw the probe once.
            assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/server-identity")
            assertThat(redirectTarget.takeRequest().pathText()).isEqualTo("/api/v1/server-identity")
            assertThat(redirectTarget.serverUrl).isNotEqualTo(url)
        } finally {
            redirectTarget.close()
        }
    }

    @Test
    fun `a probe redirected to a sub-path answers with the address with that path`() = runBlocking {
        server.enqueue(redirect("/orbit/api/v1/server-identity", code = 308))
        server.enqueueFixture("server-identity.json")

        assertThat(probe.probe(url)).isEqualTo(mine("$url/orbit"))
    }

    @Test
    fun `a probe redirected away from the identity route is not BookOrbit's`() = runBlocking {
        server.enqueue(redirect("/somewhere-else"))
        server.enqueueFixture("server-identity.json")

        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    private fun redirect(location: String, code: Int = 301) =
        MockResponse(code = code, headers = headersOf("Location", location))
}
