package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.source.komga.testing.AnnJson
import app.tfdn.lirelle.source.komga.testing.FakeProfileSession
import app.tfdn.lirelle.source.komga.testing.annWithRoles
import app.tfdn.lirelle.source.komga.testing.enqueueJson
import app.tfdn.lirelle.source.komga.testing.komgaSession
import app.tfdn.lirelle.source.komga.testing.pathText
import app.tfdn.lirelle.source.komga.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * A Komga account is named by its email: it is the account's username and name. It may download with its
 * `FILE_DOWNLOAD` and `PAGE_STREAMING` roles both; it has no picture, and the server tells no version to an account
 * that is not its administrator.
 */
class KomgaAccountRepositoryTest {

    private val server = MockWebServer()
    private lateinit var url: String
    private lateinit var session: FakeProfileSession
    private lateinit var account: KomgaAccountRepository

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        session = FakeProfileSession(
            komgaSession(url).copy(user = UserProfile("0B5Y2S1ZK4N7Q", "old@example.com", "old", null)),
        )
        account = KomgaAccountRepository(
            session,
            KomgaApis(OkHttpClient(), KomgaJson, KomgaAccountApi::class.java),
            KomgaErrors(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val ann = UserProfile(
        id = "0B5Y2S1ZK4N7Q",
        username = "ann@example.com",
        name = "ann@example.com",
        email = "ann@example.com",
        canDownload = true,
        avatarUrl = null,
    )

    @Test
    fun `the account is its email, with no server version, and what it learns is kept`() = runBlocking {
        server.enqueueJson(AnnJson)

        assertThat(account.account()).isEqualTo(
            ApiResult.Success(AccountInfo("ann@example.com", "ann@example.com", "ann@example.com", url, null)),
        )
        assertThat(server.takeRequest().pathText()).isEqualTo("/api/v2/users/me")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(session.session.value.user).isEqualTo(ann)
    }

    @Test
    fun `an account read again may download only with both rights, which an administrator does not have alone`() =
        runBlocking {
            val answers = listOf(
                listOf("USER", "FILE_DOWNLOAD", "PAGE_STREAMING") to true,
                listOf("USER", "FILE_DOWNLOAD") to false,
                listOf("USER", "PAGE_STREAMING") to false,
                listOf("USER", "ADMIN") to false,
                listOf("USER") to false,
                listOf("USER", "FILE_DOWNLOAD", "PAGE_STREAMING") to true,
            )
            answers.forEach { (roles, _) -> server.enqueueJson(annWithRoles(*roles.toTypedArray())) }

            // Each reading changes the stored profile: a right taken away, or given back, is followed.
            val stored = answers.map {
                assertThat(account.refreshAccount()).isEqualTo(ApiResult.Success(Unit))
                session.session.value.user
            }

            assertThat(stored).isEqualTo(answers.map { (_, may) -> ann.copy(canDownload = may) })
        }

    @Test
    fun `a failing server is told, and nothing is changed`() = runBlocking {
        server.enqueueJson("""{"status":503}""", code = 503)

        assertThat(account.account()).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
        assertThat(session.session.value.user.username).isEqualTo("old@example.com")
    }
}
