package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.source.kavita.network.dto.AuthKeyRequest
import app.tfdn.lirelle.source.kavita.testing.KeyJson
import app.tfdn.lirelle.source.kavita.testing.annJson
import app.tfdn.lirelle.source.kavita.testing.enqueueDone
import app.tfdn.lirelle.source.kavita.testing.enqueueJson
import app.tfdn.lirelle.source.kavita.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val Cookie = ".AspNetCore.Cookies=c00k1e"

/**
 * The client of Kavita's sign-in and of the deletion of its keys: a session or a key it carries goes to the server
 * it was given for only. OkHttp takes `Authorization` off a redirect to another host, but not a cookie nor a
 * header of the server's own; a redirect to another server, or from `https` to cleartext on the same host, would
 * hand them over.
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
    private fun MockWebServer.redirectTo(target: MockWebServer, path: String) =
        enqueue(MockResponse(code = 307, headers = headersOf("Location", target.url(path).toString())))

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

    @Test
    fun `a session's token is not carried to another server`() = runBlocking<Unit> {
        kavita.redirectTo(elsewhere, "/api/Account/create-auth-key")
        elsewhere.enqueueJson(KeyJson)

        api.createKey("Bearer t0k3n", Cookie, AuthKeyRequest(32, "Lirelle", expiresUtc = null))

        assertThat(kavita.takeRequest().headers["Authorization"]).isEqualTo("Bearer t0k3n")
        val followed = elsewhere.takeRequest()
        assertThat(followed.headers["Authorization"]).isNull()
        assertThat(followed.headers["Cookie"]).isNull()
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
