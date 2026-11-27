package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.PendingOidcRequest
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.source.audiobookshelf.testing.AbsOidcServer
import app.tfdn.lirelle.source.audiobookshelf.testing.AccessExpiry
import app.tfdn.lirelle.source.audiobookshelf.testing.AnnId
import app.tfdn.lirelle.source.audiobookshelf.testing.MemoryPendingOidc
import app.tfdn.lirelle.source.audiobookshelf.testing.MemoryStore
import app.tfdn.lirelle.source.audiobookshelf.testing.RefreshExpiry
import app.tfdn.lirelle.source.audiobookshelf.testing.SignedInAt
import app.tfdn.lirelle.source.audiobookshelf.testing.TheAccessToken
import app.tfdn.lirelle.source.audiobookshelf.testing.TheCookieHeader
import app.tfdn.lirelle.source.audiobookshelf.testing.TheRefreshToken
import app.tfdn.lirelle.source.audiobookshelf.testing.TheSignInId
import app.tfdn.lirelle.source.audiobookshelf.testing.absSignIn
import app.tfdn.lirelle.source.audiobookshelf.testing.exchangeAnswer
import app.tfdn.lirelle.source.audiobookshelf.testing.failedExchange
import app.tfdn.lirelle.source.audiobookshelf.testing.loginJson
import app.tfdn.lirelle.source.audiobookshelf.testing.pathText
import app.tfdn.lirelle.source.audiobookshelf.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val State = "state-1"
private const val Verifier = "verifier-1"
private const val Return = "lirelle://oauth?code=code%2F1&state=$State"

/**
 * Ending a sign-in through Audiobookshelf's provider: the return to the address the server accepted, carrying the
 * state of the sign-in that waits, has its code exchanged (`GET auth/openid/callback`, with the verifier and the
 * first call's cookies) for the same answer as a password sign-in, saved the same way. Any other return leaves the
 * sign-in waiting; a refusal, a failed exchange and a sign-in begun too long ago end it.
 */
class AbsOidcSignInTest {

    private val server = MockWebServer()
    private val routes = AbsOidcServer()
    private val store = MemoryStore()
    private val pendingOidc = MemoryPendingOidc()
    private lateinit var url: String
    private lateinit var signIn: AbsSignIn

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
        url = server.serverUrl
        signIn = absSignIn(store, pendingOidc = pendingOidc)
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** A sign-in begun [ago], returning to [address], waiting for the return that carries [State]. */
    private fun begun(ago: Duration? = Duration.ZERO, address: String? = "lirelle://oauth") = PendingOidcRequest(
        serverUrl = url,
        providerSlug = "openid",
        state = State,
        codeVerifier = Verifier,
        nonce = "",
        begunAtMillis = ago?.let { SignedInAt.minus(it).toEpochMilli() },
        redirectUri = address,
        cookies = TheCookieHeader,
    )

    private val refused = ApiResult.Failure(ApiError.OidcRefused)

    @Test
    fun `the return of the sign-in that waits has its code exchanged, and the profile saved`() = runBlocking<Unit> {
        pendingOidc.pending = begun()

        assertThat(signIn.completeOidc(Return)).isEqualTo(ApiResult.Success(Unit))

        val exchange = server.takeRequest()
        assertThat(exchange.method).isEqualTo("GET")
        assertThat(exchange.pathText()).isEqualTo("/auth/openid/callback")
        assertThat(exchange.url.queryParameter("state")).isEqualTo(State)
        assertThat(exchange.url.queryParameter("code")).isEqualTo("code/1")
        assertThat(exchange.url.queryParameter("code_verifier")).isEqualTo(Verifier)
        assertThat(exchange.headers["Cookie"]).isEqualTo(TheCookieHeader)
        assertThat(exchange.headers["Authorization"]).isNull()
        assertThat(server.requestCount).isEqualTo(1)
        val ann = UserProfile(id = AnnId, username = "ann", name = "ann", email = null, canDownload = true)
        val tokens = Tokens(TheAccessToken, AccessExpiry, TheRefreshToken, RefreshExpiry)
        val credential = Credential.RotatingTokens(TheSignInId, tokens)
        assertThat(store.saved).containsExactly(Session(url, credential, ann) to ServerKind.Audiobookshelf)
        assertThat(pendingOidc.pending).isNull()
    }

