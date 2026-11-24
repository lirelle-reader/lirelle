package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.sampleBook
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The check mark of a read book and the volume number of a book of a series, over the cover. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics, for the size of the volume number.
class CoverMarksTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val saga = SeriesRef(SeriesId("9"), "A Saga", "3")

    private fun show(
        vararg books: BookSummary,
        presentation: Presentation = Presentation.Grid,
        showSeriesIndex: Boolean = false,
    ) {
        compose.setContent {
            LirelleTheme {
                BookCollection(
                    items = bookItemsOf(books.toList()),
                    presentation = presentation,
                    onOpenBook = {},
                    showSeriesIndex = showSeriesIndex,
                )
            }
        }
    }

    private fun within(id: Long, tag: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag("book-$id"))

    private fun bounds(id: Long, tag: String) =
        compose.onNode(within(id, tag), useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun assertCount(tag: String, count: Int) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(count)

    private fun cover(id: Long) = compose.onNodeWithTag("cover-$id", useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun spoken(id: Long): String = compose.onNodeWithTag("book-$id").fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    /** A mark of about 22 dp, a few dp inside the cover. */
    private fun assertInCorner(mark: DpRect, cover: DpRect, atStart: Boolean) {
        val side = if (atStart) mark.left - cover.left else cover.right - mark.right
        assertThat(side.value).isWithin(3f).of(5f)
        assertThat((cover.bottom - mark.bottom).value).isWithin(3f).of(5f)
        assertThat((mark.bottom - mark.top).value).isWithin(1f).of(22f)
        assertThat((mark.right - mark.left).value).isAtLeast(21f)
        assertThat(mark.top).isGreaterThan(cover.top)
    }

    @Test
    fun `a read book has a check at the bottom left of its cover, in a grid`() {
        show(sampleBook("1", readState = ReadState.Read))

        val mark = bounds(1, ReadMarkTag)
        assertInCorner(mark, cover(1), atStart = true)
        assertThat((mark.right - mark.left).value).isWithin(1f).of(22f)
    }

    @Test
    fun `a read book has a check at the bottom left of its cover, in a list`() {
        show(sampleBook("1", readState = ReadState.Read), presentation = Presentation.List)

        assertInCorner(bounds(1, ReadMarkTag), cover(1), atStart = true)
        assertThat(bounds(1, ReadMarkTag).right).isLessThan(cover(1).right)
    }

    @Test
    fun `a book that is not read has no check`() {
        show(
            sampleBook("1"),
            sampleBook("2", readState = ReadState.Unread),
            sampleBook("3", readState = ReadState.Abandoned),
        )

        assertCount(ReadMarkTag, 0)
        assertCount(BookProgressTag, 0)
        assertThat(spoken(2)).isEqualTo("Book 2, Author 2")
    }

    @Test
    fun `a book in progress has its bar and no check`() {
        show(sampleBook("1", readState = ReadState.Reading, progressPercent = 40))

        assertCount(BookProgressTag, 1)
        assertCount(ReadMarkTag, 0)
        assertThat(spoken(1)).doesNotContain(context.getString(R.string.book_read_description))
    }

    @Test
    fun `a read book has the check and no bar, whatever its progress`() {
        show(
            sampleBook("1", readState = ReadState.Read, progressPercent = 100),
            sampleBook("2", readState = ReadState.Read, progressPercent = 60),
        )

        assertCount(ReadMarkTag, 2)
        assertCount(BookProgressTag, 0)
    }

    @Test
    fun `a read book is announced as read, in words`() {
        show(sampleBook("1", title = "T", author = "A", readState = ReadState.Read, progressPercent = 60))

        assertThat(spoken(1)).isEqualTo("T, A, Read")
    }

    @Test
    fun `a read book is announced as read in a list too`() {
        show(sampleBook("1", title = "T", author = "A", readState = ReadState.Read), presentation = Presentation.List)

        assertThat(spoken(1)).isEqualTo("T, A, Read")
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the read state is said in French`() {
        show(sampleBook("1", title = "T", author = "A", readState = ReadState.Read))

        assertThat(spoken(1)).isEqualTo("T, A, Lu")
    }

    @Test
    fun `where asked, a cover carries its volume number at its bottom right`() {
        show(
            sampleBook("1", series = saga),
            sampleBook("2", series = saga.copy(index = "2.5")),
            showSeriesIndex = true,
        )

        assertInCorner(bounds(1, SeriesIndexTag), cover(1), atStart = false)
        assertInCorner(bounds(2, SeriesIndexTag), cover(2), atStart = false)
        val numbers = compose.assertAllTextFits().filter { it.text == "3" || it.text == "2.5" }
        assertThat(numbers.map { it.text }).containsExactly("3", "2.5")
        numbers.forEach {
            assertWithMessage("lines of $it").that(it.lineCount).isEqualTo(1)
            assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse()
        }
        assertThat(spoken(1)).isEqualTo("Book 1, Author 1, Volume 3")
    }

    @Test
    fun `volume numbers are not shown unless asked`() {
        show(sampleBook("1", series = saga), sampleBook("2", series = saga, readState = ReadState.Read))

        assertCount(SeriesIndexTag, 0)
        assertThat(spoken(1)).isEqualTo("Book 1, Author 1")
    }

    @Test
    fun `a book without a volume number has no pill`() {
        show(
            sampleBook("1"),
            sampleBook("2", series = saga.copy(index = null)),
            sampleBook("3", series = saga.copy(index = " ")),
            showSeriesIndex = true,
        )

        assertCount(SeriesIndexTag, 0)
    }

    @Test
    fun `a list row names the volume on its series line and carries no pill`() {
        show(sampleBook("1", series = saga), presentation = Presentation.List, showSeriesIndex = true)

        assertCount(SeriesIndexTag, 0)
        assertThat(spoken(1)).isEqualTo("Book 1, Author 1, A Saga #3")
    }

    @Test
    fun `the check and the volume number sit side by side`() {
        show(sampleBook("1", series = saga, readState = ReadState.Read), showSeriesIndex = true)

        assertInCorner(bounds(1, ReadMarkTag), cover(1), atStart = true)
        assertInCorner(bounds(1, SeriesIndexTag), cover(1), atStart = false)
        assertThat(bounds(1, SeriesIndexTag).left).isGreaterThan(bounds(1, ReadMarkTag).right)
        assertThat(spoken(1)).isEqualTo("Book 1, Author 1, Volume 3, Read")
    }

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp", fontScale = 2.0f)
    fun `on a narrow phone at the largest text size a read book keeps its check and its whole volume number`() {
        show(
            sampleBook("1", title = "T", author = "A", series = saga.copy(index = "2.5"), readState = ReadState.Read),
            // A number too long for what the check leaves of the cover is cut short, never drawn over it.
            sampleBook(
                "2",
                title = "U",
                author = "B",
                series = saga.copy(index = "1000.5"),
                readState = ReadState.Read,
            ),
            showSeriesIndex = true,
        )

        val texts = compose.assertAllTextFits()
        val number = texts.single { it.text == "2.5" }
        assertWithMessage("lines of $number").that(number.lineCount).isEqualTo(1)
        assertWithMessage("$number is cut short").that(number.isEllipsized).isFalse()
        assertWithMessage("$number is whole").that(number.isWhollyShown).isTrue()
        assertThat(texts.single { it.text == "1000.5" }.lineCount).isEqualTo(1)
        listOf(1L, 2L).forEach { id ->
            val check = bounds(id, ReadMarkTag)
            val pill = bounds(id, SeriesIndexTag)
            assertThat((check.right - check.left).value).isWithin(1f).of(22f)
            assertThat(check.left).isGreaterThan(cover(id).left)
            assertThat(pill.left).isGreaterThan(check.right)
            assertThat(pill.right).isLessThan(cover(id).right)
            assertThat(pill.top).isGreaterThan(cover(id).top)
            assertThat(pill.bottom).isLessThan(cover(id).bottom)
        }
        assertThat(spoken(1)).isEqualTo("T, A, Tome 2.5, Lu")
    }
}
