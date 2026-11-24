package app.tfdn.lirelle.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.MarkOrigin
import app.tfdn.lirelle.core.reader.marks.BookMarks
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.sampleBookmark
import app.tfdn.lirelle.core.testing.sampleHighlight
import app.tfdn.lirelle.feature.reader.bridge.TocEntry
import app.tfdn.lirelle.feature.reader.web.testBook
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * No text of the panel of contents, bookmarks and notes overflows or is clipped, at any width,
 * language or text size, with long titles, long notes and long chapter names.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class PanelTextFitTest(screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val nothing = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing)

    private val labels = listOf(null, LongChapter, LongChapter, LongChapter)

    private val reading by lazy {
        ReaderUiState(
            title = LongTitle,
            phase = ReaderPhase.Reading(testBook(folder.newFile("book.epub"))),
            opened = true,
            toc = DeepToc,
            location = location(chapter = DeepToc[4].label, tocId = 4),
            settings = ReaderSettings(header = nothing, footer = nothing),
            sectionLabels = labels,
            openSheet = ReaderSheet.Contents,
            pageBookmarks = listOf(MarkId("b1")),
        )
    }

    private val bookmarks = BookMarks(
        bookmarks = listOf(
            sampleBookmark("b1", "epubcfi(/6/4!/4/2/1:0)", title = LongTitle, createdAt = Now),
            sampleBookmark("b2", "epubcfi(/6/6!/4/2/1:0)", title = LongTitle, createdAt = OtherYear)
                .copy(origin = MarkOrigin.Kobo, note = LongNote),
            sampleBookmark("b3", "epubcfi(/6/8!/4/2/1:0)", title = "Short", createdAt = Now)
                .copy(origin = MarkOrigin.KoReader),
        ),
    )

    private val highlights = BookMarks(
        highlights = listOf(
            sampleHighlight("h1", "epubcfi(/6/4!/4/2,/1:0,/1:5)", text = LongPassage, note = LongNote)
                .copy(createdAt = OtherYear, origin = MarkOrigin.KoReader),
            sampleHighlight("h2", "epubcfi(/6/6!/4/2,/1:0,/1:5)", text = "Short").copy(chapterTitle = LongChapter),
            sampleHighlight("h3", cfi = null, text = LongPassage).copy(chapterIndex = 2, origin = MarkOrigin.Kobo),
        ),
    )

    @Test
    fun `the contents tab, deeply nested and with long labels`() {
        // The label of an entry is meant to be cut: two lines, then an ellipsis.
        val labels = DeepToc.map(TocEntry::label).toSet()
        val texts = show(reading.copy(panelTab = PanelTab.Contents), unbreakable = labels)

        assertTabsWhole(texts)
        val entries = texts.filter { it.text in labels }
        assertWithMessage("entries shown").that(entries.size).isAtLeast(2)
        entries.forEach { assertWithMessage("lines of $it").that(it.lineCount).isAtMost(2) }
    }

    @Test
    fun `the bookmarks tab with long titles, a long note and long chapter names`() {
        // The title of a bookmark is meant to be cut, as an entry of the contents: two lines, then an ellipsis.
        val texts = show(reading.copy(panelTab = PanelTab.Bookmarks, marks = bookmarks), unbreakable = setOf(LongTitle))

        assertTabsWhole(texts)
        val titles = texts.filter { it.text == LongTitle }
        assertWithMessage("titles shown").that(titles.size).isAtLeast(1)
        titles.forEach { assertWithMessage("lines of $it").that(it.lineCount).isAtMost(2) }
    }

    @Test
    fun `the notes tab with long passages, a long note and long chapter names`() {
        val texts = show(reading.copy(panelTab = PanelTab.Notes, marks = highlights), unbreakable = setOf(LongPassage))

        assertTabsWhole(texts)
        texts.filter { it.text == LongPassage }.forEach {
            assertWithMessage("lines of $it").that(it.lineCount).isAtMost(3)
        }
    }

    @Test
    fun `the empty tabs`() {
        assertTabsWhole(show(reading.copy(panelTab = PanelTab.Bookmarks)))
        assertTabsWhole(show(reading.copy(panelTab = PanelTab.Notes)))
    }

    /** The names of the tabs, each on one line and not cut short. */
    private fun assertTabsWhole(texts: List<LaidOutText>) {
        listOf(R.string.reader_tab_contents, R.string.reader_tab_bookmarks, R.string.reader_tab_notes)
            .map { compose.activity.getString(it) }
            .forEach { name ->
                val tab = texts.firstOrNull { it.text == name }
                assertWithMessage("tab $name").that(tab).isNotNull()
                assertWithMessage("lines of $tab").that(tab!!.lineCount).isEqualTo(1)
                assertWithMessage("$tab is cut short").that(tab.isEllipsized).isFalse()
            }
    }

    private var shown by mutableStateOf(ReaderUiState())
    private var composed = false

    private fun show(state: ReaderUiState, unbreakable: Set<String> = emptySet()): List<LaidOutText> {
        shown = state
        if (composed) return compose.assertAllTextFits(unbreakable)
        composed = true
        compose.setContent {
            LirelleTheme {
                ReaderContent(
                    state = shown,
                    actions = RecordingActions(),
                    engineSlot = { Box(it) },
                    device = DeviceReadings(now = Now),
                )
            }
        }
        return compose.assertAllTextFits(unbreakable)
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Wide = "w1280dp-h800dp"
        private val Now: Instant = Instant.parse("2026-11-14T10:00:00Z")
        private val OtherYear: Instant = Instant.parse("2025-09-30T10:00:00Z")

        private const val LongNote = "A note long enough to need several lines in the panel, written in a hurry " +
            "while reading, about the places, the names and the dates, to be checked against the map later on."

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
