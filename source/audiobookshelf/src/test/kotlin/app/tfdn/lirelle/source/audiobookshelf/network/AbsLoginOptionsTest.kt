package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.network.SignInIdentifier
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.source.audiobookshelf.testing.MemoryStore
import app.tfdn.lirelle.source.audiobookshelf.testing.absSignIn
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueJson
import app.tfdn.lirelle.source.audiobookshelf.testing.enqueueText
import app.tfdn.lirelle.source.audiobookshelf.testing.pathText
import app.tfdn.lirelle.source.audiobookshelf.testing.serverUrl
import app.tfdn.lirelle.source.audiobookshelf.testing.statusJson
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * What the sign-in screen offers for an Audiobookshelf server, from its `status`: the name and the password when
 * it announces `local`, its provider's button in the browser when it announces `openid`, named as the server
 * names it; never a pasted key. A server too old is refused here too, for a profile that signs in again without
 * the probe.
 */
class AbsLoginOptionsTest {

    private val server = MockWebServer()
    private lateinit var url: String
    private lateinit var signIn: AbsSignIn

    @Before
    fun setUp() {
        server.start()
        url = server.serverUrl
        signIn = absSignIn(MemoryStore())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun provider(name: String) = OidcProvider("openid", name, clientId = "", scopes = "", ProviderFlow.Browser)

    private fun options(password: Boolean, providers: List<OidcProvider>) = ApiResult.Success(
        LoginOptions(password, providers, identifier = SignInIdentifier.Username, keyEntry = false),
    )

    @Test
    fun `a server taking passwords and its provider offers both`() = runBlocking {
        server.enqueueJson(statusJson(methods = listOf("local", "openid"), buttonText = "Authentik"))

        assertThat(signIn.loginOptions(url)).isEqualTo(options(password = true, listOf(provider("Authentik"))))
        val status = server.takeRequest()
        assertThat(status.method).isEqualTo("GET")
        assertThat(status.pathText()).isEqualTo("/status")
    }

    @Test
    fun `a server taking passwords only offers no provider`() = runBlocking {
        server.enqueueJson(statusJson(methods = listOf("local"), buttonText = "Authentik"))

        assertThat(signIn.loginOptions(url)).isEqualTo(options(password = true, emptyList()))
    }

    @Test
    fun `a server taking its provider only offers no password`() = runBlocking {
        server.enqueueJson(statusJson(methods = listOf("openid"), buttonText = "Authentik"))

        assertThat(signIn.loginOptions(url)).isEqualTo(options(password = false, listOf(provider("Authentik"))))
    }

    @Test
    fun `a provider's button without a text is called OpenID`() = runBlocking {
        server.enqueueJson(statusJson(buttonText = ""))
        assertThat(signIn.loginOptions(url)).isEqualTo(options(password = true, listOf(provider("OpenID"))))

        server.enqueueJson(statusJson(buttonText = "  "))
        assertThat(signIn.loginOptions(url)).isEqualTo(options(password = true, listOf(provider("OpenID"))))

        server.enqueueJson(statusJson(buttonText = null))
        assertThat(signIn.loginOptions(url)).isEqualTo(options(password = true, listOf(provider("OpenID"))))
    }

    @Test
    fun `a server under a base path is asked under it`() = runBlocking {
        server.enqueueJson(statusJson())

        signIn.loginOptions("$url/audiobookshelf")

        assertThat(server.takeRequest().pathText()).isEqualTo("/audiobookshelf/status")
    }

    @Test
    fun `a server too old is refused with its version`() = runBlocking {
        server.enqueueJson(statusJson(version = "2.25.1"))

        assertThat(signIn.loginOptions(url))
            .isEqualTo(ApiResult.Failure(ApiError.ServerTooOld(ServerKind.Audiobookshelf, "2.25.1", "2.26")))
    }

    @Test
    fun `an answer that is not Audiobookshelf's status is unexpected`() = runBlocking {
        server.enqueueText("<html></html>")
        assertThat(signIn.loginOptions(url)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))

        server.enqueueJson("""{"app":"jellyfin","serverVersion":"2.38.0","authMethods":["local"]}""")
        assertThat(signIn.loginOptions(url)).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
    }

    @Test
    fun `a failing server is a failure`() = runBlocking {
        server.enqueueText("Error", code = 500)

        assertThat(signIn.loginOptions(url)).isEqualTo(ApiResult.Failure(ApiError.ServerFailed))
    }
}
