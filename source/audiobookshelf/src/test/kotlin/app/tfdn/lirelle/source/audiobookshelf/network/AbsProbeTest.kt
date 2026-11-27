package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.ProbeAnswer
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueJson
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueRefusal
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueText
import app.tfdn.lirelle.source.audiobookshelf.testing.pathText
import app.tfdn.lirelle.source.audiobookshelf.testing.serverUrl
import app.tfdn.lirelle.source.audiobookshelf.testing.statusJson
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val Megabyte = 1024 * 1024
private const val SlowBytes = 8L * 1024
private const val SlowPeriodMillis = 100L

/** Long enough to read 64 KB of a slow answer, far too short to read its megabyte. */
private const val BoundedReadMillis = 5_000L

/**
 * Audiobookshelf's servers told from any other by what they answer anybody at `GET status`: a JSON object whose
 * `app` is `"audiobookshelf"`. BookOrbit, Komga and Kavita answer that address with their web page or a refusal,
 * and another server's JSON has no such `app`. The same answer tells the version: a server too old is recognised,
 * and refused there, so that the address step says why.
 */
class AbsProbeTest {

    private val server = MockWebServer()
    private lateinit var probe: AbsProbe
    private lateinit var url: String

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        probe = AbsProbe(AbsApis(OkHttpClient(), AbsJson, AbsPublicApi::class.java), AbsErrors())
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
    fun `an Audiobookshelf server is recognised by its status, asked once and without credentials`() = runBlocking {
        server.enqueueJson(statusJson())

        assertThat(probe.probe(url)).isEqualTo(mine(url))
        val status = server.takeRequest()
        assertThat(status.method).isEqualTo("GET")
        assertThat(status.pathText()).isEqualTo("/status")
        assertThat(status.headers["Authorization"]).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an address under its base path is the address typed`() = runBlocking {
        server.enqueueJson(statusJson())

        assertThat(probe.probe("$url/audiobookshelf")).isEqualTo(mine("$url/audiobookshelf"))
        assertThat(server.takeRequest().pathText()).isEqualTo("/audiobookshelf/status")
    }

    @Test
    fun `a probe redirected to a base path answers with that path`() = runBlocking {
        server.enqueue(MockResponse(code = 308, headers = headersOf("Location", "/audiobookshelf/status")))
        server.enqueueJson(statusJson())

        assertThat(probe.probe(url)).isEqualTo(mine("$url/audiobookshelf"))
    }

    @Test
    fun `a probe redirected away from the status is not Audiobookshelf's`() = runBlocking {
        server.enqueue(MockResponse(code = 302, headers = headersOf("Location", "/login")))
        server.enqueueJson(statusJson())

        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    /** BookOrbit and Komga answer `status` with their web page, as a single-page app answers every address. */
    @Test
    fun `a web page is not Audiobookshelf's`() = runBlocking {
        server.enqueue(html())
        assertThat(probe.probe(url)).isEqualTo(notMine)

        server.enqueue(html(code = 404))
        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    @Test
    fun `a server that answers as BookOrbit does is not Audiobookshelf's`() = runBlocking {
        server.enqueueJson("""{"error":"Unauthorized"}""", code = 401)
        assertThat(probe.probe(url)).isEqualTo(notMine)

        server.enqueueJson("""{"product":"bookorbit","status":"ok"}""")
        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    @Test
    fun `a server that answers as Komga does is not Audiobookshelf's`() = runBlocking {
        server.enqueueJson("""{"status":404,"error":"Not Found","path":"/status"}""", code = 404)
        assertThat(probe.probe(url)).isEqualTo(notMine)

        server.enqueueJson("""{"status":401,"error":"Unauthorized"}""", code = 401, "WWW-Authenticate" to "Basic")
        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    @Test
    fun `a server that answers as Kavita does is not Audiobookshelf's`() = runBlocking {
        server.enqueueText("Ok")
        assertThat(probe.probe(url)).isEqualTo(notMine)

        server.enqueue(MockResponse(code = 401, headers = headersOf("WWW-Authenticate", "Bearer")))
        assertThat(probe.probe(url)).isEqualTo(notMine)

        server.enqueueRefusal()
        assertThat(probe.probe(url)).isEqualTo(notMine)
    }

    @Test
    fun `another server's JSON status is not Audiobookshelf's`() = runBlocking {
        val others = listOf(
            """{"status":"ok","version":"1.0"}""",
            """{"app":"jellyfin","serverVersion":"10.9.0"}""",
            """{"app":1,"serverVersion":"2.38.0"}""",
            """{"app":null}""",
            """{"app":"Audiobookshelf"}""",
            """["audiobookshelf"]""",
            "\"audiobookshelf\"",
            "{",
        )
        others.forEach { status ->
            server.enqueueJson(status)

            assertWithMessage(status).that(probe.probe(url)).isEqualTo(notMine)
        }
    }

    @Test
    fun `a megabyte is not Audiobookshelf's, and is not read whole`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(statusJson().dropLast(1) + ",\"x\":\"" + " ".repeat(Megabyte) + "\"}")
                .throttleBody(SlowBytes, SlowPeriodMillis, TimeUnit.MILLISECONDS)
                .build(),
        )

        assertThat(withTimeout(BoundedReadMillis) { probe.probe(url) }).isEqualTo(notMine)
    }

    @Test
    fun `a server too old is recognised, and refused with its version`() = runBlocking {
        server.enqueueJson(statusJson(version = "2.25.1"))

        assertThat(probe.probe(url))
            .isEqualTo(ApiResult.Failure(ApiError.ServerTooOld(ServerKind.Audiobookshelf, "2.25.1", "2.26")))
    }

    @Test
    fun `a server whose version has no numbers is refused as too old`() = runBlocking {
        server.enqueueJson(statusJson(version = "beta"))

        assertThat(probe.probe(url))
            .isEqualTo(ApiResult.Failure(ApiError.ServerTooOld(ServerKind.Audiobookshelf, "beta", "2.26")))
    }

    @Test
    fun `the minimum version is recognised`() = runBlocking {
        server.enqueueJson(statusJson(version = "2.26.0", methods = listOf("local"), buttonText = null))

        assertThat(probe.probe(url)).isEqualTo(mine(url))
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
        server.enqueueText("Error: boom", code = 500)
        assertThat(probe.probe(url)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))

        server.enqueueText("Bad gateway", code = 502)
        assertThat(probe.probe(url)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }

    @Test
    fun `the address that answered keeps its base path, and never leaves https for cleartext`() {
        fun answered(base: String) = "$base/status".toHttpUrl()

        assertThat(absAnsweringAddress("https://abs.example.com", answered("https://abs.example.com")))
            .isEqualTo("https://abs.example.com")
        assertThat(absAnsweringAddress("http://abs.example.com", answered("https://abs.example.com/audiobookshelf")))
            .isEqualTo("https://abs.example.com/audiobookshelf")
        assertThat(absAnsweringAddress("https://abs.example.com", answered("http://abs.example.com"))).isNull()
        assertThat(absAnsweringAddress("https://abs.example.com", "https://abs.example.com/login".toHttpUrl()))
            .isNull()
    }
}
