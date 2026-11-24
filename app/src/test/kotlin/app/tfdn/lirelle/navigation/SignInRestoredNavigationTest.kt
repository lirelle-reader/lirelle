package app.tfdn.lirelle.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import app.tfdn.lirelle.feature.login.RedirectsOutsideSignIn
import app.tfdn.lirelle.feature.login.SignInViewModel
import app.tfdn.lirelle.feature.login.StrayRedirectsViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val Redirect = "bookorbit://oauth2-callback?code=code-1&state=state-1"

/**
 * The signed-in level made again from its saved state while the sign-in screens are shown over the profile:
 * what stands beside the screens is told so from the first composition. Told otherwise for one frame, it would
 * take the redirect meant for the sign-in screen made again, or cancel the sign-in that screen still waits for.
 */
@RunWith(RobolectricTestRunner::class)
class SignInRestoredNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val restoration = StateRestorationTester(compose)
    private val auth = FakeAuthRepository()

    /** The relay the screens made next are given: replaced to play a redirect that came before they existed. */
    private var relay = OidcRedirectRelay()

    private var entrances: ProfileEntrances? = null

    /** Whether the sign-in screens are shown, each time what stands beside the screens was told. */
    private val told = mutableListOf<Boolean>()

    /** How many sign-ins the sign-in screen's own view model told to have succeeded. */
    private var completedByScreen = 0

    /**
     * @param stray what follows the redirects outside the sign-in screens when it outlives the screens, as a
     *   view model does through a configuration change; null for one made with them, as after the process ended.
     */
    private fun show(stray: StrayRedirectsViewModel? = null) {
        restoration.setContent {
            RootNavHost(
                shell = { _, _, _, profiles ->
                    entrances = profiles
                    Text("shell")
                },
                reader = { _, _, _ -> },
                player = { _, _, _ -> },
                playerSettings = {},
                listen = { _, _ -> },
                signIn = { _, _ ->
                    // Made with the screen, as after the process ended: it holds nothing of the sign-in begun.
                    val step = remember { SignInViewModel(auth, relay, server = null, kind = null) }
                    LaunchedEffect(step) { step.signedIn.collect { completedByScreen++ } }
                    Text("sign-in")
                },
                outsideSignIn = { signInShown ->
                    SideEffect { told += signInShown }
                    RedirectsOutsideSignIn(signInShown, stray ?: remember { StrayRedirectsViewModel(auth, relay) })
                },
            )
        }
    }

    private fun openSignIn() {
        compose.runOnUiThread { checkNotNull(entrances).addProfile() }
        compose.onNodeWithText("sign-in").assertIsDisplayed()
    }

    /** The process ended with the browser in front; the redirect reached the activity before any screen was. */
    @Test
    fun `a sign-in screen made again takes the redirect that waited for it, and nothing else does`() {
        show()
        openSignIn()
        auth.oidcPending = true
        relay = OidcRedirectRelay().also { it.deliver(Redirect) }
        told.clear()

        restoration.emulateSavedInstanceStateRestore()

        compose.waitUntil { completedByScreen == 1 }
        assertThat(told).doesNotContain(false)
        assertThat(auth.calls).containsExactly("completeOidc $Redirect")
        compose.onNodeWithText("sign-in").assertIsDisplayed()
    }

    /** A change of configuration the activity does not handle: the screens are made again, their view models kept. */
    @Test
    fun `screens made again while the sign-in is shown do not cancel the sign-in it waits for`() {
        show(StrayRedirectsViewModel(auth, relay))
        openSignIn()
        auth.oidcPending = true
        told.clear()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("sign-in").assertIsDisplayed()
        assertThat(told).doesNotContain(false)
        assertThat(auth.calls).isEmpty()
        assertThat(auth.oidcPending).isTrue()
    }
}
