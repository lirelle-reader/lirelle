package app.tfdn.lirelle.feature.login

import app.cash.turbine.test
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.TestUserAgent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The sign-in view models while a profile is current: they read the server the device remembers, or the one
 * they are given, never the session's state; and they tell when a sign-in has succeeded, which the screens
 * shown over a profile need to know.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInWhileSignedInTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    /** Camille is signed in; the address last typed, to add a profile, is another server's. */
    private val auth = FakeAuthRepository().apply {
        session.value = SessionState.SignedIn(Camille.session)
        serverUrl.value = SachaServer
    }
    private val relay = OidcRedirectRelay()
    private val provider = OidcProvider("authentik", "Authentik", "client-id-1", "openid profile email")
    private val redirect = "bookorbit://oauth2-callback?code=code-1&state=state-1"

    private fun signIn(server: String? = null) =
        SignInViewModel(auth, relay, TestUserAgent, server, ServerKind.BookOrbit.takeIf { server != null })

    private fun SignInViewModel.typeCredentials() {
        onUsernameChange("camille")
        onPasswordChange("secret")
    }

    @Test
    fun `the server step offers the remembered address while a profile is current`() = runTest(mainRule.dispatcher) {
        assertThat(ServerViewModel(auth).state.value.address).isEqualTo(SachaServer)
    }

    @Test
    fun `an address checked while a profile is current is remembered, and the session stays`() =
        runTest(mainRule.dispatcher) {
            val viewModel = ServerViewModel(auth)
            viewModel.onAddressChange("other.example.net")

            viewModel.connected.test {
                viewModel.onContinue()
                awaitItem()
            }

            assertThat(auth.serverUrl.value).isEqualTo("https://other.example.net")
            assertThat(auth.session.value).isEqualTo(SessionState.SignedIn(Camille.session))
        }

    @Test
    fun `the sign-in step signs in to the remembered server while a profile is current`() =
        runTest(mainRule.dispatcher) {
            val viewModel = signIn()
            viewModel.typeCredentials()

            viewModel.onSignIn()

            assertThat(viewModel.state.value.serverUrl).isEqualTo(SachaServer)
            assertThat(viewModel.state.value.needsServer).isFalse()
            assertThat(auth.calls)
                .containsExactly("loginOptions $SachaServer", "signIn $SachaServer camille secret")
                .inOrder()
        }

    /** The remembered address is the server last signed in to: the store sets it when it saves the session. */
    @Test
    fun `a profile signing in again does so on its own server, which is the one remembered from then on`() =
        runTest(mainRule.dispatcher) {
            val viewModel = signIn(server = CamilleServer)
            viewModel.typeCredentials()
            // Until it has signed in, the address typed last stays the one remembered.
            assertThat(auth.serverUrl.value).isEqualTo(SachaServer)

            viewModel.onSignIn()

            assertThat(viewModel.state.value.serverUrl).isEqualTo(CamilleServer)
            assertThat(auth.calls)
                .containsExactly("loginOptions $CamilleServer", "signIn $CamilleServer camille secret")
                .inOrder()
            assertThat(auth.serverUrl.value).isEqualTo(CamilleServer)
        }

    @Test
    fun `a profile whose sign-in is refused leaves the remembered address as it is`() =
        runTest(mainRule.dispatcher) {
            auth.signInResult = ApiResult.Failure(ApiError.InvalidCredentials)
            val viewModel = signIn(server = CamilleServer)
            viewModel.typeCredentials()

            viewModel.onSignIn()

            assertThat(auth.serverUrl.value).isEqualTo(SachaServer)
        }

    @Test
    fun `a profile signing in again through OIDC does so on its own server`() = runTest(mainRule.dispatcher) {
        val viewModel = signIn(server = CamilleServer)

        viewModel.onOidc(provider)

        assertThat(auth.calls).contains("beginOidc $CamilleServer authentik")
    }

    /** The profile that was to sign in again is gone: there is no server, and the remembered one is not used. */
    @Test
    fun `with no server of its own to sign in to, it asks for nothing`() = runTest(mainRule.dispatcher) {
        val viewModel = signIn(server = "")

        assertThat(viewModel.state.value.needsServer).isTrue()
        assertThat(auth.calls).isEmpty()
    }

    @Test
    fun `a password sign-in that succeeded is told`() = runTest(mainRule.dispatcher) {
        val viewModel = signIn()
        viewModel.typeCredentials()

        viewModel.signedIn.test {
            viewModel.onSignIn()
            awaitItem()
        }
    }

    @Test
    fun `a password sign-in that was refused is not told`() = runTest(mainRule.dispatcher) {
        auth.signInResult = ApiResult.Failure(ApiError.InvalidCredentials)
        val viewModel = signIn()
        viewModel.typeCredentials()

        viewModel.signedIn.test {
            viewModel.onSignIn()
            expectNoEvents()
        }
    }

    @Test
    fun `an OIDC sign-in that succeeded is told`() = runTest(mainRule.dispatcher) {
        val viewModel = signIn()
        viewModel.onOidc(provider)

        viewModel.signedIn.test {
            relay.deliver(redirect)
            awaitItem()
        }
    }

    @Test
    fun `an OIDC sign-in that was refused is not told`() = runTest(mainRule.dispatcher) {
        auth.completeOidcResult = ApiResult.Failure(ApiError.OidcRefused)
        val viewModel = signIn()
        viewModel.onOidc(provider)

        viewModel.signedIn.test {
            relay.deliver(redirect)
            expectNoEvents()
        }
    }

    /** The process ended with the browser in front: the screen made again finds the request left on disk. */
    @Test
    fun `a sign-in left pending by an earlier process is completed while a profile is current`() =
        runTest(mainRule.dispatcher) {
            auth.oidcPending = true
            val viewModel = signIn()

            viewModel.signedIn.test {
                relay.deliver(redirect)
                awaitItem()
            }

            assertThat(auth.calls).contains("completeOidc $redirect")
        }
}
