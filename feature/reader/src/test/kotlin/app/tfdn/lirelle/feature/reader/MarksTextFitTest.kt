package app.tfdn.lirelle.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.marks.HighlightColour
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.text.TextAction
import app.tfdn.lirelle.feature.reader.web.testBook
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * No text of the selection bar, the highlight's panel or the undo bar overflows or is clipped, at
 * any width, language or text size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class MarksTextFitTest(private val screen: String, language: String, private val fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val nothing = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing)

    private val reading by lazy {
        ReaderUiState(
            title = "A book",
            phase = ReaderPhase.Reading(testBook(folder.newFile("book.epub"))),
            opened = true,
            location = location(),
            settings = ReaderSettings(header = nothing, footer = nothing),
        )
    }

    /** Every action a device can offer, with an app whose name is long. */
    private val everything = listOf(
        TextAction.Translate,
        TextAction.Define,
        TextAction.Copy,
        TextAction.Share,
        TextAction.SearchWeb,
        TextAction.App("A dictionary of the language with a remarkably long name", "org.example.a", "org.example.A"),
    )

    private val selection = SelectionUi(
        cfi = "epubcfi(/6/4!/4/2,/1:0,/1:20)",
        text = "a passage",
        sectionIndex = 1,
        bounds = PageBounds(left = 0.1, top = 0.5, right = 0.9, bottom = 0.55),
        exactId = null,
    )

    private val panel = HighlightPanelUi(
        id = MarkId("h1"),
        text = LongPassage,
        color = HighlightColour.Yellow.hex,
        note = "",
        draft = "a".repeat(3_990),
        focusNote = false,
        confirmDiscard = false,
    )

    @Test
    fun `the selection bar with every action`() {
        val texts = show(reading.copy(selection = selection, textActions = everything))

        texts.forEach { assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse() }
        if (screen != Narrow && fontScale == 1f) {
            val buttons = listOf(
                R.string.reader_selection_note,
                R.string.reader_selection_translate,
                R.string.reader_selection_define,
                R.string.reader_selection_search,
            ).map { compose.activity.getString(it) }
            assertWithMessage("buttons of the bar").that(texts.map { it.text }).containsAtLeastElementsIn(buttons)
        }
    }

    @Test
    fun `the menu of the selection bar`() {
        show(reading.copy(selection = selection, textActions = everything))
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.reader_selection_more)).performClick()

        val texts = compose.assertAllTextFits()
        texts.forEach { assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse() }
    }

    @Test
    fun `the highlight's panel near the limit of its note`() {
        val texts = show(reading.copy(highlightPanel = panel), unbreakable = setOf(LongPassage))

        val quote = texts.single { it.text == LongPassage }
        assertWithMessage("lines of $quote").that(quote.lineCount).isAtMost(3)
        texts.filter { it.text != LongPassage }.forEach {
            assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse()
        }
    }

    @Test
    fun `the question before discarding a note`() {
        show(reading.copy(highlightPanel = panel.copy(confirmDiscard = true)), unbreakable = setOf(LongPassage))
    }

    @Test
    fun `the undo of a deleted highlight`() {
        // The title is in a slot of one line, and gives way to the top bar's buttons: it may end with an ellipsis.
        show(
            reading.copy(undo = UndoUi.Highlight(MarkId("h1")), controlsShown = true, goBackShown = true),
            unbreakable = setOf(reading.title),
        )
    }

    @Test
    fun `the undo of removed bookmarks`() {
        show(reading.copy(undo = UndoUi.Bookmarks(List(12) { MarkId("b$it") })))
    }

    private fun show(state: ReaderUiState, unbreakable: Set<String> = emptySet()): List<LaidOutText> {
        compose.setContent {
            LirelleTheme {
                ReaderContent(state = state, actions = RecordingActions(), engineSlot = { Box(it) })
            }
        }
        return compose.assertAllTextFits(unbreakable)
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Small = "w360dp-h740dp"
        private const val Phone = "w411dp-h891dp"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Small, Phone).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
