package app.tfdn.lirelle.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A book whose chapters have no titles of their own is in parts: the player says "Part", "Parts" where it says
 * "Chapter", "Chapters" of another book.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PlayerPartsTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(PlayerSamples.playing)

    /** The sample book, its three chapters named as parts. */
    private val inParts = PlayerSamples.playing.copy(
        inParts = true,
        chapterTitle = "Part 2",
        chapters = PlayerSamples.playing.chapters.mapIndexed { index, row -> row.copy(title = "Part ${index + 1}") },
    )

    private fun show(shown: PlayerUiState) {
        state = shown
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                PlayerContent(state, RecordingActions(), onMinimise = {}, onOpenBook = {}, onOpenSettings = {})
            }
        }
    }

    private fun within(tag: String, text: String) = hasText(text) and hasAnyAncestor(hasTestTag(tag))

    private fun inTile(text: String) = compose.onNode(within(ChaptersButtonTag, text), useUnmergedTree = true)

    private fun inSheet(text: String) = compose.onNode(within(ChaptersSheetTag, text))

    private fun inColumn(text: String) = compose.onNode(within(ChaptersColumnTag, text))

    private fun openSheet() = compose.onNodeWithTag(ChaptersButtonTag).performScrollTo().performClick()

    @Test
    fun `the book's line counts the parts of a book in parts`() {
        show(inParts)
        compose.onNodeWithText("Part 2 of 3 · 43% · 2 min left").assertIsDisplayed()

        state = inParts.copy(totalMs = null, bookPercent = null, bookTimeLeftMs = null)
        compose.onNodeWithText("Part 2 of 3").assertIsDisplayed()
    }

    @Test
    fun `the book's line counts the chapters of a book in chapters`() {
        show(PlayerSamples.playing)

        compose.onNodeWithText("Chapter 2 of 3 · 43% · 2 min left").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the book's line, the tile and the sheet of a book in parts, in French`() {
        show(inParts.copy(totalMs = null, bookPercent = null, bookTimeLeftMs = null))

        compose.onNodeWithText("Partie 2 sur 3").assertIsDisplayed()
        inTile("Parties").assertExists()
        openSheet()
        compose.onAllNodes(within(ChaptersSheetTag, "Parties")).assertCountEquals(2)
        inSheet("3 parties").assertIsDisplayed()
    }

    @Test
    fun `the tile of a book in parts is called Parts, of another Chapters`() {
        show(inParts)
        inTile("Parts").assertExists()
        inTile("Chapters").assertDoesNotExist()

        state = PlayerSamples.playing
        inTile("Chapters").assertExists()
        inTile("Parts").assertDoesNotExist()
    }

    @Test
    fun `the sheet of a book in parts is headed Parts and counts them, beside the bookmarks`() {
        show(inParts)
        openSheet()

        // The tab beside the bookmarks, and the heading.
        compose.onAllNodes(within(ChaptersSheetTag, "Parts")).assertCountEquals(2)
        inSheet("3 parts · 3 min").assertIsDisplayed()
        inSheet("Chapters").assertDoesNotExist()
        inSheet("3 chapters · 3 min").assertDoesNotExist()
    }

    @Test
    fun `one part is counted in the singular`() {
        show(inParts.copy(chapters = inParts.chapters.take(1), chapterCount = 1, chapterIndex = 0))
        openSheet()

        inSheet("1 part · 3 min").assertIsDisplayed()
    }

    @Test
    fun `the sheet of a book in chapters is headed Chapters and counts them`() {
        show(PlayerSamples.playing)
        openSheet()

        compose.onAllNodes(within(ChaptersSheetTag, "Chapters")).assertCountEquals(2)
        inSheet("3 chapters · 3 min").assertIsDisplayed()
        inSheet("Parts").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the column of a wide window is headed Parts and counts them for a book in parts`() {
        show(inParts)

        compose.onAllNodes(within(ChaptersColumnTag, "Parts")).assertCountEquals(2)
        inColumn("3 parts · 3 min").assertIsDisplayed()
        inColumn("Chapters").assertDoesNotExist()

        state = PlayerSamples.playing
        compose.onAllNodes(within(ChaptersColumnTag, "Chapters")).assertCountEquals(2)
        inColumn("3 chapters · 3 min").assertIsDisplayed()
        inColumn("Parts").assertDoesNotExist()
    }
}
