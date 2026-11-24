package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.newAuthenticatedClient
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.bookorbit.network.testing.FakeProfileSession
import app.tfdn.lirelle.source.bookorbit.network.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.testing.TestNow
import app.tfdn.lirelle.source.bookorbit.network.testing.enqueueFixture
import app.tfdn.lirelle.source.bookorbit.network.testing.enqueueJson
import app.tfdn.lirelle.source.bookorbit.network.testing.pathText
import app.tfdn.lirelle.source.bookorbit.network.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.network.testing.testSession
import app.tfdn.lirelle.source.bookorbit.network.testing.testTokens
import app.tfdn.lirelle.source.bookorbit.network.testing.tokens
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class AuthInterceptorTest {

    private val server = MockWebServer()
    private val otherHost = MockWebServer()
    private lateinit var profile: FakeProfileSession
    private lateinit var authenticatedClient: OkHttpClient
    private lateinit var api: AccountApi

    private val unauthorized = """{"statusCode":401,"message":"Unauthorized"}"""

    /** The renewal's own retries are [TokenRefresherTest]'s business. */
    private val noRetry = RefreshRetry(pausesMs = emptyList())

    @Before
    fun setUp() {
        server.start()
        otherHost.start()
        profile = FakeProfileSession(testSession(server.serverUrl))
        val publicClient = OkHttpClient()
        val refresher =
            TokenRefresher(profile, ApiProvider(publicClient, ApiJson, PublicApi::class.java), noRetry) { TestNow }
        authenticatedClient = newAuthenticatedClient(publicClient, refresher)
        api = ApiProvider(authenticatedClient, ApiJson, AccountApi::class.java).forServer(server.serverUrl)
    }

    @After
    fun tearDown() {
        server.close()
        otherHost.close()
    }

    private fun signIn(accessTokenExpiresAt: Instant = TestNow.plusSeconds(900)) {
        profile.session.value = testSession(server.serverUrl, testTokens(accessTokenExpiresAt = accessTokenExpiresAt))
    }

    @Test
    fun `sends the access token as a bearer header`() = runBlocking {
        signIn()
        server.enqueueFixture("auth-me.json")

        api.me()

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer access-1")
    }

    @Test
    fun `renews before the request when the token is about to expire`() = runBlocking {
        signIn(accessTokenExpiresAt = TestNow.plusSeconds(10))
        server.enqueueFixture("refresh.json")
        server.enqueueFixture("auth-me.json")

        api.me()

        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/auth/refresh")
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer access-2")
    }

    @Test
    fun `on 401 renews and replays the request with the new token`() = runBlocking {
        signIn()
        server.enqueueJson(unauthorized, code = 401)
        server.enqueueFixture("refresh.json")
        server.enqueueFixture("auth-me.json")

        val me = api.me()

        assertThat(me.username).isEqualTo("reader")
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer access-1")
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/auth/refresh")
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer access-2")
    }

    @Test
    fun `replays a rejected request once only`() {
        signIn()
        server.enqueueJson(unauthorized, code = 401)
        server.enqueueFixture("refresh.json")
        server.enqueueJson(unauthorized, code = 401)

        val exception = assertThrows(HttpException::class.java) { runBlocking { api.me() } }

        assertThat(exception.code()).isEqualTo(401)
        assertThat(server.requestCount).isEqualTo(3)
    }

    /** As without a network: whoever asked tries again later, and a download is not failed for good. */
    @Test
    fun `when renewal is refused the request fails as a network error and the session is marked`() {
        signIn()
        server.enqueueJson(unauthorized, code = 401)
        server.enqueueJson(unauthorized, code = 401)

        assertThrows(IOException::class.java) { runBlocking { api.me() } }

        assertThat(profile.expired).isTrue()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a failed renewal surfaces as a network error and keeps the session`() {
        signIn(accessTokenExpiresAt = TestNow.plusSeconds(10))
        server.enqueueJson("""{"statusCode":503}""", code = 503)

        assertThrows(IOException::class.java) { runBlocking { api.me() } }
        assertThat(profile.expired).isFalse()
    }

    @Test
    fun `a 401 after a redirect on the same server is still renewed and replayed`() = runBlocking {
        signIn()
        server.enqueue(MockResponse(code = 307, headers = headersOf("Location", "/api/v1/users/me")))
        server.enqueueJson(unauthorized, code = 401)
        server.enqueueFixture("refresh.json")
        server.enqueueFixture("auth-me.json")

        val me = api.me()

        assertThat(me.username).isEqualTo("reader")
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/auth/me")
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer access-1")
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/auth/refresh")
        val replayed = server.takeRequest()
        assertThat(replayed.pathText()).isEqualTo("/api/v1/users/me")
        assertThat(replayed.headers["Authorization"]).isEqualTo("Bearer access-2")
    }

    /** A view model made on a profile that closed, a service's last write: nothing is renewed for them. */
    @Test
    fun `a request of a closed profile whose token must be renewed fails as a network error`() {
        signIn(accessTokenExpiresAt = TestNow.plusSeconds(10))
        profile.gone = true

        assertThrows(IOException::class.java) { runBlocking { api.me() } }

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a request of a closed profile that the server rejects is not replayed`() {
        signIn()
        server.enqueueJson(unauthorized, code = 401)
        profile.gone = true

        assertThrows(IOException::class.java) { runBlocking { api.me() } }

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(profile.expired).isFalse()
    }

    // --- a session that needs a sign-in

    /** The dead tokens are shown to the server once, by the request that learns they are: never in a loop. */
    @Test
    fun `once renewal was refused, no call reaches the server until somebody signs in again`() {
        signIn()
        server.enqueueJson(unauthorized, code = 401)
        server.enqueueJson(unauthorized, code = 401)
        assertThrows(IOException::class.java) { runBlocking { api.me() } }
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/auth/me")
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/auth/refresh")

        repeat(10) { assertThrows(IOException::class.java) { runBlocking { api.me() } } }

        assertThat(server.requestCount).isEqualTo(2)
    }

    /** The request that meets the refusal fails as those after it do: none goes out without a token. */
    @Test
    fun `a renewal refused before the request sends nothing without a token, then or after`() {
        signIn(accessTokenExpiresAt = TestNow.plusSeconds(10))
        server.enqueueJson(unauthorized, code = 401)
        // What a request sent without a token would get, were one wrongly sent.
        server.enqueueJson(unauthorized, code = 401)

        assertThrows(IOException::class.java) { runBlocking { api.me() } }
        repeat(10) { assertThrows(IOException::class.java) { runBlocking { api.me() } } }

        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/auth/refresh")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a request to another host still goes out while the session needs a sign-in`() {
        signIn()
        profile.signInNeeded.value = true
        otherHost.enqueueJson("{}")

        authenticatedClient.newCall(Request.Builder().url(otherHost.url("/cover.jpg")).build()).execute().close()

        assertThat(otherHost.takeRequest().headers["Authorization"]).isNull()
    }

    @Test
    fun `the calls go out again, with the new tokens, once the same account has signed in`() = runBlocking<Unit> {
        signIn()
        profile.signInNeeded.value = true
        assertThrows(IOException::class.java) { runBlocking { api.me() } }

        profile.session.value = testSession(server.serverUrl, testTokens(accessToken = "access-again"))
        profile.signInNeeded.value = false
        server.enqueueFixture("auth-me.json")
        api.me()

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer access-again")
    }

    // --- other hosts

    @Test
    fun `a request to another host carries no access token`() {
        signIn()
        otherHost.enqueueJson("{}")

        authenticatedClient.newCall(Request.Builder().url(otherHost.url("/cover.jpg")).build()).execute().close()

        assertThat(otherHost.takeRequest().headers["Authorization"]).isNull()
    }

    @Test
    fun `a request to another host does not renew an expiring token`() {
        signIn(accessTokenExpiresAt = TestNow.plusSeconds(10))
        otherHost.enqueueJson("{}")

        authenticatedClient.newCall(Request.Builder().url(otherHost.url("/cover.jpg")).build()).execute().close()

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a 401 from another host neither renews nor ends the session`() {
        signIn()
        otherHost.enqueueJson(unauthorized, code = 401)
        // What a renewal would get if one were wrongly attempted.
        server.enqueueJson(unauthorized, code = 401)

        val response = authenticatedClient.newCall(Request.Builder().url(otherHost.url("/cover.jpg")).build()).execute()
        response.close()

        assertThat(response.code).isEqualTo(401)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(otherHost.requestCount).isEqualTo(1)
        assertThat(profile.expired).isFalse()
    }

    @Test
    fun `a 401 from another host to a request that brought its own bearer token is left alone`() {
        signIn()
        otherHost.enqueueJson(unauthorized, code = 401)
        server.enqueueJson(unauthorized, code = 401)
        val request = Request.Builder()
            .url(otherHost.url("/cover.jpg"))
            .header("Authorization", "Bearer access-1")
            .build()

        val response = authenticatedClient.newCall(request).execute()
        response.close()

        assertThat(response.code).isEqualTo(401)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(profile.expired).isFalse()
    }

    // --- failures that are not network errors

    /** A profile whose token write fails the way a broken Keystore would. */
    private class FailingWrites(private val delegate: ProfileSession) : ProfileSession by delegate {
        override suspend fun updateCredential(credential: Credential, replacing: Credential): Unit =
            error("Keystore unavailable")
    }

    private fun clientOver(failing: ProfileSession): OkHttpClient {
        val publicClient = OkHttpClient()
        val refresher =
            TokenRefresher(failing, ApiProvider(publicClient, ApiJson, PublicApi::class.java), noRetry) { TestNow }
        return newAuthenticatedClient(publicClient, refresher)
    }

    private fun me() = Request.Builder().url(server.url("/api/v1/auth/me")).build()

    // OkHttp rethrows anything but an IOException on its own thread, which ends an Android app.
    // A synchronous call shows exactly what leaves the interceptor.
    @Test
    fun `an unexpected failure while renewing before a request surfaces as a network error`() {
        signIn(accessTokenExpiresAt = TestNow.plusSeconds(10))
        server.enqueueFixture("refresh.json")
        val client = clientOver(FailingWrites(profile))

        val exception = assertThrows(IOException::class.java) { client.newCall(me()).execute() }

        assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException::class.java)
        assertThat(profile.expired).isFalse()
    }

    @Test
    fun `an unexpected failure while renewing after a 401 surfaces as a network error`() {
        signIn()
        server.enqueueJson(unauthorized, code = 401)
        server.enqueueFixture("refresh.json")
        val client = clientOver(FailingWrites(profile))

        val exception = assertThrows(IOException::class.java) { client.newCall(me()).execute() }

        assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException::class.java)
        assertThat(profile.expired).isFalse()
    }

    @Test
    fun `an interrupted wait surfaces as an interrupted network error and the renewal still completes`() {
        signIn(accessTokenExpiresAt = TestNow.plusSeconds(10))
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body(Fixtures.read("refresh.json"))
                .headersDelay(RenewalDelayMillis, TimeUnit.MILLISECONDS)
                .build(),
        )
        server.enqueueFixture("auth-me.json")

        // The interruption is seen as soon as the thread starts waiting for the renewal.
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { authenticatedClient.newCall(me()).execute() }
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }

        // The refresh token was already on its way: its answer must be stored, and the next
        // call must not find the refresher stuck.
        val username = runBlocking { withTimeout(TimeoutMillis) { api.me().username } }
        assertThat(username).isEqualTo("reader")
        assertThat(profile.session.value.tokens.accessToken).isEqualTo("access-2")
        assertThat(server.requestCount).isEqualTo(2)
    }

    private companion object {
        const val RenewalDelayMillis = 300L
        const val TimeoutMillis = 10_000L
    }
}
