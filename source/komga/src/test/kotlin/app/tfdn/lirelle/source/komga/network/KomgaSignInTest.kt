package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.network.SignInIdentifier
import app.tfdn.lirelle.core.network.formatDeviceLabel
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.source.komga.testing.AnnJson
import app.tfdn.lirelle.source.komga.testing.CollisionJson
import app.tfdn.lirelle.source.komga.testing.KeyJson
import app.tfdn.lirelle.source.komga.testing.MemoryStore
import app.tfdn.lirelle.source.komga.testing.TheKey
import app.tfdn.lirelle.source.komga.testing.TheKeyId
import app.tfdn.lirelle.source.komga.testing.annWithRoles
import app.tfdn.lirelle.source.komga.testing.bodyText
import app.tfdn.lirelle.source.komga.testing.enqueueChallenge
import app.tfdn.lirelle.source.komga.testing.enqueueJson
import app.tfdn.lirelle.source.komga.testing.enqueueNoContent
import app.tfdn.lirelle.source.komga.testing.komgaSession
import app.tfdn.lirelle.source.komga.testing.pathText
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val Email = "ann@example.com"
private const val Password = "secret"
private const val SessionToken = "5e5510n"

/**
 * Signing in to Komga by email and password: the account read with HTTP Basic, a key of the device's own minted
 * with a comment that names the app, the device and the time, the session the password opened ended, and a Komga
 * profile saved with the key. Once the key exists, nothing stops the profile from being saved, and a profile that
 * cannot be saved takes its key with it.
 */
class KomgaSignInTest {

