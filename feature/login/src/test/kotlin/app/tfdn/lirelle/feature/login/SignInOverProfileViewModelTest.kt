package app.tfdn.lirelle.feature.login

import app.cash.turbine.test
import app.tfdn.lirelle.core.session.ServerKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The sign-in screens shown over a profile name the profile they return to and the one that signs in again,
 * and leave by themselves only when a sign-in left the same profile current.
 */
class SignInOverProfileViewModelTest {

    private val profiles = FakeProfiles(Camille, Sacha)

    private fun viewModel(purpose: SignInPurpose) = SignInOverProfileViewModel(profiles, purpose)

    @Test
    fun `adding a profile names the current profile to return to, and nobody signing in again`() {
        val state = viewModel(SignInPurpose.Add).state

        assertThat(state.current).isEqualTo(ProfileLabel("camille", ServerKind.BookOrbit, CamilleServer))
        assertThat(state.again).isNull()
    }

    @Test
    fun `signing in again names that profile and its server, and the current profile to return to`() {
        val state = viewModel(SignInPurpose.Again("p1")).state

        assertThat(state.again).isEqualTo(ProfileLabel("sacha", ServerKind.BookOrbit, SachaServer))
        assertThat(state.current?.username).isEqualTo("camille")
    }

    @Test
    fun `a profile that is no longer on the device is not named`() {
        assertThat(viewModel(SignInPurpose.Again("gone")).state.again).isNull()
    }

    @Test
    fun `the current profile signing in again leaves the screens, back to it`() = runTest {
        val viewModel = viewModel(SignInPurpose.Again("p0"))

        viewModel.left.test {
            profiles.signedIn(Camille.copy(signInNeeded = false))
            viewModel.onSignedIn()

            awaitItem()
        }
    }

    @Test
    fun `adding the account that is already the current profile leaves the screens, back to it`() = runTest {
        val viewModel = viewModel(SignInPurpose.Add)

        viewModel.left.test {
            profiles.signedIn(Camille)
            viewModel.onSignedIn()

            awaitItem()
        }
    }

    /** The level is taken down and built again for the new profile: nothing is navigated meanwhile. */
    @Test
    fun `a sign-in that makes another profile current leaves nothing by itself`() = runTest {
        val viewModel = viewModel(SignInPurpose.Add)

        viewModel.left.test {
            profiles.signedIn(profile("p2", userId = 3, username = "noa", server = CamilleServer))
            viewModel.onSignedIn()

            expectNoEvents()
        }
    }

    @Test
    fun `another profile signing in again becomes current, and nothing is left by itself`() = runTest {
        val viewModel = viewModel(SignInPurpose.Again("p1"))

        viewModel.left.test {
            profiles.signedIn(Sacha.copy(signInNeeded = false))
            viewModel.onSignedIn()

            expectNoEvents()
        }
    }
}
