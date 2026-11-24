package app.tfdn.lirelle.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.shell.Shell
import app.tfdn.lirelle.shell.TopLevelDestination
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player and its settings above the shell: opened from Listen, the mini-player and the player, left
 * for where they were opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PlayerNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val listened = mutableListOf<Pair<BookId, Boolean>>()

    private fun show() {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Compact) {
                    RootNavHost(
                        shell = { modes, bookRequests, player, _ ->
                            Shell(
                                account = {},
                                home = { listeningTab<HomeDestination>("home", it, modes) },
                                library = { listeningTab<LibraryDestination>("library", it, modes) },
                                search = { listeningTab<SearchDestination>("search", it, modes) },
                                bookRequests = bookRequests,
                                miniPlayer = {
                                    Column(it) {
                                        Text("mini-player", Modifier.clickable(onClick = player.onOpenPlayer))
                                        // The bar of a narration: the reader of its book.
                                        Text("mini-narration", Modifier.clickable { player.onOpenReader(FirstBook) })
                                    }
                                },
                            )
                        },
                        // Without a Back of its own: the route's is what leaves it.
                        reader = { book, _, _ -> Text("reader-${book.bookId}") },
                        player = { onMinimise, onOpenBook, onOpenSettings ->
                            val playing = listened.lastOrNull()?.first ?: OtherBook
                            StubPlayer(playing, onMinimise, onOpenBook, onOpenSettings)
                        },
                        playerSettings = { onBack -> Text("settings-back", Modifier.clickable(onClick = onBack)) },
                        listen = { bookId, fromStart -> listened += bookId to fromStart },
                    )
                }
            }
        }
    }

    /** Two clicks with no frame in between, as a fast double tap lands on the device. */
    private fun doubleTap(node: SemanticsNodeInteraction) {
        val click = checkNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread {
            click()
            click()
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun tap(text: String) = compose.onNodeWithText(text).performClick()

    /** Taps [text], then presses Back while the screen it opens is still coming in, over the shell. */
    private fun tapThenBackInTransition(text: String) {
        compose.mainClock.autoAdvance = false
        tap(text)
        compose.mainClock.advanceTimeBy(TransitionMidwayMs)
        pressBack()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun assertShown(text: String) = compose.onNodeWithText(text).assertIsDisplayed()

    private fun assertGone(text: String) = compose.onNodeWithText(text).assertDoesNotExist()

    private fun assertNoNavigation() =
        TopLevelDestination.entries.forEach { compose.onNodeWithTag(it.testTag).assertDoesNotExist() }

    private fun openBookInLibrary() {
        compose.onNodeWithTag("nav-library").performClick()
        tap("library-root")
        assertShown("library-book-7")
    }

    @Test
    fun `listening from a book page loads the book, then opens the player above the shell`() {
        show()
        openBookInLibrary()

        tap("library-listen-7")

        assertThat(listened).containsExactly(FirstBook to false)
        assertShown("player")
        assertNoNavigation()
        assertGone("library-book-7")
    }

    @Test
    fun `listening again loads the book from its start`() {
        show()
        openBookInLibrary()

        tap("library-relisten-7")

        assertThat(listened).containsExactly(FirstBook to true)
        assertShown("player")
    }

    @Test
    fun `back from the player returns to the page it was opened from`() {
        show()
        openBookInLibrary()
        tap("library-listen-7")
        assertShown("player")

        pressBack()

        assertShown("library-book-7")
        compose.onNodeWithTag("nav-library").assertIsSelected()
    }

    @Test
    fun `listening twice in a double tap loads once and opens one player`() {
        show()
        openBookInLibrary()

        doubleTap(compose.onNodeWithText("library-listen-7"))

        assertThat(listened).containsExactly(FirstBook to false)
        assertShown("player")
        pressBack()
        assertShown("library-book-7")
    }

    @Test
    fun `the mini-player opens the player, and minimising returns to the tab`() {
        show()

        tap("mini-player")
        assertShown("player")
        assertNoNavigation()

        tap("player-minimise")

        assertShown("home-root")
        compose.onNodeWithTag("nav-home").assertIsSelected()
    }

    @Test
    fun `the mini-player of a narration opens the reader on its book, and back returns to the tab`() {
        show()

        tap("mini-narration")
        assertShown("reader-7")
        assertNoNavigation()

        pressBack()

        assertShown("home-root")
    }

    @Test
    fun `a double tap on the mini-player opens one player`() {
        show()

        doubleTap(compose.onNodeWithText("mini-player"))
        assertShown("player")
        pressBack()

        assertShown("home-root")
    }

    @Test
    fun `a player that asks twice to be minimised is left once`() {
        show()
        openBookInLibrary()
        tap("library-listen-7")

        doubleTap(compose.onNodeWithText("player-minimise"))

        assertShown("library-book-7")
    }

    @Test
    fun `the book page from the player closes it and opens the page in the tab underneath`() {
        show()
        tap("mini-player")

        tap("player-book-$OtherBook")

        assertGone("player")
        assertShown("home-book-$OtherBook")
        compose.onNodeWithTag("nav-home").assertIsSelected()
        pressBack()
        assertShown("home-root")
    }

    @Test
    fun `the page of the book playing, from the player opened on that page, is not opened twice`() {
        show()
        openBookInLibrary()
        tap("library-listen-7")

        tap("player-book-7")

        assertGone("player")
        assertShown("library-book-7")
        pressBack()
        assertShown("library-root")
    }

    @Test
    fun `a Back pressed while the player is still coming in leaves it`() {
        show()
        openBookInLibrary()

        tapThenBackInTransition("library-listen-7")

        assertGone("player")
        assertShown("library-book-7")
    }

    @Test
    fun `a Back pressed while the reader is still coming in leaves it`() {
        show()
        openBookInLibrary()

        tapThenBackInTransition("library-read-7")

        assertGone("reader-7")
        assertShown("library-book-7")
    }

    @Test
    fun `a Back pressed while the settings are still coming in returns to the player`() {
        show()
        tap("mini-player")
        assertShown("player")

        tapThenBackInTransition("player-settings")

        assertGone("settings-back")
        assertShown("player")
    }

    @Test
    fun `the settings from the player open above it, and back returns to the player`() {
        show()
        tap("mini-player")

        tap("player-settings")
        assertShown("settings-back")
        assertGone("player")

        tap("settings-back")

        assertShown("player")
    }

    @Test
    fun `settings asked twice from the player open once`() {
        show()
        tap("mini-player")

        doubleTap(compose.onNodeWithText("player-settings"))
        pressBack()

        assertShown("player")
    }

}

private val FirstBook = BookId("7")
private val OtherBook = BookId("12")

/** Into the screens' fade in, the shell still on screen under it. */
private const val TransitionMidwayMs = 200L

/**
 * A tab without data: a root that opens book 7, and a book page that can be listened to, where it
 * was left or again.
 */
private inline fun <reified Root : Any> NavGraphBuilder.listeningTab(
    tab: String,
    navController: NavHostController,
    modes: BookModes,
) {
    composable<Root> {
        Text("$tab-root", Modifier.clickable { navController.navigate(BookDestination(FirstBook.value)) })
    }
    composable<BookDestination> { entry ->
        val id = BookId(entry.toRoute<BookDestination>().id)
        Column {
            Text("$tab-book-$id")
            // Not guarded here: the root's own guard is what keeps a double tap to one player.
            Text("$tab-listen-$id", Modifier.clickable { modes.onListen(id, false) })
            Text("$tab-relisten-$id", Modifier.clickable { modes.onListen(id, true) })
            Text("$tab-read-$id", Modifier.clickable { modes.onRead(ReaderDestination(id.value)) })
        }
    }
}

/** A player of [bookId] without data: it minimises, opens the page of its book and its settings. */
@Composable
private fun StubPlayer(
    bookId: BookId,
    onMinimise: () -> Unit,
    onOpenBook: (BookId) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column {
        Text("player")
        Text("player-minimise", Modifier.clickable(onClick = onMinimise))
        Text("player-book-$bookId", Modifier.clickable { onOpenBook(bookId) })
        Text("player-settings", Modifier.clickable(onClick = onOpenSettings))
    }
}
