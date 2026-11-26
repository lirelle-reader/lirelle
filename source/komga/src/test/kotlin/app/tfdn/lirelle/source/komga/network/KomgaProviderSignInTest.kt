package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.formatDeviceLabel
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.source.komga.testing.AnnJson
import app.tfdn.lirelle.source.komga.testing.KeyJson
import app.tfdn.lirelle.source.komga.testing.MemoryStore
import app.tfdn.lirelle.source.komga.testing.TheKey
import app.tfdn.lirelle.source.komga.testing.TheKeyId
import app.tfdn.lirelle.source.komga.testing.bodyText
import app.tfdn.lirelle.source.komga.testing.enqueueChallenge
import app.tfdn.lirelle.source.komga.testing.enqueueJson
import app.tfdn.lirelle.source.komga.testing.enqueueNoContent
import app.tfdn.lirelle.source.komga.testing.pathText
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val Email = "ann@example.com"

/** What the WebView holds for the server once the provider's flow is done: the session, beside a cookie of the page. */
private const val Cookies = "theme=dark; KOMGA-SESSION=c00k1e"

/**
 * Signing in to Komga through its identity provider: the provider's page, in a WebView, ends on Komga's own page
 * with a session, which the screen hands over as the cookies the WebView holds for the server. With that session,
 * as with a password, the account is read, a key of the device's own is minted, the session is ended, and a Komga
 * profile is saved with the key.
 */
class KomgaProviderSignInTest {

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
            api = KomgaApis(komgaSignInClient(OkHttpClient()), KomgaJson, KomgaPublicApi::class.java),
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

    @Test
    fun `a provider's session reads the account, mints a key, ends the session, and saves a Komga profile`() =
        runBlocking<Unit> {
            server.enqueueJson(AnnJson)
            server.enqueueJson(KeyJson)
            server.enqueueNoContent()

            assertThat(signIn.completeWithSession(url, Cookies)).isEqualTo(ApiResult.Success(Unit))

            // Only the session goes, as the cookie the WebView would send: nothing of the page, no header to ask
            // for another session.
            val account = server.takeRequest()
            assertThat(account.pathText()).isEqualTo("/api/v2/users/me")
            assertThat(account.headers["Cookie"]).isEqualTo("KOMGA-SESSION=c00k1e")
            assertThat(account.headers["X-Auth-Token"]).isNull()
            assertThat(account.headers["Authorization"]).isNull()
            val mint = server.takeRequest()
            assertThat(mint.pathText()).isEqualTo("/api/v2/users/me/api-keys")
            assertThat(mint.headers["Cookie"]).isEqualTo("KOMGA-SESSION=c00k1e")
            assertThat(mint.bodyText()).isEqualTo("""{"comment":"Lirelle — Pixel 9 — 2026-11-22 10:15:30"}""")
            val logout = server.takeRequest()
            assertThat(logout.method).isEqualTo("POST")
            assertThat(logout.pathText()).isEqualTo("/api/logout")
            assertThat(logout.headers["Cookie"]).isEqualTo("KOMGA-SESSION=c00k1e")
            assertThat(server.requestCount).isEqualTo(3)
            val ann = UserProfile("0B5Y2S1ZK4N7Q", Email, Email, Email, canDownload = true, avatarUrl = null)
            assertThat(store.saved).containsExactly(
                Session(url, Credential.ApiKey(TheKey, TheKeyId), ann) to ServerKind.Komga,
            )
        }

    /** The WebView lists the cookie of the most precise path first: the server's own, under its base path. */
    @Test
    fun `of two sessions, the first one listed is the server's`() = runBlocking<Unit> {
        server.enqueueJson(AnnJson)
        server.enqueueJson(KeyJson)
        server.enqueueNoContent()

        signIn.completeWithSession(url, "KOMGA-SESSION=under-base-path; KOMGA-SESSION=at-root")

        assertThat(server.takeRequest().headers["Cookie"]).isEqualTo("KOMGA-SESSION=under-base-path")
    }

    /** Komga's session cookie is base64: its padding is part of the value, not a second `=`. */
    @Test
    fun `a session whose value ends in padding is carried whole`() = runBlocking<Unit> {
        server.enqueueJson(AnnJson)
        server.enqueueJson(KeyJson)
        server.enqueueNoContent()

        signIn.completeWithSession(url, "theme=dark; KOMGA-SESSION=ZjQ1YjU2Nzg=; other=x==")

        assertThat(server.takeRequest().headers["Cookie"]).isEqualTo("KOMGA-SESSION=ZjQ1YjU2Nzg=")
        assertThat(sessionIn("KOMGA-SESSION=YWJjZA==")).isEqualTo("YWJjZA==")
    }

    /** The app makes its own key on Komga: one the user pasted is not taken. */
    @Test
    fun `a pasted key is not Komga's, and nothing is sent`() = runBlocking {
        assertThat(signIn.signInWithKey(url, "pasted-key")).isEqualTo(ApiResult.Failure(ApiError.OidcRefused))

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `without the server's session, the sign-in did not succeed and nothing is sent`() = runBlocking {
        val refused = ApiResult.Failure(ApiError.SignInRefused)

        assertThat(signIn.completeWithSession(url, "theme=dark")).isEqualTo(refused)
        assertThat(signIn.completeWithSession(url, "KOMGA-SESSION=")).isEqualTo(refused)
        assertThat(signIn.completeWithSession(url, "")).isEqualTo(refused)

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a session the server does not accept is a sign-in that did not succeed, ended at best, nothing saved`() =
        runBlocking {
            server.enqueueChallenge()
            server.enqueueNoContent()

            assertThat(signIn.completeWithSession(url, Cookies)).isEqualTo(ApiResult.Failure(ApiError.SignInRefused))

            server.takeRequest()
            assertThat(server.takeRequest().pathText()).isEqualTo("/api/logout")
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(store.saved).isEmpty()
        }

    @Test
    fun `a profile that cannot be saved revokes the key minted with the session`() = runBlocking {
        store.failSaves = true
        server.enqueueJson(AnnJson)
        server.enqueueJson(KeyJson)
        server.enqueueNoContent()
        server.enqueueNoContent()

        assertThat(signIn.completeWithSession(url, Cookies)).isInstanceOf(ApiResult.Failure::class.java)

        repeat(3) { server.takeRequest() }
        val revoke = server.takeRequest()
        assertThat(revoke.method).isEqualTo("DELETE")
        assertThat(revoke.headers["X-API-Key"]).isEqualTo(TheKey)
        assertThat(store.saved).isEmpty()
    }
}
