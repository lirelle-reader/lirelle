package app.tfdn.lirelle.feature.login

import app.cash.turbine.test
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.network.ProviderFlow
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private const val Server = "https://bd.example.com"
private const val Start = "$Server/oauth2/authorization/authentik"

/**
 * A provider that signs in through a WebView of the app: its page is opened in the app rather than the browser,
 * and what it ends on decides the sign-in — the server's own page, whose cookies are handed to the server's kind;
 * the server's refusal; or the user closing it, which returns to the sign-in as it was.
 */
class ProviderPageSignInTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val auth = FakeAuthRepository().apply {
        serverUrl.value = Server
        serverKind.value = ServerKind.Komga
        beginOidcResult = ApiResult.Success(Start)
    }
    private val provider = OidcProvider("authentik", "Authentik", "", "", ProviderFlow.WebView)

    private fun viewModel() = SignInViewModel(auth, OidcRedirectRelay(), server = null, kind = null)

    private val handedOver get() = auth.calls.filter { it.startsWith("completeWithSession") }

    @Test
    fun `the provider's page opens in the app, not in the browser`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.browserUrls.test {
            viewModel.onOidc(provider)
            expectNoEvents()
        }

        assertThat(viewModel.providerPage.page.value)
            .isEqualTo(ProviderPage(Server, ServerKind.Komga, Start, "Authentik"))
        // The form waits for the page: no second sign-in starts under it.
        assertThat(viewModel.state.value.isBusy).isTrue()
        assertThat(auth.calls).doesNotContain("cancelOidc")
    }

    @Test
    fun `a page that reached the server's own hands its cookies to the server's kind, and signs in`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onOidc(provider)

            viewModel.signedIn.test {
                viewModel.providerPage.end(ProviderPageEnd.SignedIn("KOMGA-SESSION=s1"))
                awaitItem()
            }

            assertThat(handedOver).containsExactly("completeWithSession $Server KOMGA-SESSION=s1")
            assertThat(auth.kinds.last()).isEqualTo(ServerKind.Komga)
            assertThat(viewModel.providerPage.page.value).isNull()
            // Signed in, the form stays busy until the screen leaves, as after a password.
            assertThat(viewModel.state.value.isBusy).isTrue()
            assertThat(viewModel.state.value.error).isNull()
        }

    @Test
    fun `the form is busy while the session is being turned into the device's own key`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onOidc(provider)
            auth.gate = CompletableDeferred()

            viewModel.providerPage.end(ProviderPageEnd.SignedIn("KOMGA-SESSION=s1"))

            assertThat(viewModel.providerPage.page.value).isNull()
            assertThat(viewModel.state.value.isBusy).isTrue()
            auth.gate?.complete(Unit)
        }

    @Test
    fun `a session the server would not turn into a key says why`() = runTest(mainRule.dispatcher) {
        auth.completeWithSessionResult = ApiResult.Failure(ApiError.SignInRefused)
        val viewModel = viewModel()
        viewModel.onOidc(provider)

        viewModel.providerPage.end(ProviderPageEnd.SignedIn("KOMGA-SESSION=s1"))

        assertThat(viewModel.state.value.error).isEqualTo(ApiError.SignInRefused)
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `a page that ended on the server's refusal says the sign-in did not succeed`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onOidc(provider)

            viewModel.providerPage.end(ProviderPageEnd.Refused)

            assertThat(viewModel.providerPage.page.value).isNull()
            assertThat(viewModel.state.value.error).isEqualTo(ApiError.SignInRefused)
            assertThat(viewModel.state.value.isBusy).isFalse()
            assertThat(handedOver).isEmpty()
        }

    @Test
    fun `a page that led to a sign-in page in cleartext says the page was not encrypted`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onOidc(provider)

            viewModel.providerPage.end(ProviderPageEnd.Unencrypted)

            assertThat(viewModel.providerPage.page.value).isNull()
            assertThat(viewModel.state.value.error).isEqualTo(ApiError.UnencryptedSignInPage)
            assertThat(viewModel.state.value.isBusy).isFalse()
            assertThat(handedOver).isEmpty()
        }

    @Test
    fun `closing the page returns to the sign-in as it was, nothing sent and nothing said`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onOidc(provider)

            viewModel.providerPage.end(ProviderPageEnd.Closed)

            assertThat(viewModel.providerPage.page.value).isNull()
            assertThat(viewModel.state.value.error).isNull()
            assertThat(viewModel.state.value.isBusy).isFalse()
            assertThat(handedOver).isEmpty()
        }

    @Test
    fun `a page ends once, and an end with no page open is dropped`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.providerPage.end(ProviderPageEnd.SignedIn("KOMGA-SESSION=s0"))
        viewModel.onOidc(provider)

        viewModel.providerPage.end(ProviderPageEnd.Closed)
        viewModel.providerPage.end(ProviderPageEnd.SignedIn("KOMGA-SESSION=s1"))

        assertThat(handedOver).isEmpty()
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    @Test
    fun `the cookies handed over are never printed`() {
        assertThat(ProviderPageEnd.SignedIn("KOMGA-SESSION=s1").toString()).doesNotContain("s1")
    }
}
