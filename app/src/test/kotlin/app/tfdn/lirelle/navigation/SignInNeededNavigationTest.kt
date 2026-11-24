package app.tfdn.lirelle.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.composable
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.ui.FailedState
import app.tfdn.lirelle.core.ui.NoNetworkStateTag
import app.tfdn.lirelle.core.ui.SignInNeeded
import app.tfdn.lirelle.core.ui.SignInNeededAgainTag
import app.tfdn.lirelle.core.ui.SignInNeededStateTag
import app.tfdn.lirelle.feature.login.SignInPurpose
import app.tfdn.lirelle.shell.Shell
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every screen of the shell is told whether the current profile waits for a sign-in, and how to sign it in
 * again, without a parameter of its own: what failed to load for that says so, and its "Sign in again" opens the
 * sign-in screens over the profile, for the current profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SignInNeededNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shownFor = mutableListOf<SignInPurpose>()
    private val signInNeeded = MutableStateFlow<SignInNeeded?>(SignInNeeded("p7", "camille", ServerKind.BookOrbit))

    /** Home is a screen that could not be loaded, as any of the shell's could be. */
    private fun show() {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact) {
                    RootNavHost(
                        shell = { _, bookRequests, _, _ ->
                            Shell(
                                account = {},
                                home = {
                                    composable<HomeDestination> {
                                        FailedState(ApiError.ServerUnreachable, onRetry = {})
                                    }
                                },
                                library = { composable<LibraryDestination> { Text("library-root") } },
                                search = { composable<SearchDestination> { Text("search-root") } },
                                bookRequests = bookRequests,
                            )
                        },
                        reader = { _, _, _ -> },
                        player = { _, _, _ -> },
                        playerSettings = {},
                        listen = { _, _ -> },
                        signIn = { purpose, _ ->
                            SideEffect { if (shownFor.lastOrNull() != purpose) shownFor += purpose }
                            Text("sign-in")
                        },
                        signInNeeded = signInNeeded,
                    )
                }
            }
        }
    }

    @Test
    fun `a screen of the shell that could not be loaded signs the current profile in again`() {
        show()

        compose.onNodeWithTag(SignInNeededStateTag).assertIsDisplayed()
        compose.onNodeWithTag(SignInNeededAgainTag).performClick()

        compose.onNodeWithText("sign-in").assertIsDisplayed()
        assertThat(shownFor).containsExactly(SignInPurpose.Again("p7"))
    }

    @Test
    fun `once the mark falls the screen says what it says without network`() {
        show()
        compose.onNodeWithTag(SignInNeededStateTag).assertIsDisplayed()

        signInNeeded.value = null
        compose.waitForIdle()

        compose.onNodeWithTag(SignInNeededStateTag).assertDoesNotExist()
        compose.onNodeWithTag(NoNetworkStateTag).assertIsDisplayed()
    }
}
