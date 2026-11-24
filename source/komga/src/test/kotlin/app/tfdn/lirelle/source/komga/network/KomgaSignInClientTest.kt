package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.source.komga.testing.enqueueNoContent
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The client of Komga's sign-in and revocations: a session or a key it carries goes to the server it was given
 * for only. OkHttp takes `Authorization` off a redirect to another host, but not a header of the server's own; a
 * redirect to another server, or from `https` to cleartext on the same host, would hand them over.
 */
class KomgaSignInClientTest {

    private val komga = MockWebServer()
    private val elsewhere = MockWebServer()
    private lateinit var api: KomgaPublicApi

    @Before
    fun setUp() {
        komga.start()
        elsewhere.start()
        api = KomgaApis(komgaSignInClient(OkHttpClient()), KomgaJson, KomgaPublicApi::class.java)
            .forServer(komga.serverUrl)
    }

    @After
    fun tearDown() {
        komga.close()
        elsewhere.close()
    }

    /** A redirect that keeps the method and the body, as a proxy may answer a POST or a DELETE. */
    private fun MockWebServer.redirectTo(target: MockWebServer, path: String) =
        enqueue(MockResponse(code = 307, headers = headersOf("Location", target.url(path).toString())))

    @Test
    fun `a session is not carried to another server`() = runBlocking<Unit> {
        komga.redirectTo(elsewhere, "/api/logout")
        elsewhere.enqueueNoContent()

        api.logout("5e5510n")

        assertThat(komga.takeRequest().headers["X-Auth-Token"]).isEqualTo("5e5510n")
        val followed = elsewhere.takeRequest()
        assertThat(followed.url.encodedPath).isEqualTo("/api/logout")
        assertThat(followed.headers["X-Auth-Token"]).isNull()
    }

    @Test
    fun `a key is not carried to another server`() = runBlocking<Unit> {
        komga.redirectTo(elsewhere, "/api/v2/users/me/api-keys/k1")
        elsewhere.enqueueNoContent()

        api.revokeKey("the-key", "k1")

        assertThat(komga.takeRequest().headers["X-API-Key"]).isEqualTo("the-key")
        assertThat(elsewhere.takeRequest().headers["X-API-Key"]).isNull()
    }

    @Test
    fun `a provider's session, in its cookie, is not carried to another server`() = runBlocking<Unit> {
        komga.redirectTo(elsewhere, "/api/v2/users/me")
        elsewhere.enqueueNoContent()

        api.account(authorization = null, session = null, cookie = "KOMGA-SESSION=c00k1e")

        assertThat(komga.takeRequest().headers["Cookie"]).isEqualTo("KOMGA-SESSION=c00k1e")
        assertThat(elsewhere.takeRequest().headers["Cookie"]).isNull()
    }

    @Test
    fun `a password is not carried to another server`() = runBlocking<Unit> {
        komga.redirectTo(elsewhere, "/api/v2/users/me")
        elsewhere.enqueueNoContent()

        api.account("Basic YW5uOnNlY3JldA==", "")

        val followed = elsewhere.takeRequest()
        assertThat(followed.headers["Authorization"]).isNull()
        assertThat(followed.headers["X-Auth-Token"]).isNull()
    }

    @Test
    fun `a redirect on the same server keeps what proves the account`() = runBlocking<Unit> {
        komga.redirectTo(komga, "/api/logout")
        komga.enqueueNoContent()

        api.logout("5e5510n")

        komga.takeRequest()
        assertThat(komga.takeRequest().headers["X-Auth-Token"]).isEqualTo("5e5510n")
    }
}
