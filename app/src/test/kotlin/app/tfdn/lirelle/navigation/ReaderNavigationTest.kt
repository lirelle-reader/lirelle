package app.tfdn.lirelle.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.samplePagedDetail
import app.tfdn.lirelle.shell.Shell
import app.tfdn.lirelle.shell.TopLevelDestination
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The reader above the shell: opened from a tab, left for that tab as it was. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ReaderNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val listened = mutableListOf<Pair<BookId, Boolean>>()

    private fun show(width: WindowWidthClass = WindowWidthClass.Compact) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    RootNavHost(
                        shell = { modes, bookRequests, _, _ ->
                            Shell(
                                account = {},
                                home = { stubTab<HomeDestination>("home", it, modes) },
                                library = { stubTab<LibraryDestination>("library", it, modes) },
                                search = { stubTab<SearchDestination>("search", it, modes) },
                                bookRequests = bookRequests,
                            )
                        },
                        reader = { book, onLeave, onOpenBook -> StubReader(book, onLeave, onOpenBook) },
                        comics = { album, onLeave, onOpenNext -> StubComics(album, onLeave, onOpenNext) },
                        player = { _, _, _ -> },
                        playerSettings = {},
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

    private fun assertShown(text: String) = compose.onNodeWithText(text).assertIsDisplayed()

    private fun assertNoNavigation() =
        TopLevelDestination.entries.forEach { compose.onNodeWithTag(it.testTag).assertDoesNotExist() }

    private fun openBookInLibrary() {
        compose.onNodeWithTag("nav-library").performClick()
        tap("library-root")
        assertShown("library-book-7")
    }

    @Test
    fun `the reader opened from a book page covers the navigation bar, and Back returns to that page`() {
        show()
        openBookInLibrary()

        tap("library-read-7")

        assertShown("reader-7")
        assertNoNavigation()
        compose.onNodeWithText("library-book-7").assertDoesNotExist()

        pressBack()

        assertShown("library-book-7")
        compose.onNodeWithTag("nav-library").assertIsSelected()
        pressBack()
        assertShown("library-root")
    }

    @Test
    fun `a book read from its page opens where it was left`() {
        show()
        openBookInLibrary()

        tap("library-read-7")

        assertShown("reader-7")
        assertShown("reader-where-left")
    }

    @Test
    fun `a book read again from its page opens at its beginning`() {
        show()
        openBookInLibrary()

        tap("library-reread-7")

        assertShown("reader-7")
        assertShown("reader-from-start")
        pressBack()
        assertShown("library-book-7")
    }

    @Test
    fun `a book listened to from a tab is loaded by the root, where it was left`() {
        show()

        tap("home-listen")

        assertThat(listened).containsExactly(BookId(BookBeingRead) to false)
    }

    @Test
    fun `a book gone on with from Home opens where it was left`() {
        show()

        tap("home-continue")

        assertShown("reader-where-left")
    }

    @Test
    fun `a reader made again from its saved state is not one shown for the first time`() {
        val restoration = StateRestorationTester(compose)
        var first: Boolean? = null
        restoration.setContent { first = rememberIsFirstShowing() }
        compose.runOnIdle { assertThat(first).isTrue() }

        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle { assertThat(first).isFalse() }
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun `the reader covers the navigation rail too`() {
        show(WindowWidthClass.Expanded)
        openBookInLibrary()

        tap("library-read-7")

        assertShown("reader-7")
        assertNoNavigation()
    }

    @Test
    fun `the reader opened from Home covers the navigation bar, and Back returns to Home`() {
        show()

        tap("home-continue")

        assertShown("reader-3")
        assertNoNavigation()

        pressBack()

        assertShown("home-root")
        compose.onNodeWithTag("nav-home").assertIsSelected()
    }

    @Test
    fun `the tabs are as they were left when the reader is closed`() {
        show()
        compose.onNodeWithTag("nav-search").performClick()
        tap("search-root")
        compose.onNodeWithTag("nav-home").performClick()

        tap("home-continue")
        tap("reader-leave")

        assertShown("home-root")
        compose.onNodeWithTag("nav-search").performClick()
        assertShown("search-book-7")
    }

    @Test
    fun `the next volume closes the reader and opens its page in the tab underneath`() {
        show()
        openBookInLibrary()
        tap("library-read-7")

        tap("reader-next")

        compose.onNodeWithText("reader-7").assertDoesNotExist()
        assertShown("library-book-8")
        compose.onNodeWithTag("nav-library").assertIsSelected()
        // On top of the page the reader was opened from.
        pressBack()
        assertShown("library-book-7")
    }

    @Test
    fun `the next volume of a book read from Home opens its page in Home`() {
        show()
        tap("home-continue")

        tap("reader-next")

        assertShown("home-book-4")
        compose.onNodeWithTag("nav-home").assertIsSelected()
        pressBack()
        assertShown("home-root")
    }

    @Test
    fun `a page asked for once is not opened again by the next reader`() {
        show()
        tap("home-continue")
        tap("reader-next")
        assertShown("home-book-4")

        tap("home-read-4")
        assertShown("reader-4")
        pressBack()

        assertShown("home-book-4")
        pressBack()
        assertShown("home-root")
    }

    @Test
    fun `a comic read from its page opens in the comics reader, and Back returns to that page`() {
        show()
        openBookInLibrary()

        tap("library-read-comic-7")

        assertShown("comics-7")
        assertShown("comics-where-left")
        assertNoNavigation()
        compose.onNodeWithText("reader-7").assertDoesNotExist()

        pressBack()

        assertShown("library-book-7")
        compose.onNodeWithTag("nav-library").assertIsSelected()
    }

    @Test
    fun `a PDF read from its page opens in the page reader, not in the book reader, and Back returns to that page`() {
        show()
        openBookInLibrary()

        tap("library-read-pdf-7")

        assertShown("comics-7")
        assertShown("comics-where-left")
        assertNoNavigation()
        compose.onNodeWithText("reader-7").assertDoesNotExist()

        pressBack()

        assertShown("library-book-7")
        compose.onNodeWithTag("nav-library").assertIsSelected()
    }

    @Test
    fun `a PDF gone on with from Home opens in the page reader, and leaving it returns to Home`() {
        show()

        tap("home-continue-pdf")

        assertShown("comics-3")
        assertShown("comics-where-left")
        assertNoNavigation()
        compose.onNodeWithText("reader-3").assertDoesNotExist()

        tap("comics-leave")

        assertShown("home-root")
        compose.onNodeWithTag("nav-home").assertIsSelected()
    }

    @Test
    fun `a book that is not read by pages opens in the book reader by the same rule`() {
        show()
        openBookInLibrary()

        tap("library-read-epub-7")

        assertShown("reader-7")
        compose.onNodeWithText("comics-7").assertDoesNotExist()
    }

    @Test
    fun `a comic read again from its page opens at its first page`() {
        show()
        openBookInLibrary()

        tap("library-reread-comic-7")

        assertShown("comics-7")
        assertShown("comics-from-start")
    }

    @Test
    fun `a comic gone on with from Home opens in the comics reader, and leaving it returns to Home`() {
        show()

        tap("home-continue-comic")

        assertShown("comics-3")
        assertShown("comics-where-left")
        assertNoNavigation()
        compose.onNodeWithText("reader-3").assertDoesNotExist()

        tap("comics-leave")

        assertShown("home-root")
        compose.onNodeWithTag("nav-home").assertIsSelected()
    }

    @Test
    fun `the next album takes the place of the one read, and Back returns to where the first was opened from`() {
        show()
        openBookInLibrary()
        tap("library-read-comic-7")

        tap("comics-next")

        assertShown("comics-8")
        // The next album goes on where it was left, whatever the way the one before was opened.
        assertShown("comics-where-left")
        assertNoNavigation()
        compose.onNodeWithText("comics-7").assertDoesNotExist()

        tap("comics-next")
        assertShown("comics-9")

        pressBack()

        assertShown("library-book-7")
        pressBack()
        assertShown("library-root")
    }

    @Test
    fun `a comics reader that asks twice for the next album opens it once`() {
        show()
        openBookInLibrary()
        tap("library-read-comic-7")

        doubleTap(compose.onNodeWithText("comics-next"))

        assertShown("comics-8")
        pressBack()
        assertShown("library-book-7")
    }

    @Test
    fun `a comics reader that asks twice to be left is left once`() {
        show()
        openBookInLibrary()
        tap("library-read-comic-7")

        doubleTap(compose.onNodeWithText("comics-leave"))

        assertShown("library-book-7")
    }

    @Test
    fun `tapping the reading button of a comic twice opens the comics reader once`() {
        show()
        openBookInLibrary()

        doubleTap(compose.onNodeWithText("library-read-comic-7"))
        assertShown("comics-7")
        pressBack()

        assertShown("library-book-7")
    }

    @Test
    fun `tapping the reading button twice opens the reader once`() {
        show()
        openBookInLibrary()

        doubleTap(compose.onNodeWithText("library-read-7"))
        assertShown("reader-7")
        pressBack()

        assertShown("library-book-7")
    }

    @Test
    fun `a reader that asks twice to be left is left once`() {
        show()
        openBookInLibrary()
        tap("library-read-7")

        doubleTap(compose.onNodeWithText("reader-leave"))

        assertShown("library-book-7")
    }

    @Test
    fun `a reader that asks twice for the next volume opens its page once`() {
        show()
        openBookInLibrary()
        tap("library-read-7")

        doubleTap(compose.onNodeWithText("reader-next"))

        assertShown("library-book-8")
        pressBack()
        assertShown("library-book-7")
    }
}

// The books' ids, as their text: what a route carries.
private const val FirstBook = "7"
private const val BookBeingRead = "3"

/** The next volume of each book the stubs show: ids are not numbers to count on. */
private val NextVolume = mapOf("3" to "4", "4" to "5", "7" to "8", "8" to "9")

private fun nextOf(bookId: String): BookId = BookId(checkNotNull(NextVolume[bookId]))

/**
 * A tab without data: a root that opens book 7 or goes on reading book 3, and a book page that
 * can be read, where it was left or again from its beginning, as a book or as a comic, or as its file says
 * (a PDF, an EPUB). The texts say whose copy is shown.
 */
private inline fun <reified Root : Any> NavGraphBuilder.stubTab(
    tab: String,
    navController: NavHostController,
    modes: BookModes,
) {
    composable<Root> { entry ->
        Column {
            Text("$tab-root", Modifier.clickable { navController.navigate(BookDestination(FirstBook)) })
            val goOn = entry.ifResumed { modes.onRead(ReaderDestination(BookBeingRead)) }
            Text("$tab-continue", Modifier.clickable(onClick = goOn))
            val goOnComic = entry.ifResumed { modes.onReadComic(ComicsDestination(BookBeingRead)) }
            Text("$tab-continue-comic", Modifier.clickable(onClick = goOnComic))
            // The card of a PDF, which goes where the "Continue" card of Home sends a book of its kind.
            val pdf = sampleBook(BookBeingRead, pagedKind = PagedKind.Pdf)
            val byPages = entry.ifResumed { modes.onReadComic(ComicsDestination(pdf.id.value)) }
            val goOnPdf = if (pdf.isPaged) byPages else goOn
            Text("$tab-continue-pdf", Modifier.clickable(onClick = goOnPdf))
            Text("$tab-listen", Modifier.clickable { modes.onListen(BookId(BookBeingRead), false) })
        }
    }
    composable<BookDestination> { entry ->
        val id = entry.toRoute<BookDestination>().id
        Column {
            Text("$tab-book-$id")
            val read = entry.ifResumedWith(modes.onRead)
            Text("$tab-read-$id", Modifier.clickable { read(ReaderDestination(id)) })
            Text("$tab-reread-$id", Modifier.clickable { read(ReaderDestination(id, fromStart = true)) })
            val readComic = entry.ifResumedWith(modes.onReadComic)
            Text("$tab-read-comic-$id", Modifier.clickable { readComic(ComicsDestination(id)) })
            Text("$tab-reread-comic-$id", Modifier.clickable { readComic(ComicsDestination(id, fromStart = true)) })
            // A book by its file, read where the book page reads a book of its kind.
            mapOf("pdf" to samplePagedDetail("PDF", id), "epub" to sampleBookDetail(id)).forEach { (format, book) ->
                val open = { if (book.isPaged) readComic(ComicsDestination(id)) else read(ReaderDestination(id)) }
                Text("$tab-read-$format-$id", Modifier.clickable(onClick = open))
            }
        }
    }
}

/** A reader without a book: it leaves as the real one does, by itself, on the system's Back. */
@Composable
private fun StubReader(book: ReaderDestination, onLeave: () -> Unit, onOpenBook: (BookId) -> Unit) {
    val bookId = book.bookId
    BackHandler(onBack = onLeave)
    Column {
        Text("reader-$bookId")
        Text(if (book.fromStart) "reader-from-start" else "reader-where-left")
        Text("reader-leave", Modifier.clickable(onClick = onLeave))
        Text("reader-next", Modifier.clickable { onOpenBook(nextOf(bookId)) })
    }
}

/** A comics reader without an album: it leaves and asks for the next album as the real one does. */
@Composable
private fun StubComics(album: ComicsDestination, onLeave: () -> Unit, onOpenNext: (BookId) -> Unit) {
    val bookId = album.bookId
    BackHandler(onBack = onLeave)
    Column {
        Text("comics-$bookId")
        Text(if (album.fromStart) "comics-from-start" else "comics-where-left")
        Text("comics-leave", Modifier.clickable(onClick = onLeave))
        Text("comics-next", Modifier.clickable { onOpenNext(nextOf(bookId)) })
    }
}
