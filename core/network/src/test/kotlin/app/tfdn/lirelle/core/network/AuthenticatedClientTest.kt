package app.tfdn.lirelle.core.network

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.delay
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * An authorizer as the contract describes one, played by the test: the header it gives for its server, the one it
 * renews it into after a refusal, nothing for another host, and the failure it is told to throw. By default a
 * source of renewed bearer tokens, as BookOrbit is.
 *
 * @param name the header it sends.
 * @param prefix what comes before the credential in the header's value.
 */
private class ScriptedAuthorizer(
    private val serverPort: Int,
    private val name: String = "Authorization",
    private val prefix: String = "Bearer ",
) : SessionAuthorizer {
    var held: String = "access-1"
    val renewed: String = "access-2"
    var failure: Exception? = null

    /** What it throws when told of a refusal, and only then. */
    var renewalFailure: Exception? = null

    /**
     * Answers a refusal as a source whose credential is not renewed: the profile marked as needing a sign-in, and
     * the request failed.
     */
    var refusing = false

    /** The mark a refusal leaves, as [app.tfdn.lirelle.core.session.profile.ProfileSession.expire] does. */
    var signInNeeded = false

    /** Waits a while before it answers, as a renewal on its way does. */
    var slow = false

    /** What it was asked, in order: the value refused, if any, and where the header goes. */
    val asked = mutableListOf<Pair<String?, HttpUrl>>()

    /**
     * A query parameter it puts in the address of every request, as a source whose server takes the credential
     * there; null to leave addresses as the contract's default does.
     */
    var inAddress: Pair<String, String>? = null

    override suspend fun header(target: HttpUrl): AuthHeader? = answer(sent = null, target, held)

    override fun address(target: HttpUrl): HttpUrl = inAddress
        ?.let { (name, value) -> target.newBuilder().setQueryParameter(name, value).build() }
        ?: super.address(target)

    override suspend fun rejected(sent: String, target: HttpUrl): AuthHeader? {
        if (refusing) {
            asked += sent to target
            signInNeeded = true
            throw IOException("The server refused the key")
        }
        return answer(sent, target, renewed)
    }

    private suspend fun answer(sent: String?, target: HttpUrl, credential: String): AuthHeader? {
        asked += sent to target
        if (slow) delay(SlowMillis)
        failure?.let { throw it }
        if (sent != null) renewalFailure?.let { throw it }
        if (target.port != serverPort) return null
        return AuthHeader(name, prefix + credential)
    }

    private companion object {
        const val SlowMillis = 200L
    }
}

/** The client that proves a profile's account, over whatever authorizer the profile's source gives it. */
class AuthenticatedClientTest {

