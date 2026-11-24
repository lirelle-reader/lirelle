package app.tfdn.lirelle.feature.reader

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.narration.ReaderNarrationUi
import app.tfdn.lirelle.feature.reader.narration.WaveButtonTag
import app.tfdn.lirelle.feature.reader.settings.ReadingSettingsPage
import app.tfdn.lirelle.feature.reader.text.TextAction
import app.tfdn.lirelle.feature.reader.web.testBook
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The EPUB reader of a Komga-like source, on a phone as on a tablet (spec 2c): no highlight, note nor bookmark,
 * no annotations list, no narration, no stars at the end; the selection keeps every action of the system.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ReaderCapabilitiesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = RecordingActions()

    private val dictionary = TextAction.App("A dictionary", "org.example.dictionary", "org.example.Define")

    /** Everything a device can offer to do with a selected text. */
    private val systemActions = listOf(
        TextAction.Translate,
        TextAction.Define,
        TextAction.Copy,
        TextAction.Share,
        TextAction.SearchWeb,
        dictionary,
    )

    /** A book with a narration, read from a source that has no annotations, read-along nor ratings. */
    private val reading by lazy {
        ReaderUiState(
            title = "A book",
            phase = ReaderPhase.Reading(testBook(folder.newFile("book.epub"))),
            opened = true,
            toc = SampleToc,
            location = Location("One", tocId = 0, fraction = 0.1, page = 2, pages = 9, atEnd = false),
            narration = ReaderNarrationUi(available = true),
            textActions = systemActions,
            capabilities = KomgaLikeCapabilities,
        )
    }

    private val selection = SelectionUi(
        cfi = "epubcfi(/6/4!/4/2,/1:0,/1:20)",
        text = "a passage of the book",
        sectionIndex = 1,
        bounds = PageBounds(left = 0.2, top = 0.6, right = 0.8, bottom = 0.7),
        exactId = null,
    )

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(state: ReaderUiState, width: WindowWidthClass = WindowWidthClass.Compact) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    ReaderContent(
                        state = state,
                        actions = actions,
                        engineSlot = { Box(it.testTag("engine")) },
                        sheets = { ReaderSheets(state, actions) },
                    )
                }
            }
        }
    }

    /** The top bar keeps back, search, the panel and the settings; no bookmark nor narration's wave. */
    private fun assertTopBar() {
        compose.onNodeWithContentDescription(text(R.string.reader_search_book)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.reader_panel)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.reader_settings)).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(text(R.string.reader_bookmark_add)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(text(R.string.reader_bookmark_remove)).assertCountEquals(0)
        compose.onNodeWithTag(WaveButtonTag).assertDoesNotExist()
    }

    /** The panel is the contents alone, even on the tab last used with annotations. */
    private fun assertPanel() {
        compose.onNodeWithText("Two").assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.reader_tab_bookmarks)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.reader_tab_notes)).assertCountEquals(0)
    }

    /**
     * The selection has no colour dots, no Note nor "Read from here": every action the device offers is there,
     * and the search in the book.
     */
    private fun assertSelection() {
        compose.onNodeWithTag(SelectionBarTag).assertIsDisplayed()
        for (colour in listOf(R.string.reader_highlight_yellow, R.string.reader_highlight_blue)) {
            compose.onAllNodesWithContentDescription(text(colour), substring = true).assertCountEquals(0)
        }
        compose.onAllNodesWithText(text(R.string.reader_selection_note)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.reader_read_from_here)).assertCountEquals(0)

        compose.onNodeWithContentDescription(text(R.string.reader_selection_more)).performClick()
        val offered = listOf(
            text(R.string.reader_selection_translate),
            text(R.string.reader_selection_define),
            text(R.string.reader_selection_search),
            text(R.string.reader_selection_copy),
            text(R.string.reader_selection_share),
            text(R.string.reader_selection_search_web),
            "A dictionary",
        )
        offered.forEach { label -> compose.onAllNodesWithText(label).assertCountEquals(1) }
    }

    @Test
    fun `on a phone the top bar has no bookmark nor narration`() {
        show(reading.copy(controlsShown = true))

        assertTopBar()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a tablet the top bar has no bookmark nor narration`() {
        show(reading.copy(controlsShown = true), WindowWidthClass.Expanded)

        assertTopBar()
    }

    @Test
    fun `on a phone the panel holds the contents, without bookmarks nor notes`() {
        show(reading.copy(openSheet = ReaderSheet.Contents, panelTab = PanelTab.Bookmarks))

        assertPanel()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a tablet the panel holds the contents, without bookmarks nor notes`() {
        show(reading.copy(openSheet = ReaderSheet.Contents, panelTab = PanelTab.Notes), WindowWidthClass.Expanded)

        assertPanel()
    }

    @Test
    fun `on a phone the selection loses only the app's own actions`() {
        show(reading.copy(selection = selection))

        assertSelection()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a tablet the selection loses only the app's own actions`() {
        show(reading.copy(selection = selection), WindowWidthClass.Expanded)

        assertSelection()
    }

    @Test
    fun `with everything the selection still has its dots, Note and Read from here`() {
        show(reading.copy(selection = selection, capabilities = SourceCapabilities.Everything))

        compose.onNodeWithText(text(R.string.reader_selection_note)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_read_from_here)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.reader_highlight_yellow), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `the more options have no corner bookmark`() {
        show(reading.copy(openSheet = ReaderSheet.MoreOptions))

        compose.onNodeWithText(text(R.string.reader_more_volume_keys)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.reader_more_corner_bookmark)).assertCountEquals(0)
    }

    @Test
    fun `the reading settings have no corner bookmark without annotations`() {
        compose.setContent {
            LirelleTheme {
                ReadingSettingsPage(ReaderSettings(), change = {}, onBack = {}, cornerBookmark = false)
            }
        }

        compose.onAllNodesWithText(text(R.string.reader_more_corner_bookmark)).assertCountEquals(0)
    }

    @Test
    fun `the end of the book has no stars without ratings, and keeps the next volume`() {
        val next = NextVolume(bookId = BookId("13"), title = "The Next One", seriesIndex = "3")
        val asked = mutableListOf<String>()
        compose.setContent {
            LirelleTheme {
                EndPanelSheet(
                    EndPanel(nextVolume = next, ratings = false),
                    title = "The Green Lighthouse",
                    colours = ReaderTheme.Sepia.colours(systemDark = false),
                    actions = RecordingEndActions(asked),
                    modifier = Modifier,
                )
            }
        }

        compose.onAllNodesWithText(text(R.string.reader_end_rating)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(
            context.resources.getQuantityString(R.plurals.reader_end_stars, 1, 1),
        ).assertCountEquals(0)
        compose.onNodeWithText("The Next One").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_end_view)).performClick()
        assertThat(asked).containsExactly("next")
    }
}

/** The end panel's actions, each noted in [asked]. */
private class RecordingEndActions(private val asked: MutableList<String>) : EndPanelActions {
    override fun setRating(stars: Int?) {
        asked += "rate $stars"
    }

    override fun closeEndPanel() {
        asked += "close"
    }

    override fun openNextVolume() {
        asked += "next"
    }

    override fun dismissEndPanel() {
        asked += "dismiss"
    }

    override fun deleteFromDevice() = Unit

    override fun keepOnDevice() = Unit
}
