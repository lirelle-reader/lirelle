package app.tfdn.lirelle.feature.comics.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.ComicNavigation
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Taps on an edge of two pages side by side, zoomed: one page is walked after the other. The clock only goes on
 * when the test says so, and the reader here shows the spread it was asked for a few frames later, as the real
 * one does. The pages are loading: each has the shape of half the screen, so that at twice the fit each is as
 * wide as the screen and twice as high. The spread shown is the pages 6 and 7, the next one 8 and 9.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadPairWalkTest {

    @get:Rule
    val compose = createComposeRule()

    private val recording = RecordingNavigation()
    private var state by mutableStateOf(album(spreadIndex = 3, twoPages = true))
    private var hidden = 0

    /** The turns asked that the reader does not show yet, each with the frames left before it does. */
    private val coming = mutableListOf<Pair<Int, Int>>()

    private val navigation = object : ComicNavigation by recording {
        override fun next() {
            recording.next()
            coming += FollowAfterFrames to 1
        }

        override fun previous() {
            recording.previous()
            coming += FollowAfterFrames to -1
        }
    }
    private val screen: Rect get() = compose.viewport()

    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `forward, the first page is walked to its bottom before the second shows its top`() {
        show()
        zoomAt(Offset.Zero)

        tap(Forward)
        settle()
        // Down the first page: the second one is still out of the screen.
        assertThat(page(6).left).isWithin(1f).of(0f)
        assertThat(page(6).top).isWithin(2f).of(-0.9f * screen.height)
        tap(Forward)
        settle()
        assertThat(page(6).left).isWithin(1f).of(0f)
        assertThat(page(6).bottom).isWithin(1f).of(screen.height)

        tap(Forward)
        settle()

        assertThat(page(7).topLeft.getDistance()).isWithin(1f).of(0f)
        assertThat(recording.calls).isEmpty()
        assertThat(hidden).isEqualTo(3)
    }

    @Test
    fun `at the bottom of the first page, two quick taps forward go to the second page's top, then a step down`() {
        show()
        zoomAt(Offset(0f, screen.height - 1f))
        // The press left the page a pixel from its bottom: a first tap ends there.
        tap(Forward)
        settle()
        assertThat(page(6).left).isWithin(0.1f).of(0f)
        assertThat(page(6).bottom).isWithin(0.1f).of(screen.height)

        tap(Forward)
        run(QuickTapAfter)
        // The passage goes on: the second page is on its way in.
        assertThat(page(7).left).isGreaterThan(2f)
        tap(Forward)
        settle()

        assertThat(page(7).left).isWithin(1f).of(0f)
        assertThat(page(7).top).isWithin(2f).of(-0.9f * screen.height)
        assertThat(recording.calls).isEmpty()
    }

    @Test
    fun `at the end of the second page, two quick taps forward turn once, then walk the next pair's first page`() {
        show()
        zoomAt(Offset(screen.width - 1f, screen.height - 1f))
        // The press left the page a pixel from its end each way: two taps end there.
        repeat(2) {
            tap(Forward)
            settle()
        }
        assertThat(page(7).right).isWithin(0.1f).of(screen.width)
        assertThat(page(7).bottom).isWithin(0.1f).of(screen.height)
        assertThat(recording.calls).isEmpty()

        tap(Forward)
        run(QuickTapAfter)
        tap(Forward)
        settle()

        // The next pair opened on the top of its first page, and went a step down it.
        assertThat(recording.calls).containsExactly("next")
        assertThat(page(8).left).isWithin(1f).of(0f)
        assertThat(page(8).top).isWithin(2f).of(-0.9f * screen.height)
    }

    @Test
    fun `at the top of the second page, two quick taps back go to the first page's bottom, then a step up`() {
        show()
        zoomAt(Offset(screen.width - 1f, 0f))
        assertThat(page(7).topLeft.getDistance()).isWithin(1.5f).of(0f)

        tap(Back)
        run(QuickTapAfter)
        tap(Back)
        settle()

        assertThat(page(6).left).isWithin(1f).of(0f)
        assertThat(page(6).top).isWithin(2f).of(-0.1f * screen.height)
        assertThat(recording.calls).isEmpty()
    }

    @Test
    fun `at the top of the first page, two quick taps back turn back once, then walk the pair before from its end`() {
        show()
        zoomAt(Offset.Zero)

        tap(Back)
        run(QuickTapAfter)
        tap(Back)
        settle()

        // The pair before opened on the bottom of its second page, and went a step up it.
        assertThat(recording.calls).containsExactly("previous")
        assertThat(page(5).right).isWithin(1f).of(screen.width)
        assertThat(page(5).top).isWithin(2f).of(-0.1f * screen.height)
    }

    @Test
    fun `right to left, the page at the right is walked first, then the one at the left from its top`() {
        state = album(spreadIndex = 3, twoPages = true, settings = ComicSettings(direction = Direction.RightToLeft))
        show()
        zoomAt(Offset(screen.width - 1f, 0f))

        // Right to left the forward edge is the left one.
        repeat(2) {
            tap(Back)
            settle()
        }
        assertThat(page(6).right).isWithin(1f).of(screen.width)
        assertThat(page(6).bottom).isWithin(1f).of(screen.height)

        tap(Back)
        settle()

        assertThat(page(7).topLeft.getDistance()).isWithin(1f).of(0f)
        assertThat(recording.calls).isEmpty()
    }

    private fun show() {
        compose.setContent {
            LirelleTheme { ComicSpreadView(state, navigation, onToggleBars = {}, onHideBars = { hidden++ }) }
        }
    }

    /** Where the page of that number (from 1) is drawn. */
    private fun page(number: Int): Rect = compose.onNodeWithContentDescription("Page $number").drawn()

    /** A long press: twice the fit, around the place pressed. */
    private fun zoomAt(at: Offset) {
        compose.longPressAt(at)
        assertThat(page(6).width).isWithin(1f).of(screen.width)
        assertThat(page(6).height).isWithin(1f).of(2 * screen.height)
    }

    /** A tap at [x] of the width, at mid-height: the clock goes no further than the tap lasts. */
    private fun tap(x: Float) =
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput { click(Offset(width * x, height / 2f)) }

    /** Lets the clock go on, frame by frame, the reader showing the spreads asked for when their time comes. */
    private fun run(millis: Long) {
        val until = compose.mainClock.currentTime + millis
        while (compose.mainClock.currentTime < until) {
            compose.mainClock.advanceTimeByFrame()
            val due = coming.map { (frames, turn) -> frames - 1 to turn }
            coming.clear()
            due.forEach { (frames, turn) ->
                if (frames > 0) {
                    coming += frames to turn
                } else {
                    val index = (state.spreadIndex + turn).coerceIn(0, state.spreads.lastIndex)
                    state = state.copy(spreadIndex = index, forward = turn > 0)
                }
            }
        }
        compose.waitForIdle()
    }

    /** Long enough for every slide and every step to end. */
    private fun settle() = run(SettleWait)

    private companion object {
        const val Forward = 0.9f
        const val Back = 0.1f
        const val QuickTapAfter = 80L
        const val FollowAfterFrames = 3
    }
}
