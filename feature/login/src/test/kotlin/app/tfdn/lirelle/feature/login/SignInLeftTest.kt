package app.tfdn.lirelle.feature.login

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.FakeAuthRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val Answer = "bookorbit://oauth2-callback?code=code-1&state=state-1"

/**
 * Leaving the sign-in screens shown over a profile — the system's Back, the bar's arrow — while a sign-in is
 * in flight: the step's view model lives until the screens have gone, and the answer landing meanwhile must not
 * save a session, nor make its profile current, after the user left. A sign-in already saved stands.
 */
@RunWith(RobolectricTestRunner::class)
class SignInLeftTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Camille is current; the address typed last, to add a profile, is another server's. */
    private val auth = FakeAuthRepository().apply { serverUrl.value = SachaServer }
    private val relay = OidcRedirectRelay()
    private val step = SignInViewModel(auth, relay, server = CamilleServer, kind = ServerKind.BookOrbit)
    private var left = 0
    private var toldSignedIn = 0

    private fun show() {
        val screens = SignInOverProfileViewModel(FakeProfiles(Camille, Sacha), SignInPurpose.Again("p0"))
        compose.setContent {
            LirelleTheme {
                SignInOverProfile(SignInPurpose.Again("p0"), onLeave = { left++ }, viewModel = screens) { again, _ ->
                    SignInRoute(
                        onChangeServer = {},
                        purpose = SignInPurpose.Again("p0"),
                        again = again,
                        onSignedIn = { toldSignedIn++ },
                        viewModel = step,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** A password sign-in sent, the server not having answered yet. @return what makes it answer. */
    private fun passwordSignInInFlight(): CompletableDeferred<Unit> {
        val answer = CompletableDeferred<Unit>()
        compose.runOnUiThread {
            step.onUsernameChange("camille")
            step.onPasswordChange("secret")
            auth.gate = answer
            step.onSignIn()
        }
        assertThat(auth.calls).contains("signIn $CamilleServer camille secret")
        return answer
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun answers(answer: CompletableDeferred<Unit>) {
        compose.runOnUiThread { answer.complete(Unit) }
        compose.waitForIdle()
    }

    @Test
    fun `Back during a password sign-in ends it, and its answer saves nothing`() {
        show()
        val answer = passwordSignInInFlight()

        pressBack()
        answers(answer)

        assertThat(left).isEqualTo(1)
        // The fake remembers the server signed in to, as the real store does when it saves a session.
        assertThat(auth.serverUrl.value).isEqualTo(SachaServer)
        assertThat(toldSignedIn).isEqualTo(0)
    }

    @Test
    fun `the bar's arrow during a password sign-in ends it too`() {
        show()
        val answer = passwordSignInInFlight()

        val back = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.sign_in_back_to_profile, "camille · BookOrbit")
        compose.onNodeWithContentDescription(back).performClick()
        answers(answer)

        assertThat(left).isEqualTo(1)
        assertThat(auth.serverUrl.value).isEqualTo(SachaServer)
        assertThat(toldSignedIn).isEqualTo(0)
    }

    @Test
    fun `Back while an OIDC redirect is being exchanged ends that too, and no later redirect is taken`() {
        show()
        val answer = CompletableDeferred<Unit>()
        compose.runOnUiThread {
            step.onOidc(OidcProvider("authentik", "Authentik", "client-id-1", "openid"))
            auth.gate = answer
            relay.deliver(Answer)
        }
        assertThat(auth.calls).contains("completeOidc $Answer")

        pressBack()
        answers(answer)
        auth.calls.clear()
        compose.runOnUiThread { relay.deliver(Answer) }
        compose.waitForIdle()

        assertThat(auth.serverUrl.value).isEqualTo(SachaServer)
        assertThat(toldSignedIn).isEqualTo(0)
        assertThat(auth.calls).isEmpty()
    }

    /** The limit: what the server answered before the user left is saved, and that sign-in stands. */
    @Test
    fun `a sign-in already saved when the screens are left stands`() {
        show()
        answers(passwordSignInInFlight())

        pressBack()

        assertThat(auth.serverUrl.value).isEqualTo(CamilleServer)
        assertThat(toldSignedIn).isEqualTo(1)
    }
}
