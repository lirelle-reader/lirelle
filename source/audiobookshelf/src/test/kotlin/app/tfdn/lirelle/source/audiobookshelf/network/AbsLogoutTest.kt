package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.source.audiobookshelf.testing.MemoryStore
import app.tfdn.lirelle.source.audiobookshelf.testing.absSignIn
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueDone
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueRefusal
import app.tfdn.lirelle.source.audiobookshelf.testing.pathText
import app.tfdn.lirelle.source.audiobookshelf.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Far less than the 20 s a server takes not to answer; far more than the second a logout is given. */
private const val GivenUpMillis = 5_000L

/**
 * Ending an Audiobookshelf session: `POST logout` with its refresh token, at best and soon given up — the profile
 * is forgotten here whatever the server answers.
 */
class AbsLogoutTest {

    private val server = MockWebServer()
    private val store = MemoryStore()
    private lateinit var url: String
    private lateinit var signIn: AbsSignIn

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        signIn = absSignIn(store)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val tokens = Tokens("4cc355", Instant.EPOCH, "r3fr35h", Instant.EPOCH)

    private fun current(kind: ServerKind, credential: Credential = Credential.RotatingTokens("s1", tokens)) {
        val user = UserProfile(id = "u1", username = "ann", name = "ann", email = null)
        store.current.value = Profile("p1", kind, Session(url, credential, user))
    }

    @Test
    fun `signing out removes the profile, then closes its session with its refresh token`() = runBlocking<Unit> {
        current(ServerKind.Audiobookshelf)
        server.enqueueDone()

        signIn.signOut("p1")

        assertThat(store.cleared).containsExactly("p1")
        val logout = server.takeRequest()
        assertThat(logout.method).isEqualTo("POST")
        assertThat(logout.pathText()).isEqualTo("/logout")
        assertThat(logout.headers["x-refresh-token"]).isEqualTo("r3fr35h")
    }

    @Test
    fun `another profile, or one of another kind, is neither removed nor sent anywhere`() = runBlocking<Unit> {
        current(ServerKind.Audiobookshelf)
        signIn.signOut("p2")

        current(ServerKind.Kavita, Credential.ApiKey("k3y", "1"))
        signIn.signOut("p1")

        assertThat(store.cleared).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a session refused or unanswered still signs out`() = runBlocking<Unit> {
        current(ServerKind.Audiobookshelf)
        server.enqueueRefusal()
        signIn.signOut("p1")
        assertThat(store.cleared).containsExactly("p1")

        current(ServerKind.Audiobookshelf)
        server.enqueue(MockResponse.Builder().headersDelay(20, TimeUnit.SECONDS).build())
        withTimeout(GivenUpMillis) { signIn.signOut("p1") }
        assertThat(store.cleared).containsExactly("p1", "p1")
    }

    @Test
    fun `the session of a profile removed while it slept is closed`() = runBlocking<Unit> {
        server.enqueueDone()

        signIn.endCredential(url, Credential.RotatingTokens("s1", tokens))

        assertThat(server.takeRequest().headers["x-refresh-token"]).isEqualTo("r3fr35h")
    }

    @Test
    fun `a credential of another sort is sent nowhere`() = runBlocking<Unit> {
        signIn.endCredential(url, Credential.ApiKey("k3y", "1"))

        assertThat(server.requestCount).isEqualTo(0)
    }
}
