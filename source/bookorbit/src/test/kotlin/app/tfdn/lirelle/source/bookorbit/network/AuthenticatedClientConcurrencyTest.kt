package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.newAuthenticatedClient
import app.tfdn.lirelle.source.bookorbit.network.testing.FakeProfileSession
import app.tfdn.lirelle.source.bookorbit.network.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.testing.TestNow
import app.tfdn.lirelle.source.bookorbit.network.testing.pathText
import app.tfdn.lirelle.source.bookorbit.network.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.network.testing.testSession
import app.tfdn.lirelle.source.bookorbit.network.testing.testTokens
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Exercises the client wiring the app really uses, with more calls than OkHttp runs per host. */
class AuthenticatedClientConcurrencyTest {

    private val server = MockWebServer()
    private val refreshes = AtomicInteger()
    private val staleCalls = AtomicInteger()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.pathText()) {
                "/api/v1/auth/refresh" -> {
                    refreshes.incrementAndGet()
                    json(Fixtures.read("refresh.json"))
                }
                else -> {
                    if (request.headers["Authorization"] != "Bearer access-2") staleCalls.incrementAndGet()
                    json(Fixtures.read("auth-me.json"))
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(body: String) =
        MockResponse(code = 200, headers = headersOf("Content-Type", "application/json"), body = body)

    @Test
    fun `concurrent calls on an expiring token all complete after a single renewal`() = runBlocking {
        val profile = FakeProfileSession(
            testSession(server.serverUrl, testTokens(accessTokenExpiresAt = TestNow.plusSeconds(10))),
        )
        val publicClient = OkHttpClient()
        val refresher = TokenRefresher(profile, ApiProvider(publicClient, ApiJson, PublicApi::class.java)) { TestNow }
        val api = ApiProvider(newAuthenticatedClient(publicClient, refresher), ApiJson, AccountApi::class.java)
            .forServer(server.serverUrl)

        // A deadlock must fail the test instead of hanging it.
        val users = withTimeout(TimeoutMillis) {
            List(ConcurrentCalls) { async(Dispatchers.IO) { api.me() } }.awaitAll()
        }

        assertThat(users.map { it.username }).isEqualTo(List(ConcurrentCalls) { "reader" })
        assertThat(refreshes.get()).isEqualTo(1)
        assertThat(staleCalls.get()).isEqualTo(0)
    }

    private companion object {
        const val ConcurrentCalls = 12
        const val TimeoutMillis = 15_000L
    }
}
