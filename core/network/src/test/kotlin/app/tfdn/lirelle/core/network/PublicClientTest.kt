package app.tfdn.lirelle.core.network

import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test

/** No account: the header is what is tested, the authorizer adds nothing. */
private object NoAuthorizer : SessionAuthorizer {
    override suspend fun header(target: HttpUrl): AuthHeader? = null
    override suspend fun rejected(sent: String, target: HttpUrl): AuthHeader? = null
}

class PublicClientTest {

    private val server = MockWebServer()
    private val agent = formatUserAgent("0.7.0", "16", "Pixel 9")

    @Before
    fun setUp() {
        server.start()
        server.enqueue(MockResponse(code = Ok))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun sentAgent(client: OkHttpClient, request: Request = Request.Builder().url(server.url("/")).build()) =
        client.newCall(request).execute().use { server.takeRequest().headers["User-Agent"] }

    @Test
    fun `a request of the public client names Lirelle`() {
        assertThat(sentAgent(newPublicClient(agent))).isEqualTo("Lirelle/0.7.0 (Android 16; Pixel 9)")
    }

    @Test
    fun `Lirelle's agent replaces one a library set`() {
        val request = Request.Builder().url(server.url("/")).header("User-Agent", "ExoPlayerLib/1.8").build()

        assertThat(sentAgent(newPublicClient(agent), request)).isEqualTo(agent.value)
    }

    @Test
    fun `a client made from the public one names Lirelle too`() {
        val derived = newPublicClient(agent).newBuilder().followRedirects(false).build()

        assertThat(sentAgent(derived)).isEqualTo(agent.value)
    }

    @Test
    fun `the authenticated client names Lirelle too`() {
        assertThat(sentAgent(newAuthenticatedClient(newPublicClient(agent), NoAuthorizer))).isEqualTo(agent.value)
    }

    private companion object {
        const val Ok = 200
    }
}
