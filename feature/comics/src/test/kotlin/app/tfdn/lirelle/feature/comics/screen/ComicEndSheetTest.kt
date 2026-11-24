package app.tfdn.lirelle.feature.comics.screen

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.ui.SheetGripTag
import app.tfdn.lirelle.feature.comics.ComicEndPanel
import app.tfdn.lirelle.feature.comics.page.drawn
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val NextTitle = "Arctic-Nation"

/** The sheet at the end of an album: what it holds, its stars, its buttons, and what they ask for. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicEndSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val next = NextVolume(bookId = BookId("15"), title = NextTitle, seriesIndex = "2")
    private val screen = ScreenUnderTest(openAlbum(spreadIndex = 47, barsShown = false))
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun show(panel: ComicEndPanel?) {
        screen.state = screen.state.copy(endPanel = panel)
        compose.setContent { screen.Content() }
        compose.waitForIdle()
    }

    @Test
    fun `the sheet shows only while the state has a panel`() {
        show(null)
        compose.onNodeWithText("You finished this album").assertDoesNotExist()

        screen.state = screen.state.copy(endPanel = ComicEndPanel())
        compose.waitForIdle()

        compose.onNode(hasText("You finished this album") and heading).assertExists()
        compose.onNodeWithText(Title).assertExists()
        compose.onNodeWithText("Your rating (optional)").assertExists()
        compose.onNodeWithContentDescription("Close the album").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        // An album alone: no next one, nothing sent, nothing failed.
        compose.onNodeWithText("Next volume").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sending the rating").assertDoesNotExist()
        compose.onNodeWithText("The rating could not be sent.").assertDoesNotExist()
    }

    @Test
    fun `a PDF and a DjVu are books, not albums`() {
        show(null)
        listOf(PagedKind.Pdf, PagedKind.Djvu).forEach { kind ->
            screen.state = screen.state.copy(kind = kind, endPanel = ComicEndPanel(nextVolume = next))
            compose.waitForIdle()

            compose.onNode(hasText("You finished this book") and heading).assertExists()
            compose.onNodeWithContentDescription("Close the book").assertIsEnabled().assertHasClickAction()
            compose.onNodeWithText("You finished this album").assertDoesNotExist()
            compose.onNodeWithContentDescription("Close the album").assertDoesNotExist()
            // The rest of the sheet says what it says of an album.
            compose.onNodeWithText("Next volume").assertExists()
            compose.onNodeWithContentDescription("Read the next one").assertExists()
        }
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, a book is closed and an album is closed, each by its name`() {
        show(ComicEndPanel())
        compose.onNodeWithText("Vous avez terminé cet album").assertExists()
        compose.onNodeWithContentDescription("Fermer l’album").assertExists()

        screen.state = screen.state.copy(kind = PagedKind.Pdf)
        compose.waitForIdle()

        compose.onNodeWithText("Vous avez terminé ce livre").assertExists()
        compose.onNodeWithContentDescription("Fermer le livre").assertExists()
    }

    @Test
    fun `the stars are named, 48 dp wide and high, and the chosen one is selected`() {
        show(ComicEndPanel(rating = 3))

        compose.onNodeWithContentDescription("1 star").assertIsNotSelected().assertHasClickAction()
        compose.onNodeWithContentDescription("3 stars").assertIsSelected()
        listOf("2 stars", "4 stars", "5 stars").forEach {
            compose.onNodeWithContentDescription(it)
                .assertIsNotSelected()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun `a star asks for its rating, and the chosen one for none`() {
        show(ComicEndPanel(rating = 3))

        compose.onNodeWithContentDescription("5 stars").performClick()
        compose.onNodeWithContentDescription("1 star").performClick()
        compose.onNodeWithContentDescription("3 stars").performClick()

        assertThat(screen.actions.calls).containsExactly("rate 5", "rate 1", "rate null").inOrder()
    }

    @Test
    fun `the next album has its title and its button, beside it when there is room`() {
        show(ComicEndPanel(nextVolume = next))

        compose.onNodeWithText("Next volume").assertExists()
        val title = compose.onNodeWithText(NextTitle).drawn()
        val button = compose.onNodeWithContentDescription("Read the next one").assertHeightIsAtLeast(48.dp).drawn()
        assertThat(button.left).isGreaterThan(title.left)
        assertThat(button.top).isLessThan(title.bottom)

        compose.onNodeWithContentDescription("Read the next one").performClick()
        compose.onNodeWithContentDescription("Close the album").performClick()

        assertThat(screen.actions.calls).containsExactly("readNext", "closeAlbum").inOrder()
    }

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp", fontScale = 2f)
    fun `the button goes under the next album's title when it has no room beside it`() {
        show(ComicEndPanel(nextVolume = next))

        val title = compose.onNodeWithText(NextTitle).performScrollTo().drawn()
        val button = compose.onNodeWithContentDescription("Lire le suivant").performScrollTo().drawn()

        assertThat(button.top).isAtLeast(title.bottom)
    }

    @Test
    fun `while the rating is on its way the mark shows and nothing can be pressed`() {
        show(ComicEndPanel(rating = 4, nextVolume = next, sending = true))

        compose.onNodeWithContentDescription("Sending the rating").assertExists()
        compose.onNodeWithContentDescription("Close the album").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Read the next one").assertIsNotEnabled()
        listOf("1 star", "2 stars", "3 stars", "4 stars", "5 stars").forEach {
            compose.onNodeWithContentDescription(it).assertIsNotEnabled()
        }
    }

    @Test
    fun `a rating that could not be sent says so, politely, and offers to try again`() {
        show(ComicEndPanel(rating = 4, nextVolume = next, failed = true))

        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        compose.onNode(hasText("The rating could not be sent.") and polite).assertExists()
        compose.onNodeWithText("Try again").assertHeightIsAtLeast(48.dp).performClick()

        assertThat(screen.actions.calls).containsExactly("retryRating")
        compose.onNodeWithContentDescription("Close the album").assertIsEnabled()
    }

    @Test
    fun `the grip dismisses the sheet`() {
        show(ComicEndPanel())

        compose.onNodeWithTag(SheetGripTag).performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()

        assertThat(screen.actions.calls).containsExactly("dismissEndPanel")
    }
}
