package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.AuthHeader
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.source.audiobookshelf.testing.AccessExpiry
import app.tfdn.lirelle.source.audiobookshelf.testing.RefreshExpiry
import app.tfdn.lirelle.source.audiobookshelf.testing.RefresherRig
import app.tfdn.lirelle.source.audiobookshelf.testing.RenewedAccess
import app.tfdn.lirelle.source.audiobookshelf.testing.RenewedRefresh
import app.tfdn.lirelle.source.audiobookshelf.testing.SignedInAt
import app.tfdn.lirelle.source.audiobookshelf.testing.TheSignInId
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueJson
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueRefusal
import app.tfdn.lirelle.source.audiobookshelf.testing.jsonAnswer
import app.tfdn.lirelle.source.audiobookshelf.testing.madeUpJwt
import app.tfdn.lirelle.source.audiobookshelf.testing.pathText
import app.tfdn.lirelle.source.audiobookshelf.testing.refreshJson
import app.tfdn.lirelle.source.audiobookshelf.testing.tokens
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Audiobookshelf's authorizer: the access token as a bearer, for the profile's server only, and its renewal before
 * it expires and after a 401 — one at a time, the new pair stored before anything uses it, a refusal marking the
 * profile. Failures and the profile's end: [AbsTokenRefresherFailuresTest].
 */
class AbsTokenRefresherTest {

    private val server = MockWebServer()
    private lateinit var rig: RefresherRig
    private val profile get() = rig.profile
    private val refresher get() = rig.refresher

