package app.tfdn.lirelle.feature.book

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookEdition
import app.tfdn.lirelle.core.library.model.EditionMedia
import app.tfdn.lirelle.core.library.model.EditionProgress
import app.tfdn.lirelle.core.testing.sampleBookDetail
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The editions of the work on the page of one of them. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class EditionsBlockTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<BookId>()

    private val english = BookEdition(
        BookId("1046"), isOpen = false, label = "Version en anglais", title = "A Game of Thrones",
        coverUrl = null, media = EditionMedia.TextAndAudio, progress = EditionProgress.Finished,
    )
    private val first = BookEdition(
        BookId("1052"), isOpen = false, label = "Version en français, tome 1/2", title = "Le trône de fer",
        coverUrl = null, media = EditionMedia.TextAndAudio, progress = EditionProgress.InProgress(42),
    )
    private val open = BookEdition(
        BookId("1051"), isOpen = true, label = "Version en français, tome 2/2", title = "Le donjon rouge",
        coverUrl = null, media = EditionMedia.Text, progress = EditionProgress.NotStarted,
    )

    private fun show(editions: List<BookEdition> = listOf(english, first, open), columns: Int = 1) {
        val book = sampleBookDetail().copy(title = "Le donjon rouge", editions = editions)
        compose.setContent {
            LirelleTheme { EditionsBlock(book, onOpenBook = { opened += it }, columns = columns) }
        }
    }

    private fun line(edition: BookEdition) = compose.onNodeWithTag(editionTag(edition.bookId))

    private fun bounds(edition: BookEdition): DpRect = line(edition).getUnclippedBoundsInRoot()

    /** How many times the line of an edition shows a text: the texts lie on the line, they are not held by it. */
    private fun shown(edition: BookEdition, text: String): Int {
        val within = bounds(edition)
        val texts = compose.onAllNodesWithText(text, useUnmergedTree = true)
        return texts.fetchSemanticsNodes().indices.count { index ->
            val at = texts[index].getUnclippedBoundsInRoot()
            at.top >= within.top && at.bottom <= within.bottom && at.left >= within.left && at.right <= within.right
        }
    }

    private fun spoken(edition: BookEdition): String? = line(edition).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    @Test
    fun `one line per edition, in the order given`() {
        show()

        listOf(english, first, open).forEach { line(it).assertExists() }
        assertThat(bounds(first).top).isAtLeast(bounds(english).bottom)
        assertThat(bounds(open).top).isAtLeast(bounds(first).bottom)
        // As wide as the block, and high enough for a finger.
        val block = compose.onNodeWithTag(EditionsTag).getUnclippedBoundsInRoot()
        listOf(english, first, open).forEach {
            assertThat(bounds(it).left).isEqualTo(block.left)
            assertThat(bounds(it).right).isEqualTo(block.right)
            assertThat((bounds(it).bottom - bounds(it).top).value).isAtLeast(72f)
        }
    }

    @Test
    fun `a line says its label, its title when it differs, its media and its progress`() {
        show()

        assertThat(shown(english, "Version en anglais")).isEqualTo(1)
        assertThat(shown(english, "A Game of Thrones")).isEqualTo(1)
        assertThat(shown(english, "Book and audio · finished")).isEqualTo(1)
        assertThat(shown(first, "Version en français, tome 1/2")).isEqualTo(1)
        assertThat(shown(first, "Le trône de fer")).isEqualTo(1)
        assertThat(shown(first, "Book and audio · 42% read")).isEqualTo(1)
        assertThat(shown(english, "This edition")).isEqualTo(0)
    }

    @Test
    fun `the open edition is marked, has no title line of its own book, and takes no tap`() {
        show()

        assertThat(shown(open, "Version en français, tome 2/2")).isEqualTo(1)
        assertThat(shown(open, "Book · not started")).isEqualTo(1)
        assertThat(shown(open, "This edition")).isEqualTo(1)
        // The title of the open book is at the top of its page: no line says it again.
        compose.onAllNodesWithText("Le donjon rouge", useUnmergedTree = true).assertCountEquals(0)
        line(open).assertHasNoClickAction()
        assertThat(line(open).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)).isNull()

        line(open).performClick()

        assertThat(opened).isEmpty()
    }

    @Test
    fun `a tap on another edition opens its book`() {
        show()

        line(english).assertHasClickAction()
        val config = line(english).fetchSemanticsNode().config
        assertThat(config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
        assertThat(config.getOrNull(SemanticsActions.OnClick)?.label).isEqualTo("Open this edition")

        line(english).performClick()

        assertThat(opened).containsExactly(BookId("1046"))
    }

    @Test
    fun `an edition its server does not name shows its title first, and no progress`() {
        val unnamed = english.copy(label = null, media = EditionMedia.Text, progress = EditionProgress.Unknown)
        val here = open.copy(label = null, progress = EditionProgress.Unknown)
        show(listOf(unnamed, here))

        assertThat(shown(unnamed, "A Game of Thrones")).isEqualTo(1)
        assertThat(shown(unnamed, "Book")).isEqualTo(1)
        // Its title names the open edition too, once.
        assertThat(shown(here, "Le donjon rouge")).isEqualTo(1)
        assertThat(shown(here, "Book")).isEqualTo(1)
        compose.onAllNodesWithText(" · ", substring = true, useUnmergedTree = true).assertCountEquals(0)
        assertThat(spoken(unnamed)).isEqualTo("A Game of Thrones, Book")
    }

    @Test
    fun `nothing is shown for a book that has no edition`() {
        show(emptyList())

        compose.onAllNodesWithTag(EditionsTag).assertCountEquals(0)
    }

    @Test
    fun `nothing is shown for a book that is its only edition`() {
        show(listOf(open))

        compose.onAllNodesWithTag(EditionsTag).assertCountEquals(0)
        compose.onAllNodesWithTag(editionTag(open.bookId)).assertCountEquals(0)
    }

    @Test
    fun `two columns put two editions side by side`() {
        show(columns = 2)

        assertThat(bounds(first).top).isEqualTo(bounds(english).top)
        assertThat(bounds(first).left).isGreaterThan(bounds(english).right)
        assertThat(bounds(open).top).isAtLeast(bounds(english).bottom)
        // The last one, alone on its row, is as wide as the others and under the first.
        assertThat(bounds(open).left).isEqualTo(bounds(english).left)
        assertThat(bounds(open).right).isEqualTo(bounds(english).right)
    }

    @Test
    fun `a line is announced as one element`() {
        show()

        assertThat(spoken(english)).isEqualTo("Version en anglais, A Game of Thrones, Book and audio · finished")
        assertThat(spoken(first)).isEqualTo("Version en français, tome 1/2, Le trône de fer, Book and audio · 42% read")
        assertThat(spoken(open)).isEqualTo("Version en français, tome 2/2, Book · not started, This edition")
        // Nothing of the block is read a second time: its heading, and one element per line.
        val read = compose.onAllNodesWithText("", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .filterNot { SemanticsProperties.HideFromAccessibility in it.config }
        assertThat(read.map { it.config[SemanticsProperties.Text].joinToString { text -> text.text } })
            .containsExactly("Editions")
        // The bar of an edition being read says nothing: its line says the percentage.
        val bars = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        )
        bars.assertCountEquals(0)
    }
}
