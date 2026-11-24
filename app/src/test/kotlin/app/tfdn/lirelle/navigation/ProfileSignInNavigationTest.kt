package app.tfdn.lirelle.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.composable
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.login.SignInPurpose
import app.tfdn.lirelle.shell.Shell
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two ways from the shell into the sign-in screens shown over the current profile — a profile added, a
 * profile signing in again: each opens them above the shell for what it is for, once; they are left for the
 * shell as it was; and what stands beside the screens is told when they are shown and when they no longer are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ProfileSignInNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val requests = ReaderRequests()

    /** The shell's ways into the sign-in screens, as its profile button and its pages will have them. */
    private var entrances: ProfileEntrances? = null

    /** What the sign-in screens were shown for, each time they were opened. */
    private val shownFor = mutableListOf<SignInPurpose>()

    /** What returns from the sign-in screens to the profile, as they are given it. */
    private var leave: () -> Unit = {}

    /** Whether the sign-in screens are shown, as what stands beside the screens was last told. */
    private var toldShown: Boolean? = null

    private fun show() {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact) {
                    RootNavHost(
                        shell = { _, bookRequests, _, profiles ->
                            entrances = profiles
                            Shell(
                                account = {},
                                home = { composable<HomeDestination> { Text("home-root") } },
                                library = { composable<LibraryDestination> { Text("library-root") } },
                                search = { composable<SearchDestination> { Text("search-root") } },
                                downloads = { composable<DownloadsDestination> { Text("downloads-root") } },
                                bookRequests = bookRequests,
                                miniPlayer = { Text("mini-player", it) },
                            )
                        },
                        reader = { book, _, _ -> Text("reader-${book.bookId}") },
                        player = { _, _, _ -> Text("player") },
                        playerSettings = {},
                        listen = { _, _ -> },
                        readerRequests = requests,
                        signIn = { purpose, onLeave ->
                            SideEffect {
                                if (shownFor.lastOrNull() != purpose) shownFor += purpose
                                leave = onLeave
                            }
                            Text("sign-in")
                        },
                        outsideSignIn = { signInShown -> SideEffect { toldShown = signInShown } },
                    )
                }
            }
        }
    }

    private fun onUi(action: () -> Unit) {
        compose.runOnUiThread(action)
        compose.waitForIdle()
    }

    private fun pressBack() = onUi { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun assertShown(text: String) = compose.onNodeWithText(text).assertIsDisplayed()

    @Test
    fun `adding a profile opens the sign-in screens for it, above the shell`() {
        show()
        assertThat(toldShown).isFalse()

        onUi { checkNotNull(entrances).addProfile() }

        assertShown("sign-in")
        assertThat(shownFor).containsExactly(SignInPurpose.Add)
        assertThat(toldShown).isTrue()
        // Above the shell: neither its screens nor its bar, nor its mini-player.
        compose.onNodeWithText("home-root").assertDoesNotExist()
        compose.onNodeWithText("mini-player").assertDoesNotExist()
    }

    @Test
    fun `signing in again opens the sign-in screens for that profile`() {
        show()

        onUi { checkNotNull(entrances).signInAgain("p7") }

        assertShown("sign-in")
        assertThat(shownFor).containsExactly(SignInPurpose.Again("p7"))
        assertThat(toldShown).isTrue()
    }

    @Test
    fun `the system's Back returns to the shell as it was`() {
        show()
        onUi { checkNotNull(entrances).addProfile() }

        pressBack()

        assertShown("home-root")
        assertThat(toldShown).isFalse()
    }

    @Test
    fun `the screens leave for the shell once, however many times they ask`() {
        show()
        onUi { checkNotNull(entrances).signInAgain("p7") }

        onUi {
            leave()
            leave()
        }

        assertShown("home-root")
        assertThat(toldShown).isFalse()
        // Asked again once left, as a sign-in that ends late: the shell stays.
        onUi { leave() }
        assertShown("home-root")
    }

    @Test
    fun `asked twice at once, the sign-in screens open once`() {
        show()

        onUi {
            checkNotNull(entrances).addProfile()
            checkNotNull(entrances).addProfile()
        }

        assertShown("sign-in")
        pressBack()
        assertShown("home-root")
    }

    @Test
    fun `a reader asked for from outside takes the place of the sign-in screens, which are told left`() {
        show()
        onUi { checkNotNull(entrances).addProfile() }

        onUi { requests.open(FirstBook) }

        assertShown("reader-7")
        assertThat(toldShown).isFalse()
        pressBack()
        assertShown("home-root")
    }
}

private val FirstBook = BookId("7")
