package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.network.newPublicClient
import app.tfdn.lirelle.core.testing.TestUserAgent
import app.tfdn.lirelle.source.audiobookshelf.testing.AbsOidcServer
import app.tfdn.lirelle.source.audiobookshelf.testing.MemoryPendingOidc
import app.tfdn.lirelle.source.audiobookshelf.testing.MemoryStore
import app.tfdn.lirelle.source.audiobookshelf.testing.SignedInAt
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueDone
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueJson
import app.tfdn.lirelle.source.audiobookshelf.testing.loginJson
import app.tfdn.lirelle.source.audiobookshelf.testing.pathText
import app.tfdn.lirelle.source.audiobookshelf.testing.serverUrl
import app.tfdn.lirelle.source.audiobookshelf.testing.statusJson
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val UserAgentHeader = "User-Agent"

/**
 * Every request Audiobookshelf's sign-in sends names Lirelle: its clients are made from the public client, as the
 * app makes them ([absProbe], [absPublicApi], [absOidcApi], [AbsLogout]), so that a client built aside, without the
 * agent, fails here.
 */
class AbsUserAgentTest {

    private val server = MockWebServer()
    private val publicClient = newPublicClient(TestUserAgent)
    private lateinit var url: String

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun RecordedRequest.assertNamesLirelle(path: String) {
        assertThat(pathText()).isEqualTo(path)
        assertThat(headers.values(UserAgentHeader)).containsExactly(TestUserAgent.value)
    }

    private val pendingOidc = MemoryPendingOidc()

    private fun signIn() = AbsSignIn(
        store = MemoryStore(),
        api = absPublicApi(publicClient),
        oidcApi = absOidcApi(publicClient),
        pendingOidc = pendingOidc,
        errors = AbsErrors(),
        logout = AbsLogout(publicClient),
        now = { SignedInAt },
    )

    @Test
    fun `the probe's request names Lirelle`() = runBlocking<Unit> {
        server.enqueueJson(statusJson())

        absProbe(publicClient).probe(url)

        server.takeRequest().assertNamesLirelle("/status")
    }

    @Test
    fun `the options and a password sign-in name Lirelle`() = runBlocking<Unit> {
        server.enqueueJson(statusJson())
        server.enqueueJson(loginJson())

        signIn().loginOptions(url)
        signIn().signInWithPassword(url, "ann", "s3cr3t-p4ssw0rd")

        server.takeRequest().assertNamesLirelle("/status")
        server.takeRequest().assertNamesLirelle("/login")
    }

    @Test
    fun `a sign-in through the provider names Lirelle, from its first call to its exchange`() = runBlocking<Unit> {
        val routes = AbsOidcServer()
        server.dispatcher = routes

        signIn().beginOidc(url, OidcProvider("openid", "OpenID", "", "", ProviderFlow.Browser))
        val state = pendingOidc.pending!!.state
        signIn().completeOidc("lirelle://oauth?code=c&state=$state")

        server.takeRequest().assertNamesLirelle("/auth/openid")
        server.takeRequest().assertNamesLirelle("/auth/openid/callback")
    }

    @Test
    fun `a logout names Lirelle`() = runBlocking<Unit> {
        server.enqueueDone()

        AbsLogout(publicClient).close(url, "r3fr35h")

        server.takeRequest().assertNamesLirelle("/logout")
    }
}
