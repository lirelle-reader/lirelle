package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.PendingOidcRequest
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.source.bookorbit.network.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.testing.InMemoryPendingOidcStore
import app.tfdn.lirelle.source.bookorbit.network.testing.InMemorySessionStore
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The refresh token of the session native-auth.json opens. */
private val NewRefreshToken = "1".repeat(64)

/** The refresh token of the session the device held for the account before it signed in again. */
private val OldRefreshToken = "0".repeat(64)

private val OldTokens = Tokens(
    accessToken = "access-0",
    accessTokenExpiresAt = TestNow.plusSeconds(900),
    refreshToken = OldRefreshToken,
    refreshTokenExpiresAt = TestNow.plusSeconds(7 * 24 * 3600L),
)

/**
 * A sign-in for an account the device already has a profile of replaces that profile's tokens: the session
 * those tokens held is ended on the server, so that the device does not leave one valid session behind at each
 * time the account is added again.
 */
class ReplacedSessionTest {

    private val server = MockWebServer()
    private val store = InMemorySessionStore()
    private val pendingOidc = InMemoryPendingOidcStore()
    private lateinit var repository: BookOrbitSignIn
    private lateinit var url: String

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        repository = BookOrbitSignIn(
            store = store,
            pendingOidc = pendingOidc,
            publicApi = ApiProvider(OkHttpClient(), ApiJson, PublicApi::class.java),
            errors = ErrorMapper(ApiJson),
            deviceLabel = { "Phone (Lirelle)" },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun refreshTokenOf(request: RecordedRequest): String? =
        ApiJson.decodeFromString<JsonObject>(request.bodyText())["refreshToken"]?.jsonPrimitive?.content

    private fun json(body: String = "{}", code: Int = 200) =
        MockResponse(code = code, headers = headersOf("Content-Type", "application/json"), body = body)

    /** The account of native-auth.json, signed in on this device before with [OldTokens]. */
    private suspend fun alreadyOnDevice(): Session = testSession(url, OldTokens).also { store.save(it) }

    @Test
    fun `re-adding a ready profile's account ends its old session on the server, once, after the save`() =
        runBlocking {
            alreadyOnDevice()
            var refreshSeenByServer: String? = null
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.pathText() == "/api/v1/auth/logout") {
                        refreshSeenByServer = store.session?.tokens?.refreshToken
                        json()
                    } else {
                        json(Fixtures.read("native-auth.json"))
                    }
            }

            val result = repository.signInWithPassword(url, "reader", "secret")

            assertThat(result).isEqualTo(ApiResult.Success(Unit))
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/auth/login")
            val logout = server.takeRequest()
            assertThat(logout.pathText()).isEqualTo("/api/v1/auth/logout")
            assertThat(refreshTokenOf(logout)).isEqualTo(OldRefreshToken)
            // Sent once the new session was saved: never the other way round.
            assertThat(refreshSeenByServer).isEqualTo(NewRefreshToken)
        }

    @Test
    fun `re-adding through OIDC ends the old session as well, with the old token only`() = runBlocking {
        alreadyOnDevice()
        pendingOidc.pending =
            PendingOidcRequest(url, "authentik", "state-1", "verifier-1", "nonce-1", System.currentTimeMillis())
        server.enqueueFixture("native-auth.json")
        server.enqueueJson("{}")

        val result = repository.completeOidc("bookorbit://oauth2-callback?code=code-1&state=state-1")

        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        // Counted first: a request that was not made is not waited for.
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/auth/oidc/callback")
        val logout = server.takeRequest()
        assertThat(logout.pathText()).isEqualTo("/api/v1/auth/logout")
        assertThat(refreshTokenOf(logout)).isEqualTo(OldRefreshToken)
        assertThat(refreshTokenOf(logout)).isNotEqualTo(NewRefreshToken)
    }

    @Test
    fun `a refused logout of the old session keeps the new one`() = runBlocking {
        alreadyOnDevice()
        server.enqueueFixture("native-auth.json")
        // The old session had expired on the server meanwhile.
        server.enqueueJson("""{"statusCode":401}""", code = 401)

        val result = repository.signInWithPassword(url, "reader", "secret")

        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(store.session?.tokens?.refreshToken).isEqualTo(NewRefreshToken)
    }

    @Test
    fun `a logout that cannot reach the server keeps the new session`() = runBlocking {
        alreadyOnDevice()
        server.enqueueFixture("native-auth.json")
        // The answer to the logout is lost on the way.
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

        val result = repository.signInWithPassword(url, "reader", "secret")

        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        assertThat(store.session?.tokens?.refreshToken).isEqualTo(NewRefreshToken)
    }

    @Test
    fun `a new account ends nobody's session`() = runBlocking {
        server.enqueueFixture("native-auth.json")

        repository.signInWithPassword(url, "reader", "secret")

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `another account of the same server ends nobody's session`() = runBlocking {
        val other = testSession(url, OldTokens)
        store.save(other.copy(user = other.user.copy(id = "2", username = "other")))
        server.enqueueFixture("native-auth.json")

        repository.signInWithPassword(url, "reader", "secret")

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `signing in again a profile marked as needing it ends nothing on the server`() = runBlocking {
        alreadyOnDevice()
        store.markSignInNeeded(checkNotNull(store.current.value).id)
        server.enqueueFixture("native-auth.json")

        val result = repository.signInWithPassword(url, "reader", "secret")

        // Its session is already refused there: telling the server would change nothing.
        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(store.current.value?.signInNeeded).isFalse()
    }

    @Test
    fun `the same session saved again is not ended`() = runBlocking {
        store.save(testSession(url, testTokens()))
        server.enqueueFixture("native-auth.json")

        repository.signInWithPassword(url, "reader", "secret")

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(store.session?.tokens?.refreshToken).isEqualTo(NewRefreshToken)
    }
}
