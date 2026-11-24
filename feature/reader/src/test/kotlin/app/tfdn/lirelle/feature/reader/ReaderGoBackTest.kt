package app.tfdn.lirelle.feature.reader

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderFlow
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.feature.reader.web.testBook
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.tfdn.lirelle.core.ui.R as UiR

/** "Go back" after a jump: in the footer's line, under the page, never over its text. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ReaderGoBackTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = RecordingActions()

    private val reading by lazy {
        ReaderUiState(
            title = "A book",
            phase = ReaderPhase.Reading(testBook(folder.newFile("book.epub"))),
            opened = true,
            toc = SampleToc,
            location = location(),
        )
    }

    /** Without header nor footer. */
    private val bare by lazy {
        val nothing = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing)
        reading.copy(settings = ReaderSettings(header = nothing, footer = nothing))
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(state: ReaderUiState) {
        compose.setContent {
            LirelleTheme { ReaderContent(state, actions, engineSlot = { Box(it.testTag(EngineTag)) }) }
        }
    }

    @Test
    fun `the go-back button returns from a jump, from inside the footer's line`() {
        show(reading.copy(goBackShown = true))

        val inFooter = hasAnyAncestor(hasTestTag(RunningFooterTag))
        compose.onNode(hasTestTag(GoBackTag) and inFooter)
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        val node = compose.onNodeWithTag(GoBackTag).fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
        compose.onNodeWithText(text(R.string.reader_go_back)).performClick()
        assertThat(actions.calls).containsExactly("goBack")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `go back sits in the middle of the footer, between its two slots, rising into the page's empty band only`() {
        val settings = ReaderSettings(
            header = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing),
            footer = RunningLine(RunningSlot.ChapterTitle, RunningSlot.BookPercent),
        )
        show(reading.copy(settings = settings, goBackShown = true))

        val page = compose.onNodeWithTag(EngineTag).getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag(RunningFooterTag).getUnclippedBoundsInRoot()
        val goBack = compose.onNodeWithTag(GoBackTag).getUnclippedBoundsInRoot()
        val left = compose.onNodeWithText("Two").getUnclippedBoundsInRoot()
        val right = compose.onNodeWithText(text(UiR.string.progress_percent, 42)).getUnclippedBoundsInRoot()
        assertGoBackOverhangsTheBandOnly(page, footer, goBack)
        assertThat((goBack.left + goBack.right).value / 2).isWithin(1f).of((footer.left + footer.right).value / 2)
        assertThat(left.right.value).isAtMost(goBack.left.value)
        assertThat(right.left.value).isAtLeast(goBack.right.value)
    }

    /**
     * "Go back" rises above the footer's line by no more than the empty band the page keeps at its
     * bottom, and covers nothing of the page above that band; its bottom is the line's.
     */
    private fun assertGoBackOverhangsTheBandOnly(page: DpRect, footer: DpRect, goBack: DpRect) {
        assertThat(goBack.bottom.value).isWithin(0.5f).of(footer.bottom.value)
        assertThat((footer.top - goBack.top).value).isAtMost(PageBottomBand.value + 0.5f)
        assertThat(goBack.top.value).isAtLeast((page.bottom - PageBottomBand).value - 0.5f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the page keeps its size when go back comes and goes`() = assertPageStableWithGoBack()

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp", fontScale = 2f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `on a narrow screen in French with large text, the page keeps its size when go back comes and goes`() =
        assertPageStableWithGoBack()

    private fun assertPageStableWithGoBack() {
        // The header is as it was before "Go back" went into the footer: the footer is as high as it.
        val settings = ReaderSettings(
            header = RunningLine(RunningSlot.BookTitle, RunningSlot.Nothing),
            footer = RunningLine(RunningSlot.ChapterTitle, RunningSlot.BookPercent),
        )
        val reading = reading.copy(settings = settings)
        var state by mutableStateOf(reading)
        compose.setContent {
            LirelleTheme { ReaderContent(state, actions, engineSlot = { Box(it.testTag(EngineTag)) }) }
        }
        val page = compose.onNodeWithTag(EngineTag).getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag(RunningFooterTag).getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag(RunningHeaderTag).getUnclippedBoundsInRoot()
        assertThat(footer.height.value).isWithin(0.5f).of(header.height.value)

        listOf(
            reading.copy(goBackShown = true),
            reading.copy(goBackShown = true, noteOpen = true),
            reading.copy(goBackShown = true, controlsShown = true),
            reading,
        ).forEach {
            state = it
            compose.waitForIdle()
            assertThat(compose.onNodeWithTag(EngineTag).getUnclippedBoundsInRoot()).isEqualTo(page)
            assertThat(compose.onNodeWithTag(RunningFooterTag).getUnclippedBoundsInRoot()).isEqualTo(footer)
            if (it.goBackShown && !it.noteOpen && !it.controlsShown) {
                val goBack = compose.onNodeWithTag(GoBackTag).getUnclippedBoundsInRoot()
                assertGoBackOverhangsTheBandOnly(page, footer, goBack)
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a touch on go back where it rises above the footer's line is go back's`() {
        show(reading.copy(goBackShown = true))
        val footer = compose.onNodeWithTag(RunningFooterTag).getUnclippedBoundsInRoot()
        val goBack = compose.onNodeWithTag(GoBackTag).getUnclippedBoundsInRoot()
        assertThat(goBack.top.value).isLessThan(footer.top.value)

        compose.onNodeWithTag(GoBackTag).performTouchInput { click(Offset(centerX, 1f)) }

        assertThat(actions.calls).containsExactly("goBack")
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `in a scrolled book too, the footer keeps the header's height and go back overhangs it`() {
        val settings = ReaderSettings(
            flow = ReaderFlow.Scrolled,
            header = RunningLine(RunningSlot.BookTitle, RunningSlot.Nothing),
            footer = RunningLine(RunningSlot.ChapterTitle, RunningSlot.BookPercent),
        )
        var state by mutableStateOf(reading.copy(settings = settings))
        compose.setContent {
            LirelleTheme { ReaderContent(state, actions, engineSlot = { Box(it.testTag(EngineTag)) }) }
        }
        val page = compose.onNodeWithTag(EngineTag).getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag(RunningFooterTag).getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag(RunningHeaderTag).getUnclippedBoundsInRoot()
        assertThat(footer.height.value).isWithin(0.5f).of(header.height.value)

        state = reading.copy(settings = settings, goBackShown = true)
        compose.waitForIdle()
        val goBack = compose.onNodeWithTag(GoBackTag).getUnclippedBoundsInRoot()
        assertThat(compose.onNodeWithTag(EngineTag).getUnclippedBoundsInRoot()).isEqualTo(page)
        assertThat(compose.onNodeWithTag(RunningFooterTag).getUnclippedBoundsInRoot()).isEqualTo(footer)
        assertThat(goBack.bottom.value).isWithin(0.5f).of(footer.bottom.value)
        assertThat((footer.top - goBack.top).value).isAtMost(PageBottomBand.value + 0.5f)
    }

    @Test
    fun `with both footer slots set to nothing, the line comes with go back and goes with it`() {
        var state by mutableStateOf(bare)
        compose.setContent {
            LirelleTheme { ReaderContent(state, actions, engineSlot = { Box(it.testTag(EngineTag)) }) }
        }
        compose.onNodeWithTag(RunningFooterTag).assertDoesNotExist()

        state = bare.copy(goBackShown = true)
        val inFooter = hasAnyAncestor(hasTestTag(RunningFooterTag))
        compose.onNode(hasTestTag(GoBackTag) and inFooter).assertIsDisplayed()
        val page = compose.onNodeWithTag(EngineTag).getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag(RunningFooterTag).getUnclippedBoundsInRoot()
        assertThat(page.bottom.value).isAtMost(footer.top.value)
        assertThat(footer.bottom.value).isEqualTo(891f)
        assertGoBackOverhangsTheBandOnly(page, footer, compose.onNodeWithTag(GoBackTag).getUnclippedBoundsInRoot())

        // A footnote hides go back, but the line keeps its room: the page does not move under the note.
        state = bare.copy(goBackShown = true, noteOpen = true)
        compose.onNodeWithTag(GoBackTag).assertDoesNotExist()
        assertThat(compose.onNodeWithTag(EngineTag).getUnclippedBoundsInRoot()).isEqualTo(page)

        state = bare
        compose.onNodeWithTag(RunningFooterTag).assertDoesNotExist()
        assertThat(compose.onNodeWithTag(EngineTag).getUnclippedBoundsInRoot().bottom.value).isEqualTo(891f)
    }

    @Test
    fun `with the controls, go back stays above the bottom bar, once`() {
        show(reading.copy(goBackShown = true, controlsShown = true))

        assertThat(compose.onAllNodesWithTag(GoBackTag).fetchSemanticsNodes()).hasSize(1)
        compose.onNode(hasTestTag(GoBackTag) and hasAnyAncestor(hasTestTag(RunningFooterTag))).assertDoesNotExist()
        val button = compose.onNodeWithTag(GoBackTag).getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag(ReaderBottomBarTag).getUnclippedBoundsInRoot()
        assertThat(button.bottom.value).isAtMost(bar.top.value)
    }

    @Test
    fun `go back is not offered while a footnote is open`() {
        show(reading.copy(goBackShown = true, noteOpen = true))

        compose.onNodeWithTag(GoBackTag).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.reader_go_back)).assertDoesNotExist()
    }

    @Test
    fun `undo is offered above go back in the footer`() {
        show(reading.copy(undo = UndoUi.Highlight(MarkId("h1")), goBackShown = true))

        val undo = compose.onNodeWithTag(UndoBarTag).getUnclippedBoundsInRoot()
        val goBack = compose.onNodeWithTag(GoBackTag).getUnclippedBoundsInRoot()
        assertThat(undo.bottom.value).isAtMost(goBack.top.value)
    }

    @Test
    fun `undo is offered above the bottom bar and go back`() {
        show(reading.copy(undo = UndoUi.Highlight(MarkId("h1")), controlsShown = true, goBackShown = true))

        val undo = compose.onNodeWithTag(UndoBarTag).getUnclippedBoundsInRoot()
        val goBack = compose.onNodeWithTag(GoBackTag).getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag(ReaderBottomBarTag).getUnclippedBoundsInRoot()
        assertThat(undo.bottom.value).isAtMost(goBack.top.value)
        assertThat(goBack.bottom.value).isAtMost(bar.top.value)
    }

    private companion object {
        const val EngineTag = "engine"
    }
}
