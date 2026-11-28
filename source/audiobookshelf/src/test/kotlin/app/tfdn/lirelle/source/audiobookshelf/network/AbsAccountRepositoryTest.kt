package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.AuthHeader
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.source.audiobookshelf.testing.Ann
import app.tfdn.lirelle.source.audiobookshelf.testing.AnnId
import app.tfdn.lirelle.source.audiobookshelf.testing.FakeProfileSession
import app.tfdn.lirelle.source.audiobookshelf.testing.SignedInAt
import app.tfdn.lirelle.source.audiobookshelf.testing.absSession
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueJson
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueRefusal
import app.tfdn.lirelle.source.audiobookshelf.testing.pathText
import app.tfdn.lirelle.source.audiobookshelf.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/** What `GET api/me` answers for Ann, her download permission [download] (left out when null). */
private fun meJson(download: Boolean? = true): String {
    val permission = download?.let { """"download":$it,""" }.orEmpty()
    return """{"id":"$AnnId","username":"ann","email":null,"type":"user","isActive":true,"isLocked":false,""" +
        """"permissions":{$permission"update":false,"delete":false,"upload":false},"mediaProgress":[]}"""
}

/**
 * An Audiobookshelf account is named by its username, its id a UUID; it has no picture, and `api/me` tells no
 * version. What is read is kept by the profile; a token the server refuses is told as a session that ended.
 */
class AbsAccountRepositoryTest {

    private val server = MockWebServer()
    private lateinit var url: String
    private lateinit var session: FakeProfileSession
    private lateinit var account: AbsAccountRepository

    /** The profile's client, as far as the account is concerned: what the authorizer gives goes with the request. */
    private fun bearing(authorizer: AbsTokenRefresher) = Interceptor { chain ->
        val header: AuthHeader? = runBlocking { authorizer.header(chain.request().url) }
        val request = chain.request().newBuilder()
        header?.let { request.header(it.name, it.value) }
        chain.proceed(request.build())
    }

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        session = FakeProfileSession(absSession(url).copy(user = UserProfile(AnnId, "old", "old", null)))
        val authorizer = AbsTokenRefresher(session, absRefreshApi(OkHttpClient())) { SignedInAt }
        val client = OkHttpClient.Builder().addInterceptor(bearing(authorizer)).build()
        account = AbsAccountRepository(session, AbsApis(client, AbsJson, AbsAccountApi::class.java), AbsErrors())
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the account is its username, with no version, and what it learns is kept`() = runBlocking {
        server.enqueueJson(meJson())

        assertThat(account.account()).isEqualTo(ApiResult.Success(AccountInfo("ann", "ann", null, url, null)))
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.pathText()).isEqualTo("/api/me")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer access-1")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(session.session.value.user).isEqualTo(Ann)
    }

    @Test
    fun `an account read again is kept, its id the server's UUID`() = runBlocking {
        server.enqueueJson(meJson())

        assertThat(account.refreshAccount()).isEqualTo(ApiResult.Success(Unit))

        assertThat(server.takeRequest().pathText()).isEqualTo("/api/me")
        assertThat(session.session.value.user).isEqualTo(Ann)
        assertThat(session.session.value.user.id).isEqualTo(AnnId)
    }

    @Test
    fun `an account that may not download is kept so`() = runBlocking {
        server.enqueueJson(meJson(download = false))

        account.refreshAccount()

        assertThat(session.session.value.user.canDownload).isFalse()
    }

    @Test
    fun `an account whose permissions say nothing of downloads may download`() = runBlocking {
        server.enqueueJson(meJson(download = null))

        account.refreshAccount()

        assertThat(session.session.value.user.canDownload).isTrue()
    }

    @Test
    fun `a token the server refuses is a session that ended, and nothing is changed`() = runBlocking {
        server.enqueueRefusal()

        assertThat(account.refreshAccount()).isEqualTo(ApiResult.Failure(ApiError.SessionExpired))
        assertThat(session.session.value.user.username).isEqualTo("old")
    }

    @Test
    fun `a failing server is told, and nothing is changed`() = runBlocking {
        server.enqueueJson("""{"error":"failed"}""", code = 503)

        assertThat(account.account()).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
        assertThat(session.session.value.user.username).isEqualTo("old")
    }

    @Test
    fun `an Audiobookshelf account has no picture`() {
        assertThat(AbsAccountPictures.pictureUrl(url, "$url/api/users/$AnnId/avatar")).isNull()
    }
}