    private val server = MockWebServer()
    private val store = MemoryStore()
    private lateinit var url: String
    private lateinit var signIn: KomgaSignIn

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        signIn = KomgaSignIn(
            store = store,
            api = KomgaApis(OkHttpClient(), KomgaJson, KomgaPublicApi::class.java),
            errors = KomgaErrors(),
            deviceLabel = DeviceLabelProvider { formatDeviceLabel("Pixel 9") },
            revocation = KomgaRevocation(OkHttpClient(), KomgaJson),
            now = { Instant.parse("2026-11-22T09:15:30Z") },
            zone = { ZoneId.of("Europe/Paris") },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val ann = UserProfile("0B5Y2S1ZK4N7Q", Email, Email, Email, canDownload = true, avatarUrl = null)

    private fun enqueueAccount(json: String = AnnJson) = server.enqueueJson(json, 200, "X-Auth-Token" to SessionToken)

    @Test
    fun `a password sign-in reads the account, mints a key of its own, ends the session, and saves a Komga profile`() =
        runBlocking<Unit> {
            enqueueAccount()
            server.enqueueJson(KeyJson)
            server.enqueueNoContent()

            assertThat(signIn.signInWithPassword(url, Email, Password)).isEqualTo(ApiResult.Success(Unit))

            val account = server.takeRequest()
            assertThat(account.method).isEqualTo("GET")
            assertThat(account.pathText()).isEqualTo("/api/v2/users/me")
            assertThat(account.headers["Authorization"]).isEqualTo(Credentials.basic(Email, Password, Charsets.UTF_8))
            // Asked for the session in a header, which the next calls carry instead of a cookie.
            assertThat(account.headers["X-Auth-Token"]).isEmpty()
            val mint = server.takeRequest()
            assertThat(mint.method).isEqualTo("POST")
            assertThat(mint.pathText()).isEqualTo("/api/v2/users/me/api-keys")
            assertThat(mint.headers["X-Auth-Token"]).isEqualTo(SessionToken)
            assertThat(mint.bodyText()).isEqualTo("""{"comment":"Lirelle — Pixel 9 — 2026-11-22 10:15:30"}""")
            val logout = server.takeRequest()
            assertThat(logout.method).isEqualTo("POST")
            assertThat(logout.pathText()).isEqualTo("/api/logout")
            assertThat(logout.headers["X-Auth-Token"]).isEqualTo(SessionToken)
            assertThat(logout.headers["Authorization"]).isNull()
            assertThat(server.requestCount).isEqualTo(3)
            assertThat(store.saved).containsExactly(
                Session(url, Credential.ApiKey(TheKey, TheKeyId), ann) to ServerKind.Komga,
            )
        }

    /** Komga decodes Basic credentials as UTF-8: in ISO-8859-1, "€" is lost and every accent is another byte. */
    @Test
    fun `an accented password is sent in UTF-8, as Komga reads it`() = runBlocking {
        enqueueAccount()
        server.enqueueJson(KeyJson)
        server.enqueueNoContent()

        signIn.signInWithPassword(url, Email, "mötdepässe-é€")

        // base64("ann@example.com:mötdepässe-é€"), the text in UTF-8.
        assertThat(server.takeRequest().headers["Authorization"])
            .isEqualTo("Basic YW5uQGV4YW1wbGUuY29tOm3DtnRkZXDDpHNzZS3DqeKCrA==")
    }

    @Test
    fun `each sign-in names its key by the time it was made, so that two never collide`() = runBlocking {
        var now = Instant.parse("2026-11-22T09:15:30Z")
        val later = KomgaSignIn(
            store,
            KomgaApis(OkHttpClient(), KomgaJson, KomgaPublicApi::class.java),
            KomgaErrors(),
            DeviceLabelProvider { formatDeviceLabel("Pixel 9") },
            KomgaRevocation(OkHttpClient(), KomgaJson),
            now = { now },
            zone = { ZoneId.of("UTC") },
        )
        repeat(2) {
            enqueueAccount()
            server.enqueueJson(KeyJson)
            server.enqueueNoContent()
        }

        later.signInWithPassword(url, Email, Password)
        now = now.plusSeconds(1)
        later.signInWithPassword(url, Email, Password)

        val comments = (1..6).map { server.takeRequest() }.filter { it.pathText().endsWith("/api-keys") }
            .map { it.bodyText() }
        assertThat(comments).containsExactly(
            """{"comment":"Lirelle — Pixel 9 — 2026-11-22 09:15:30"}""",
            """{"comment":"Lirelle — Pixel 9 — 2026-11-22 09:15:31"}""",
        ).inOrder()
    }

    @Test
    fun `an account that may download files but not stream pages is saved as one that may not download`() =
        runBlocking {
            enqueueAccount(annWithRoles("USER", "FILE_DOWNLOAD"))
            server.enqueueJson(KeyJson)
            server.enqueueNoContent()

            signIn.signInWithPassword(url, Email, Password)

            assertThat(store.saved.single().first.user.canDownload).isFalse()
        }

    @Test
    fun `a wrong email or password is wrong credentials, and nothing is minted nor saved`() = runBlocking {
        server.enqueueChallenge()

        assertThat(signIn.signInWithPassword(url, Email, "wrong"))
            .isEqualTo(ApiResult.Failure(ApiError.InvalidCredentials))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a key refused for its comment is a sign-in that did not succeed, the session ended, nothing saved`() =
        runBlocking {
            enqueueAccount()
            server.enqueueJson(CollisionJson, code = 400)
            server.enqueueNoContent()

            assertThat(signIn.signInWithPassword(url, Email, Password))
                .isEqualTo(ApiResult.Failure(ApiError.SignInRefused))
            server.takeRequest()
            server.takeRequest()
            assertThat(server.takeRequest().pathText()).isEqualTo("/api/logout")
            assertThat(store.saved).isEmpty()
        }

    @Test
    fun `an account that may not make keys is a sign-in that did not succeed`() = runBlocking {
        enqueueAccount()
        server.enqueueJson("""{"status":403,"error":"Forbidden"}""", code = 403)
        server.enqueueNoContent()

        assertThat(signIn.signInWithPassword(url, Email, Password))
            .isEqualTo(ApiResult.Failure(ApiError.SignInRefused))
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a session that could not be ended does not undo the sign-in`() = runBlocking {
        enqueueAccount()
        server.enqueueJson(KeyJson)
        server.enqueueJson("""{"status":500}""", code = 500)

        assertThat(signIn.signInWithPassword(url, Email, Password)).isEqualTo(ApiResult.Success(Unit))
        assertThat(store.saved).hasSize(1)
    }

    @Test
    fun `a profile that cannot be saved revokes its key at once, with the key itself`() = runBlocking {
        store.failSaves = true
        enqueueAccount()
        server.enqueueJson(KeyJson)
        server.enqueueNoContent()
        server.enqueueNoContent()

        val result = signIn.signInWithPassword(url, Email, Password)

        assertThat(result).isInstanceOf(ApiResult.Failure::class.java)
        repeat(3) { server.takeRequest() }
        val revoke = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "The key was not revoked" }
        assertThat(revoke.method).isEqualTo("DELETE")
        assertThat(revoke.pathText()).isEqualTo("/api/v2/users/me/api-keys/$TheKeyId")
        assertThat(revoke.headers["X-API-Key"]).isEqualTo(TheKey)
        assertThat(revoke.headers["Authorization"]).isNull()
        assertThat(store.saved).isEmpty()
    }

    /** The key exists on the server from the moment it is asked for: a profile must hold it, or nobody does. */
    @Test
    fun `leaving the screen once the key is asked for still ends the session and saves the profile`() = runBlocking {
        val minting = CountDownLatch(1)
        val left = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.pathText().endsWith("/api-keys") -> {
                    minting.countDown()
                    left.await(5, TimeUnit.SECONDS)
                    json(KeyJson)
                }
                request.pathText().endsWith("/logout") -> MockResponse(code = 204)
                else -> json(AnnJson, "X-Auth-Token" to SessionToken)
            }
        }

