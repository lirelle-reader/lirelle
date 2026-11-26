package app.tfdn.lirelle.source.kavita.di

import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.formatDeviceLabel
import app.tfdn.lirelle.core.network.newAuthenticatedClient
import app.tfdn.lirelle.core.network.newPublicClient
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.testing.TestUserAgent
import app.tfdn.lirelle.source.kavita.network.KavitaAccountRepository
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import app.tfdn.lirelle.source.kavita.network.KavitaJson
import app.tfdn.lirelle.source.kavita.testing.FakeProfileSession
import app.tfdn.lirelle.source.kavita.testing.KeyJson
import app.tfdn.lirelle.source.kavita.testing.MemoryStore
import app.tfdn.lirelle.source.kavita.testing.OidcOffJson
import app.tfdn.lirelle.source.kavita.testing.TheKey
import app.tfdn.lirelle.source.kavita.testing.TheKeyId
import app.tfdn.lirelle.source.kavita.testing.annJson
import app.tfdn.lirelle.source.kavita.testing.enqueueDone
import app.tfdn.lirelle.source.kavita.testing.enqueueHealthy
import app.tfdn.lirelle.source.kavita.testing.enqueueJson
import app.tfdn.lirelle.source.kavita.testing.kavitaSession
import app.tfdn.lirelle.source.kavita.testing.pathText
import app.tfdn.lirelle.source.kavita.testing.serverUrl
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val UserAgentHeader = "User-Agent"

/**
 * Every request Kavita's module sends names Lirelle: Kavita lists an account's devices by their user agent. Its
 * clients are made as the app makes them — by the module's own bindings, from the public client and from the
 * profile's, itself made from the public one — so that a client built aside, without the agent, fails here.
 */
class KavitaUserAgentTest {

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

    private fun signIn() = KavitaNetworkModule.signIn(
        store = MemoryStore(),
        publicApi = KavitaNetworkModule.publicApi(publicClient, KavitaJson),
        deviceLabel = DeviceLabelProvider { formatDeviceLabel("Pixel 9") },
        client = publicClient,
        json = KavitaJson,
    )

    private fun RecordedRequest.assertNamesLirelle(path: String) {
        assertThat(pathText()).isEqualTo(path)
        assertThat(headers.values(UserAgentHeader)).containsExactly(TestUserAgent.value)
    }

    @Test
    fun `the probe's requests name Lirelle`() = runBlocking<Unit> {
        server.enqueueHealthy()
        server.enqueueJson(OidcOffJson)

        KavitaNetworkModule.probe(publicClient, KavitaJson).probe(url)

        server.takeRequest().assertNamesLirelle("/api/Health")
        server.takeRequest().assertNamesLirelle("/api/Settings/oidc")
    }

    @Test
    fun `a password sign-in and the key it makes name Lirelle`() = runBlocking<Unit> {
        server.enqueueJson(annJson(withTokens = true))
        server.enqueueJson(KeyJson)

        signIn().signInWithPassword(url, "ann", "s3cr3t-p4ssw0rd")

        server.takeRequest().assertNamesLirelle("/api/Account/login")
        server.takeRequest().assertNamesLirelle("/api/Account/create-auth-key")
    }

    @Test
    fun `the deletion of a key names Lirelle`() = runBlocking<Unit> {
        server.enqueueDone()

        signIn().endCredential(url, Credential.ApiKey(TheKey, TheKeyId, ownedByApp = true))

        val deletion = server.takeRequest()
        assertThat(deletion.method).isEqualTo("DELETE")
        assertThat(deletion.headers.values(UserAgentHeader)).containsExactly(TestUserAgent.value)
    }

    @Test
    fun `the profile's account request names Lirelle`() = runBlocking<Unit> {
        val session = FakeProfileSession(kavitaSession(url))
        val client = newAuthenticatedClient(publicClient, KavitaNetworkProfileModule.authorizer(session))
        val api = KavitaNetworkProfileModule.accountApi(client, KavitaJson)
        server.enqueueJson(annJson())

        KavitaAccountRepository(session, api, KavitaErrors()).account()

        val request = server.takeRequest()
        request.assertNamesLirelle("/api/Account")
        assertThat(request.headers["x-api-key"]).isEqualTo(TheKey)
    }
}
