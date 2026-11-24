package app.tfdn.lirelle.feature.book

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.RelatedBook
import app.tfdn.lirelle.core.library.offline.DownloadMark
import app.tfdn.lirelle.core.ui.LocalDownloadMarks
import app.tfdn.lirelle.core.ui.ReadMarkTag
import app.tfdn.lirelle.core.ui.SeriesIndexTag
import app.tfdn.lirelle.core.ui.downloadMarkTag
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The marks on the covers of the related books of the book page. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class RelatedBlockTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<BookId>()
    private var seriesOpened = 0

    private fun related(id: String, index: String? = null, state: ReadState? = null) =
        RelatedBook(BookId(id), "Book $id", listOf("An Author"), null, CoverShape.Portrait, index, state)

    /** @param marks the marks of the books kept on the device, as the shell gives them; null for none given. */
    private fun show(marks: Map<BookId, DownloadMark>? = null) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalDownloadMarks provides marks?.let { MutableStateFlow(it) }) { Blocks() }
            }
        }
    }

    @Composable
    private fun Blocks() {
        Column {
            RelatedBlock(
                title = R.string.section_in_series,
                tag = "book-in-series",
                books = listOf(related("1", "3", ReadState.Read), related("2", "4"), related("3")),
                onOpenBook = { opened += it },
                showSeriesIndex = true,
                onOpenAll = { seriesOpened++ },
            )
            RelatedBlock(
                title = R.string.section_similar,
                tag = "book-similar",
                books = listOf(related("11", "7", ReadState.Read), related("12", "8", ReadState.Reading)),
                onOpenBook = { opened += it },
            )
        }
    }

    private fun marks(id: String, tag: String) =
        compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("related-$id")), useUnmergedTree = true)

    private fun spoken(id: String): String? = compose.onNodeWithTag("related-$id").fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    @Test
    fun `the books of the series carry their volume number, and a read one its check`() {
        show()

        marks("1", SeriesIndexTag).assertCountEquals(1)
        marks("1", ReadMarkTag).assertCountEquals(1)
        marks("2", SeriesIndexTag).assertCountEquals(1)
        marks("2", ReadMarkTag).assertCountEquals(0)
        marks("3", SeriesIndexTag).assertCountEquals(0)
        marks("3", ReadMarkTag).assertCountEquals(0)
        compose.onNode(hasText("3") and hasAnyAncestor(hasTestTag("related-1")), useUnmergedTree = true)
            .assertIsDisplayed()
        // The check at the bottom left of the cover, the number at its bottom right.
        val check = marks("1", ReadMarkTag)[0].getUnclippedBoundsInRoot()
        val pill = marks("1", SeriesIndexTag)[0].getUnclippedBoundsInRoot()
        val item = compose.onNodeWithTag("related-1").getUnclippedBoundsInRoot()
        assertThat((check.left - item.left).value).isWithin(3f).of(5f)
        assertThat((item.right - pill.right).value).isWithin(3f).of(5f)
        assertThat(pill.left).isGreaterThan(check.right)
    }

    @Test
    fun `other rows show the check of a read book and no volume number`() {
        show()

        marks("11", ReadMarkTag).assertCountEquals(1)
        marks("11", SeriesIndexTag).assertCountEquals(0)
        marks("12", ReadMarkTag).assertCountEquals(0)
        marks("12", SeriesIndexTag).assertCountEquals(0)
    }

    @Test
    fun `the marks are said in words with the title`() {
        show()

        assertThat(spoken("1")).isEqualTo("Book 1, Volume 3, Read")
        assertThat(spoken("2")).isEqualTo("Book 2, Volume 4")
        assertThat(spoken("11")).isEqualTo("Book 11, Read")
        // Nothing to add: the title is the only thing said, as before.
        assertThat(spoken("3")).isNull()
        assertThat(spoken("12")).isNull()
    }

    @Test
    fun `a related book kept on the device carries its mark at the top start of its cover`() {
        show(
            mapOf(
                BookId("1") to DownloadMark.Kept,
                BookId("2") to DownloadMark.InProgress,
                BookId("11") to DownloadMark.Update,
            ),
        )

        mapOf("1" to DownloadMark.Kept, "2" to DownloadMark.InProgress, "11" to DownloadMark.Update, "3" to null)
            .forEach { (id, expected) ->
                DownloadMark.entries.forEach {
                    marks(id, downloadMarkTag(it)).assertCountEquals(if (it == expected) 1 else 0)
                }
            }
        val disc = marks("1", downloadMarkTag(DownloadMark.Kept))[0].getUnclippedBoundsInRoot()
        val item = compose.onNodeWithTag("related-1").getUnclippedBoundsInRoot()
        assertThat((disc.left - item.left).value).isWithin(0.5f).of(5f)
        assertThat((disc.top - item.top).value).isWithin(0.5f).of(5f)
        assertThat((disc.right - disc.left).value).isWithin(0.5f).of(24f)
        assertThat((disc.bottom - disc.top).value).isWithin(0.5f).of(24f)
    }

    @Test
    fun `the mark of a related book is said after its other marks`() {
        show(
            mapOf(
                BookId("1") to DownloadMark.Kept,
                BookId("3") to DownloadMark.InProgress,
                BookId("12") to DownloadMark.Update,
            ),
        )

        assertThat(spoken("1")).isEqualTo("Book 1, Volume 3, Read, downloaded")
        assertThat(spoken("3")).isEqualTo("Book 3, downloading")
        assertThat(spoken("12")).isEqualTo("Book 12, update available")
        assertThat(spoken("2")).isEqualTo("Book 2, Volume 4")
    }

    @Test
    fun `no related book is marked where no marks are given`() {
        show()

        listOf("1", "2", "3", "11", "12").forEach { id ->
            DownloadMark.entries.forEach { marks(id, downloadMarkTag(it)).assertCountEquals(0) }
        }
    }

    @Test
    fun `a marked book still opens`() {
        show()

        compose.onNodeWithText("Book 1").assertIsDisplayed()
        compose.onNodeWithTag("related-1").performClick()

        assertThat(opened).containsExactly(BookId("1"))
    }

    @Test
    fun `the heading of a row that leads somewhere is a line that takes the tap`() {
        show()

        val line = compose.onNodeWithTag("book-in-series-open")
        line.assertIsDisplayed().assertHasClickAction()
        val config = line.fetchSemanticsNode().config
        assertThat(config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
        assertThat(config.contains(SemanticsProperties.Heading)).isTrue()
        assertThat(config.getOrNull(SemanticsActions.OnClick)?.label).isEqualTo("Open the series")
        assertThat(config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }).isEqualTo("In the series")
        // As wide as its section, and high enough for a finger.
        val bounds = line.getUnclippedBoundsInRoot()
        val section = compose.onNodeWithTag("book-in-series").getUnclippedBoundsInRoot()
        assertThat(bounds.left).isEqualTo(section.left)
        assertThat(bounds.right).isEqualTo(section.right)
        assertThat((bounds.bottom - bounds.top).value).isAtLeast(48f)

        line.performClick()

        assertThat(seriesOpened).isEqualTo(1)
        assertThat(opened).isEmpty()
    }

    @Test
    fun `the heading of a row that leads nowhere stays a plain heading`() {
        show()

        compose.onAllNodesWithTag("book-similar-open").assertCountEquals(0)
        val heading = compose.onNodeWithText("Similar books")
        heading.assertHasNoClickAction()
        assertThat(heading.fetchSemanticsNode().config.contains(SemanticsProperties.Heading)).isTrue()
    }
}