        val screen = launch(Dispatchers.IO) { signIn.signInWithPassword(url, Email, Password) }
        assertThat(minting.await(5, TimeUnit.SECONDS)).isTrue()
        screen.cancel()
        left.countDown()
        screen.join()

        assertThat(store.saved.map { it.first.credential }).containsExactly(Credential.ApiKey(TheKey, TheKeyId))
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `the options take an email address, and offer each provider through a WebView`() = runBlocking {
        server.enqueueJson("""[{"name":"Authentik","registrationId":"authentik"}]""")

        val options = signIn.loginOptions(url)

        assertThat(options).isEqualTo(
            ApiResult.Success(
                LoginOptions(
                    passwordEnabled = true,
                    oidcProviders = listOf(OidcProvider("authentik", "Authentik", "", "", ProviderFlow.WebView)),
                    identifier = SignInIdentifier.Email,
                ),
            ),
        )
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/oauth2/providers")
    }

    @Test
    fun `a provider's sign-in begins at Komga's own address for it, under the base path`() = runBlocking {
        val provider = OidcProvider("authentik", "Authentik", "", "", ProviderFlow.WebView)

        assertThat(signIn.beginOidc("https://bd.example.com/komga", provider))
            .isEqualTo(ApiResult.Success("https://bd.example.com/komga/oauth2/authorization/authentik"))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `no browser redirect is ever Komga's`() = runBlocking {
        val answer = "bookorbit://oauth2-callback?code=c&state=s"

        assertThat(signIn.hasPendingOidc()).isFalse()
        assertThat(signIn.answersPendingOidc(answer)).isFalse()
        assertThat(signIn.completeOidc(answer)).isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
    }

    /** Its key revoked after, as [KomgaKeyEndingTest] shows. */
    @Test
    fun `a sign-out removes the current Komga profile`() = runBlocking<Unit> {
        store.current.value = Profile("p0", ServerKind.Komga, komgaSession(url))
        server.enqueueNoContent()

        signIn.signOut("p0")

        assertThat(store.cleared).containsExactly("p0")
    }

    @Test
    fun `a sign-out leaves a profile of another kind, or one that is not current, as it is`() = runBlocking {
        val bookOrbit = bookOrbitSession(url, 1, Tokens("a", Instant.MAX, "r", Instant.MAX), ann)
        store.current.value = Profile("p0", ServerKind.BookOrbit, bookOrbit)
        signIn.signOut("p0")
        store.current.value = Profile("p1", ServerKind.Komga, komgaSession(url))
        signIn.signOut("p0")

        assertThat(store.cleared).isEmpty()
    }

    private fun json(body: String, vararg headers: Pair<String, String>): MockResponse {
        val all = listOf("Content-Type" to "application/json") + headers
        return MockResponse(code = 200, headers = headersOf(*all.flatMap { it.toList() }.toTypedArray()), body = body)
    }
}
