package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.Pkce
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.source.audiobookshelf.testing.AbsOidcServer
import app.tfdn.lirelle.source.audiobookshelf.testing.MemoryPendingOidc
import app.tfdn.lirelle.source.audiobookshelf.testing.MemoryStore
import app.tfdn.lirelle.source.audiobookshelf.testing.SignedInAt
import app.tfdn.lirelle.source.audiobookshelf.testing.TheCookieHeader
import app.tfdn.lirelle.source.audiobookshelf.testing.absSignIn
import app.tfdn.lirelle.source.audiobookshelf.testing.pathText
import app.tfdn.lirelle.source.audiobookshelf.testing.providerPage
import app.tfdn.lirelle.source.audiobookshelf.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

private val Provider = OidcProvider("openid", "Authentik", clientId = "", scopes = "", ProviderFlow.Browser)

/**
 * Beginning a sign-in through Audiobookshelf's provider: the app's own first call, `GET auth/openid` with its state
 * and its PKCE challenge, returning to `lirelle://oauth` when the server allows it and to `audiobookshelf://oauth`
 * otherwise. Its redirect is not followed: it is the provider's page, which the browser opens; the cookies it sets
 * are kept with the pending sign-in, for the exchange of the code.
 */
class AbsOidcBeginTest {

    private val server = MockWebServer()
    private val routes = AbsOidcServer()
    private val pendingOidc = MemoryPendingOidc()
    private lateinit var url: String
    private lateinit var signIn: AbsSignIn

    @Before
    fun setUp() {
        server.dispatcher = routes
        server.start()
        url = server.serverUrl
        signIn = absSignIn(MemoryStore(), pendingOidc = pendingOidc)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun RecordedRequest.query(name: String): String? = url.queryParameter(name)

    @Test
    fun `a server that allows lirelle's address sends the browser to its provider's page`() = runBlocking<Unit> {
        val begun = signIn.beginOidc(url, Provider)

        val pending = pendingOidc.pending!!
        assertThat(begun).isEqualTo(ApiResult.Success(providerPage(pending.state)))
        val first = server.takeRequest()
        assertThat(first.method).isEqualTo("GET")
        assertThat(first.pathText()).isEqualTo("/auth/openid")
        assertThat(first.query("response_type")).isEqualTo("code")
        assertThat(first.query("code_challenge")).isEqualTo(Pkce.challenge(pending.codeVerifier))
        assertThat(first.query("code_challenge_method")).isEqualTo("S256")
        assertThat(first.query("redirect_uri")).isEqualTo("lirelle://oauth")
        assertThat(first.query("state")).isEqualTo(pending.state)
        assertThat(first.query("client_id")).isNull()
        assertThat(first.headers["Cookie"]).isNull()
        // The provider's page is the browser's to open: the redirect is not followed.
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(pending.serverUrl).isEqualTo(url)
        assertThat(pending.providerSlug).isEqualTo("openid")
        assertThat(pending.nonce).isEmpty()
        assertThat(pending.begunAtMillis).isEqualTo(SignedInAt.toEpochMilli())
        assertThat(pending.redirectUri).isEqualTo("lirelle://oauth")
        assertThat(pending.cookies).isEqualTo(TheCookieHeader)
    }

    @Test
    fun `each sign-in draws its own state and verifier`() = runBlocking<Unit> {
        signIn.beginOidc(url, Provider)
        val first = pendingOidc.pending!!
        signIn.beginOidc(url, Provider)
        val second = pendingOidc.pending!!

        assertThat(second.state).isNotEqualTo(first.state)
        assertThat(second.codeVerifier).isNotEqualTo(first.codeVerifier)
        assertThat(first.state).isNotEqualTo(first.codeVerifier)
    }

    @Test
    fun `a server that refuses lirelle's address is asked again with the official app's`() = runBlocking<Unit> {
        routes.allowed = setOf("audiobookshelf://oauth")

        val begun = signIn.beginOidc(url, Provider)

        val pending = pendingOidc.pending!!
        assertThat(begun).isEqualTo(ApiResult.Success(providerPage(pending.state)))
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.query("redirect_uri")).isEqualTo("lirelle://oauth")
        assertThat(second.query("redirect_uri")).isEqualTo("audiobookshelf://oauth")
        assertThat(second.pathText()).isEqualTo("/auth/openid")
        assertThat(second.query("state")).isEqualTo(first.query("state"))
        assertThat(second.query("code_challenge")).isEqualTo(first.query("code_challenge"))
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(pending.redirectUri).isEqualTo("audiobookshelf://oauth")
        assertThat(pending.cookies).isEqualTo(TheCookieHeader)
    }

