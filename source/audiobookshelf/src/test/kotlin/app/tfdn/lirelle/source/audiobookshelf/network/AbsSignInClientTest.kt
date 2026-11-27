package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.source.audiobookshelf.network.dto.AbsLoginRequest
import app.tfdn.lirelle.source.audiobookshelf.testing.bodyText
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueDone
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueJson
import app.tfdn.lirelle.source.audiobookshelf.testing.loginJson
import app.tfdn.lirelle.source.audiobookshelf.testing.pathText
import app.tfdn.lirelle.source.audiobookshelf.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.IOException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val Cookie = "connect.sid=s%3Ac00k1e; auth_method=openid-mobile"

/**
 * The client of Audiobookshelf's sign-in and logout: what proves the account — a token, the cookies of a
 * provider's sign-in, a refresh token, the password in a body — goes to the server it was given for only. OkHttp
 * takes `Authorization` off a redirect to another host, but not a cookie nor a header of the server's own, and it
 * sends a body again along a 307 or a 308.
 */
class AbsSignInClientTest {

    private val abs = MockWebServer()
    private val elsewhere = MockWebServer()
    private lateinit var client: OkHttpClient
    private lateinit var api: AbsPublicApi

    @Before
    fun setUp() {
        abs.start()
        elsewhere.start()
        client = absSignInClient(OkHttpClient())
        api = AbsApis(client, AbsJson, AbsPublicApi::class.java).forServer(abs.serverUrl)
    }

    @After
    fun tearDown() {
        abs.close()
        elsewhere.close()
    }

    private fun MockWebServer.redirectTo(target: MockWebServer, path: String, code: Int = 307) =
        enqueue(MockResponse(code = code, headers = headersOf("Location", target.url(path).toString())))

    private fun credentialed(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer 4cc355")
        .header("Cookie", Cookie)
        .header("x-refresh-token", "r3fr35h")
        .build()

    @Test
    fun `no credential is carried to another server`() {
        abs.redirectTo(elsewhere, "/auth/openid/callback", code = 302)
        elsewhere.enqueueDone()

        client.newCall(credentialed(abs.url("/auth/openid/callback").toString())).execute().close()

        val first = abs.takeRequest()
        assertThat(first.headers["Cookie"]).isEqualTo(Cookie)
        assertThat(first.headers["x-refresh-token"]).isEqualTo("r3fr35h")
        val followed = elsewhere.takeRequest()
        assertThat(followed.pathText()).isEqualTo("/auth/openid/callback")
        assertThat(followed.headers["Authorization"]).isNull()
        assertThat(followed.headers["Cookie"]).isNull()
        assertThat(followed.headers["x-refresh-token"]).isNull()
    }

    @Test
    fun `a redirect on the same server keeps what proves the account`() {
        abs.redirectTo(abs, "/audiobookshelf/api/me", code = 302)
        abs.enqueueDone()

        client.newCall(credentialed(abs.url("/api/me").toString())).execute().close()

        abs.takeRequest()
        val followed = abs.takeRequest()
        assertThat(followed.headers["Authorization"]).isEqualTo("Bearer 4cc355")
        assertThat(followed.headers["Cookie"]).isEqualTo(Cookie)
        assertThat(followed.headers["x-refresh-token"]).isEqualTo("r3fr35h")
    }

    @Test
    fun `a password is not carried to another server, whichever redirect keeps the body`() = runBlocking<Unit> {
        listOf(307, 308).forEach { code ->
            abs.redirectTo(elsewhere, "/login", code)
            elsewhere.enqueueJson(loginJson())

            val answer = runCatching { api.login(AbsLoginRequest("ann", "s3cr3t-p4ssw0rd")) }

            assertWithMessage("$code").that(answer.exceptionOrNull()).isInstanceOf(IOException::class.java)
            assertThat(answer.exceptionOrNull()?.message.orEmpty()).doesNotContain("s3cr3t-p4ssw0rd")
            assertThat(abs.takeRequest().bodyText()).contains("s3cr3t-p4ssw0rd")
        }
        assertThat(elsewhere.requestCount).isEqualTo(0)
    }

    /** The logout has a body too, empty, and carries the refresh token: it stops where the password does. */
    @Test
    fun `a logout is not carried to another server`() = runBlocking<Unit> {
        abs.redirectTo(elsewhere, "/logout")
        elsewhere.enqueueDone()

        val answer = runCatching { api.logout("r3fr35h") }

        assertThat(answer.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(elsewhere.requestCount).isEqualTo(0)
    }

    @Test
    fun `a password redirected on the same server, to another path, is sent there`() = runBlocking<Unit> {
        abs.redirectTo(abs, "/audiobookshelf/login")
        abs.enqueueJson(loginJson())

        val answer = api.login(AbsLoginRequest("ann", "s3cr3t-p4ssw0rd"))

        assertThat(answer.isSuccessful).isTrue()
        abs.takeRequest()
        val followed = abs.takeRequest()
        assertThat(followed.pathText()).isEqualTo("/audiobookshelf/login")
        assertThat(followed.bodyText()).contains("s3cr3t-p4ssw0rd")
    }

    /** OkHttp follows a redirect from `https` to `http`: the same host in cleartext is another server. */
    @Test
    fun `a body is not carried from https to cleartext on the same host, nor to another port or host`() {
        val server = "https://abs.example/login".toHttpUrl()
        fun post(url: String) = Request.Builder().url(url).post("{}".toRequestBody()).build()

        assertThat(post("http://abs.example/login").sendsBodyAwayFrom(server)).isTrue()
        assertThat(post("https://abs.example:8443/login").sendsBodyAwayFrom(server)).isTrue()
        assertThat(post("https://elsewhere.example/login").sendsBodyAwayFrom(server)).isTrue()
        assertThat(post("https://abs.example/audiobookshelf/login").sendsBodyAwayFrom(server)).isFalse()
        val bare = Request.Builder().url("http://abs.example/status").build()
        assertThat(bare.sendsBodyAwayFrom(server)).isFalse()
    }

    /**
     * A code is good for one exchange, and a refresh token is rotated by the call that sends it: OkHttp replays
     * neither after a connection failure; whoever makes the call decides alone whether to send it again.
     */
    @Test
    fun `the code exchange and the renewal are not replayed by OkHttp`() {
        val oidc = absOidcApi(OkHttpClient()).client
        assertThat(oidc.retryOnConnectionFailure).isFalse()
        assertThat(oidc.followRedirects).isFalse()
        assertThat(absRefreshApi(OkHttpClient()).client.retryOnConnectionFailure).isFalse()
    }
}
