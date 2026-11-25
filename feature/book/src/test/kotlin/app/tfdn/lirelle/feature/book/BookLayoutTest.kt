package app.tfdn.lirelle.feature.book

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookEdition
import app.tfdn.lirelle.core.library.model.EditionMedia
import app.tfdn.lirelle.core.library.model.EditionProgress
import app.tfdn.lirelle.core.testing.laidOutTexts
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where the header of the book page and the block of its editions are laid out, by width and text size; its
 * actions: `BookActionsLayoutTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
@Config(qualifiers = Phone)
class BookLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(width: WindowWidthClass = WindowWidthClass.Compact, book: BookDetail = sampleBookDetail()) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    BookContent(
                        state = BookUiState(Loadable.Ready(book)),
                        onBack = {},
                        onRetry = {},
                        onRefresh = {},
                        onOpenBook = {},
                        onOpenAuthor = {},
                        onOpenGenre = {},
                        onRead = { _, _ -> },
                        onListen = { _, _ -> },
                        onNarrate = {},
                    )
                }
            }
        }
    }

    private val editions = listOf(
        edition("1046", "Version en anglais", EditionProgress.Finished),
        edition("1052", "Version en français, tome 1/2", EditionProgress.InProgress(42)),
        edition("12", "Version en français, tome 2/2", EditionProgress.NotStarted, isOpen = true),
    )

    private fun edition(id: String, label: String, progress: EditionProgress, isOpen: Boolean = false) =
        BookEdition(BookId(id), isOpen, label, "Edition $id", coverUrl = null, EditionMedia.TextAndAudio, progress)

    private fun bounds(tag: String): DpRect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun assertTextsBesideCover() {
        val cover = bounds(BookCoverTag)
        val title = bounds(BookTitleTag)
        assertThat(title.left).isGreaterThan(cover.right)
        assertThat((title.top - cover.top).value).isWithin(1f).of(0f)
        assertThat(cover.left).isEqualTo(bounds("book-header").left)
    }

    private fun assertTextsUnderCover() {
        val cover = bounds(BookCoverTag)
        val title = bounds(BookTitleTag)
        assertThat(title.top).isAtLeast(cover.bottom)
    }

    @Test
    fun `on a phone the title is to the right of the cover and level with its top`() {
        show()

        assertTextsBesideCover()
        assertThat(bounds(BookCoverTag).width.value).isWithin(1f).of(120f)
        // The cover keeps its shape: a portrait one is taller than wide.
        assertThat(bounds(BookCoverTag).height).isGreaterThan(bounds(BookCoverTag).width)
    }

    @Test
    fun `a long title wraps next to the cover without being cut`() {
        val title = "A title long enough to need several lines on a narrow phone at a large text size"
        show(book = sampleBookDetail().copy(title = title))

        val laidOut = compose.laidOutTexts().single { it.text == title }
        assertThat(laidOut.lineCount).isGreaterThan(1)
        assertThat(laidOut.isEllipsized).isFalse()
        assertThat(bounds(BookTitleTag).right).isAtMost(bounds("book-header").right)
    }

    @Test
    fun `the author follows the texts above with no gap and keeps a full touch target`() {
        show()

        val subtitle = compose.onNodeWithText("With a subtitle").getUnclippedBoundsInRoot()
        val author = compose.onNodeWithTag("author-9")
        assertThat(author.getUnclippedBoundsInRoot().top - subtitle.bottom).isAtMost(8.dp)
        // The minimum touch target is not laid out: the name is as tall as a line of its text.
        assertThat(author.getUnclippedBoundsInRoot().height).isLessThan(32.dp)
        val touchHeight = with(compose.density) { author.fetchSemanticsNode().touchBoundsInRoot.height.toDp() }
        assertThat(touchHeight).isAtLeast(48.dp)
    }

    @Test
    fun `the name of the edition is under the series of the book`() {
        show(book = sampleBookDetail().copy(editionName = "Full cast"))

        val name = compose.onNodeWithText("Full cast").getUnclippedBoundsInRoot()
        assertThat(bounds(BookEditionNameTag)).isEqualTo(name)
        val series = compose.onNodeWithText("A Saga", substring = true).getUnclippedBoundsInRoot()
        assertThat(name.top).isAtLeast(series.bottom)
        assertThat(name.top).isAtLeast(bounds("author-9").bottom)
        assertThat(name.left).isEqualTo(bounds(BookTitleTag).left)
    }

    @Test
    fun `a book without a name for its edition has no such line`() {
        show()

        compose.onAllNodesWithTag(BookEditionNameTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `on a phone the editions are under the facts and above the reading, one per row`() {
        show(book = sampleBookDetail().copy(editions = editions))

        val block = bounds(EditionsTag)
        val facts = compose.onNodeWithText("412", substring = true).getUnclippedBoundsInRoot()
        assertThat(block.top).isAtLeast(facts.bottom)
        assertThat(bounds("book-reading").top).isAtLeast(block.bottom)
        assertThat(block.left).isEqualTo(bounds("book-header").left)
        assertThat(block.right).isEqualTo(bounds("book-header").right)
        val lines = editions.map { bounds(editionTag(it.bookId)) }
        assertThat(lines[1].top).isAtLeast(lines[0].bottom)
        assertThat(lines[2].top).isAtLeast(lines[1].bottom)
    }

    @Test
    @Config(qualifiers = "w1100dp-h800dp")
    fun `on a wide screen the editions open the right column, two per row`() {
        show(WindowWidthClass.Expanded, sampleBookDetail().copy(editions = editions))

        val block = bounds(EditionsTag)
        assertThat(block.left).isGreaterThan(bounds("book-header").right)
        // The first block of its column: level with the top of the header, above the reading.
        assertThat(block.top).isEqualTo(bounds("book-header").top)
        assertThat(bounds("book-reading").top).isAtLeast(block.bottom)
        assertThat(bounds("book-reading").left).isEqualTo(block.left)
        val lines = editions.map { bounds(editionTag(it.bookId)) }
        assertThat(lines[1].top).isEqualTo(lines[0].top)
        assertThat(lines[1].left).isGreaterThan(lines[0].right)
        assertThat(lines[2].top).isAtLeast(lines[0].bottom)
        assertThat(lines[2].left).isEqualTo(lines[0].left)
    }

    @Test
    @Config(qualifiers = Narrow)
    fun `on a narrow phone at the default text size the texts are still beside the cover`() {
        show()

        assertTextsBesideCover()
    }

    @Test
    @Config(qualifiers = Narrow, fontScale = 2f)
    fun `on a narrow phone with very large text the cover is above the texts`() {
        show()

        assertTextsUnderCover()
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `on a small tablet the texts are beside the cover`() {
        show(WindowWidthClass.Medium)

        assertTextsBesideCover()
    }

    @Test
    @Config(qualifiers = "w1100dp-h800dp")
    fun `on a wide screen the cover stays large, above the texts`() {
        show(WindowWidthClass.Expanded)

        assertTextsUnderCover()
        assertThat(bounds(BookCoverTag).width).isGreaterThan(180.dp)
    }
}

private const val Phone = "w411dp-h891dp"
private const val Narrow = "w320dp-h640dp"
