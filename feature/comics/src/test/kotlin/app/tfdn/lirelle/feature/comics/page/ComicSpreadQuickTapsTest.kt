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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Taps on an edge that follow one another faster than the page moves: each one is taken from where the one
 * before leads to. The clock only goes on when the test says so, and the reader here shows the spread it was
 * asked for a few frames later, as the real one does. The pages are on the device, half as wide as they are
 * high: zoomed twice, they are two screens wide and nearly two screens high.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadQuickTapsTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val recording = RecordingNavigation()
    private var state by mutableStateOf(album(spreadIndex = 5))

    /** The turns asked that the reader does not show yet, each with the frames left before it does. */
    private val coming = mutableListOf<Pair<Int, Int>>()

    /** Whether the reader takes the turns: it does not while it is not ready. */
    private var follows = true

    private val navigation = object : ComicNavigation by recording {
        override fun next() {
            recording.next()
            if (follows) coming += FollowAfterFrames to 1
        }

        override fun previous() {
            recording.previous()
            if (follows) coming += FollowAfterFrames to -1
        }
    }
    private val screen: Rect get() = compose.viewport()

    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `at the end of a zoomed page, two quick taps forward turn once, then walk the next page by a step`() {
        show()
        zoomAt(Offset(screen.width - 1f, screen.height - 1f))
        tap(Forward)
        settle()
        assertThat(page(6).right).isWithin(1f).of(screen.width)
        assertThat(page(6).bottom).isWithin(1f).of(screen.height)
        assertThat(recording.calls).isEmpty()

        tap(Forward)
        run(QuickTapAfter)
        tap(Forward)
        settle()

        assertThat(recording.calls).containsExactly("next")
        assertThat(page(7).left).isWithin(2f).of(-0.9f * screen.width)
        assertThat(page(7).top).isWithin(2f).of(0f)
    }

    @Test
    fun `from the top of a zoomed page, three quick taps forward are three steps of the walk, and no turn`() {
        show()
        zoomAt(Offset(1f, 1f))

        tap(Forward)
        run(QuickTapAfter)
        // The first step goes on: the page is on its way.
        assertThat(page(6).left).isLessThan(-2f)
        assertThat(page(6).left).isGreaterThan(-0.9f * screen.width + 2f)
        tap(Forward)
        run(QuickTapAfter)
        tap(Forward)
        settle()

        // Most of a screen, then the end of the row, then the row below from its start.
        assertThat(page(6).left).isWithin(2f).of(0f)
        assertThat(page(6).bottom).isWithin(2f).of(screen.height)
        assertThat(recording.calls).isEmpty()
    }

    @Test
    fun `quick taps never move the page the other way`() {
        show()
        zoomAt(Offset(1f, 1f))
        repeat(2) {
            tap(Forward)
            settle()
        }
        assertThat(page(6).right).isWithin(1f).of(screen.width)

        // To the row below, and on along it before that move ended.
        tap(Forward)
        run(QuickTapAfter)
        tap(Forward)
        settle()

        assertThat(page(6).left).isWithin(2f).of(-0.9f * screen.width)
        assertThat(page(6).bottom).isWithin(2f).of(screen.height)
        assertThat(recording.calls).isEmpty()
    }

    @Test
    fun `at the top of a zoomed page, two quick taps back turn back once, then walk the page before by a step`() {
        show()
        zoomAt(Offset(1f, 1f))
        tap(Back)
        settle()
        assertThat(page(6).topLeft.getDistance()).isWithin(1f).of(0f)
        assertThat(recording.calls).isEmpty()

        tap(Back)
        run(QuickTapAfter)
        tap(Back)
        settle()

        // The page before opened on its bottom right, and went a step towards the start of its last row.
        assertThat(recording.calls).containsExactly("previous")
        assertThat(page(5).right).isWithin(2f).of(1.9f * screen.width)
        assertThat(page(5).bottom).isWithin(2f).of(screen.height)
    }

    @Test
    fun `right to left, two quick taps forward at the end of a zoomed page turn once, then walk the next page`() {
        state = album(spreadIndex = 5, settings = ComicSettings(direction = Direction.RightToLeft), pages = pages())
        show()
        zoomAt(Offset(1f, screen.height - 1f))
        tap(Back)
        settle()
        assertThat(page(6).left).isWithin(1f).of(0f)
        assertThat(page(6).bottom).isWithin(1f).of(screen.height)
        assertThat(recording.calls).isEmpty()

        // Right to left the forward edge is the left one, and the next page opens on its top right.
        tap(Back)
        run(QuickTapAfter)
        tap(Back)
        settle()

        assertThat(recording.calls).containsExactly("next")
        assertThat(page(7).left).isWithin(2f).of(-0.1f * screen.width)
        assertThat(page(7).top).isWithin(2f).of(0f)
    }

    @Test
    fun `a page that is not zoomed has nothing to walk - two quick taps turn twice`() {
        show()

        tap(Forward)
        run(QuickTapAfter)
        tap(Forward)
        settle()

        assertThat(recording.calls).containsExactly("next", "next")
        assertThat(page(8).left).isWithin(1f).of(0f)
        assertThat(page(8).width).isWithin(1f).of(screen.width)
    }

    @Test
    fun `a step taken on a page the reader then does not show is forgotten`() {
        show()
        zoomAt(Offset(screen.width - 1f, screen.height - 1f))
        tap(Forward)
        settle()

        // The reader takes no turn: the page shown stays, and the step meant for the next one is lost.
        follows = false
        tap(Forward)
        run(QuickTapAfter)
        tap(Forward)
        settle()
        follows = true
        assertThat(recording.calls).containsExactly("next")
        assertThat(page(6).right).isWithin(1f).of(screen.width)

        // Later it takes one: the next page opens on its top left.
        tap(Forward)
        settle()

        assertThat(recording.calls).containsExactly("next", "next")
        assertThat(page(7).topLeft.getDistance()).isWithin(2f).of(0f)
    }

    private fun show() {
        if (state.pages.isEmpty()) state = state.copy(pages = pages())
        compose.setContent {
            images.Provide {
                LirelleTheme { ComicSpreadView(state, navigation, onToggleBars = {}) }
            }
        }
    }

    /** The pages around the one shown, on the device. */
    private fun pages() = (3..8).associateWith { pageImage(folder.root, it, width = 400, height = 800) }

    /** Where the page of that number (from 1) is drawn. */
    private fun page(number: Int): Rect = compose.onNodeWithContentDescription("Page $number").drawn()

    /** A long press: twice the fit, around the place pressed. */
    private fun zoomAt(at: Offset) {
        compose.longPressAt(at)
        assertThat(page(6).width).isWithin(1f).of(2 * screen.width)
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
