package app.tfdn.lirelle.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.feature.home.ExpiredSession
import app.tfdn.lirelle.feature.home.HomeContent
import app.tfdn.lirelle.feature.home.HomeUiState
import app.tfdn.lirelle.feature.home.SignInAgainTag
import app.tfdn.lirelle.feature.home.SignInOfflineBooksTag
import app.tfdn.lirelle.feature.login.SignInPurpose
import app.tfdn.lirelle.shell.Shell
import app.tfdn.lirelle.shell.TopLevelDestination
import app.tfdn.lirelle.shell.select
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The notice of an expired session leads where "Sign in again" leads everywhere: to the sign-in screens over
 * the profile, for the profile Home is shown for, and back to Home as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SignInNoticeNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shownFor = mutableListOf<SignInPurpose>()
    private val marked = HomeUiState(
        rows = HomeRowKind.entries.associateWith { Loadable.Failed(ApiError.ServerUnreachable) },
        expired = ExpiredSession("p7", "camille", ServerKind.BookOrbit),
    )

    private fun show() {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact) {
                    RootNavHost(
                        shell = { _, bookRequests, _, profiles ->
                            Shell(
                                account = {},
                                home = { shell ->
                                    composable<HomeDestination> {
                                        Home(profiles) { shell.select(TopLevelDestination.Downloads) }
                                    }
                                },
                                library = { composable<LibraryDestination> { Text("library-root") } },
                                search = { composable<SearchDestination> { Text("search-root") } },
                                downloads = { composable<DownloadsDestination> { Text("downloads-root") } },
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
                    )
                }
            }
        }
    }

    /**
     * Home as its destination shows it, given the shell's way to sign in again and to the downloads. It has
     * no row: a Home that never loaded, which is when the notice offers the offline books.
     */
    @Composable
    private fun Home(profiles: ProfileEntrances, onOpenDownloads: () -> Unit) {
        HomeContent(
            state = marked,
            onRetry = {},
            onRefresh = {},
            onOpenBook = {},
            onRead = {},
            onListen = {},
            onNarrate = {},
            onSeeAll = {},
            onSignInAgain = profiles.signInAgain,
            onOpenDownloads = onOpenDownloads,
        )
    }

    @Test
    fun `the action under the notice selects the Downloads destination, and Home is there on return`() {
        show()

        compose.onNodeWithTag(SignInOfflineBooksTag).performClick()

        compose.onNodeWithText("downloads-root").assertIsDisplayed()
        compose.onNodeWithText("sign-in").assertDoesNotExist()
        assertThat(shownFor).isEmpty()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithTag(SignInAgainTag).assertIsDisplayed()
    }

    @Test
    fun `the notice's button opens the sign-in screens for the current profile, above the shell`() {
        show()

        compose.onNodeWithTag(SignInAgainTag).performClick()

        compose.onNodeWithText("sign-in").assertIsDisplayed()
        assertThat(shownFor).containsExactly(SignInPurpose.Again("p7"))
        compose.onNodeWithTag(SignInAgainTag).assertDoesNotExist()
    }

    @Test
    fun `Back from the sign-in screens returns to Home, where the notice still is`() {
        show()
        compose.onNodeWithTag(SignInAgainTag).performClick()
        compose.onNodeWithText("sign-in").assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText("sign-in").assertDoesNotExist()
        compose.onNodeWithTag(SignInAgainTag).assertIsDisplayed()
    }
}