    @Test
    fun `a sign-in begun from the start returns to the address the server took`() = runBlocking<Unit> {
        routes.allowed = setOf("audiobookshelf://oauth")
        val provider = OidcProvider("openid", "OpenID", clientId = "", scopes = "", ProviderFlow.Browser)
        signIn.beginOidc(url, provider)
        val state = pendingOidc.pending!!.state

        assertThat(signIn.answersPendingOidc("lirelle://oauth?code=c&state=$state")).isFalse()
        assertThat(signIn.answersPendingOidc("audiobookshelf://oauth?code=c&state=$state")).isTrue()
        assertThat(signIn.hasPendingOidc()).isTrue()
        assertThat(signIn.completeOidc("audiobookshelf://oauth?code=c&state=$state"))
            .isEqualTo(ApiResult.Success(Unit))

        // The two first calls, then the exchange.
        repeat(2) { server.takeRequest() }
        val exchange = server.takeRequest()
        assertThat(exchange.pathText()).isEqualTo("/auth/openid/callback")
        assertThat(exchange.headers["Cookie"]).isEqualTo(TheCookieHeader)
        assertThat(exchange.url.queryParameter("state")).isEqualTo(state)
        assertThat(store.saved).hasSize(1)
        assertThat(signIn.hasPendingOidc()).isFalse()
    }

    @Test
    fun `a return answers the sign-in that waits when it comes to its address with its state`() = runBlocking {
        assertThat(signIn.answersPendingOidc(Return)).isFalse()
        pendingOidc.pending = begun()

        assertThat(signIn.answersPendingOidc(Return)).isTrue()
        assertThat(signIn.answersPendingOidc("lirelle://oauth?code=undefined&state=$State")).isTrue()
        assertThat(signIn.answersPendingOidc("LIRELLE://OAUTH?code=c&state=$State")).isTrue()
        assertThat(signIn.answersPendingOidc("lirelle://oauth?code=c&state=other")).isFalse()
        assertThat(signIn.answersPendingOidc("lirelle://oauth?code=c")).isFalse()
        assertThat(signIn.answersPendingOidc("audiobookshelf://oauth?code=c&state=$State")).isFalse()
        assertThat(signIn.answersPendingOidc("lirelle://elsewhere?code=c&state=$State")).isFalse()
        assertThat(signIn.answersPendingOidc("bookorbit://oauth2-callback?code=c&state=$State")).isFalse()
        assertThat(signIn.answersPendingOidc("https://evil.example.com/?code=c&state=$State")).isFalse()
        assertThat(signIn.answersPendingOidc("not a uri at all %zz")).isFalse()
        // Asking uses nothing up.
        assertThat(pendingOidc.pending).isEqualTo(begun())
    }

