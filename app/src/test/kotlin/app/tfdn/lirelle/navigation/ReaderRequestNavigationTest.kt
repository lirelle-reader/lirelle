package app.tfdn.lirelle.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.composable
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.shell.Shell
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reader asked for from outside the screens (a narration's notification) and from the mini-player of a
 * narration: opened once above the shell, never a second time over itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ReaderRequestNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val requests = ReaderRequests()
    private val downloadsRequests = DownloadsRequests()

    /** Each reader shown, as it was opened: a reader made again for the same book is a reload. */
    private val opened = mutableListOf<ReaderDestination>()

    /** The shell's ways to the reader, as its book pages have them. */
    private var modes: BookModes? = null

    private fun show() {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact) {
                    RootNavHost(
                        shell = { bookModes, bookRequests, player, _ ->
                            modes = bookModes
                            Shell(
                                account = {},
                                home = { composable<HomeDestination> { Text("home-root") } },
                                library = { composable<LibraryDestination> { Text("library-root") } },
                                search = { composable<SearchDestination> { Text("search-root") } },
                                downloads = { composable<DownloadsDestination> { Text("downloads-root") } },
                                downloadsRequests = downloadsRequests,
                                bookRequests = bookRequests,
                                miniPlayer = {
                                    Column(it) {
                                        Text("mini-player", Modifier.clickable(onClick = player.onOpenPlayer))
                                        Text("mini-narration", Modifier.clickable { player.onOpenReader(FirstBook) })
                                    }
                                },
                            )
                        },
                        reader = { book, _, _ ->
                            LaunchedEffect(Unit) { opened += book }
                            Text("reader-${book.bookId}")
                        },
                        player = { _, _, _ -> Text("player") },
                        playerSettings = {},
                        listen = { _, _ -> },
                        readerRequests = requests,
                        downloadsRequests = downloadsRequests,
                    )
                }
            }
        }
    }

    private fun request(bookId: BookId) {
        compose.runOnUiThread { requests.open(bookId) }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun assertShown(text: String) = compose.onNodeWithText(text).assertIsDisplayed()

    @Test
    fun `a reader asked for before the screens exist opens once they do, where the narration is`() {
        requests.open(FirstBook)

        show()

        assertShown("reader-7")
        assertThat(opened).containsExactly(ReaderDestination(FirstBook.value))
        assertThat(requests.pending).isNull()
        pressBack()
        assertShown("home-root")
    }

    @Test
    fun `the downloads asked for while a reader is in front leave it for the Downloads destination`() {
        show()
        request(FirstBook)
        assertShown("reader-7")

        compose.runOnUiThread { downloadsRequests.open() }
        compose.waitForIdle()

        assertShown("downloads-root")
        assertThat(downloadsRequests.pending).isFalse()
        pressBack()
        assertShown("home-root")
    }

    @Test
    fun `a reader asked for while the shell is shown opens above it`() {
        show()

        request(FirstBook)

        assertShown("reader-7")
        assertThat(opened).containsExactly(ReaderDestination(FirstBook.value))
        pressBack()
        assertShown("home-root")
    }

    @Test
    fun `the reader asked for again while it is in front stays as it is`() {
        show()
        request(FirstBook)

        request(FirstBook)

        assertThat(opened).containsExactly(ReaderDestination(FirstBook.value))
        pressBack()
        assertShown("home-root")
    }

    @Test
    fun `a narrating reader in front is the reader asked for`() {
        show()
        compose.runOnUiThread { checkNotNull(modes).onRead(ReaderDestination(FirstBook.value, narrate = true)) }
        compose.waitForIdle()

        request(FirstBook)

        assertThat(opened).containsExactly(ReaderDestination(FirstBook.value, narrate = true))
    }

    @Test
    fun `the reader of another book gives way to the one asked for`() {
        show()
        request(FirstBook)

        request(OtherBook)

        assertShown("reader-12")
        pressBack()
        assertShown("home-root")
    }

    @Test
    fun `the player gives way to the reader asked for`() {
        show()
        compose.onNodeWithText("mini-player").performClick()
        assertShown("player")

        request(FirstBook)

        assertShown("reader-7")
        pressBack()
        assertShown("home-root")
    }

    @Test
    fun `a double tap on the mini-player of a narration opens one reader`() {
        show()
        val click = checkNotNull(
            compose.onNodeWithText("mini-narration").fetchSemanticsNode().config[SemanticsActions.OnClick].action,
        )

        compose.runOnUiThread {
            click()
            click()
        }
        compose.waitForIdle()

        assertThat(opened).containsExactly(ReaderDestination(FirstBook.value))
        pressBack()
        assertShown("home-root")
    }
}

private val FirstBook = BookId("7")
private val OtherBook = BookId("12")