    @Before
    fun setUp() {
        server.start()
        rig = RefresherRig(server)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun onServer(path: String = "/api/me") = server.url(path)

    @Test
    fun `the access token goes as a bearer to the profile's server, and nowhere else`() = runBlocking {
        rig.signIn()

        assertThat(refresher.header(onServer())).isEqualTo(AuthHeader("Authorization", "Bearer access-1"))
        assertThat(refresher.header("https://elsewhere.example/api/me".toHttpUrl())).isNull()
        assertThat(refresher.header(server.url("/").newBuilder().port(server.port + 1).build())).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a token that expires in more than 60 seconds is not renewed`() = runBlocking {
        rig.signIn(accessExpiresAt = SignedInAt.plusSeconds(61))

        assertThat(refresher.freshTokens()?.accessToken).isEqualTo("access-1")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a token that expires within 60 seconds is renewed with the refresh token in its header`() = runBlocking {
        rig.signIn(accessExpiresAt = SignedInAt.plusSeconds(59))
        server.enqueueJson(refreshJson())

        assertThat(refresher.header(onServer())).isEqualTo(AuthHeader("Authorization", "Bearer $RenewedAccess"))

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.pathText()).isEqualTo("/auth/refresh")
        assertThat(request.headers["x-refresh-token"]).isEqualTo("refresh-1")
        assertThat(request.headers["Authorization"]).isNull()
    }

    @Test
    fun `the renewed pair is stored, its expiries read from it, before it is returned`() = runBlocking {
        rig.signIn(accessExpiresAt = SignedInAt)
        server.enqueueJson(refreshJson())

        val tokens = refresher.freshTokens()

        assertThat(profile.session.value.tokens).isEqualTo(tokens)
        assertThat(tokens?.refreshToken).isEqualTo(RenewedRefresh)
        assertThat(tokens?.accessTokenExpiresAt).isEqualTo(AccessExpiry.plusSeconds(60))
        assertThat(tokens?.refreshTokenExpiresAt).isEqualTo(RefreshExpiry.plusSeconds(60))
        // A renewal is the same sign-in.
        assertThat(profile.session.value.credential)
            .isEqualTo(Credential.RotatingTokens(TheSignInId, checkNotNull(tokens)))
    }

    /** The server's clock says the new token lasts an hour; the phone's, an hour ahead, must not see it expired. */
    @Test
    fun `a phone clock ahead of the server's does not renew the tokens it just received`() = runBlocking {
        rig.clock = SignedInAt.plus(Duration.ofHours(1))
        rig.signIn(accessExpiresAt = SignedInAt)
        server.enqueueJson(refreshJson())

        val renewed = refresher.freshTokens()
        assertThat(renewed?.accessTokenExpiresAt).isEqualTo(rig.clock.plusSeconds(3_660))

        rig.clock = rig.clock.plusSeconds(5)
        assertThat(refresher.freshTokens()).isEqualTo(renewed)
        assertThat(server.requestCount).isEqualTo(1)
    }

    /** Never taken as expiring now, which would renew it before every request: the server's 401 renews it. */
    @Test
    fun `an access token without exp is not renewed until the server refuses it`() = runBlocking {
        val noExp = madeUpJwt("""{"userId":"u","type":"access","iat":${SignedInAt.epochSecond}}""")
        rig.signIn(accessExpiresAt = SignedInAt)
        server.enqueueJson(refreshJson(accessToken = noExp))

        assertThat(refresher.freshTokens()?.accessToken).isEqualTo(noExp)
        rig.clock = rig.clock.plus(Duration.ofDays(2))
        assertThat(refresher.freshTokens()?.accessToken).isEqualTo(noExp)
        assertThat(server.requestCount).isEqualTo(1)

        server.enqueueJson(refreshJson())
        assertThat(refresher.rejected("Bearer $noExp", onServer()))
            .isEqualTo(AuthHeader("Authorization", "Bearer $RenewedAccess"))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `an answer without a refresh token keeps the one sent`() = runBlocking {
        rig.signIn(accessExpiresAt = SignedInAt)
        server.enqueueJson(refreshJson(refreshToken = null))

        val tokens = refresher.freshTokens()

        assertThat(tokens?.accessToken).isEqualTo(RenewedAccess)
        assertThat(tokens?.refreshToken).isEqualTo("refresh-1")
        assertThat(tokens?.refreshTokenExpiresAt).isEqualTo(RefreshExpiry)
    }

    @Test
    fun `a token the server refused is renewed even when it has not expired, and the request replayed`() =
        runBlocking {
            rig.signIn()
            server.enqueueJson(refreshJson())

            val header = refresher.rejected("Bearer access-1", onServer())

            assertThat(header).isEqualTo(AuthHeader("Authorization", "Bearer $RenewedAccess"))
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `a refused token already replaced is not renewed again`() = runBlocking {
        rig.signIn()

        assertThat(refresher.rejected("Bearer access-0", onServer()))
            .isEqualTo(AuthHeader("Authorization", "Bearer access-1"))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a 401 off the profile's server is handed back as it is`() = runBlocking {
        rig.signIn()

        assertThat(refresher.rejected("Bearer access-1", "https://elsewhere.example/".toHttpUrl())).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `concurrent callers share one renewal`() = runBlocking(Dispatchers.Default) {
        rig.signIn()
        server.enqueueJson(refreshJson())

        val results = List(8) { async { refresher.freshTokens(rejectedAccessToken = "access-1") } }.awaitAll()

        assertThat(results.map { it?.accessToken }.toSet()).containsExactly(RenewedAccess)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a refused renewal marks the profile, and nothing is asked in its name afterwards`() {
        rig.signIn(accessExpiresAt = SignedInAt)
        server.enqueueRefusal()

        assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }
        assertThat(profile.expired).isTrue()

        repeat(3) { assertThrows(IOException::class.java) { runBlocking { refresher.header(onServer()) } } }
        assertThrows(IOException::class.java) { runBlocking { refresher.rejected("Bearer access-1", onServer()) } }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `concurrent callers behind a refused renewal send the dead token once`() = runBlocking(Dispatchers.Default) {
        rig.signIn()
        val answer = rig.held(jsonAnswer("Unauthorized", code = 401))

        val callers = List(8) { async { runCatching { refresher.freshTokens(rejectedAccessToken = "access-1") } } }
        server.takeRequest()
        answer.countDown()

        callers.awaitAll().forEach { assertThat(it.exceptionOrNull()).isInstanceOf(IOException::class.java) }
        assertThat(profile.expired).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `too many attempts marks nothing, and is not tried again`() {
        rig.signIn(accessExpiresAt = SignedInAt)
        server.enqueueJson("""{"error":"Too many authentication requests"}""", code = 429)

        assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }

        assertThat(profile.expired).isFalse()
        assertThat(profile.session.value.tokens.refreshToken).isEqualTo("refresh-1")
        assertThat(rig.attempts.get()).isEqualTo(1)
    }

    @Test
    fun `tokens that have not expired are not handed out while the profile waits for a sign-in`() {
        rig.signIn()
        profile.signInNeeded.value = true

        assertThrows(IOException::class.java) { runBlocking { refresher.freshTokens() } }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `the tokens of a new sign-in are handed out, and renewed in their turn`() = runBlocking<Unit> {
        rig.signIn(accessExpiresAt = SignedInAt)
        profile.signInNeeded.value = true

        val again = rig.signInAgain()
        profile.signInNeeded.value = false

        assertThat(refresher.freshTokens()).isEqualTo(again)
        server.enqueueJson(refreshJson())
        assertThat(refresher.freshTokens(rejectedAccessToken = "access-again")?.accessToken).isEqualTo(RenewedAccess)
        assertThat(server.takeRequest().headers["x-refresh-token"]).isEqualTo("refresh-again")
    }

    @Test
    fun `a refusal that comes after a new sign-in marks nobody, and the new tokens are served`() = runBlocking<Unit> {
        rig.signIn(accessExpiresAt = SignedInAt)
        val answer = rig.held(jsonAnswer("Unauthorized", code = 401))

        val caller = async(Dispatchers.Default) { refresher.freshTokens() }
        server.takeRequest()
        val again = rig.signInAgain()
        answer.countDown()

        assertThat(caller.await()).isEqualTo(again)
        assertThat(profile.expired).isFalse()
        assertThat(refresher.freshTokens()).isEqualTo(again)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a renewal that ends after a new sign-in leaves the new tokens, and serves them`() = runBlocking<Unit> {
        rig.signIn(accessExpiresAt = SignedInAt)
        val answer = rig.held(jsonAnswer(refreshJson()))

        val caller = async(Dispatchers.Default) { refresher.freshTokens() }
        server.takeRequest()
        val again = rig.signInAgain()
        answer.countDown()

        assertThat(caller.await()).isEqualTo(again)
        assertThat(profile.session.value.tokens).isEqualTo(again)
    }
}
