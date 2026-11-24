package app.tfdn.lirelle.feature.comics.page

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A finger that comes down and does not move: lifted soon it is a tap, which acts when it is lifted; kept
 * down it is a long press, which zooms. The clock here only goes on when the test says so. The pages are
 * loading: such a page has the shape of the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadPressTest {

    @get:Rule
    val compose = createComposeRule()

    private val navigation = RecordingNavigation()
    private val haptics = RecordingHaptics()
    private var toggles = 0
    private val screen: Rect get() = compose.viewport()
    private val spread get() = compose.onNodeWithTag(ComicSpreadTag)

    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `a tap in the middle shows the bars when the finger is lifted, and a second one hides them`() {
        show(album(spreadIndex = 5))

        tap(0.5f)
        assertThat(toggles).isEqualTo(1)

        compose.mainClock.advanceTimeBy(QuickTapAfter)
        tap(0.5f)
        assertThat(toggles).isEqualTo(2)
        assertThat(page(6).width).isWithin(1f).of(screen.width)
        assertThat(navigation.calls).isEmpty()
    }

    @Test
    fun `a tap on the forward edge starts the slide when the finger is lifted`() {
        show(album(spreadIndex = 5))

        tap(0.9f)
        repeat(2) { compose.mainClock.advanceTimeByFrame() }

        assertThat(page(6).left).isLessThan(0f)

        compose.mainClock.advanceTimeBy(SettleWait)
        assertThat(navigation.calls).containsExactly("next")
        assertThat(toggles).isEqualTo(0)
    }

    @Test
    fun `past the last page a tap on the forward edge asks at once, and two quick taps ask twice`() {
        show(album(spreadIndex = AlbumPages - 1))

        tap(0.9f)
        assertThat(navigation.calls).containsExactly("next")

        compose.mainClock.advanceTimeBy(QuickTapAfter)
        tap(0.9f)
        assertThat(navigation.calls).containsExactly("next", "next")
    }

    @Test
    fun `two quick taps on an edge turn twice`() {
        show(album(spreadIndex = 5))

        tap(0.1f)
        compose.mainClock.advanceTimeBy(QuickTapAfter)
        tap(0.1f)
        compose.mainClock.advanceTimeBy(SettleWait)

        assertThat(navigation.calls).containsExactly("previous", "previous")
        assertThat(page(6).width).isWithin(1f).of(screen.width)
    }

    @Test
    fun `right to left, the left edge asks at once past the last page, and two quick taps on the right go back`() {
        val settings = ComicSettings(direction = Direction.RightToLeft)
        show(album(spreadIndex = AlbumPages - 1, settings = settings))

        tap(0.1f)
        assertThat(navigation.calls).containsExactly("next")

        tap(0.9f)
        compose.mainClock.advanceTimeBy(QuickTapAfter)
        tap(0.9f)
        compose.mainClock.advanceTimeBy(SettleWait)
        assertThat(navigation.calls).containsExactly("next", "previous", "previous").inOrder()
    }

    @Test
    fun `a long press goes to twice the fit around the place pressed, with a tick, before the finger is lifted`() {
        show(album(spreadIndex = 5))
        val at = Offset(screen.width * 0.5f, screen.height * 0.25f)

        compose.pressLong(at)

        assertThat(page(6).width).isWithin(1f).of(2 * screen.width)
        assertThat(page(6).left).isWithin(1f).of(-0.5f * screen.width)
        assertThat(page(6).top).isWithin(1f).of(-0.25f * screen.height)
        assertThat(haptics.ticks).containsExactly(HapticFeedbackType.LongPress)

        lift()

        // The finger that leaves is not a tap.
        assertThat(toggles).isEqualTo(0)
        assertThat(navigation.calls).isEmpty()
        assertThat(page(6).width).isWithin(1f).of(2 * screen.width)
        assertThat(haptics.ticks).hasSize(1)
    }

    @Test
    fun `a long press on a zoomed page goes back to the fit, with a tick`() {
        show(album(spreadIndex = 5))
        compose.pressLong(screen.center)
        lift()

        compose.pressLong(Offset(screen.width * 0.6f, screen.height * 0.4f))

        assertThat(page(6).width).isWithin(1f).of(screen.width)
        assertThat(page(6).topLeft.getDistance()).isWithin(1f).of(0f)
        assertThat(haptics.ticks).hasSize(2)

        lift()
        assertThat(toggles).isEqualTo(0)
        assertThat(navigation.calls).isEmpty()
    }

    @Test
    fun `a long press on an edge zooms there and does not turn`() {
        show(album(spreadIndex = 5))

        compose.pressLong(Offset(screen.width * 0.9f, screen.height * 0.5f))
        lift()

        assertThat(page(6).width).isWithin(1f).of(2 * screen.width)
        assertThat(page(6).left).isWithin(1f).of(-0.9f * screen.width)
        assertThat(navigation.calls).isEmpty()
        assertThat(toggles).isEqualTo(0)
    }

    @Test
    fun `right to left, a long press on the forward edge zooms there and does not turn`() {
        show(album(spreadIndex = 5, settings = ComicSettings(direction = Direction.RightToLeft)))

        compose.pressLong(Offset(screen.width * 0.1f, screen.height * 0.5f))
        lift()

        assertThat(page(6).width).isWithin(1f).of(2 * screen.width)
        assertThat(page(6).left).isWithin(1f).of(-0.1f * screen.width)
        assertThat(navigation.calls).isEmpty()
    }

    @Test
    fun `a finger lifted just before the long press is a tap`() {
        show(album(spreadIndex = 5))
        var timeout = 0L
        spread.performTouchInput {
            timeout = viewConfiguration.longPressTimeoutMillis
            down(center)
        }

        compose.mainClock.advanceTimeBy(timeout - LongPressMargin)
        assertThat(toggles).isEqualTo(0)
        lift()

        assertThat(toggles).isEqualTo(1)
        compose.mainClock.advanceTimeBy(SettleWait)
        assertThat(page(6).width).isWithin(1f).of(screen.width)
        assertThat(haptics.ticks).isEmpty()
    }

    @Test
    fun `a finger that moved further than a tap may is not a long press, nor a tap`() {
        show(album(spreadIndex = 5))
        spread.performTouchInput {
            down(center)
            moveBy(Offset(0f, 2 * viewConfiguration.touchSlop))
        }

        compose.mainClock.advanceTimeBy(SettleWait)
        lift()

        assertThat(page(6).width).isWithin(1f).of(screen.width)
        assertThat(haptics.ticks).isEmpty()
        assertThat(toggles).isEqualTo(0)
        assertThat(navigation.calls).isEmpty()
    }

    @Test
    fun `a second finger coming down ends the wait for a long press`() {
        show(album(spreadIndex = 5))
        spread.performTouchInput {
            down(0, Offset(width * 0.4f, height / 2f))
            down(1, Offset(width * 0.6f, height / 2f))
        }

        compose.mainClock.advanceTimeBy(SettleWait)
        spread.performTouchInput {
            up(0)
            up(1)
        }

        assertThat(page(6).width).isWithin(1f).of(screen.width)
        assertThat(haptics.ticks).isEmpty()
        assertThat(toggles).isEqualTo(0)
        assertThat(navigation.calls).isEmpty()
    }

    private fun show(state: ComicReaderUiState) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                LirelleTheme { ComicSpreadView(state, navigation, onToggleBars = { toggles++ }) }
            }
        }
    }

    /** Where the page of that number (from 1) is drawn. */
    private fun page(number: Int): Rect = compose.onNodeWithContentDescription("Page $number").drawn()

    /** A tap at [x] of the width, at mid-height: the clock goes no further than the tap lasts. */
    private fun tap(x: Float) = spread.performTouchInput { click(Offset(width * x, height / 2f)) }

    private fun lift() = spread.performTouchInput { up() }

    private companion object {
        /** Well inside the time in which a second tap would once have made a double tap. */
        const val QuickTapAfter = 100L
    }
}
