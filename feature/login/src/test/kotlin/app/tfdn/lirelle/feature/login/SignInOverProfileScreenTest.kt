package app.tfdn.lirelle.feature.login

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.session.ServerKind
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sign-in screens as they are shown over a profile: what they say they are for, the server that cannot be
 * changed when a profile signs in again, and the bar that returns to the current profile — by its arrow, by the
 * system's Back, and by itself once the same profile has signed in.
 */
@RunWith(RobolectricTestRunner::class)
class SignInOverProfileScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val profiles = FakeProfiles(Camille, Sacha)
    private val sacha = ProfileLabel("sacha", ServerKind.BookOrbit, SachaServer)
    private val loaded = SignInUiState(
        serverUrl = SachaServer,
        isLoadingOptions = false,
        options = LoginOptions(
            passwordEnabled = true,
            oidcProviders = listOf(OidcProvider("authentik", "Authentik", "client-id-1", "openid")),
        ),
    )
    private var left = 0

    /** What the steps were given: the profile that signs in again, and what they tell once signed in. */
    private var givenAgain: ProfileLabel? = null
    private var onSignedIn: () -> Unit = {}

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val backToCamille get() = text(R.string.sign_in_back_to_profile, "camille · BookOrbit")

    private fun showSignIn(purpose: SignInPurpose, again: ProfileLabel? = null) {
        compose.setContent {
            LirelleTheme {
                SignInScreen(
                    state = loaded,
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSignIn = {},
                    onOidc = {},
                    onChangeServer = {},
                    onRetry = {},
                    purpose = purpose,
                    again = again,
                )
            }
        }
    }

    private fun showServer(purpose: SignInPurpose) {
        compose.setContent {
            LirelleTheme {
                ServerScreen(
                    state = ServerUiState(address = SachaServer),
                    onAddressChange = {},
                    onContinue = {},
                    onCleartextConfirmed = {},
                    onCleartextDismissed = {},
                    purpose = purpose,
                )
            }
        }
    }

    private fun showOverProfile(purpose: SignInPurpose) {
        val viewModel = SignInOverProfileViewModel(profiles, purpose)
        compose.setContent {
            LirelleTheme {
                SignInOverProfile(purpose, onLeave = { left++ }, viewModel = viewModel) { again, signedIn ->
                    givenAgain = again
                    onSignedIn = signedIn
                    Text("the steps")
                }
            }
        }
    }

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /** The heading that says [title]: a button may carry the same words. */
    private fun heading(title: String) = compose.onNode(hasText(title) and isHeading())

    @Test
    fun `adding a profile says so in a heading, and that the profiles stay separate`() {
        showSignIn(SignInPurpose.Add)

        compose.onNodeWithText(text(R.string.add_profile_title)).assertIsDisplayed().assert(isHeading())
        compose.onNodeWithText(text(R.string.add_profile_text)).assertIsDisplayed()
        heading(text(R.string.sign_in_title)).assertDoesNotExist()
    }

    @Test
    fun `adding a profile offers the server, which can be changed`() {
        showSignIn(SignInPurpose.Add)

        compose.onNodeWithText(SachaServer).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_change_server)).assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun `the server step of adding a profile carries the same heading`() {
        showServer(SignInPurpose.Add)

        compose.onNodeWithText(text(R.string.add_profile_title)).assertIsDisplayed().assert(isHeading())
        compose.onNodeWithText(text(R.string.add_profile_text)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.server_title)).assertDoesNotExist()
    }

    @Test
    fun `signing in again names the profile and its server under a heading, with no way to change server`() {
        showSignIn(SignInPurpose.Again("p1"), again = sacha)

        compose.onNodeWithText(text(R.string.sign_in_again_title)).assertIsDisplayed().assert(isHeading())
        compose.onNodeWithText("sacha · BookOrbit").assertIsDisplayed()
        compose.onNodeWithText(SachaServer).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_change_server)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.sign_in_username)).assertIsDisplayed()
    }

    @Test
    fun `the first sign-in keeps its title and its server line`() {
        showSignIn(SignInPurpose.First)

        heading(text(R.string.sign_in_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sign_in_change_server)).assertIsDisplayed()
    }

    @Test
    fun `the back arrow is described by the profile it returns to, shown beside it`() {
        showOverProfile(SignInPurpose.Add)

        val arrow = compose.onNodeWithContentDescription(backToCamille)
        arrow.assertIsDisplayed().assertHasClickAction()
        val node = arrow.fetchSemanticsNode()
        val touch = with(compose.density) { node.touchBoundsInRoot.size.let { it.width.toDp() to it.height.toDp() } }
        assertThat(touch.first).isAtLeast(48.dp)
        assertThat(touch.second).isAtLeast(48.dp)
        assertThat(node.config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
        // The same words beside the arrow, for the eyes only: a screen reader says them once, on the arrow.
        compose.onNodeWithText(backToCamille, useUnmergedTree = true)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    @Test
    fun `the back arrow returns to the profile`() {
        showOverProfile(SignInPurpose.Add)

        compose.onNodeWithContentDescription(backToCamille).performClick()

        assertThat(left).isEqualTo(1)
    }

    @Test
    fun `the system's Back returns to the profile`() {
        showOverProfile(SignInPurpose.Again("p1"))

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertThat(left).isEqualTo(1)
    }

    @Test
    fun `the steps are given the profile that signs in again`() {
        showOverProfile(SignInPurpose.Again("p1"))

        compose.onNodeWithText("the steps").assertIsDisplayed()
        assertThat(givenAgain).isEqualTo(sacha)
    }

    @Test
    fun `once the same profile has signed in, the screens leave by themselves`() {
        showOverProfile(SignInPurpose.Again("p0"))

        compose.runOnUiThread {
            profiles.signedIn(Camille)
            onSignedIn()
        }
        compose.waitForIdle()

        assertThat(left).isEqualTo(1)
    }

    @Test
    fun `once another profile has signed in, the screens stay until their level leaves`() {
        showOverProfile(SignInPurpose.Add)

        compose.runOnUiThread {
            profiles.signedIn(profile("p2", userId = 3, username = "noa", server = SachaServer))
            onSignedIn()
        }
        compose.waitForIdle()

        assertThat(left).isEqualTo(0)
        compose.onNodeWithText("the steps").assertIsDisplayed()
    }
}
