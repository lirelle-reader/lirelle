package app.tfdn.lirelle.feature.reader

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.laidOutTexts
import app.tfdn.lirelle.feature.reader.bridge.TocEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TocSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val chosen = mutableListOf<TocEntry>()

    private fun show(toc: List<TocEntry>, currentId: Int?) {
        compose.setContent {
            LirelleTheme { TocList(toc = toc, currentId = currentId, onChoose = { chosen += it }) }
        }
    }

    private fun left(label: String) =
        compose.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot().left.value

    @Test
    fun `entries are listed, indented by their depth`() {
        show(SampleToc, currentId = null)

        compose.onNodeWithText("One").assertIsDisplayed()
        assertThat(left("Two")).isEqualTo(left("One"))
        assertThat(left("Three")).isGreaterThan(left("Two"))
    }

    @Test
    fun `a very deep entry is not pushed out of the sheet`() {
        val toc = (0..30).map { TocEntry(it, "Level $it", "l$it.xhtml", depth = it) }
        show(toc, currentId = null)

        assertThat(left("Level 3")).isGreaterThan(left("Level 2"))
        assertThat(left("Level 9")).isEqualTo(left("Level 8"))
    }

    @Test
    fun `the current entry is marked`() {
        show(SampleToc, currentId = 1)

        compose.onNodeWithText("Two").assertIsSelected()
        compose.onNodeWithText("One").assertIsNotSelected()
    }

    @Test
    fun `the list opens on the current entry`() {
        val toc = (0..200).map { TocEntry(it, "Chapter $it", "c$it.xhtml", depth = 0) }
        show(toc, currentId = 150)

        compose.onNodeWithText("Chapter 150").assertIsDisplayed()
    }

    @Test
    fun `a tap reports the entry`() {
        show(SampleToc, currentId = 0)

        compose.onNodeWithText("Three").performClick()

        assertThat(chosen).containsExactly(SampleToc[2])
    }

    @Test
    fun `an entry without a link is a plain heading, indented as the others`() {
        val toc = listOf(
            TocEntry(id = 0, label = "Part one", href = "", depth = 0),
            TocEntry(id = 1, label = "Chapter one", href = "one.xhtml", depth = 1),
            TocEntry(id = 2, label = "Section", href = "", depth = 1),
        )
        show(toc, currentId = null)

        listOf("Part one", "Section").forEach { label ->
            val config = compose.onNodeWithText(label).fetchSemanticsNode().config
            assertThat(config.getOrNull(SemanticsActions.OnClick)).isNull()
            assertThat(config.getOrNull(SemanticsProperties.Role)).isNull()
            assertThat(config.getOrNull(SemanticsProperties.Selected)).isNull()
        }
        compose.onNodeWithText("Chapter one").assertHasClickAction()
        assertThat(left("Section")).isEqualTo(left("Chapter one"))
        assertThat(left("Part one")).isLessThan(left("Section"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
    fun `a very long label takes two lines and ends with an ellipsis, its row as tall as a touch target`() {
        val toc = listOf(
            TocEntry(id = 0, label = LongLabel, href = "one.xhtml", depth = 0),
            TocEntry(id = 1, label = "Two", href = "two.xhtml", depth = 0),
        )
        show(toc, currentId = 0)

        val long = compose.laidOutTexts().single { it.text == LongLabel }
        assertThat(long.lineCount).isEqualTo(2)
        assertThat(long.isEllipsized).isTrue()
        listOf(LongLabel, "Two").forEach { label ->
            val row = compose.onNodeWithText(label).assertHasClickAction().getUnclippedBoundsInRoot()
            assertThat(row.height).isAtLeast(48.dp)
        }
        compose.onNodeWithText(LongLabel).assertIsSelected()
    }

    private companion object {
        /** The first paragraph of a section without a title, as a label: far more than two lines of the sheet. */
        val LongLabel = "The harbour master kept a ledger of every tide he had ever seen, and of every boat. ".repeat(6)
    }
}
