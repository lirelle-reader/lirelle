package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.bookorbit.network.testing.FakeProfileSession
import app.tfdn.lirelle.source.bookorbit.network.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.testing.TestNow
import app.tfdn.lirelle.source.bookorbit.network.testing.bodyText
import app.tfdn.lirelle.source.bookorbit.network.testing.enqueueFixture
import app.tfdn.lirelle.source.bookorbit.network.testing.enqueueJson
import app.tfdn.lirelle.source.bookorbit.network.testing.pathText
import app.tfdn.lirelle.source.bookorbit.network.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.network.testing.testSession
import app.tfdn.lirelle.source.bookorbit.network.testing.testTokens
import app.tfdn.lirelle.source.bookorbit.network.testing.tokens
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class TokenRefresherTest {

    private val server = MockWebServer()
    private lateinit var profile: FakeProfileSession
    private lateinit var refresher: TokenRefresher

    /** Milliseconds on the refresher's clock: moved by its pauses, and by the server below. */
    @Volatile
    private var clock = 0L
    private val pauses = mutableListOf<Long>()

    @Before
    fun setUp() {
        server.start()
        profile = FakeProfileSession(testSession(server.serverUrl))
        // As in the app: OkHttp does not retry the refresh itself.
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val retry = RefreshRetry(elapsed = { clock }, pause = { pauses += it; clock += it })
        refresher = TokenRefresher(profile, ApiProvider(client, ApiJson, PublicApi::class.java), retry) { TestNow }
    }

    /** The server reads the request, so it may have rotated the token, and the answer never comes back. */
    private fun lostAnswer() = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()

    /**
     * The server holds [response] back until the returned latch is opened: the test closes the profile under
     * the renewal, then lets the answer come. No delay is raced: however slow the machine, the answer never
     * comes before the profile is closed.
     */
    private fun held(response: MockResponse.Builder): CountDownLatch {
        val answer = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(answer.await(HeldAnswerSeconds, TimeUnit.SECONDS)) { "The test never let the answer go" }
                return response.addHeader("Content-Type", "application/json").build()
            }
        }
        return answer
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun signIn(accessTokenExpiresAt: Instant = TestNow.plusSeconds(900)) {
        profile.session.value = testSession(server.serverUrl, testTokens(accessTokenExpiresAt = accessTokenExpiresAt))
    }

    @Test
    fun `returns the current tokens when they are still valid`() = runBlocking {
        signIn()

        assertThat(refresher.freshTokens()?.accessToken).isEqualTo("access-1")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `renews a token that expires within 60 seconds`() = runBlocking {
        signIn(accessTokenExpiresAt = TestNow.plusSeconds(59))
        server.enqueueFixture("refresh.json")

        val tokens = refresher.freshTokens()

        assertThat(tokens?.accessToken).isEqualTo("access-2")
        val request = server.takeRequest()
        assertThat(request.pathText()).isEqualTo("/api/v1/auth/refresh")
        assertThat(request.bodyText()).isEqualTo("""{"refreshToken":"${"1".repeat(64)}"}""")
    }

    @Test
    fun `does not renew a token that expires in more than 60 seconds`() = runBlocking {
        signIn(accessTokenExpiresAt = TestNow.plusSeconds(61))

        assertThat(refresher.freshTokens()?.accessToken).isEqualTo("access-1")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `renewed tokens are in the store before they are returned`() = runBlocking {
        signIn(accessTokenExpiresAt = TestNow)
        server.enqueueFixture("refresh.json")

        val tokens = refresher.freshTokens()

        assertThat(profile.session.value.tokens).isEqualTo(tokens)
        assertThat(tokens?.refreshToken).isEqualTo("2".repeat(64))
        assertThat(tokens?.accessTokenExpiresAt).isEqualTo(Instant.parse("2026-11-13T08:30:00Z"))
        assertThat(tokens?.refreshTokenExpiresAt).isEqualTo(Instant.parse("2026-11-20T08:15:00Z"))
    }

    @Test
    fun `renews a token the server rejected even if it has not expired`() = runBlocking {
        signIn()
        server.enqueueFixture("refresh.json")

        assertThat(refresher.freshTokens(rejectedAccessToken = "access-1")?.accessToken).isEqualTo("access-2")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `does not renew again when the rejected token was already replaced`() = runBlocking {
        signIn()

        assertThat(refresher.freshTokens(rejectedAccessToken = "access-0")?.accessToken).isEqualTo("access-1")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `concurrent callers share one renewal`() = runBlocking(Dispatchers.Default) {
        signIn()
        server.enqueueFixture("refresh.json")

        val results = List(8) { async { refresher.freshTokens(rejectedAccessToken = "access-1") } }.awaitAll()

        assertThat(results.map { it?.accessToken }.toSet()).containsExactly("access-2")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a 401 from the refresh route marks the session as needing a sign-in`() = runBlocking {
        signIn(accessTokenExpiresAt = TestNow)
        server.enqueueJson("""{"statusCode":401,"message":"Unauthorized"}""", code = 401)

        // As every request after it: nothing is handed out, and the caller sees a network failure.
        assertThat(runCatching { refresher.freshTokens() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(profile.expired).isTrue()
    }

    /**
     * The mark is looked at under the renewal's lock: those who waited behind the renewal that was refused
     * learn of it there, and none sends the dead refresh token again.
     */
    @Test
    fun `concurrent callers behind a refused renewal send the dead token once`() = runBlocking(Dispatchers.Default) {
        signIn()
        val answer = held(MockResponse.Builder().code(401).body("""{"statusCode":401,"message":"Unauthorized"}"""))

        val callers = List(8) { async { runCatching { refresher.freshTokens(rejectedAccessToken = "access-1") } } }
        // The first renewal is at the server, the others wait behind it, or are about to.
        server.takeRequest()
        answer.countDown()
        val results = callers.awaitAll()

        results.forEach { assertThat(it.exceptionOrNull()).isInstanceOf(IOException::class.java) }
        assertThat(profile.expired).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a session that needs a sign-in is neither renewed nor used`() {
        signIn(accessTokenExpiresAt = TestNow)
        server.enqueueJson("""{"statusCode":401,"message":"Unauthorized"}""", code = 401)
        assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }

        repeat(10) { assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } } }
        assertThrows(IOException::class.java) {
            runBlocking { refresher.freshTokens(rejectedAccessToken = "access-1") }
        }

        assertThat(server.requestCount).isEqualTo(1)
    }

    /** The server refused a token the clock still held good: it is not shown to the server again either. */
    @Test
    fun `tokens that have not expired are not handed out while the session needs a sign-in`() {
        signIn()
        profile.signInNeeded.value = true

        assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `the tokens of a new sign-in are handed out, and renewed in their turn`() = runBlocking<Unit> {
        signIn(accessTokenExpiresAt = TestNow)
        profile.signInNeeded.value = true

        // As the same account signing in again leaves the profile's session.
        signIn()
        profile.signInNeeded.value = false

        assertThat(refresher.freshTokens()?.accessToken).isEqualTo("access-1")
        server.enqueueFixture("refresh.json")
        assertThat(refresher.freshTokens(rejectedAccessToken = "access-1")?.accessToken).isEqualTo("access-2")
    }

    @Test
    fun `a server error is tried again, then keeps the session and surfaces as an IOException`() {
        signIn(accessTokenExpiresAt = TestNow)
        repeat(4) { server.enqueueJson("""{"statusCode":500}""", code = 500) }

        assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }
        assertThat(profile.session.value.tokens.accessToken).isEqualTo("access-1")
        assertThat(server.requestCount).isEqualTo(4)
        assertThat(pauses).containsExactly(1_000L, 3_000L, 6_000L).inOrder()
    }

    @Test
    fun `a connection that could not be made rotated nothing and is not tried again`() {
        signIn(accessTokenExpiresAt = TestNow)
        server.close()

        assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }
        assertThat(profile.session.value.tokens.accessToken).isEqualTo("access-1")
        assertThat(pauses).isEmpty()
    }

    @Test
    fun `an answer lost after the server had the request is asked again with the same token`() = runBlocking<Unit> {
        signIn(accessTokenExpiresAt = TestNow)
        server.enqueue(lostAnswer())
        server.enqueueFixture("refresh.json")

        val tokens = refresher.freshTokens()

        assertThat(tokens?.accessToken).isEqualTo("access-2")
        assertThat(profile.session.value.tokens.refreshToken).isEqualTo("2".repeat(64))
        val sent = """{"refreshToken":"${"1".repeat(64)}"}"""
        assertThat(server.takeRequest().bodyText()).isEqualTo(sent)
        assertThat(server.takeRequest().bodyText()).isEqualTo(sent)
        assertThat(pauses).containsExactly(1_000L)
    }

    @Test
    fun `every retry starts within the server's grace for a replayed token`() {
        signIn(accessTokenExpiresAt = TestNow)
        val starts = mutableListOf<Long>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                starts += clock
                // Each attempt takes as long as the app lets one take.
                clock += 8_000
                return lostAnswer()
            }
        }

        assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }

        assertThat(starts).containsExactly(0L, 9_000L, 20_000L).inOrder()
        assertThat(starts.max()).isAtMost(25_000L)
        assertThat(profile.session.value.tokens.accessToken).isEqualTo("access-1")
    }

    @Test
    fun `a 401 on a retry still marks the session`() = runBlocking<Unit> {
        signIn(accessTokenExpiresAt = TestNow)
        server.enqueue(lostAnswer())
        server.enqueueJson("""{"statusCode":401,"message":"Unauthorized"}""", code = 401)

        assertThat(runCatching { refresher.freshTokens() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(profile.expired).isTrue()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a caller cancelled during the renewal still stores the renewed tokens`() = runBlocking {
        signIn(accessTokenExpiresAt = TestNow)
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body(Fixtures.read("refresh.json"))
                .headersDelay(ResponseDelayMillis, TimeUnit.MILLISECONDS)
                .build(),
        )

        val caller = launch(Dispatchers.Default) { refresher.freshTokens() }
        // The server has the request, so it has rotated the refresh token.
        server.takeRequest()
        caller.cancelAndJoin()

        assertThat(profile.session.value.tokens.accessToken).isEqualTo("access-2")
        assertThat(profile.session.value.tokens.refreshToken).isEqualTo("2".repeat(64))
    }

    /** A screen that is leaving, a service's last write: served while the tokens it has still do. */
    @Test
    fun `a request begun after close goes out with the tokens already known`() = runBlocking<Unit> {
        signIn()
        profile.gone = true

        assertThat(refresher.freshTokens()?.accessToken).isEqualTo("access-1")
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** The sign-out is sending that refresh token to the server: it is not replayed beside it. */
    @Test
    fun `a request begun after close renews nothing and fails`() {
        signIn(accessTokenExpiresAt = TestNow)
        val before = profile.session.value
        server.enqueueFixture("refresh.json")
        profile.gone = true

        assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }
        assertThrows(IOException::class.java) {
            runBlocking { refresher.freshTokens(rejectedAccessToken = "access-1") }
        }

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(profile.session.value).isEqualTo(before)
        assertThat(profile.expired).isFalse()
    }

    /** A renewal still in flight when its profile closes: the next account's session is not its to write. */
    @Test
    fun `a renewal in flight at close still writes nowhere`() = runBlocking<Unit> {
        signIn(accessTokenExpiresAt = TestNow)
        val before = profile.session.value
        val answer = held(MockResponse.Builder().body(Fixtures.read("refresh.json")))

        val caller = async(Dispatchers.Default) { refresher.freshTokens() }
        server.takeRequest()
        profile.gone = true
        answer.countDown()

        // The caller's own request may still go out with them; nothing keeps them.
        assertThat(caller.await()?.accessToken).isEqualTo("access-2")
        assertThat(profile.session.value).isEqualTo(before)
    }

    @Test
    fun `a renewal in flight at close that the server refuses ends nothing`() = runBlocking<Unit> {
        signIn(accessTokenExpiresAt = TestNow)
        val answer = held(MockResponse.Builder().code(401).body("""{"statusCode":401,"message":"Unauthorized"}"""))

        val caller = async(Dispatchers.Default) { runCatching { refresher.freshTokens() } }
        server.takeRequest()
        profile.gone = true
        answer.countDown()

        assertThat(caller.await().exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(profile.expired).isFalse()
    }

    // --- the same account signs in again while a renewal of its old tokens is on its way

    /** As [ProfileSession.session] is left by the account signing in again: another refresh token. */
    private fun signInAgain(): Tokens {
        val again = testTokens(accessToken = "access-again").copy(refreshToken = "9".repeat(64))
        profile.session.value = testSession(server.serverUrl, again)
        return again
    }

    @Test
    fun `a refusal that comes after a new sign-in marks nobody, and the new tokens are served`() = runBlocking<Unit> {
        signIn(accessTokenExpiresAt = TestNow)
        val answer = held(MockResponse.Builder().code(401).body("""{"statusCode":401,"message":"Unauthorized"}"""))

        val caller = async(Dispatchers.Default) { refresher.freshTokens() }
        server.takeRequest()
        val again = signInAgain()
        answer.countDown()

        assertThat(caller.await()).isEqualTo(again)
        assertThat(profile.expired).isFalse()
        assertThat(profile.session.value.tokens).isEqualTo(again)
        // Nothing is left of the refusal: the next caller is served too, without a word to the server.
        assertThat(refresher.freshTokens()).isEqualTo(again)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a renewal that ends after a new sign-in leaves the new tokens, and serves them`() = runBlocking<Unit> {
        signIn(accessTokenExpiresAt = TestNow)
        val answer = held(MockResponse.Builder().body(Fixtures.read("refresh.json")))

        val caller = async(Dispatchers.Default) { refresher.freshTokens() }
        server.takeRequest()
        val again = signInAgain()
        answer.countDown()

        assertThat(caller.await()).isEqualTo(again)
        assertThat(profile.session.value.tokens).isEqualTo(again)
    }

    @Test
    fun `an answer lost for a profile that closed meanwhile is not asked again`() {
        signIn(accessTokenExpiresAt = TestNow)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                profile.gone = true
                return lostAnswer()
            }
        }

        assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(pauses).isEmpty()
    }

    @Test
    fun `an answer that is not JSON keeps the session and surfaces as an IOException`() {
        signIn(accessTokenExpiresAt = TestNow)
        repeat(4) {
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "text/html")
                    .body("<html><body>Sign in to the proxy</body></html>")
                    .build(),
            )
        }

        assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }
        assertThat(profile.session.value.tokens.accessToken).isEqualTo("access-1")
    }

    private companion object {
        const val ResponseDelayMillis = 500L

        /** How long a held answer waits for the test to let it go before it fails the test instead. */
        const val HeldAnswerSeconds = 60L
    }
}
