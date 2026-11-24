package app.tfdn.lirelle.feature.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ui.SheetGripTag
import app.tfdn.lirelle.feature.player.controller.ChapterRow
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A list of chapters flung to its end stays in its sheet: the sheet neither moves nor changes height. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ChaptersSheetFlingTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = PlayerSamples.playing.copy(
        chapterIndex = 0,
        chapterCount = ChapterCount,
        chapters = (1..ChapterCount).map { ChapterRow("Chapter $it", 900_000, heard = false, current = it == 1) },
    )

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private val sheet get() = compose.onNodeWithTag(ChaptersSheetTag)
    private val list get() = compose.onNodeWithTag(ChapterListTag)

    private fun open() {
        compose.setContent {
            LirelleTheme(darkTheme = true) { PlayerContent(state, RecordingActions(), {}, {}, {}) }
        }
        compose.onNodeWithTag(ChaptersButtonTag).performScrollTo().performClick()
    }

    /** The sheet stays at [bounds], and open, over a few looks. */
    private fun assertStays(bounds: Rect) {
        repeat(Looks) { look ->
            compose.mainClock.advanceTimeBy(LookEveryMs)
            assertWithMessage("the sheet, look $look").that(sheet.bounds()).isEqualTo(bounds)
        }
    }

    @Test
    fun `a fast fling to the end leaves the sheet where it is, and the last chapter whole`() {
        open()
        val sheetBefore = sheet.bounds()
        val listBefore = list.bounds()

        repeat(Flings) { list.performTouchInput { swipeUp(durationMillis = FastSwipeMs) } }

        repeat(Looks) { look ->
            compose.mainClock.advanceTimeBy(LookEveryMs)
            assertWithMessage("the sheet, look $look").that(sheet.bounds()).isEqualTo(sheetBefore)
            assertWithMessage("the list's height, look $look").that(list.bounds().height).isEqualTo(listBefore.height)
        }
        val last = compose.onNodeWithText("Chapter $ChapterCount").assertIsDisplayed()
        val node = last.fetchSemanticsNode()
        val whole = node.size.height.toFloat()
        assertWithMessage("the last chapter, whole").that(node.boundsInRoot.height).isEqualTo(whole)
    }

    @Test
    fun `a fast fling back to the start neither moves nor closes the sheet`() {
        open()
        val before = sheet.bounds()
        list.performScrollToIndex(ChapterCount - 1)

        // One fast swipe from the end: its fling reaches the start. A second would begin at the start,
        // where a drag down is meant to close the sheet.
        list.performTouchInput { swipeDown(durationMillis = FastSwipeMs) }

        assertStays(before)
        compose.onNodeWithText("Chapter 1").assertIsDisplayed()
    }

    @Test
    fun `a drag down on the list never closes the sheet, its grip does`() {
        open()
        val before = sheet.bounds()

        list.performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = SlowSwipeMs) }
        assertStays(before)

        compose.onNodeWithTag(SheetGripTag).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + GripDragPx, durationMillis = SlowSwipeMs)
        }
        compose.waitForIdle()
        sheet.assertDoesNotExist()
    }

    private companion object {
        const val ChapterCount = 44
        const val Flings = 4
        const val FastSwipeMs = 60L
        const val SlowSwipeMs = 1_500L
        const val GripDragPx = 600f
        const val Looks = 6
        const val LookEveryMs = 250L
    }
}
