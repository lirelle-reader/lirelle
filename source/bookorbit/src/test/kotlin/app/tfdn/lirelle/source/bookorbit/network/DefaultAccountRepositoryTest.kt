package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.network.testing.FakeProfileSession
import app.tfdn.lirelle.source.bookorbit.network.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.testing.enqueueFixture
import app.tfdn.lirelle.source.bookorbit.network.testing.enqueueJson
import app.tfdn.lirelle.source.bookorbit.network.testing.pathText
import app.tfdn.lirelle.source.bookorbit.network.testing.serverUrl
import app.tfdn.lirelle.source.bookorbit.network.testing.testSession
import app.tfdn.lirelle.source.bookorbit.network.testing.tokens
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class DefaultAccountRepositoryTest {

    private val server = MockWebServer()
    private lateinit var profile: FakeProfileSession
    private lateinit var repository: DefaultAccountRepository
    private lateinit var url: String

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        profile = FakeProfileSession(testSession(url))
        repository = DefaultAccountRepository(
            session = profile,
            accountApi = ApiProvider(OkHttpClient(), ApiJson, AccountApi::class.java),
            errors = ErrorMapper(ApiJson),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun withoutPermission(fixture: String) =
        Fixtures.read(fixture).replace("\"permissions\":[\"library_download\"]", "\"permissions\":[]")

    private fun barred() = testSession(url).let { it.copy(user = it.user.copy(canDownload = false)) }

    // --- account

    @Test
    fun `account combines the user and the server version`() = runBlocking {
        server.enqueueFixture("auth-me.json")
        server.enqueueFixture("app-info.json")

        val result = repository.account()

        assertThat(result).isEqualTo(
            ApiResult.Success(
                AccountInfo("Ann Reader", "reader", "ann@example.com", serverUrl = url, serverVersion = "v3.8.0"),
            ),
        )
    }

    @Test
    fun `account reports a network failure`() = runBlocking {
        server.close()

        assertThat(repository.account()).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
    }

    @Test
    fun `the account screen updates the session too`() = runBlocking {
        server.enqueueJson(withoutPermission("auth-me.json").replace("Ann Reader", "Ann B. Reader"))
        server.enqueueFixture("app-info.json")

        repository.account()

        assertThat(profile.session.value.user.canDownload).isFalse()
        assertThat(profile.session.value.user.name).isEqualTo("Ann B. Reader")
    }

    // --- refreshAccount

    @Test
    fun `a refresh that finds the permission withdrawn updates the session and emits`() = runBlocking {
        val seen = mutableListOf<Boolean>()
        val collecting = launch(Dispatchers.Unconfined) { profile.session.collect { seen += it.user.canDownload } }
        server.enqueueFixture("recorded/auth-me.json")

        val result = repository.refreshAccount()

        collecting.cancel()
        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v1/auth/me")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(seen).containsExactly(true, false).inOrder()
        // The tokens are not the refresh's to touch.
        assertThat(profile.session.value.tokens).isEqualTo(testSession(url).tokens)
    }

    @Test
    fun `a refresh that finds the permission granted updates the session`() = runBlocking {
        profile.session.value = barred()
        server.enqueueFixture("auth-me.json")

        repository.refreshAccount()

        assertThat(profile.session.value).isEqualTo(testSession(url))
    }

    @Test
    fun `a refresh stores where the account's picture is, and that it has none any more`() = runBlocking {
        server.enqueueFixture("auth-me-with-picture.json")
        server.enqueueFixture("recorded/auth-me.json")

        repository.refreshAccount()
        val withPicture = profile.session.value.user.avatarUrl
        repository.refreshAccount()

        assertThat(withPicture).isEqualTo("/api/v1/users/1/avatar?v=1")
        assertThat(profile.session.value.user.avatarUrl).isNull()
    }

    @Test
    fun `a failed refresh changes nothing`() = runBlocking {
        profile.session.value = barred()
        server.enqueueJson("""{"statusCode":500}""", code = 500)

        val result = repository.refreshAccount()

        assertThat(result).isInstanceOf(ApiResult.Failure::class.java)
        assertThat(profile.session.value).isEqualTo(barred())
    }

    @Test
    fun `a refresh without a network changes nothing`() = runBlocking {
        server.close()

        assertThat(repository.refreshAccount()).isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
        assertThat(profile.session.value).isEqualTo(testSession(url))
    }

    /** The answer of a refresh asked before the profile closed: the account that signed in next is not its. */
    @Test
    fun `a refresh that ends after the profile is gone writes nowhere`() = runBlocking {
        profile.session.value = barred()
        profile.gone = true
        server.enqueueFixture("auth-me.json")

        assertThat(repository.refreshAccount()).isEqualTo(ApiResult.Success(Unit))
        assertThat(profile.session.value).isEqualTo(barred())
    }
}
