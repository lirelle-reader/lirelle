package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.source.kavita.network.dto.AuthKeyRequest
import app.tfdn.lirelle.source.kavita.network.dto.LoginRequest
import app.tfdn.lirelle.source.kavita.testing.KeyJson
import app.tfdn.lirelle.source.kavita.testing.annJson
import app.tfdn.lirelle.source.kavita.testing.bodyText
import app.tfdn.lirelle.source.kavita.testing.enqueueDone
import app.tfdn.lirelle.source.kavita.testing.enqueueJson
import app.tfdn.lirelle.source.kavita.testing.pathText
import app.tfdn.lirelle.source.kavita.testing.serverUrl
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

private const val Cookie = ".AspNetCore.Cookies=c00k1e"

/**
 * The client of Kavita's sign-in and of the deletion of its keys: a password, a session or a key it carries goes to
 * the server it was given for only. OkHttp takes `Authorization` off a redirect to another host, but not a cookie
 * nor a header of the server's own, and it sends the body of a request again along a 307 or a 308; a redirect to
 * another server, or from `https` to cleartext on the same host, would hand them over.
 */
class KavitaSignInClientTest {

    private val kavita = MockWebServer()
    private val elsewhere = MockWebServer()
    private lateinit var api: KavitaPublicApi

    @Before
    fun setUp() {
        kavita.start()
        elsewhere.start()
        api = KavitaApis(kavitaSignInClient(OkHttpClient()), KavitaJson, KavitaPublicApi::class.java)
            .forServer(kavita.serverUrl)
    }

    @After
    fun tearDown() {
        kavita.close()
        elsewhere.close()
    }

    /** A redirect that keeps the method and the body, as a proxy may answer a POST or a DELETE. */
    private fun MockWebServer.redirectTo(target: MockWebServer, path: String, code: Int = 307) =
        enqueue(MockResponse(code = code, headers = headersOf("Location", target.url(path).toString())))

    @Test
    fun `a key is not carried to another server`() = runBlocking<Unit> {
        kavita.redirectTo(elsewhere, "/api/Account/auth-key?authKeyId=42")
        elsewhere.enqueueDone()

        api.deleteKey("the-key", "42")

        assertThat(kavita.takeRequest().headers["x-api-key"]).isEqualTo("the-key")
        val followed = elsewhere.takeRequest()
        assertThat(followed.url.encodedPath).isEqualTo("/api/Account/auth-key")
        assertThat(followed.headers["x-api-key"]).isNull()
    }

    @Test
    fun `a provider's session, in its cookies, is not carried to another server`() = runBlocking<Unit> {
        kavita.redirectTo(elsewhere, "/api/Account")
        elsewhere.enqueueJson(annJson())

        api.account(key = null, cookie = Cookie)

        assertThat(kavita.takeRequest().headers["Cookie"]).isEqualTo(Cookie)
        assertThat(elsewhere.takeRequest().headers["Cookie"]).isNull()
    }

    /** Kavita's password is in the body of its sign-in, which a 307 and a 308 keep: nothing is sent on. */
    @Test
    fun `a password is not carried to another server, whichever redirect keeps the body`() = runBlocking<Unit> {
        listOf(307, 308).forEach { code ->
            kavita.redirectTo(elsewhere, "/api/Account/login", code)
            elsewhere.enqueueJson(annJson(withTokens = true))

            val answer = runCatching { api.login(LoginRequest("ann", "s3cr3t-p4ssw0rd", apiKey = "")) }

            assertWithMessage("$code").that(answer.exceptionOrNull()).isInstanceOf(IOException::class.java)
            assertThat(answer.exceptionOrNull()?.message.orEmpty()).doesNotContain("s3cr3t-p4ssw0rd")
            assertThat(kavita.takeRequest().bodyText()).contains("s3cr3t-p4ssw0rd")
        }
        assertThat(elsewhere.requestCount).isEqualTo(0)
    }

    /** The request that makes a key has a body too, and carries the session: it stops where the password does. */
    @Test
    fun `a session's token is not carried to another server, nor is the request it came with`() = runBlocking<Unit> {
        kavita.redirectTo(elsewhere, "/api/Account/create-auth-key")
        elsewhere.enqueueJson(KeyJson)

        val answer =
            runCatching { api.createKey("Bearer t0k3n", Cookie, AuthKeyRequest(32, "Lirelle", expiresUtc = null)) }

        assertThat(answer.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(kavita.takeRequest().headers["Authorization"]).isEqualTo("Bearer t0k3n")
        assertThat(elsewhere.requestCount).isEqualTo(0)
    }

    /** OkHttp follows a redirect from `https` to `http`: the same host in cleartext is another server. */
    @Test
    fun `a body is not carried from https to cleartext on the same host, nor to another port or host`() {
        val server = "https://kavita.example/api/Account/login".toHttpUrl()
        fun post(url: String) = Request.Builder().url(url).post("{}".toRequestBody()).build()

        assertThat(post("http://kavita.example/api/Account/login").sendsBodyAwayFrom(server)).isTrue()
        assertThat(post("https://kavita.example:8443/api/Account/login").sendsBodyAwayFrom(server)).isTrue()
        assertThat(post("https://elsewhere.example/api/Account/login").sendsBodyAwayFrom(server)).isTrue()
        assertThat(post("https://kavita.example/kavita/api/Account/login").sendsBodyAwayFrom(server)).isFalse()
        // Without a body there is nothing but headers to keep, which are taken off instead.
        val bare = Request.Builder().url("http://kavita.example/api/Account").build()
        assertThat(bare.sendsBodyAwayFrom(server)).isFalse()
    }

    @Test
    fun `a password redirected on the same server, to another path, is sent there`() = runBlocking<Unit> {
        kavita.redirectTo(kavita, "/kavita/api/Account/login")
        kavita.enqueueJson(annJson(withTokens = true))

        val answer = api.login(LoginRequest("ann", "s3cr3t-p4ssw0rd", apiKey = ""))

        assertThat(answer.isSuccessful).isTrue()
        kavita.takeRequest()
        val followed = kavita.takeRequest()
        assertThat(followed.pathText()).isEqualTo("/kavita/api/Account/login")
        assertThat(followed.bodyText()).contains("s3cr3t-p4ssw0rd")
    }

    @Test
    fun `a redirect on the same server keeps what proves the account`() = runBlocking<Unit> {
        kavita.redirectTo(kavita, "/kavita/api/Account/auth-key?authKeyId=42")
        kavita.enqueueDone()

        api.deleteKey("the-key", "42")

        kavita.takeRequest()
        assertThat(kavita.takeRequest().headers["x-api-key"]).isEqualTo("the-key")
    }
}