    /** BookOrbit's sign-in waits in the same place, and returns to its own address, which the record does not name. */
    @Test
    fun `a BookOrbit sign-in that waits is not Audiobookshelf's to end`() = runBlocking {
        pendingOidc.pending = begun(address = null)

        assertThat(signIn.answersPendingOidc("bookorbit://oauth2-callback?code=c&state=$State")).isFalse()
        assertThat(signIn.answersPendingOidc(Return)).isFalse()
        assertThat(signIn.completeOidc("bookorbit://oauth2-callback?code=c&state=$State")).isEqualTo(refused)
        assertThat(signIn.completeOidc(Return)).isEqualTo(refused)

        assertThat(pendingOidc.pending).isEqualTo(begun(address = null))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a return that is not the sign-in's leaves it waiting`() = runBlocking {
        pendingOidc.pending = begun()

        listOf(
            "lirelle://oauth?code=c&state=other",
            "lirelle://oauth?error=access_denied&state=other",
            "lirelle://oauth?code=c",
            "audiobookshelf://oauth?code=c&state=$State",
            "lirelle://oauth?code=c&state=%zz",
        ).forEach { assertThat(signIn.completeOidc(it)).isEqualTo(refused) }

        assertThat(pendingOidc.pending).isEqualTo(begun())
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** The address the server took is compared whole, as BookOrbit compares its own: no path, no user, no port. */
    @Test
    fun `a return to another address than the sign-in's, even by a path, leaves it waiting`() = runBlocking {
        pendingOidc.pending = begun()

        listOf(
            "lirelle://oauth/x?code=c&state=$State",
            "lirelle://oauth/?code=c&state=$State",
            "lirelle://user@oauth?code=c&state=$State",
            "lirelle://oauth:80?code=c&state=$State",
            "LIRELLE://OAUTH/x?code=c&state=$State",
        ).forEach {
            assertThat(signIn.answersPendingOidc(it)).isFalse()
            assertThat(signIn.completeOidc(it)).isEqualTo(refused)
        }

        assertThat(pendingOidc.pending).isEqualTo(begun())
        assertThat(server.requestCount).isEqualTo(0)
    }

    /** Audiobookshelf passes the provider's refusal on as a code `undefined`, or not at all. */
    @Test
    fun `a return without a code ends the sign-in, refused`() = runBlocking {
        listOf(
            "lirelle://oauth?code=undefined&state=$State",
            "lirelle://oauth?code=&state=$State",
            "lirelle://oauth?state=$State",
            "lirelle://oauth?error=access_denied&state=$State",
            "lirelle://oauth?code=c&error=access_denied&state=$State",
        ).forEach {
            pendingOidc.pending = begun()

            assertThat(signIn.completeOidc(it)).isEqualTo(refused)
            assertThat(pendingOidc.pending).isNull()
        }
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a sign-in begun too long ago, or not saying when, is refused and waits no more`() = runBlocking {
        listOf(Duration.ofMinutes(11), Duration.ofMinutes(-11), null).forEach { ago ->
            pendingOidc.pending = begun(ago = ago)

            assertThat(signIn.completeOidc(Return)).isEqualTo(refused)
            assertThat(pendingOidc.pending).isNull()
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a sign-in begun a few minutes ago is completed`() = runBlocking {
        pendingOidc.pending = begun(ago = Duration.ofMinutes(9))

        assertThat(signIn.completeOidc(Return)).isEqualTo(ApiResult.Success(Unit))
    }

    /** The server answers a failed exchange with a redirect to its web sign-in page, which is never followed. */
    @Test
    fun `a failed exchange is a refused sign-in, and its redirect is not followed`() = runBlocking {
        pendingOidc.pending = begun()
        routes.exchange = failedExchange()

        assertThat(signIn.completeOidc(Return)).isEqualTo(ApiResult.Failure(ApiError.SignInRefused))

        assertThat(server.requestCount).isEqualTo(1)
        assertThat(pendingOidc.pending).isNull()
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `each refusal of the exchange is told, and ends the sign-in`() = runBlocking {
        val told = listOf(
            exchangeAnswer(400, "No session"),
            exchangeAnswer(401, "Unauthorized"),
            exchangeAnswer(403, "Forbidden"),
            exchangeAnswer(429, "Too many requests"),
            exchangeAnswer(500, "Error"),
            exchangeAnswer(200, """{"error":"nope"}"""),
            exchangeAnswer(200, loginJson(refreshToken = null)),
        ).map { answer ->
            pendingOidc.pending = begun()
            routes.exchange = answer
            signIn.completeOidc(Return).also { assertThat(pendingOidc.pending).isNull() }
        }

        assertThat(told).containsExactly(
            // Its session gone — the server restarted, or it was used already: the user begins again.
            refused,
            ApiResult.Failure(ApiError.SignInRefused),
            ApiResult.Failure(ApiError.SignInRefused),
            ApiResult.Failure(ApiError.TooManyAttempts),
            ApiResult.Failure(ApiError.ServerFailed),
            ApiResult.Failure(ApiError.Unexpected),
            ApiResult.Failure(ApiError.Unexpected),
        ).inOrder()
        assertThat(server.requestCount).isEqualTo(told.size)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `an unreachable server ends the sign-in`() = runBlocking {
        pendingOidc.pending = begun()
        server.close()

        assertThat(signIn.completeOidc(Return)).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
        assertThat(pendingOidc.pending).isNull()
    }

    @Test
    fun `a cancelled sign-in waits no more`() = runBlocking {
        assertThat(signIn.hasPendingOidc()).isFalse()
        pendingOidc.pending = begun()
        assertThat(signIn.hasPendingOidc()).isTrue()

        signIn.cancelOidc()

        assertThat(signIn.hasPendingOidc()).isFalse()
        assertThat(signIn.completeOidc(Return)).isEqualTo(refused)
        assertThat(server.requestCount).isEqualTo(0)
    }
}
