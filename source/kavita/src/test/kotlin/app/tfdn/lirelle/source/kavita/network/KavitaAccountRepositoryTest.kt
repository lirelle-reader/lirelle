package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.AuthHeader
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.source.kavita.testing.Ann
import app.tfdn.lirelle.source.kavita.testing.FakeProfileSession
import app.tfdn.lirelle.source.kavita.testing.TheKey
import app.tfdn.lirelle.source.kavita.testing.annJson
import app.tfdn.lirelle.source.kavita.testing.enqueueJson
import app.tfdn.lirelle.source.kavita.testing.enqueueRefusal
import app.tfdn.lirelle.source.kavita.testing.kavitaSession
import app.tfdn.lirelle.source.kavita.testing.pathText
import app.tfdn.lirelle.source.kavita.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * A Kavita account is named by its username, and its id is its number's text; it has no picture, and the server
 * tells the version it runs. What is read is kept by the profile; a key the server refuses is told as a session
 * that ended.
 */
class KavitaAccountRepositoryTest {

    private val server = MockWebServer()
    private lateinit var url: String
    private lateinit var session: FakeProfileSession
    private lateinit var account: KavitaAccountRepository

    /** The profile's client, as far as the account is concerned: what the authorizer gives goes with the request. */
    private fun keyed(authorizer: KavitaKeyAuthorizer) = Interceptor { chain ->
        val header: AuthHeader? = runBlocking { authorizer.header(chain.request().url) }
        val request = chain.request().newBuilder()
        header?.let { request.header(it.name, it.value) }
        chain.proceed(request.build())
    }

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        session = FakeProfileSession(kavitaSession(url).copy(user = UserProfile("7", "old", "old", null)))
        val client = OkHttpClient.Builder().addInterceptor(keyed(KavitaKeyAuthorizer(session))).build()
        account = KavitaAccountRepository(
            session,
            KavitaApis(client, KavitaJson, KavitaAccountApi::class.java),
            KavitaErrors(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the account is its username, with the server's version, and what it learns is kept`() = runBlocking {
        server.enqueueJson(annJson(version = "0.9.1.4"))

        assertThat(account.account()).isEqualTo(
            ApiResult.Success(AccountInfo("ann", "ann", "ann@example.com", url, "0.9.1.4")),
        )
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.pathText()).isEqualTo("/api/Account")
        assertThat(request.headers["x-api-key"]).isEqualTo(TheKey)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(session.session.value.user).isEqualTo(Ann)
    }

    @Test
    fun `a server that tells no version gives none`() = runBlocking {
        server.enqueueJson("""{"id":7,"username":"ann"}""")

        assertThat(account.account()).isEqualTo(ApiResult.Success(AccountInfo("ann", "ann", null, url, null)))
        assertThat(session.session.value.user).isEqualTo(UserProfile("7", "ann", "ann", null))
    }

    @Test
    fun `an account read again is kept, its id the text of its number`() = runBlocking {
        server.enqueueJson(annJson())

        assertThat(account.refreshAccount()).isEqualTo(ApiResult.Success(Unit))

        assertThat(server.takeRequest().pathText()).isEqualTo("/api/Account")
        assertThat(session.session.value.user).isEqualTo(Ann)
        assertThat(session.session.value.user.id).isEqualTo("7")
    }

    @Test
    fun `a key the server refuses is a session that ended, and nothing is changed`() = runBlocking {
        server.enqueueRefusal()

        assertThat(account.refreshAccount()).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(session.session.value.user.username).isEqualTo("old")
    }

    @Test
    fun `a failing server is told, and nothing is changed`() = runBlocking {
        server.enqueueJson("""{"status":503}""", code = 503)

        assertThat(account.account()).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
        assertThat(session.session.value.user.username).isEqualTo("old")
    }

    private suspend fun canDownloadWith(json: String): Boolean {
        server.enqueueJson(json)
        assertThat(account.refreshAccount()).isEqualTo(ApiResult.Success(Unit))
        return session.session.value.user.canDownload
    }

    @Test
    fun `the Download role gives the right to download`() = runBlocking {
        assertThat(canDownloadWith(annJson())).isTrue()
    }

    @Test
    fun `an account without the Download role may not download`() = runBlocking {
        assertThat(canDownloadWith(annJson().replace("\"Download\"", "\"Login\""))).isFalse()
    }

    @Test
    fun `an administrator may download whatever its other roles`() = runBlocking {
        assertThat(canDownloadWith(annJson().replace("\"Pleb\",\"Download\"", "\"Admin\",\"Login\""))).isTrue()
    }

    @Test
    fun `a server that tells no roles keeps the app's default`() = runBlocking {
        assertThat(canDownloadWith(annJson().replace("\"roles\":[\"Pleb\",\"Download\"],", ""))).isTrue()
    }
}
