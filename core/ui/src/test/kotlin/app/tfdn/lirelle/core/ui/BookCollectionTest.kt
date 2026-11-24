package app.tfdn.lirelle.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.testing.laidOutTexts
import app.tfdn.lirelle.core.testing.sampleBook
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode makes the lines under a cover too tall.
class BookCollectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<BookId>()

    private fun show(
        books: List<BookSummary?>,
        presentation: Presentation = Presentation.Grid,
        width: WindowWidthClass = WindowWidthClass.Compact,
    ) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    BookCollection(
                        items = bookItemsOf(books),
                        presentation = presentation,
                        onOpenBook = { opened += it },
                    )
                }
            }
        }
    }

    private fun cover(id: Long) =
        compose.onNodeWithTag("cover-$id", useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun height(id: Long): Dp = cover(id).let { it.bottom - it.top }

    private val portraits = (1..3).map { sampleBook("$it") }
    private val squares = (4..6).map { sampleBook("$it", shape = CoverShape.Square, media = MediaProfile.AudioOnly) }

    @Test
    fun `a phone shows three books per row`() {
        show(portraits + squares)

        assertThat(cover(1).top).isEqualTo(cover(2).top)
        assertThat(cover(2).top).isEqualTo(cover(3).top)
        assertThat(cover(4).top).isGreaterThan(cover(1).bottom)
        assertThat(cover(2).left).isGreaterThan(cover(1).right)
    }

    @Test
    fun `a row of squares is shorter than a row of books`() {
        show(portraits + squares)

        assertThat(height(4).value).isWithin(1f).of(cover(4).let { it.right - it.left }.value)
        assertThat(height(4)).isLessThan(height(1))
        // The second row starts right after the first one: no gap left for a taller cover.
        val gap = cover(4).top - cover(1).bottom
        assertThat(gap.value).isLessThan(height(1).value - height(4).value)
    }

    @Test
    fun `in a mixed row squares sit on the same line as the books`() {
        show(listOf(sampleBook("1"), sampleBook("2", shape = CoverShape.Square), sampleBook("3")))

        assertThat(cover(2).bottom.value).isWithin(1f).of(cover(1).bottom.value)
        assertThat(cover(2).top).isGreaterThan(cover(1).top)
    }

    @Test
    fun `tapping a book opens it`() {
        show(portraits)

        compose.onNodeWithTag("book-2").performClick()

        assertThat(opened).containsExactly(BookId("2"))
    }

    @Test
    fun `items not loaded yet are placeholders`() {
        show(listOf(sampleBook("1"), null, null))

        assertThat(compose.onAllNodesWithTag(BookPlaceholderTag).fetchSemanticsNodes()).hasSize(2)
    }

    @Test
    fun `the list shows the series and its volume`() {
        val saga = SeriesRef(SeriesId("9"), "A Saga", "4")
        show(listOf(sampleBook("1", title = "A Feast", series = saga)), Presentation.List)

        // A book is one node for accessibility services: its texts are in the unmerged tree. Without
        // a cover image the title is also written on the cover, hence the sibling condition.
        compose.onNode(hasText("A Feast") and hasAnySibling(hasText("A Saga #4")), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("A Saga #4", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the list is one column on a phone`() {
        show(portraits, Presentation.List)

        assertThat(cover(2).top).isGreaterThan(cover(1).top)
        assertThat(cover(2).left).isEqualTo(cover(1).left)
    }

    @Test
    @Config(qualifiers = "w1000dp-h1600dp")
    fun `the list is two columns on a tablet`() {
        show(portraits, Presentation.List, WindowWidthClass.Expanded)

        assertThat(cover(2).top).isEqualTo(cover(1).top)
        assertThat(cover(2).left).isGreaterThan(cover(1).left)
        assertThat(cover(3).top).isGreaterThan(cover(1).top)
    }

    @Test
    fun `a book in progress is announced with its state`() {
        show(listOf(sampleBook("1", title = "T", author = "A", media = MediaProfile.ReadAlong, progressPercent = 11)))

        compose.onNodeWithTag("book-1").assertIsDisplayed()
        val description = compose.onNodeWithTag("book-1").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertThat(description).contains("T")
        assertThat(description).contains("A")
        assertThat(description).contains("Synchronised reading")
        assertThat(description).contains("11")
    }

    private fun badge() = compose.onNodeWithTag(FormatBadgeTag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun row(id: Long) = compose.onNodeWithTag("book-$id").getUnclippedBoundsInRoot()

    /** The line of a list row with this text; the cover of a book without an image writes the title too. */
    private fun rowText(text: String, sibling: String) =
        compose.onNode(hasText(text) and hasAnySibling(hasText(sibling)), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

    @Test
    fun `in a grid item the format badge is over the cover`() {
        show(listOf(sampleBook("1", media = MediaProfile.ReadAlong)))

        assertThat(badge().left).isGreaterThan(cover(1).left)
        assertThat(badge().right).isLessThan(cover(1).right)
        assertThat(badge().top).isGreaterThan(cover(1).top)
        assertThat(badge().bottom).isLessThan(cover(1).bottom)
    }

    @Test
    fun `in a list row the format badge is at the end of the row, clear of the cover`() {
        show(listOf(sampleBook("1", title = "T", author = "A", media = MediaProfile.ReadAlong)), Presentation.List)

        assertThat(badge().left).isGreaterThan(cover(1).right)
        assertThat(badge().left).isAtLeast(rowText("T", sibling = "A").right)
        assertThat(badge().right).isEqualTo(row(1).right)
        val badgeMiddle = (badge().top + badge().bottom) / 2
        val rowMiddle = (row(1).top + row(1).bottom) / 2
        assertThat(badgeMiddle.value).isWithin(1f).of(rowMiddle.value)
    }

    @Test
    fun `a list row without a badge gives its text the whole width`() {
        val title = "A title far too long to be written on one line of a row of the list of books"
        show(listOf(sampleBook("1", title = title, author = "A")), Presentation.List)

        compose.onNodeWithTag(FormatBadgeTag, useUnmergedTree = true).assertDoesNotExist()
        assertThat(rowText(title, sibling = "A").right).isEqualTo(row(1).right)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp", fontScale = 2.0f)
    fun `at the largest text size the badge keeps its size and the text has the rest of the row`() {
        val title = "A title far too long to be written on one line of a row of the list of books"
        val author = "An author whose name is as long as the title of the book, or longer"
        val series = SeriesRef(SeriesId("9"), "A saga with a name that has no end either, whatever the screen", "4")
        val book = sampleBook("1", title = title, author = author, media = MediaProfile.ReadAlong, series = series)
        show(listOf(book), Presentation.List)

        // Two glyphs of 13 dp, 3 dp apart, 6 dp from each end: text size does not change it.
        assertThat((badge().right - badge().left).value).isWithin(1f).of(41f)
        assertThat(badge().right).isEqualTo(row(1).right)
        // The text goes from the cover to the badge, 12 dp from each.
        val text = rowText(title, sibling = author)
        assertThat(text.left.value).isWithin(1f).of(cover(1).right.value + 12f)
        assertThat(text.right.value).isWithin(1f).of(badge().left.value - 12f)
        assertThat((text.right - text.left).value).isAtLeast(150f)
        val textRightPx = with(compose.density) { text.right.toPx() } + 1f

        // Each line of the row is on one line and whole: cut short with an ellipsis, never clipped.
        // (The tile of a book without a cover image writes the title too, on several lines: not a row line.)
        val seriesLabel = "${series.name} #4"
        val lines = compose.laidOutTexts().filter { it.lineCount == 1 }
        assertThat(lines.map { it.text }).containsExactly(title, author, seriesLabel)
        lines.forEach { line ->
            assertWithMessage("$line is whole").that(line.isWhollyShown).isTrue()
            assertWithMessage("$line ends with an ellipsis").that(line.isEllipsized).isTrue()
            assertWithMessage("right edge of $line").that(line.boundsInRoot.right).isAtMost(textRightPx)
        }
    }
}
