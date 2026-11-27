package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ReplacedSession
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.source.audiobookshelf.testing.AccessExpiry
import app.tfdn.lirelle.source.audiobookshelf.testing.AnnId
import app.tfdn.lirelle.source.audiobookshelf.testing.MemoryStore
import app.tfdn.lirelle.source.audiobookshelf.testing.RefreshExpiry
import app.tfdn.lirelle.source.audiobookshelf.testing.SignedInAt
import app.tfdn.lirelle.source.audiobookshelf.testing.TheAccessToken
import app.tfdn.lirelle.source.audiobookshelf.testing.TheOldToken
import app.tfdn.lirelle.source.audiobookshelf.testing.TheRefreshToken
import app.tfdn.lirelle.source.audiobookshelf.testing.TheSignInId
import app.tfdn.lirelle.source.audiobookshelf.testing.absSignIn
import app.tfdn.lirelle.source.audiobookshelf.testing.bodyText
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueDone
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueJson
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueRefusal
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueText
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueTooMany
import app.tfdn.lirelle.source.audiobookshelf.testing.loginJson
import app.tfdn.lirelle.source.audiobookshelf.testing.pathText
import app.tfdn.lirelle.source.audiobookshelf.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val Username = "ann"
private const val Password = "s3cr3t-p4ssw0rd"

/** Far less than the 20 s a server takes not to answer; far more than the second a logout is given. */
private const val GivenUpMillis = 5_000L

/**
 * Signing in to Audiobookshelf by name and password: `POST login`, asking for the tokens in the answer, saves an
 * Audiobookshelf profile holding the access and the refresh token, their expiries read from them, under a sign-in
 * id the app draws. The password and the old token every answer also carries are kept nowhere. Signing in again to
 * the same account closes, at best, the session the profile held.
 */
class AbsPasswordSignInTest {

    private val server = MockWebServer()
    private val store = MemoryStore()
    private lateinit var url: String
    private lateinit var signIn: AbsSignIn

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        signIn = absSignIn(store)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val ann = UserProfile(id = AnnId, username = "ann", name = "ann", email = null, canDownload = true)

    private val theTokens = Tokens(TheAccessToken, AccessExpiry, TheRefreshToken, RefreshExpiry)

    private val oldTokens = Tokens("old-access", Instant.EPOCH, "old-refresh", Instant.EPOCH)

    @Test
    fun `a password sign-in asks for the tokens and saves an Audiobookshelf profile holding them`() =
        runBlocking<Unit> {
            server.enqueueJson(loginJson())

            assertThat(signIn.signInWithPassword(url, Username, Password)).isEqualTo(ApiResult.Success(Unit))

            val login = server.takeRequest()
            assertThat(login.method).isEqualTo("POST")
            assertThat(login.pathText()).isEqualTo("/login")
            assertThat(login.headers["x-return-tokens"]).isEqualTo("true")
            assertThat(login.headers["Authorization"]).isNull()
            assertThat(login.bodyText()).isEqualTo("""{"username":"ann","password":"$Password"}""")
            assertThat(server.requestCount).isEqualTo(1)
            val credential = Credential.RotatingTokens(TheSignInId, theTokens)
            assertThat(store.saved).containsExactly(Session(url, credential, ann) to ServerKind.Audiobookshelf)
        }

    /** A phone an hour ahead of the server: its tokens last the hour they state, from when they came. */
    @Test
    fun `the tokens' expiries are their lifetimes from the sign-in, on the device's clock`() = runBlocking<Unit> {
        val ahead = SignedInAt.plus(Duration.ofHours(1))
        server.enqueueJson(loginJson())

        absSignIn(store, now = { ahead }).signInWithPassword(url, Username, Password)

        val tokens = (store.saved.single().first.credential as Credential.RotatingTokens).tokens
        assertThat(tokens.accessTokenExpiresAt).isEqualTo(ahead.plus(Duration.ofHours(1)))
        assertThat(tokens.refreshTokenExpiresAt).isEqualTo(ahead.plus(Duration.ofDays(30)))
    }

    @Test
    fun `each sign-in draws a sign-in id of its own`() = runBlocking<Unit> {
        val drawing = absSignIn(store, newSignInId = DrawSignInId)
        server.enqueueJson(loginJson())
        server.enqueueJson(loginJson())

        drawing.signInWithPassword(url, Username, Password)
        drawing.signInWithPassword(url, Username, Password)

        val ids = store.saved.map { (it.first.credential as Credential.RotatingTokens).signInId }
        ids.forEach { UUID.fromString(it) }
        assertThat(ids.toSet()).hasSize(2)
    }

    @Test
    fun `nothing of the password or of the old token is in what is saved`() = runBlocking {
        server.enqueueJson(loginJson())

        signIn.signInWithPassword(url, Username, Password)

        val (session, _) = store.saved.single()
        val credential = session.credential as Credential.RotatingTokens
        val kept = listOf(
            session.toString(),
            session.user.toString(),
            store.current.value.toString(),
            credential.signInId,
            credential.tokens.accessToken,
            credential.tokens.refreshToken,
        )
        kept.forEach {
            assertThat(it).doesNotContain(Password)
            assertThat(it).doesNotContain(TheOldToken)
        }
        assertThat(session.toString()).doesNotContain(TheAccessToken)
        assertThat(session.toString()).doesNotContain(TheRefreshToken)
    }

