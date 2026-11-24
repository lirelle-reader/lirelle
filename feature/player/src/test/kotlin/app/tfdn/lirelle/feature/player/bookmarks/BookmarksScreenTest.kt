package app.tfdn.lirelle.feature.player.bookmarks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import app.tfdn.lirelle.core.audio.bookmarks.AddOutcome
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.feature.player.ChaptersButtonTag
import app.tfdn.lirelle.feature.player.ChaptersColumnTag
import app.tfdn.lirelle.feature.player.ChaptersSheetTag
import app.tfdn.lirelle.feature.player.PlayerActions
import app.tfdn.lirelle.feature.player.PlayerContent
import app.tfdn.lirelle.feature.player.PlayerSamples
import app.tfdn.lirelle.feature.player.RecordingActions
import app.tfdn.lirelle.feature.player.bookmarks.FakeAudioBookmarks.Companion.bookmark
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player's bookmarks: the tile, its snackbars, the tab beside the chapters, the menu and the edit dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BookmarksScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val recorded = RecordingActions()
    private var state by mutableStateOf(PlayerSamples.playing)

    /** The view model's part, as far as the screen sees it: a bookmark deleted leaves the list at once. */
    private val actions = object : PlayerActions by recorded {
        override fun deleteBookmark(bookmark: AudioBookmark) {
            recorded.deleteBookmark(bookmark)
            state = state.copy(bookmarks = state.bookmarks - bookmark)
        }

        override fun undoDelete(bookmark: AudioBookmark) {
            recorded.undoDelete(bookmark)
            state = state.copy(bookmarks = (state.bookmarks + bookmark).sortedBy { it.positionMs })
        }
    }

    private val ferry = bookmark("b1", 70_000, "The ferry", note = "Wind at the bow")
    private val letter = bookmark("b2", 130_000, "The letter")

    /** Whether the player is on screen: set to false, it goes, as when the user leaves it. */
    private var present by mutableStateOf(true)

    private fun show(shown: PlayerUiState = PlayerSamples.playing.copy(bookmarks = listOf(ferry, letter))) {
        state = shown
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                if (present) PlayerContent(state, actions, onMinimise = {}, onOpenBook = {}, onOpenSettings = {})
            }
        }
    }

    private fun addBookmark() = compose.onNodeWithTag(BookmarkButtonTag).performScrollTo().performClick()

    private fun openBookmarksTab() {
        compose.onNodeWithTag(ChaptersButtonTag).performScrollTo().performClick()
        compose.onNode(tab("Bookmarks · ${state.bookmarks.size}")).performClick()
    }

    private fun tab(label: String) = hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())

    private fun hasClickAction() = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)

    private fun customAction(title: String, label: String) {
        val node = row(title).fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { action.action() }
    }

    @Test
    fun `the bookmark tile adds one, and the snackbar's Edit opens the dialog on it`() {
        recorded.addAnswer = AddOutcome.Added(ferry)
        show()

        addBookmark()

        compose.onNodeWithText("Bookmark added").assertIsDisplayed()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Edit the bookmark").assertIsDisplayed()
        compose.onNodeWithTag(BookmarkTitleFieldTag).assert(hasText("The ferry"))
        // The chapter and the time in it, from the bookmark's place.
        compose.onNodeWithText("Middle · 0:10").assertIsDisplayed()
        assertThat(recorded.asked).containsExactly("addBookmark")
    }

    @Test
    fun `a bookmark already at that second opens the bookmarks tab on it`() {
        val many = (0 until 30).map { bookmark("m$it", it * 5_000L) }
        recorded.addAnswer = AddOutcome.Existing(many[25])
        show(PlayerSamples.playing.copy(bookmarks = many))

        addBookmark()

        compose.onNodeWithText("A bookmark is already there").assertIsDisplayed()
        compose.onNodeWithTag(ChaptersSheetTag).assertIsDisplayed()
        compose.onNode(tab("Bookmarks · 30")).assertIsSelected()
        compose.onNodeWithTag(HighlightedBookmarkTag).assertIsDisplayed().assert(hasText("Bookmark m25"))
    }

    @Test
    fun `another bookmark at that second that could not be read is only told`() {
        recorded.addAnswer = AddOutcome.Taken(70_000)
        show()

        addBookmark()

        compose.onNodeWithText("A bookmark is already there").assertIsDisplayed()
        compose.onNodeWithTag(ChaptersSheetTag).assertDoesNotExist()
    }

    @Test
    fun `the chapters sheet has two tabs, the bookmarks counted and read again when their tab opens`() {
        show()
        compose.onNodeWithTag(ChaptersButtonTag).performScrollTo().performClick()

        compose.onNode(tab("Chapters")).assertIsSelected()
        compose.onNode(tab("Bookmarks · 2")).assertIsNotSelected()
        compose.onNodeWithText("3 chapters · 3 min").assertIsDisplayed()
        assertThat(recorded.asked).isEmpty()

        compose.onNode(tab("Bookmarks · 2")).performClick()
        compose.waitForIdle()

        assertThat(recorded.asked).containsExactly("refreshBookmarks")
        row("The ferry").assert(hasText("Middle · 0:10")).assert(hasText("Wind at the bow")).assertIsDisplayed()
        // The place: the chapter that holds it, and the time in it.
        row("The letter").assert(hasText("Closing · 0:30")).assertIsDisplayed()
    }

    @Test
    fun `an empty tab says how to add a bookmark`() {
        show(PlayerSamples.playing)

        openBookmarksTab()

        compose.onNodeWithText("Add a bookmark with the Bookmark button").assertIsDisplayed()
    }

    @Test
    fun `a bookmark tapped is gone to, and the sheet closes`() {
        show()
        openBookmarksTab()

        row("The letter").performClick()

        assertThat(recorded.asked).containsExactly("refreshBookmarks", "goToBookmark b2").inOrder()
        compose.onNodeWithTag(ChaptersSheetTag).assertDoesNotExist()
    }

    @Test
    fun `a long press offers to edit or delete`() {
        show()
        openBookmarksTab()

        row("The letter").performTouchInput { longClick() }
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Edit").performClick()

        compose.onNodeWithText("Edit the bookmark").assertIsDisplayed()
        compose.onNodeWithTag(BookmarkTitleFieldTag).assert(hasText("The letter"))
    }

    @Test
    fun `a screen reader has the same two actions`() {
        show()
        openBookmarksTab()

        customAction("The letter", "Edit")
        compose.onNodeWithText("Edit the bookmark").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        customAction("The letter", "Delete")

        assertThat(recorded.asked).containsExactly("refreshBookmarks", "deleteBookmark b2").inOrder()
    }

    @Test
    fun `a bookmark deleted leaves at once, and undo brings it back without deleting it`() {
        show()
        openBookmarksTab()

        customAction("The letter", "Delete")
        row("The letter").assertDoesNotExist()
        compose.onNode(tab("Bookmarks · 1")).assertExists()
        compose.onNodeWithText("Bookmark deleted").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()

        row("The letter").assertIsDisplayed()
        assertThat(recorded.asked).containsExactly("refreshBookmarks", "deleteBookmark b2", "undoDelete b2").inOrder()
    }

    @Test
    fun `without undo, the deletion is made once the snackbar has gone`() {
        show()
        openBookmarksTab()

        customAction("The letter", "Delete")
        assertThat(recorded.asked).doesNotContain("confirmDelete b2")
        compose.mainClock.advanceTimeBy(SnackbarGoneMs)
        compose.waitForIdle()

        compose.onNodeWithText("Bookmark deleted").assertDoesNotExist()
        assertThat(recorded.asked)
            .containsExactly("refreshBookmarks", "deleteBookmark b2", "confirmDelete b2").inOrder()
    }

    @Test
    fun `a blank title cannot be saved, and the title and the note have their limits`() {
        show()
        openBookmarksTab()
        customAction("The letter", "Edit")
        val title = compose.onNodeWithTag(BookmarkTitleFieldTag)

        title.performTextClearance()
        compose.onNodeWithText("A title is needed").assertIsDisplayed()
        title.assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "A title is needed"))
        compose.onNodeWithText("Save").assertIsNotEnabled()

        title.performTextInput("t".repeat(TitleMax + 1))
        compose.onNodeWithTag(BookmarkNoteFieldTag).performTextReplacement("n".repeat(NoteMax + 1))
        compose.onNodeWithText("Save").assertIsEnabled().performClick()

        assertThat(recorded.asked.last()).isEqualTo("editBookmark b2 ${"t".repeat(TitleMax)} ${"n".repeat(NoteMax)}")
        compose.onNodeWithText("Edit the bookmark").assertDoesNotExist()
    }

    @Test
    fun `cancel leaves the bookmark as it was`() {
        show()
        openBookmarksTab()
        customAction("The ferry", "Edit")

        compose.onNodeWithTag(BookmarkTitleFieldTag).performTextReplacement("The crossing")
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText("Edit the bookmark").assertDoesNotExist()
        assertThat(recorded.asked.none { it.startsWith("editBookmark") }).isTrue()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a wide window has the tabs in its chapters column, where a bookmark tapped is gone to`() {
        show()

        compose.onNodeWithTag(ChaptersColumnTag).assertIsDisplayed()
        compose.onNode(tab("Bookmarks · 2")).performClick()
        row("The letter").performClick()

        assertThat(recorded.asked).containsExactly("refreshBookmarks", "goToBookmark b2").inOrder()
        row("The letter").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a wide window, a bookmark already there is shown in the column, without a sheet`() {
        recorded.addAnswer = AddOutcome.Existing(letter)
        show()

        addBookmark()

        compose.onNode(tab("Bookmarks · 2")).assertIsSelected()
        compose.onNodeWithTag(HighlightedBookmarkTag).assert(hasText("The letter")).assertIsDisplayed()
        compose.onAllNodesWithTag(ChaptersSheetTag).fetchSemanticsNodes().let { assertThat(it).isEmpty() }
    }

    @Test
    fun `the fields are named by their labels, above them, and carry no description over what is typed`() {
        show()
        openBookmarksTab()
        customAction("The letter", "Edit")

        compose.onNodeWithTag(BookmarkTitleFieldTag).assert(hasText("Title")).assert(hasText("The letter"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        compose.onNodeWithTag(BookmarkNoteFieldTag).assert(hasText("Note (optional)"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun `at the limit, what is typed in the middle is kept short, and the text after it stays`() {
        val long = "a".repeat(TitleMax - 1)
        show(PlayerSamples.playing.copy(bookmarks = listOf(bookmark("b1", 90_000, long))))
        openBookmarksTab()
        customAction(long, "Edit")
        val title = compose.onNodeWithTag(BookmarkTitleFieldTag)

        title.performTextInputSelection(TextRange(0))
        title.performTextInput("bc")
        // A character outside the first plane is two halves: never one alone.
        title.performTextInputSelection(TextRange(0))
        title.performTextInput("\uD83D\uDE00")
        compose.onNodeWithText("Save").performClick()

        assertThat(recorded.asked.last()).isEqualTo("editBookmark b1 b$long null")
    }

    @Test
    fun `a bookmark that could not be added is told`() {
        recorded.addAnswer = null
        show()

        addBookmark()

        compose.onNodeWithText("The bookmark could not be added").assertIsDisplayed()
    }

    @Test
    fun `the chapters sheet opens on the chapters again once closed from the bookmarks`() {
        recorded.addAnswer = AddOutcome.Existing(letter)
        show()
        addBookmark()
        compose.onNode(tab("Bookmarks · 2")).assertIsSelected()

        row("The letter").performClick()
        compose.onNodeWithTag(ChaptersSheetTag).assertDoesNotExist()
        // The snackbar "already there" goes first: it lies over the tiles.
        compose.mainClock.advanceTimeBy(SnackbarGoneMs)
        compose.onNodeWithTag(ChaptersButtonTag).performScrollTo().performClick()

        compose.onNode(tab("Chapters")).assertIsSelected()
        compose.onNodeWithText("3 chapters · 3 min").assertIsDisplayed()
    }

    @Test
    fun `a row says what a tap does`() {
        show()
        openBookmarksTab()

        assertThat(row("The letter").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            .isEqualTo("Go to the bookmark")
    }

    @Test
    fun `a bookmark deleted as the player goes is deleted once`() {
        show()
        openBookmarksTab()

        customAction("The letter", "Delete")
        present = false
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SnackbarGoneMs)
        compose.waitForIdle()

        assertThat(recorded.asked.filter { it.startsWith("confirmDelete") }).containsExactly("confirmDelete b2")
    }

    @Test
    fun `a bookmark brought back by undo is not deleted when the player goes`() {
        show()
        openBookmarksTab()

        customAction("The letter", "Delete")
        compose.onNodeWithText("Undo").performClick()
        present = false
        compose.waitForIdle()

        assertThat(recorded.asked.none { it.startsWith("confirmDelete") }).isTrue()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the same bookmark found again is brought into view again`() {
        val many = (0 until 30).map { bookmark("m$it", it * 5_000L) }
        recorded.addAnswer = AddOutcome.Existing(many[25])
        show(PlayerSamples.playing.copy(bookmarks = many))

        addBookmark()
        compose.onNodeWithTag(HighlightedBookmarkTag).assertIsDisplayed()
        compose.onNodeWithTag(BookmarkListTag).performScrollToIndex(0)
        compose.onNodeWithTag(HighlightedBookmarkTag).assertDoesNotExist()
        addBookmark()

        compose.onNodeWithTag(HighlightedBookmarkTag).assertIsDisplayed()
    }

    private companion object {
        /** Longer than a snackbar with an action stays. */
        const val SnackbarGoneMs = 12_000L
    }
}
