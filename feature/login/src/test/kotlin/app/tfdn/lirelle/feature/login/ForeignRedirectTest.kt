package app.tfdn.lirelle.feature.login

import app.cash.turbine.test
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private const val Answer = "bookorbit://oauth2-callback?code=code-1&state=state-1"
private const val Foreign = "bookorbit://oauth2-callback?code=code-1&state=somebody-else"

/**
 * A redirect that is not the answer of the sign-in that waits — any app or page can send the link — while that
 * sign-in is in progress: it is dropped. The sign-in goes on waiting for its own answer, and the screen shows
 * no error for what was not its answer. Only an Auth Tab's own answer, which closes the tab, ends the sign-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForeignRedirectTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val auth = FakeAuthRepository().apply { serverUrl.value = CamilleServer }
    private val relay = OidcRedirectRelay()
    private val provider = OidcProvider("authentik", "Authentik", "client-id-1", "openid profile email")

    private fun waitingForItsAnswer() = SignInViewModel(auth, relay, server = null, kind = null).also {
        it.onOidc(provider)
        it.onBrowserOpened(authTab = false)
        auth.calls.clear()
    }

    @Test
    fun `the sign-in screen drops it, shows no error, and still completes with its own answer`() =
        runTest(mainRule.dispatcher) {
            val viewModel = waitingForItsAnswer()

            relay.deliver(Foreign)

            assertThat(auth.calls).isEmpty()
            assertThat(auth.oidcPending).isTrue()
            assertThat(viewModel.state.value.error).isNull()
            assertThat(viewModel.state.value.isBusy).isTrue()
            viewModel.signedIn.test {
                relay.deliver(Answer)
                awaitItem()
            }
            assertThat(auth.calls).containsExactly("completeOidc $Answer")
        }

    /** The tab has answered and is closed: no other answer can come, and the sign-in is over. */
    @Test
    fun `an Auth Tab that answers with it refuses the sign-in, which waits no more`() = runTest(mainRule.dispatcher) {
        val viewModel = waitingForItsAnswer()
        viewModel.onBrowserOpened(authTab = true)

        viewModel.onAuthTabResult(AuthTabResult.Redirected(Foreign))

        assertThat(auth.calls).containsExactly("cancelOidc")
        assertThat(auth.oidcPending).isFalse()
        assertThat(viewModel.state.value.error).isEqualTo(ApiError.OidcRefused)
        assertThat(viewModel.state.value.isBusy).isFalse()
    }

    /** The sign-in an earlier process left pending, with no sign-in screen shown at the restart. */
    @Test
    fun `outside the sign-in screens it is dropped, and the sign-in left pending still completes`() = runTest {
        auth.oidcPending = true
        val stray = StrayRedirectsViewModel(auth, relay)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { stray.follow(signInShown = false) }

        relay.deliver(Foreign)

        assertThat(auth.calls).isEmpty()
        assertThat(auth.oidcPending).isTrue()
        relay.deliver(Answer)
        assertThat(auth.calls).containsExactly("completeOidc $Answer")
    }
}
