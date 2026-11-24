package app.tfdn.lirelle.feature.reader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.MarkOrigin
import app.tfdn.lirelle.core.library.model.PositionStatus
import app.tfdn.lirelle.core.reader.marks.BookMarks
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.testing.sampleBookmark
import app.tfdn.lirelle.core.testing.sampleHighlight
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.tfdn.lirelle.core.ui.R as UiR

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ReaderPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = RecordingActions()

    private val labels = listOf(null, "Chapter one", "Chapter two", "Chapter three")

    private val base = ReaderUiState(
        title = "A book",
        toc = SampleToc,
        location = location(),
        sectionLabels = labels,
        openSheet = ReaderSheet.Contents,
    )

    private var state by mutableStateOf(base)

    private fun text(id: Int) = context.getString(id)

    private val today get() = text(UiR.string.date_today)
    private val yesterday get() = text(UiR.string.date_yesterday)
    private val fromEReader get() = text(R.string.reader_mark_from_ereader)
    private val approximate get() = text(R.string.reader_mark_approximate)
    private val notFound get() = text(R.string.reader_mark_not_found)

    private fun show(shown: ReaderUiState) {
        state = shown
        compose.setContent { LirelleTheme { ReaderPanel(state, actions, Now) } }
    }

    /** The bounds of the tabs ignoring the row's clipping, left to right, in dp. */
    private fun wholeTabs() = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        .fetchSemanticsNodes()
        .map { node ->
            with(compose.density) {
                val left = node.positionInRoot.x.toDp().value
                left to left + node.size.width.toDp().value
            }
        }
        .sortedBy { it.first }

    private fun top(label: String) =
        compose.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot().top.value

    // Tabs

    @Test
    fun `three tabs, the one of the state selected`() {
        show(base.copy(panelTab = PanelTab.Bookmarks))

        compose.onNodeWithText(text(R.string.reader_tab_bookmarks)).assertIsSelected()
        compose.onNodeWithText(text(R.string.reader_tab_contents)).assertIsNotSelected()
        compose.onNodeWithText(text(R.string.reader_tab_notes)).assertIsNotSelected()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `on a phone the three tabs share the width`() {
        show(base)

        val tabs = wholeTabs()
        assertThat(tabs).hasSize(3)
        val widths = tabs.map { it.second - it.first }
        widths.forEach { assertThat(it).isWithin(1f).of(widths[0]) }
        assertThat(tabs.first().first).isWithin(1f).of(0f)
        assertThat(tabs.last().second).isWithin(1f).of(411f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
    @Config(qualifiers = "fr-w320dp-h640dp", fontScale = 2f)
    fun `with large text on a narrow screen the tabs scroll and leave no gap at the end`() {
        show(base)

        val tabs = wholeTabs()
        assertThat(tabs).hasSize(3)
        assertThat(tabs.first().first).isWithin(1f).of(0f)
        // Each tab starts where the one before ends, and the last reaches at least the right edge.
        tabs.zipWithNext().forEach { (before, after) -> assertThat(after.first).isWithin(1f).of(before.second) }
        assertThat(tabs.last().second).isGreaterThan(320f)
    }

    @Test
    fun `the panel leaves a strip of the page above it, and keeps its height from one tab to another`() {
        show(base.copy(panelTab = PanelTab.Contents))
        val contents = compose.onNodeWithTag(ReaderPanelTag).getUnclippedBoundsInRoot()

        state = base.copy(panelTab = PanelTab.Notes)
        val notes = compose.onNodeWithTag(ReaderPanelTag).getUnclippedBoundsInRoot()

        assertThat(contents.top.value).isAtLeast(891f * 0.05f)
        assertThat(notes.top.value).isWithin(1f).of(contents.top.value)
        assertThat(notes.bottom.value).isWithin(1f).of(contents.bottom.value)
    }

    @Test
    fun `a tab is asked for by a tap`() {
        show(base)

        compose.onNodeWithText(text(R.string.reader_tab_notes)).performClick()
        compose.onNodeWithText(text(R.string.reader_tab_bookmarks)).performClick()

        assertThat(actions.calls).containsExactly("showPanelTab(Notes)", "showPanelTab(Bookmarks)").inOrder()
    }

    @Test
    fun `the contents tab lists the entries, and a tap goes there`() {
        show(base.copy(panelTab = PanelTab.Contents))

        compose.onNodeWithText("Two").assertIsSelected()
        compose.onNodeWithText("Three").performClick()

        assertThat(actions.calls).containsExactly("goToToc(2)")
    }

    // Bookmarks

    @Test
    fun `bookmarks are in reading order, whatever order they are held in`() {
        val marks = BookMarks(
            bookmarks = listOf(
                sampleBookmark("b3", "epubcfi(/6/8!/4/2/1:0)", title = "Third"),
                sampleBookmark("b1", "epubcfi(/6/4!/4/2/1:0)", title = "First"),
                sampleBookmark("b2", "epubcfi(/6/4!/4/10/1:0)", title = "Second"),
            ),
        )
        show(base.copy(panelTab = PanelTab.Bookmarks, marks = marks))

        assertThat(top("First")).isLessThan(top("Second"))
        assertThat(top("Second")).isLessThan(top("Third"))
    }

    @Test
    fun `a bookmark shows its title, its chapter and its date`() {
        val marks = BookMarks(
            bookmarks = listOf(
                sampleBookmark("b1", "epubcfi(/6/4!/4/2/1:0)", title = "A title", createdAt = Now.minusSeconds(3_600)),
                sampleBookmark("b2", "epubcfi(/6/6!/4/2/1:0)", title = "Another", createdAt = Now.minus(Day)),
                sampleBookmark("b3", "epubcfi(/6/8!/4/2/1:0)", title = "Old", createdAt = LongAgo),
            ),
        )
        show(base.copy(panelTab = PanelTab.Bookmarks, marks = marks))

        compose.onNodeWithText("A title").assertIsDisplayed()
        compose.onNodeWithText("Chapter one · $today").assertIsDisplayed()
        compose.onNodeWithText("Chapter two · $yesterday").assertIsDisplayed()
        compose.onNodeWithText("Chapter three · Nov\u00A02").assertIsDisplayed()
    }

    @Test
    fun `a bookmark titled with its chapter does not say the chapter twice`() {
        val marks = BookMarks(
            bookmarks = listOf(sampleBookmark("b1", "epubcfi(/6/4!/4/2/1:0)", title = "Chapter one", createdAt = Now)),
        )
        show(base.copy(panelTab = PanelTab.Bookmarks, marks = marks))

        compose.onNodeWithText(today).assertIsDisplayed()
    }

    @Test
    fun `a bookmark from an e-reader says so, and a Kobo note shows under its title`() {
        val marks = BookMarks(
            bookmarks = listOf(
                sampleBookmark("b1", "epubcfi(/6/4!/4/2/1:0)", title = "Koreader's", createdAt = Now)
                    .copy(origin = MarkOrigin.KoReader),
                sampleBookmark("b2", "epubcfi(/6/6!/4/2/1:0)", title = "Kobo's", createdAt = Now)
                    .copy(origin = MarkOrigin.Kobo, note = "Read this again"),
            ),
        )
        show(base.copy(panelTab = PanelTab.Bookmarks, marks = marks))

        compose.onNodeWithText("Chapter one · $today · $fromEReader").assertIsDisplayed()
        compose.onNodeWithText("Chapter two · $today · $fromEReader").assertIsDisplayed()
        assertThat(top("Kobo's")).isLessThan(top("Read this again"))
        assertThat(top("Read this again")).isLessThan(top("Chapter two · $today · $fromEReader"))
    }

    @Test
    fun `a tap on a bookmark goes there, its delete button deletes it`() {
        val marks = BookMarks(bookmarks = listOf(sampleBookmark("b1", "epubcfi(/6/4!/4/2/1:0)", title = "A title")))
        show(base.copy(panelTab = PanelTab.Bookmarks, marks = marks))

        compose.onNodeWithText("A title").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.reader_bookmark_delete, "A title"))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertThat(actions.calls).containsExactly("goToMark(epubcfi(/6/4!/4/2/1:0))", "deleteBookmark(b1)").inOrder()
    }

    @Test
    fun `a bookmark not found in this file says so and cannot be tapped, but can be deleted`() {
        val marks = BookMarks(bookmarks = listOf(sampleBookmark("b1", cfi = null, title = "Lost", createdAt = Now)))
        show(base.copy(panelTab = PanelTab.Bookmarks, marks = marks))

        compose.onNodeWithText("$today · $notFound").assertIsDisplayed()
        compose.onNode(hasText("Lost") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Lost").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.reader_bookmark_delete, "Lost")).performClick()

        assertThat(actions.calls).containsExactly("deleteBookmark(b1)")
    }

    @Test
    fun `a deletion offers undo inside the panel`() {
        val marks = BookMarks(bookmarks = listOf(sampleBookmark("b2", "epubcfi(/6/4!/4/2/1:0)", title = "Kept")))
        show(base.copy(panelTab = PanelTab.Bookmarks, marks = marks, undo = UndoUi.Bookmarks(listOf(MarkId("b1")))))

        val deleted = context.getString(R.string.reader_bookmark_deleted)
        compose.onNodeWithText(deleted).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_undo)).performClick()

        assertThat(actions.calls).containsExactly("undo")
    }

    @Test
    fun `no bookmarks says how to add one, by the corner only when the corner is on`() {
        show(base.copy(panelTab = PanelTab.Bookmarks))
        compose.onNodeWithText(text(R.string.reader_bookmarks_empty_corner)).assertIsDisplayed()

        state = base.copy(panelTab = PanelTab.Bookmarks, settings = ReaderSettings(cornerBookmark = false))
        compose.onNodeWithText(text(R.string.reader_bookmarks_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_bookmarks_empty_corner)).assertDoesNotExist()
    }

    // Notes

    @Test
    fun `highlights are in reading order, with their text, their note, their chapter and their date`() {
        val marks = BookMarks(
            highlights = listOf(
                sampleHighlight("h2", "epubcfi(/6/6!/4/2,/1:0,/1:5)", text = "Later words", note = "A thought")
                    .copy(createdAt = Now.minus(Day)),
                sampleHighlight("h1", "epubcfi(/6/4!/4/2,/1:0,/1:5)", text = "Earlier words")
                    .copy(chapterTitle = "The server's chapter", createdAt = Now),
            ),
        )
        show(base.copy(panelTab = PanelTab.Notes, marks = marks))

        assertThat(top("Earlier words")).isLessThan(top("Later words"))
        compose.onNodeWithText("The server's chapter · $today").assertIsDisplayed()
        assertThat(top("Later words")).isLessThan(top("A thought"))
        assertThat(top("A thought")).isLessThan(top("Chapter two · $yesterday"))
    }

    @Test
    fun `a highlight from an e-reader says so, and a tap goes there`() {
        val marks = BookMarks(
            highlights = listOf(
                sampleHighlight("h1", "epubcfi(/6/4!/4/2,/1:0,/1:5)", text = "Some words")
                    .copy(origin = MarkOrigin.Kobo, createdAt = Now),
            ),
        )
        show(base.copy(panelTab = PanelTab.Notes, marks = marks))

        compose.onNodeWithText("Chapter one · $today · $fromEReader").assertIsDisplayed()
        compose.onNodeWithText("Some words").performClick()

        assertThat(actions.calls).containsExactly("goToMark(epubcfi(/6/4!/4/2,/1:0,/1:5))")
    }

    @Test
    fun `an unplaced highlight says its position is approximate and goes to its chapter`() {
        val marks = BookMarks(
            highlights = listOf(
                sampleHighlight("h1", cfi = null, text = "Words from a device")
                    .copy(origin = MarkOrigin.KoReader, chapterIndex = 3, createdAt = Now),
            ),
        )
        show(base.copy(panelTab = PanelTab.Notes, marks = marks))

        compose.onNodeWithText("Chapter three · $today · $fromEReader · $approximate").assertIsDisplayed()
        compose.onNodeWithText("Words from a device").performClick()

        assertThat(actions.calls).containsExactly("goToChapter(3)")
    }

    @Test
    fun `an unplaced highlight without a chapter cannot be tapped`() {
        val marks = BookMarks(
            highlights = listOf(sampleHighlight("h1", cfi = null, text = "Lost words").copy(createdAt = Now)),
        )
        show(base.copy(panelTab = PanelTab.Notes, marks = marks))

        compose.onNodeWithText("$today · $approximate").assertIsDisplayed()
        compose.onNode(hasText("Lost words") and hasClickAction(), useUnmergedTree = false).assertIsNotEnabled()
        compose.onNodeWithText("Lost words").performClick()
        assertThat(actions.calls).isEmpty()
    }

    @Test
    fun `a highlight the server is still placing is not listed`() {
        val marks = BookMarks(
            highlights = listOf(
                sampleHighlight("h1", cfi = null, text = "Not yet").copy(positionStatus = PositionStatus.Pending),
            ),
        )
        show(base.copy(panelTab = PanelTab.Notes, marks = marks))

        compose.onNodeWithText("Not yet").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.reader_notes_empty)).assertIsDisplayed()
    }

    @Test
    fun `no highlights says how to make one`() {
        show(base.copy(panelTab = PanelTab.Notes))

        compose.onNodeWithText(text(R.string.reader_notes_empty)).assertIsDisplayed()
    }

    private companion object {
        val Now: Instant = Instant.parse("2026-11-14T10:00:00Z")
        val Day: Duration = Duration.ofDays(1)
        val LongAgo: Instant = Instant.parse("2026-11-02T10:00:00Z")
    }
}