    private val server = MockWebServer()
    private val otherHost = MockWebServer()
    private lateinit var authorizer: ScriptedAuthorizer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        otherHost.start()
        authorizer = ScriptedAuthorizer(server.port)
        client = newAuthenticatedClient(OkHttpClient(), authorizer)
    }

    @After
    fun tearDown() {
        server.close()
        otherHost.close()
    }

    /** A source that sends a key of its own, which is never renewed: what Komga's is. */
    private fun keyed() {
        authorizer = ScriptedAuthorizer(server.port, name = ApiKey, prefix = "").apply { held = "the-key" }
        client = newAuthenticatedClient(OkHttpClient(), authorizer)
    }

    private fun get(on: MockWebServer = server, path: String = "/me") =
        client.newCall(Request.Builder().url(on.url(path)).build()).execute().use { it.code }

    private fun refused() = MockResponse(code = Unauthorized)

    @Test
    fun `a request to the server carries the authorizer's header, asked for that request`() {
        server.enqueue(MockResponse(code = Ok))

        assertThat(get()).isEqualTo(Ok)

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer access-1")
        assertThat(authorizer.asked).containsExactly(null to server.url("/me"))
    }

    @Test
    fun `a request the authorizer gives no header for goes out without any`() {
        otherHost.enqueue(MockResponse(code = Ok))

        assertThat(get(on = otherHost)).isEqualTo(Ok)

        assertThat(otherHost.takeRequest().headers["Authorization"]).isNull()
    }

    @Test
    fun `a 401 hands the value sent to the authorizer and replays the request once, with the new header`() {
        server.enqueue(refused())
        server.enqueue(MockResponse(code = Ok))

        assertThat(get()).isEqualTo(Ok)

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer access-1")
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer access-2")
        assertThat(authorizer.asked)
            .containsExactly(null to server.url("/me"), "Bearer access-1" to server.url("/me"))
            .inOrder()
    }

    @Test
    fun `a request refused again after its replay is handed back as it is`() {
        repeat(3) { server.enqueue(refused()) }

        assertThat(get()).isEqualTo(Unauthorized)

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a 401 after a redirect is still renewed and replayed`() {
        server.enqueue(MockResponse(code = Redirect, headers = headersOf("Location", "/moved")))
        server.enqueue(refused())
        server.enqueue(MockResponse(code = Ok))

        assertThat(get()).isEqualTo(Ok)

        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/me")
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/moved")
        val replayed = server.takeRequest()
        assertThat(replayed.url.encodedPath).isEqualTo("/moved")
        assertThat(replayed.headers["Authorization"]).isEqualTo("Bearer access-2")
    }

    @Test
    fun `a 401 to a request that carried no header is handed back`() {
        otherHost.enqueue(refused())
        assertThat(get(on = otherHost)).isEqualTo(Unauthorized)
        assertThat(otherHost.requestCount).isEqualTo(1)
    }

    @Test
    fun `a network failure of the authorizer fails the request as it is, and nothing goes out`() {
        val failure = IOException("The session needs a sign-in")
        authorizer.failure = failure

        val thrown = assertThrows(IOException::class.java) { get() }

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a renewal the authorizer cannot give after a 401 fails the request, the 401 not handed back nor replayed`() {
        val failure = IOException("The server refused the renewal")
        authorizer.renewalFailure = failure
        server.enqueue(refused())
        server.enqueue(MockResponse(code = Ok))

        val thrown = assertThrows(IOException::class.java) { get() }

        assertThat(thrown).isSameInstanceAs(failure)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(authorizer.asked)
            .containsExactly(null to server.url("/me"), "Bearer access-1" to server.url("/me"))
            .inOrder()
    }

    // OkHttp rethrows anything but an IOException on its own thread, which ends an Android app.
    @Test
    fun `any other failure of the authorizer fails the request as a network error`() {
        authorizer.failure = IllegalStateException("Keystore unavailable")

        val thrown = assertThrows(IOException::class.java) { get() }

        assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `an interrupted wait for the header fails as an interrupted network error, and keeps the flag`() {
        authorizer.slow = true
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { get() }
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(authorizer.asked).hasSize(1)
    }

    @Test
    fun `a source's own header goes to its server, and only there`() {
        keyed()
        server.enqueue(MockResponse(code = Ok))
        otherHost.enqueue(MockResponse(code = Ok))

        assertThat(get()).isEqualTo(Ok)
        assertThat(get(on = otherHost)).isEqualTo(Ok)

        val sent = server.takeRequest()
        assertThat(sent.headers[ApiKey]).isEqualTo("the-key")
        assertThat(sent.headers["Authorization"]).isNull()
        assertThat(otherHost.takeRequest().headers[ApiKey]).isNull()
    }

    @Test
    fun `a source's own header does not follow a redirect to another server`() {
        keyed()
        val elsewhere = otherHost.url("/moved").toString()
        server.enqueue(MockResponse(code = Redirect, headers = headersOf("Location", elsewhere)))
        otherHost.enqueue(refused())

        assertThat(get()).isEqualTo(Unauthorized)

        assertThat(server.takeRequest().headers[ApiKey]).isEqualTo("the-key")
        assertThat(otherHost.takeRequest().headers[ApiKey]).isNull()
        assertThat(otherHost.requestCount).isEqualTo(1)
        assertThat(authorizer.asked).containsExactly(null to server.url("/me"))
    }

    @Test
    fun `a key the server refuses marks the profile as needing a sign-in, and fails the request without a replay`() {
        keyed()
        authorizer.refusing = true
        server.enqueue(refused())
        server.enqueue(MockResponse(code = Ok))

        val thrown = assertThrows(IOException::class.java) { get() }

        assertThat(thrown).hasMessageThat().isEqualTo("The server refused the key")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(authorizer.signInNeeded).isTrue()
        assertThat(authorizer.asked).containsExactly(null to server.url("/me"), "the-key" to server.url("/me"))
            .inOrder()
    }

    @Test
    fun `a request goes to the address the authorizer gives, and still carries its header`() {
        authorizer.inAddress = "k" to "v"
        server.enqueue(MockResponse(code = Ok))

        assertThat(get(path = "/image?id=7")).isEqualTo(Ok)

        val sent = server.takeRequest()
        assertThat(sent.url.queryParameter("k")).isEqualTo("v")
        assertThat(sent.url.queryParameter("id")).isEqualTo("7")
        assertThat(sent.headers["Authorization"]).isEqualTo("Bearer access-1")
        assertThat(authorizer.asked).containsExactly(null to server.url("/image?id=7"))
    }

    @Test
    fun `by default a request goes to its own address`() {
        server.enqueue(MockResponse(code = Ok))

        assertThat(get(path = "/image?id=7")).isEqualTo(Ok)

        assertThat(server.takeRequest().url).isEqualTo(server.url("/image?id=7"))
    }

    private companion object {
        const val ApiKey = "X-API-Key"
        const val Ok = 200
        const val Redirect = 307
        const val Unauthorized = 401
    }
}