    @Test
    fun `an account without the download permission may not download, one that does not say may`() =
        runBlocking<Unit> {
            server.enqueueJson(loginJson(download = false))
            signIn.signInWithPassword(url, Username, Password)
            assertThat(store.saved.last().first.user.canDownload).isFalse()

            server.enqueueJson(loginJson(download = null))
            signIn.signInWithPassword(url, Username, Password)
            assertThat(store.saved.last().first.user.canDownload).isTrue()
        }

    @Test
    fun `a server under a base path is signed in to under it`() = runBlocking<Unit> {
        server.enqueueJson(loginJson())

        signIn.signInWithPassword("$url/audiobookshelf", Username, Password)

        assertThat(server.takeRequest().pathText()).isEqualTo("/audiobookshelf/login")
        assertThat(store.saved.single().first.serverUrl).isEqualTo("$url/audiobookshelf")
    }

    @Test
    fun `wrong credentials are told as such, and nothing is saved`() = runBlocking {
        server.enqueueRefusal()

        assertThat(signIn.signInWithPassword(url, Username, Password))
            .isEqualTo(ApiResult.Failure(ApiError.InvalidCredentials))
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `the server's login limit is told as too many attempts`() = runBlocking {
        server.enqueueTooMany()

        assertThat(signIn.signInWithPassword(url, Username, Password))
            .isEqualTo(ApiResult.Failure(ApiError.TooManyAttempts))
        assertThat(store.saved).isEmpty()
    }

    /** A server that ignores `x-return-tokens` answers without the refresh token, or without either. */
    @Test
    fun `an answer without both tokens is unexpected, and nothing is saved`() = runBlocking {
        listOf(loginJson(accessToken = null), loginJson(refreshToken = null), loginJson(accessToken = "")).forEach {
            server.enqueueJson(it)

            assertThat(signIn.signInWithPassword(url, Username, Password))
                .isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        }
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `an answer that is not an account is unexpected`() = runBlocking {
        server.enqueueJson("""{"error":"nope"}""")

        assertThat(signIn.signInWithPassword(url, Username, Password))
            .isEqualTo(ApiResult.Failure(ApiError.Unexpected))
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a failing or unreachable server is told as such`() = runBlocking {
        server.enqueueText("Error", code = 500)
        assertThat(signIn.signInWithPassword(url, Username, Password))
            .isEqualTo(ApiResult.Failure(ApiError.ServerFailed))

        server.close()
        assertThat(signIn.signInWithPassword(url, Username, Password))
            .isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `signing in again to the same account closes the session the profile held`() = runBlocking<Unit> {
        store.replaced = ReplacedSession(Credential.RotatingTokens("an-older-sign-in", oldTokens), refused = false)
        server.enqueueJson(loginJson())
        server.enqueueDone()

        assertThat(signIn.signInWithPassword(url, Username, Password)).isEqualTo(ApiResult.Success(Unit))

        server.takeRequest()
        val logout = server.takeRequest()
        assertThat(logout.method).isEqualTo("POST")
        assertThat(logout.pathText()).isEqualTo("/logout")
        assertThat(logout.headers["x-refresh-token"]).isEqualTo("old-refresh")
        assertThat(logout.headers["Authorization"]).isNull()
    }

    /** The server refused to renew it — a disabled account, say — and may take its logout once it is enabled again. */
    @Test
    fun `a session the server had refused is closed too`() = runBlocking<Unit> {
        store.replaced = ReplacedSession(Credential.RotatingTokens("an-older-sign-in", oldTokens), refused = true)
        server.enqueueJson(loginJson())
        server.enqueueDone()

        assertThat(signIn.signInWithPassword(url, Username, Password)).isEqualTo(ApiResult.Success(Unit))

        assertThat(server.requestCount).isEqualTo(2)
        server.takeRequest()
        val logout = server.takeRequest()
        assertThat(logout.pathText()).isEqualTo("/logout")
        assertThat(logout.headers["x-refresh-token"]).isEqualTo("old-refresh")
    }

    @Test
    fun `a replaced session the server will not close leaves the sign-in standing`() = runBlocking<Unit> {
        store.replaced = ReplacedSession(Credential.RotatingTokens("an-older-sign-in", oldTokens), refused = false)
        server.enqueueJson(loginJson())
        server.enqueueRefusal()
        assertThat(signIn.signInWithPassword(url, Username, Password)).isEqualTo(ApiResult.Success(Unit))

        // A server that does not answer the logout: given up after the second the tests allow.
        server.enqueueJson(loginJson())
        server.enqueue(MockResponse.Builder().headersDelay(20, TimeUnit.SECONDS).build())
        val signedIn = withTimeout(GivenUpMillis) { signIn.signInWithPassword(url, Username, Password) }
        assertThat(signedIn).isEqualTo(ApiResult.Success(Unit))

        assertThat(store.saved).hasSize(2)
    }
}