    @Test
    fun `a server that refuses both addresses is unexpected, and nothing waits`() = runBlocking {
        routes.allowed = emptySet()

        assertThat(signIn.beginOidc(url, Provider)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(pendingOidc.pending).isNull()
    }

    @Test
    fun `a server under a base path is asked under it`() = runBlocking<Unit> {
        signIn.beginOidc("$url/audiobookshelf", Provider)

        assertThat(server.takeRequest().pathText()).isEqualTo("/audiobookshelf/auth/openid")
        assertThat(pendingOidc.pending?.serverUrl).isEqualTo("$url/audiobookshelf")
    }

    @Test
    fun `a provider's page that does not carry the state sent is unexpected`() = runBlocking {
        routes.location = { providerPage("someone-else's") }

        assertThat(signIn.beginOidc(url, Provider)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        assertThat(pendingOidc.pending).isNull()
    }

    @Test
    fun `a provider's page in cleartext is not opened`() = runBlocking {
        routes.location = { providerPage(it, origin = "http://idp.example.com") }

        assertThat(signIn.beginOidc(url, Provider)).isEqualTo(ApiResult.Failure(ApiError.UnencryptedSignInPage))
        assertThat(pendingOidc.pending).isNull()
    }

    @Test
    fun `a redirect to no page, or to one of the server's own, is unexpected`() = runBlocking {
        listOf<(String) -> String?>({ null }, { "/login?state=$it" }, { "not a page" }).forEach { where ->
            routes.location = where

            assertThat(signIn.beginOidc(url, Provider)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        }
        assertThat(pendingOidc.pending).isNull()
    }

    /** Without its session cookie the server could not exchange the code: the user would sign in for nothing. */
    @Test
    fun `a redirect that sets no cookie is unexpected`() = runBlocking {
        routes.setCookies = emptyList()

        assertThat(signIn.beginOidc(url, Provider)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        assertThat(pendingOidc.pending).isNull()
    }

    @Test
    fun `only the names and values of the cookies are kept`() = runBlocking<Unit> {
        routes.setCookies = listOf("connect.sid=abc; Path=/; HttpOnly; Secure", "auth_method=openid-mobile")

        signIn.beginOidc(url, Provider)

        assertThat(pendingOidc.pending?.cookies).isEqualTo("connect.sid=abc; auth_method=openid-mobile")
    }

    @Test
    fun `the login limit, a failing server and any other answer are told as such`() = runBlocking {
        val told = listOf(429, 500, 200, 401).map { status ->
            routes.firstStatus = status
            signIn.beginOidc(url, Provider)
        }

        assertThat(told).containsExactly(
            ApiResult.Failure(ApiError.TooManyAttempts),
            ApiResult.Failure(ApiError.ServerFailed),
            ApiResult.Failure(ApiError.Unexpected),
            ApiResult.Failure(ApiError.Unexpected),
        ).inOrder()
        assertThat(server.requestCount).isEqualTo(told.size)
        assertThat(pendingOidc.pending).isNull()
    }

    @Test
    fun `an unreachable server is told as such`() = runBlocking {
        server.close()

        assertThat(signIn.beginOidc(url, Provider)).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
        assertThat(pendingOidc.pending).isNull()
    }
}
