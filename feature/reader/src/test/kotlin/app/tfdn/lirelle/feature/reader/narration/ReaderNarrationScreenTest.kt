package app.tfdn.lirelle.feature.reader.narration

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.feature.reader.R
import app.tfdn.lirelle.feature.reader.ReaderBottomBarTag
import app.tfdn.lirelle.feature.reader.ReaderTopBarTag
import app.tfdn.lirelle.feature.reader.ReaderContent
import app.tfdn.lirelle.feature.reader.ReaderPhase
import app.tfdn.lirelle.feature.reader.ReaderUiState
import app.tfdn.lirelle.feature.reader.RecordingActions
import app.tfdn.lirelle.feature.reader.RunningFooterTag
import app.tfdn.lirelle.feature.reader.RunningHeaderTag
import app.tfdn.lirelle.feature.reader.SampleToc
import app.tfdn.lirelle.feature.reader.SelectionBarTag
import app.tfdn.lirelle.feature.reader.SelectionUi
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.location
import app.tfdn.lirelle.feature.reader.web.testBook
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.tfdn.lirelle.feature.player.R as PlayerR

private const val EngineTag = "engine"

/** The reader's layout while its book is narrated: the bar, the strip, the wave button, the notices. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ReaderNarrationScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = RecordingActions()
    private val controls = RecordingNarrationActions()

    private val reading by lazy {
        val nothing = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing)
        ReaderUiState(
            title = "A book",
            phase = ReaderPhase.Reading(testBook(folder.newFile("book.epub"))),
            opened = true,
            toc = SampleToc,
            location = location(),
            settings = ReaderSettings(header = nothing, footer = nothing),
        )
    }

    private val narrated = ReaderNarrationUi(available = true, active = narrationUi())

    private var shown by mutableStateOf(ReaderUiState())

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private var passed: RecordingNarrationActions? by mutableStateOf(null)

    private fun show(state: ReaderUiState, controls: RecordingNarrationActions? = this.controls) {
        shown = state
        passed = controls
        compose.setContent {
            LirelleTheme {
                ReaderContent(
                    state = shown,
                    actions = actions,
                    engineSlot = { Box(it.testTag(EngineTag)) },
                    narrationControls = passed,
                )
            }
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    @Test
    fun `the narration's bar is one of the controls, it comes and goes with them`() {
        show(reading.copy(narration = narrated))
        compose.onNodeWithTag(NarrationDockTag).assertDoesNotExist()

        shown = reading.copy(controlsShown = true, narration = narrated)
        compose.onNodeWithTag(NarrationDockTag).assertIsDisplayed()

        shown = reading.copy(narration = narrated)
        compose.onNodeWithTag(NarrationDockTag).assertDoesNotExist()
    }

    @Test
    fun `without a narration of the book, no bar among the controls`() {
        show(reading.copy(controlsShown = true, narration = ReaderNarrationUi(available = true)))

        compose.onNodeWithTag(NarrationDockTag).assertDoesNotExist()
    }

    @Test
    fun `the page keeps its place whatever the narration does, with the controls and without`() {
        show(reading.copy(narration = ReaderNarrationUi(available = true)))
        val page = bounds(EngineTag)
        val paused = narrated.copy(active = narrationUi(playing = false))
        val ended = narrated.copy(active = narrationUi(playing = false, finished = true), ended = true)

        for (narration in listOf(narrated, paused, ended, ReaderNarrationUi(available = true))) {
            for (controlsShown in listOf(false, true)) {
                shown = reading.copy(controlsShown = controlsShown, narration = narration)
                compose.waitForIdle()

                assertThat(bounds(EngineTag)).isEqualTo(page)
            }
        }
    }

    @Test
    fun `the page is exactly where it was with the controls given and nothing narrated`() {
        show(reading.copy(narration = ReaderNarrationUi(available = true)), controls = null)
        val without = bounds(EngineTag)

        passed = controls
        compose.waitForIdle()

        assertThat(bounds(EngineTag)).isEqualTo(without)
    }

    @Test
    fun `the strip floats on the header line, the page keeps its height, and its buttons are the ways back`() {
        val headed = reading.copy(
            settings = reading.settings.copy(header = RunningLine(RunningSlot.ChapterTitle, RunningSlot.Nothing)),
        )
        show(headed.copy(narration = narrated))
        val page = bounds(EngineTag)
        shown = headed.copy(narration = narrated.copy(following = Following.Off, strip = StripUi.BackOnly))
        val back = text(PlayerR.string.narration_back_to_sentence)

        compose.onNodeWithText(back).assertIsDisplayed()
        compose.onNodeWithText(text(PlayerR.string.narration_read_page)).assertDoesNotExist()
        assertThat(bounds(EngineTag)).isEqualTo(page)
        val label = compose.onNodeWithText(back).getUnclippedBoundsInRoot()
        val header = bounds(RunningHeaderTag)
        assertThat((label.top + label.bottom) / 2).isIn(Range.closed(header.top, header.bottom))
        compose.onNodeWithText(back).performClick()

        val paused = narrated.copy(active = narrationUi(playing = false), strip = StripUi.BackAndReadPage)
        shown = reading.copy(narration = paused)
        compose.onNodeWithText(text(PlayerR.string.narration_read_page)).performClick()

        assertThat(actions.calls).containsExactly("backToSentence", "readThisPage").inOrder()
    }

    @Test
    fun `with the controls, the strip floats under the top bar`() {
        show(reading.copy(controlsShown = true, narration = narrated.copy(strip = StripUi.BackOnly)))

        assertThat(bounds(NarrationStripTag).top).isAtLeast(bounds(ReaderTopBarTag).bottom)
    }

    @Test
    fun `with the controls, the reader's bottom bar sits on the narration's bar`() {
        show(reading.copy(controlsShown = true, narration = narrated))

        assertThat(bounds(ReaderBottomBarTag).bottom).isAtMost(bounds(NarrationDockTag).top)
        compose.onNodeWithTag(NarrationDockTag).assertIsDisplayed()
    }

    @Test
    fun `the wave button starts the narration, and once narrated opens its panel`() {
        show(reading.copy(controlsShown = true, narration = ReaderNarrationUi(available = true)))
        compose.onNodeWithContentDescription(text(R.string.reader_narration_start)).performClick()

        assertThat(actions.calls).containsExactly("startHere")

        shown = reading.copy(controlsShown = true, narration = narrated)
        compose.onNodeWithContentDescription(text(R.string.reader_narration_start)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.reader_narration_playing)).performClick()

        compose.onNodeWithText(text(PlayerR.string.narration_section_line, 1, 2, 0)).assertIsDisplayed()
        assertThat(actions.calls).containsExactly("startHere")
    }

    @Test
    fun `while narrated the wave button says that it opens the controls`() {
        show(reading.copy(controlsShown = true, narration = ReaderNarrationUi(available = true)))
        val start = compose.onNodeWithTag(WaveButtonTag).fetchSemanticsNode().config[SemanticsActions.OnClick]
        assertThat(start.label).isNull()

        shown = reading.copy(controlsShown = true, narration = narrated)
        val open = compose.onNodeWithTag(WaveButtonTag).fetchSemanticsNode().config[SemanticsActions.OnClick]

        assertThat(open.label).isEqualTo(text(R.string.reader_narration_open_controls))
    }

    @Test
    fun `a file without narration has no wave button`() {
        show(reading.copy(controlsShown = true))

        compose.onNodeWithTag(WaveButtonTag).assertDoesNotExist()
    }

    @Test
    fun `the bar's texts open the narration's panel`() {
        show(reading.copy(controlsShown = true, narration = narrated))

        compose.onNodeWithText("One").performClick()

        compose.onNodeWithText(text(PlayerR.string.narration_section_line, 1, 2, 0)).assertIsDisplayed()
    }

    @Test
    fun `read from here heads the selection's card when the file has a narration`() {
        val selection = SelectionUi("epubcfi(/6/4!/4/2,/1:0,/1:4)", "Some", 0, PageBounds(0.2, 0.5, 0.6, 0.55), null)
        show(reading.copy(selection = selection, narration = ReaderNarrationUi(available = true)))

        val row = bounds(ReadFromHereTag)
        val card = bounds(SelectionBarTag)
        assertThat(row.top - card.top).isLessThan(row.bottom - row.top)
        compose.onNodeWithText(text(R.string.reader_read_from_here)).performClick()
        assertThat(actions.calls).containsExactly("readFromSelection")

        shown = reading.copy(selection = selection)
        compose.onNodeWithTag(ReadFromHereTag).assertDoesNotExist()
    }

    @Test
    fun `at the end of the narration, the bar offers to finish the book`() {
        val ended = narrated.copy(active = narrationUi(playing = false, finished = true), ended = true)
        show(reading.copy(controlsShown = true, narration = ended))

        compose.onNodeWithText(text(PlayerR.string.narration_finished)).assertIsDisplayed()
        compose.onNodeWithText("One").assertDoesNotExist()
        compose.onNodeWithText(text(PlayerR.string.narration_finish_book)).performClick()

        assertThat(actions.calls).containsExactly("finishBook")
    }

    @Test
    fun `a skipped section is told above the bar, and read opens it`() {
        val notice = SkippedNotice(NarratedBook, sectionIndex = 1, title = "Two")
        show(reading.copy(controlsShown = true, narration = narrated.copy(notice = notice)))

        val told = compose.onNodeWithText(text(PlayerR.string.narration_skipped_titled, "Two"), substring = true)
        told.assertIsDisplayed()
        assertThat(told.getUnclippedBoundsInRoot().bottom).isAtMost(bounds(NarrationDockTag).top)
        compose.onNodeWithText(text(PlayerR.string.narration_skipped_read)).performClick()

        assertThat(actions.calls).containsExactly("readSkipped(1)")
    }

    @Test
    fun `a skipped section is told above the footer too`() {
        val footer = RunningLine(RunningSlot.ChapterTitle, RunningSlot.BookPercent)
        val notice = SkippedNotice(NarratedBook, sectionIndex = 1, title = "Two")
        val settings = reading.settings.copy(footer = footer)
        show(reading.copy(settings = settings, narration = narrated.copy(notice = notice)))

        val told = compose.onNodeWithText(text(PlayerR.string.narration_skipped_titled, "Two"), substring = true)
        assertThat(told.getUnclippedBoundsInRoot().bottom).isAtMost(bounds(RunningFooterTag).top)
    }

    @Test
    fun `a narration that cannot be had is said once, not again when the screen is made anew`() {
        val restoration = StateRestorationTester(compose)
        shown = reading.copy(narration = ReaderNarrationUi(available = true, unavailable = true))
        restoration.setContent {
            LirelleTheme {
                ReaderContent(
                    state = shown,
                    actions = actions,
                    engineSlot = { Box(it.testTag(EngineTag)) },
                    narrationControls = controls,
                )
            }
        }
        compose.onNodeWithText(text(R.string.reader_narration_unavailable)).assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(text(R.string.reader_narration_unavailable)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a tablet's window has the wide bar, with the skips`() {
        val back = text(PlayerR.string.player_button_back, 15)
        shown = reading.copy(controlsShown = true, narration = narrated)

        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Expanded) {
                    ReaderContent(
                        state = shown,
                        actions = actions,
                        engineSlot = { Box(it.testTag(EngineTag)) },
                        narrationControls = controls,
                    )
                }
            }
        }

        compose.onNodeWithContentDescription(back).assertIsDisplayed()
    }

    @Test
    fun `a narration waiting for the network says so above the bar, with nothing to press`() {
        val offline = narrated.copy(active = narrationUi().copy(offline = true))
        show(reading.copy(controlsShown = true, narration = offline))

        val said = compose.onNodeWithText(text(PlayerR.string.player_offline))
        said.assertIsDisplayed()
        assertThat(said.getUnclippedBoundsInRoot().bottom).isAtMost(bounds(NarrationDockTag).top)
        compose.onNodeWithText(text(R.string.reader_narration_retry)).assertDoesNotExist()

        shown = reading.copy(narration = narrated.copy(active = narrationUi().copy(unreachable = true)))
        compose.onNodeWithText(text(PlayerR.string.player_unreachable)).assertIsDisplayed()
    }

    @Test
    fun `the paused narration's bar swiped away stops listening, and undo takes it back`() {
        show(reading.copy(controlsShown = true, narration = narrated.copy(active = narrationUi(playing = false))))

        compose.onNodeWithTag(NarrationSwipeTag).performTouchInput { swipeRight() }
        compose.onNodeWithText(text(PlayerR.string.player_stopped)).assertIsDisplayed()
        // The narration stopped takes its bar away, and the controls may go: the message stays for its answer.
        shown = reading.copy(narration = ReaderNarrationUi(available = true))
        compose.waitForIdle()
        compose.onNodeWithTag(NarrationDockTag).assertDoesNotExist()
        compose.onNodeWithText(text(PlayerR.string.player_stopped)).assertIsDisplayed()
        compose.onNodeWithText(text(PlayerR.string.player_stopped_undo)).performClick()

        assertThat(actions.calls).containsExactly("stopNarration", "undoStopNarration").inOrder()
    }

    @Test
    fun `the ended narration's bar is swiped away too, the playing one is not`() {
        val ended = narrated.copy(active = narrationUi(playing = false), ended = true)
        show(reading.copy(controlsShown = true, narration = ended))
        compose.onNodeWithTag(NarrationSwipeTag).assertExists()

        shown = reading.copy(controlsShown = true, narration = narrated)
        compose.onNodeWithTag(NarrationDockTag).assertIsDisplayed()
        compose.waitForIdle()

        compose.onNodeWithTag(NarrationSwipeTag).assertDoesNotExist()
    }

    @Test
    fun `a narration stopped by an error says so, and try again asks again`() {
        show(reading.copy(narration = narrated.copy(active = narrationUi(playing = false).copy(error = true))))

        compose.onNodeWithText(text(R.string.reader_narration_error)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_narration_retry)).performClick()

        assertThat(actions.calls).containsExactly("retryNarration")
    }

    @Test
    fun `a narration that could not be loaded shows its failure, without a bar of nothing`() {
        val failed = narrationUi(playing = false).copy(sectionCount = 0, sectionNumber = 0, loadFailed = true)
        show(reading.copy(controlsShown = true, narration = ReaderNarrationUi(available = true, active = failed)))

        compose.onNodeWithText(text(PlayerR.string.player_load_failed)).assertIsDisplayed()
        compose.onNodeWithTag(NarrationDockTag).assertDoesNotExist()
        // Nothing is narrated: the wave button starts the narration again.
        compose.onNodeWithContentDescription(text(R.string.reader_narration_start)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_narration_retry)).performClick()

        assertThat(actions.calls).containsExactly("retryNarration")
    }

    @Test
    fun `this book's narration that failed while another is held shows its failure only`() {
        show(reading.copy(controlsShown = true, narration = ReaderNarrationUi(available = true, loadFailed = true)))

        compose.onNodeWithText(text(PlayerR.string.player_load_failed)).assertIsDisplayed()
        compose.onNodeWithTag(NarrationDockTag).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.reader_narration_start)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_narration_retry)).performClick()

        assertThat(actions.calls).containsExactly("retryNarration")
    }

    @Test
    fun `a narration that cannot be had says so, and try again asks again`() {
        show(reading.copy(narration = ReaderNarrationUi(available = true, unavailable = true)))

        compose.onNodeWithText(text(R.string.reader_narration_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.reader_narration_retry)).performClick()

        assertThat(actions.calls).containsExactly("retryNarration")
    }

    @Test
    fun `the bar's controls are the reader's`() {
        show(reading.copy(controlsShown = true, narration = narrated))

        compose.onNodeWithContentDescription(context.getString(PlayerR.string.narration_next_sentence)).performClick()

        assertThat(controls.calls).containsExactly("nextSentence")
        // A phone's bar: no skips beside the sentence buttons.
        compose.onNodeWithContentDescription(text(PlayerR.string.player_button_back, 15)).assertDoesNotExist()
    }
}
