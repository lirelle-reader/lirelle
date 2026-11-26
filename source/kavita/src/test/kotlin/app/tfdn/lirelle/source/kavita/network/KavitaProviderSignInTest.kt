package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.source.kavita.testing.Ann
import app.tfdn.lirelle.source.kavita.testing.KeyJson
import app.tfdn.lirelle.source.kavita.testing.KeyName
import app.tfdn.lirelle.source.kavita.testing.MemoryStore
import app.tfdn.lirelle.source.kavita.testing.NameTakenText
import app.tfdn.lirelle.source.kavita.testing.PreciseKeyName
import app.tfdn.lirelle.source.kavita.testing.TheKey
import app.tfdn.lirelle.source.kavita.testing.TheKeyId
import app.tfdn.lirelle.source.kavita.testing.annJson
import app.tfdn.lirelle.source.kavita.testing.bodyText
import app.tfdn.lirelle.source.kavita.testing.enqueueDone
import app.tfdn.lirelle.source.kavita.testing.enqueueJson
import app.tfdn.lirelle.source.kavita.testing.enqueueRefusal
import app.tfdn.lirelle.source.kavita.testing.enqueueText
import app.tfdn.lirelle.source.kavita.testing.kavitaSignIn
import app.tfdn.lirelle.source.kavita.testing.pathText
import app.tfdn.lirelle.source.kavita.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Kavita's session cookie, as the provider's flow leaves it. */
private const val SessionCookie = ".AspNetCore.Cookies=c00k1e"

/** What the WebView holds for the server once the provider's flow is done: the session, among cookies of the page. */
private const val Cookies = "theme=dark; $SessionCookie; .AspNetCore.Antiforgery.x1=f0rg3ry"

/**
 * Signing in to Kavita through its identity provider: the provider's page, in a WebView, ends on Kavita's own page
 * with a session, which the screen hands over as the cookies the WebView holds for the server. With that session,
 * as with a password, the account is read on a server recent enough, a key of the device's own is made, and a
 * Kavita profile is saved with the key.
 */
class KavitaProviderSignInTest {

    private val server = MockWebServer()
    private val store = MemoryStore()
    private lateinit var url: String
    private lateinit var signIn: KavitaSignIn

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        signIn = kavitaSignIn(store)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a provider's session reads the account, makes a key, and saves a Kavita profile`() = runBlocking<Unit> {
        server.enqueueJson(annJson())
        server.enqueueJson(KeyJson)

        assertThat(signIn.completeWithSession(url, Cookies)).isEqualTo(ApiResult.Success(Unit))

