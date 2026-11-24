package app.tfdn.lirelle.feature.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ui.SheetGripTag
import app.tfdn.lirelle.feature.reader.bridge.TocEntry
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A table of contents flung to its end stays in its panel: the panel neither moves nor changes height. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ReaderPanelFlingTest {

    @get:Rule
    val compose = createComposeRule()

    private val toc = (1..EntryCount).map { TocEntry(id = it, label = "Entry $it", href = "e$it.xhtml", depth = 0) }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private val actions = RecordingActions()
    private val state = ReaderUiState(title = "A book", toc = toc, openSheet = ReaderSheet.Contents)
    private val panel get() = compose.onNodeWithTag(ReaderPanelTag)

    private fun open() {
        compose.setContent { LirelleTheme { ReaderPanel(state, actions, Now) } }
    }

    private fun assertStays(bounds: Rect) {
        repeat(Looks) { look ->
            compose.mainClock.advanceTimeBy(LookEveryMs)
            assertWithMessage("the panel, look $look").that(panel.bounds()).isEqualTo(bounds)
        }
    }

    @Test
    fun `a fast fling to the end leaves the panel where it is, and the last entry whole`() {
        open()
        val before = panel.bounds()

        repeat(Flings) { panel.performTouchInput { swipeUp(durationMillis = FastSwipeMs) } }

        assertStays(before)
        val last = compose.onNodeWithText("Entry $EntryCount").assertIsDisplayed()
        val node = last.fetchSemanticsNode()
        assertWithMessage("the last entry, whole").that(node.boundsInRoot.height).isEqualTo(node.size.height.toFloat())
    }

    @Test
    fun `a fast fling back to the start neither moves nor closes the panel`() {
        open()
        val before = panel.bounds()
        repeat(Flings) { panel.performTouchInput { swipeUp(durationMillis = FastSwipeMs) } }
        compose.waitForIdle()

        // One fast swipe from the end: its fling reaches the start. A second would begin at the start,
        // where a drag down is meant to close the panel.
        // On the list, under the tabs: a drag on the tabs is the sheet's own.
        panel.performTouchInput { swipeDown(startY = top + UnderTabs, endY = bottom, durationMillis = FastSwipeMs) }

        assertStays(before)
        compose.onNodeWithText("Entry 1").assertIsDisplayed()
        assertWithMessage("asked of the reader").that(actions.calls).doesNotContain("dismissSheet")
    }

    @Test
    fun `a drag down on the list never closes the panel, its grip does`() {
        open()
        val before = panel.bounds()

        panel.performTouchInput { swipeDown(startY = top + UnderTabs, endY = bottom, durationMillis = SlowSwipeMs) }
        assertStays(before)
        assertWithMessage("asked of the reader").that(actions.calls).doesNotContain("dismissSheet")

        compose.onNodeWithTag(SheetGripTag).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + GripDragPx, durationMillis = SlowSwipeMs)
        }
        compose.waitForIdle()
        assertWithMessage("asked of the reader").that(actions.calls).contains("dismissSheet")
    }

    private companion object {
        const val EntryCount = 44
        const val Flings = 4
        const val FastSwipeMs = 60L
        const val SlowSwipeMs = 1_500L
        const val GripDragPx = 600f

        /** Pixels from the panel's top to below its tabs, on the list. */
        const val UnderTabs = 120f
        const val Looks = 6
        const val LookEveryMs = 250L
        val Now: Instant = Instant.parse("2026-11-14T10:00:00Z")
    }
}
