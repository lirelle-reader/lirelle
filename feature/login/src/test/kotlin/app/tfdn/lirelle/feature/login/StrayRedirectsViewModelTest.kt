package app.tfdn.lirelle.feature.login

import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The OIDC redirects that come while the signed-in app shows no sign-in screen: dropped once the sign-in
 * screens were left, which cancels what they started; completed when they answer a sign-in that an earlier
 * process left pending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrayRedirectsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val auth = FakeAuthRepository()
    private val relay = OidcRedirectRelay()
    private val viewModel = StrayRedirectsViewModel(auth, relay)
    private val redirect = "bookorbit://oauth2-callback?code=code-1&state=state-1"

    /** As the signed-in app does: told again, the call before cancelled, each time the screens come or go. */
    private var following: Job? = null

    private fun TestScope.signInShown(shown: Boolean) {
        following?.cancel()
        following = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.follow(shown) }
    }

    @Test
    fun `a redirect that comes once the sign-in screens were left is dropped, and signs nobody in`() = runTest {
        signInShown(true)
        auth.oidcPending = true

        signInShown(false)
        relay.deliver(redirect)

        assertThat(auth.calls).containsExactly("cancelOidc")
        assertThat(auth.oidcPending).isFalse()
    }

    @Test
    fun `leaving the sign-in screens cancels the sign-in they left pending, before any redirect`() = runTest {
        signInShown(true)
        auth.oidcPending = true

        signInShown(false)

        assertThat(auth.calls).containsExactly("cancelOidc")
    }

    @Test
    fun `a redirect nobody asked for is dropped`() = runTest {
        signInShown(false)

        relay.deliver(redirect)

        assertThat(auth.calls).isEmpty()
    }

    /** The process ended with the browser in front, and the app opened the profile without its sign-in screen. */
    @Test
    fun `a redirect that answers a sign-in left by an earlier process completes it`() = runTest {
        auth.oidcPending = true
        signInShown(false)

        relay.deliver(redirect)

        assertThat(auth.calls).containsExactly("completeOidc $redirect")
        assertThat(auth.oidcPending).isFalse()
    }

    @Test
    fun `while the sign-in screens are shown, the redirects are theirs`() = runTest {
        auth.oidcPending = true
        signInShown(true)

        relay.deliver(redirect)

        assertThat(auth.calls).isEmpty()
        assertThat(auth.oidcPending).isTrue()
    }

    @Test
    fun `a redirect is taken once the sign-in screens are left again, with nothing left to complete`() = runTest {
        signInShown(false)
        signInShown(true)
        auth.oidcPending = true
        signInShown(false)

        relay.deliver(redirect)

        assertThat(auth.calls).containsExactly("cancelOidc")
    }
}