        // Only the session goes, as the cookie the WebView would send: nothing of the page.
        val account = server.takeRequest()
        assertThat(account.method).isEqualTo("GET")
        assertThat(account.pathText()).isEqualTo("/api/Account")
        assertThat(account.headers["Cookie"]).isEqualTo(SessionCookie)
        assertThat(account.headers["Authorization"]).isNull()
        assertThat(account.headers["x-api-key"]).isNull()
        val mint = server.takeRequest()
        assertThat(mint.method).isEqualTo("POST")
        assertThat(mint.pathText()).isEqualTo("/api/Account/create-auth-key")
        assertThat(mint.headers["Cookie"]).isEqualTo(SessionCookie)
        assertThat(mint.headers["Authorization"]).isNull()
        assertThat(mint.bodyText()).isEqualTo("""{"keyLength":32,"name":"$KeyName","expiresUtc":null}""")
        assertThat(server.requestCount).isEqualTo(2)
        val key = Credential.ApiKey(TheKey, TheKeyId, ownedByApp = true)
        assertThat(store.saved).containsExactly(Session(url, key, Ann) to ServerKind.Kavita)
        assertThat(store.saved.toString()).doesNotContain("c00k1e")
    }

    /** A long session is cut in pieces, each a cookie named after the session's. */
    @Test
    fun `a session cut in pieces is carried whole, and in order`() = runBlocking<Unit> {
        server.enqueueJson(annJson())
        server.enqueueJson(KeyJson)
        val pieces = ".AspNetCore.Cookies=chunks-2; .AspNetCore.CookiesC1=f1r5t==; .AspNetCore.CookiesC2=s3c0nd"

        signIn.completeWithSession(url, "theme=dark; $pieces; other=x")

        assertThat(server.takeRequest().headers["Cookie"]).isEqualTo(pieces)
        assertThat(server.takeRequest().headers["Cookie"]).isEqualTo(pieces)
    }

    /** The WebView lists the cookie of the most precise path first: the server's own, under its base path. */
    @Test
    fun `of two sessions, the first one listed is the server's`() = runBlocking<Unit> {
        server.enqueueJson(annJson())
        server.enqueueJson(KeyJson)

        signIn.completeWithSession(url, ".AspNetCore.Cookies=under-base-path; .AspNetCore.Cookies=at-root")

        assertThat(server.takeRequest().headers["Cookie"]).isEqualTo(".AspNetCore.Cookies=under-base-path")
    }

    @Test
    fun `without the server's session, the sign-in did not succeed and nothing is sent`() = runBlocking {
        val refused = ApiResult.Failure(ApiError.SignInRefused)

        assertThat(signIn.completeWithSession(url, "theme=dark")).isEqualTo(refused)
        assertThat(signIn.completeWithSession(url, ".AspNetCore.Cookies=")).isEqualTo(refused)
        assertThat(signIn.completeWithSession(url, ".AspNetCore.Antiforgery.x1=f0rg3ry")).isEqualTo(refused)
        assertThat(signIn.completeWithSession(url, "")).isEqualTo(refused)

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a session the server does not accept is a sign-in that did not succeed, and nothing is made nor saved`() =
        runBlocking {
            server.enqueueRefusal()

            assertThat(signIn.completeWithSession(url, Cookies)).isEqualTo(ApiResult.Failure(ApiError.SignInRefused))

            assertThat(server.requestCount).isEqualTo(1)
            assertThat(store.saved).isEmpty()
        }

    @Test
    fun `a server too old says so with its version, and no key is made nor anything saved`() = runBlocking {
        server.enqueueJson(annJson(version = "0.8.8.3"))

        assertThat(signIn.completeWithSession(url, Cookies))
            .isEqualTo(ApiResult.Failure(ApiError.ServerTooOld("0.8.8.3", "0.8.9")))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun `a key whose name is taken is asked for once more with the session, named to the millisecond`() =
        runBlocking {
            server.enqueueJson(annJson())
            server.enqueueText(NameTakenText, code = 400)
            server.enqueueJson(KeyJson)

            assertThat(signIn.completeWithSession(url, Cookies)).isEqualTo(ApiResult.Success(Unit))

            repeat(2) { server.takeRequest() }
            val again = server.takeRequest()
            assertThat(again.headers["Cookie"]).isEqualTo(SessionCookie)
            assertThat(again.bodyText()).contains(PreciseKeyName)
            assertThat(store.saved).hasSize(1)
        }

    @Test
    fun `a profile that cannot be saved deletes the key made with the session`() = runBlocking {
        store.failSaves = true
        server.enqueueJson(annJson())
        server.enqueueJson(KeyJson)
        server.enqueueDone()

        assertThat(signIn.completeWithSession(url, Cookies)).isInstanceOf(ApiResult.Failure::class.java)

        repeat(2) { server.takeRequest() }
        val delete = server.takeRequest()
        assertThat(delete.method).isEqualTo("DELETE")
        assertThat(delete.url.queryParameter("authKeyId")).isEqualTo(TheKeyId)
        assertThat(delete.headers["x-api-key"]).isEqualTo(TheKey)
        assertThat(delete.headers["Cookie"]).isNull()
        assertThat(store.saved).isEmpty()
    }
}
