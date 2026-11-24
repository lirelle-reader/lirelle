package app.tfdn.lirelle.feature.comics.page

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
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
 * The finger that stays down after a long press holds the page: moving it moves the page as far as the page is
 * larger than the screen, and nothing else. The clock here only goes on when the test says so. A page that
 * loads has the shape of the screen: at the fit it is the screen itself.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadHeldTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val navigation = RecordingNavigation()
    private val haptics = RecordingHaptics()
    private var toggles = 0
    private var hidden = 0
    private val screen: Rect get() = compose.viewport()
    private val spread get() = compose.onNodeWithTag(ComicSpreadTag)

    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `after a long press the finger still down moves the page, and lifting it does nothing else`() {
        show(album(spreadIndex = 5))
        compose.pressLong(screen.center)
        val pressed = page(6)
        assertThat(pressed.width).isWithin(1f).of(2 * screen.width)
        assertThat(pressed.left).isWithin(1f).of(-0.5f * screen.width)

        move(Offset(60f, -80f))

        assertThat(page(6).left).isWithin(1f).of(pressed.left + 60f)
        assertThat(page(6).top).isWithin(1f).of(pressed.top - 80f)

        move(Offset(-30f, 50f))

        assertThat(page(6).left).isWithin(1f).of(pressed.left + 30f)
        assertThat(page(6).top).isWithin(1f).of(pressed.top - 30f)

        lift()
        compose.mainClock.advanceTimeBy(SettleWait)

        assertThat(page(6).left).isWithin(1f).of(pressed.left + 30f)
        assertThat(page(6).width).isWithin(1f).of(2 * screen.width)
        assertNothingElse()
    }

    @Test
    fun `a finger that moves less than a tap may after a long press does not move the page`() {
        show(album(spreadIndex = 5))
        compose.pressLong(screen.center)
        val pressed = page(6)
        var slop = 0f
        spread.performTouchInput { slop = viewConfiguration.touchSlop }

        move(Offset(slop / 2, 0f))

        assertThat(page(6)).isEqualTo(pressed)
        lift()
        assertNothingElse()
    }

    @Test
    fun `the page held stops at its edges, and never turns nor slides to another spread`() {
        show(album(spreadIndex = 5))
        compose.pressLong(screen.center)

        // Far past what is left of the page at its right, then on: the spread after does not come in.
        repeat(3) { move(Offset(-0.4f * screen.width, 0f)) }
        assertThat(page(6).right).isWithin(1f).of(screen.width)
        assertThat(page(6).top).isWithin(1f).of(-0.5f * screen.height)

        // The other way to its left edge, then on: the spread before does not come in either.
        repeat(5) { move(Offset(0.4f * screen.width, 0.4f * screen.height)) }
        assertThat(page(6).topLeft.getDistance()).isWithin(1f).of(0f)

        lift()
        compose.mainClock.advanceTimeBy(SettleWait)

        assertThat(page(6).topLeft.getDistance()).isWithin(1f).of(0f)
        assertNothingElse()
    }

    @Test
    fun `a long press at the edge of the page, then a pull towards the spread beside it, slides nothing`() {
        show(album(spreadIndex = 5))

        // Zoomed around its left edge the page stays at it: a finger that came down there would slide.
        compose.pressLong(Offset(0f, screen.height / 2))
        assertThat(page(6).left).isWithin(1f).of(0f)
        repeat(3) { move(Offset(0.3f * screen.width, 0f)) }

        assertThat(page(6).left).isWithin(1f).of(0f)
        lift()
        compose.mainClock.advanceTimeBy(SettleWait)
        assertThat(page(6).left).isWithin(1f).of(0f)
        assertNothingElse()
    }

    @Test
    fun `after a long press back to the fit to the width, the page held moves up and down only`() {
        val tall = pageImage(folder.root, 5, width = 400, height = 1200)
        show(album(spreadIndex = 5, settings = ComicSettings(fit = Fit.Width), pages = mapOf(5 to tall)))
        compose.longPressAt(Offset.Zero)
        assertThat(page(6).width).isWithin(1f).of(2 * screen.width)

        compose.pressLong(screen.center)
        assertThat(page(6).width).isWithin(1f).of(screen.width)
        assertThat(page(6).height).isGreaterThan(screen.height + 200f)
        assertThat(page(6).topLeft.getDistance()).isWithin(1f).of(0f)

        move(Offset(-60f, -80f))

        assertThat(page(6).left).isWithin(1f).of(0f)
        assertThat(page(6).top).isWithin(1f).of(-80f)

        // No further than its bottom, nor than its top.
        repeat(3) { move(Offset(0f, -0.4f * screen.height)) }
        assertThat(page(6).bottom).isWithin(1f).of(screen.height)
        repeat(3) { move(Offset(60f, 0.4f * screen.height)) }
        assertThat(page(6).topLeft.getDistance()).isWithin(1f).of(0f)

        lift()
        compose.mainClock.advanceTimeBy(SettleWait)
        assertThat(haptics.ticks).hasSize(2)
        assertThat(navigation.calls).isEmpty()
        assertThat(toggles).isEqualTo(0)
    }

    @Test
    fun `after a long press back to the whole page, nothing is larger than the screen and nothing moves`() {
        show(album(spreadIndex = 5))
        compose.longPressAt(screen.center)

        compose.pressLong(screen.center)
        assertThat(page(6).width).isWithin(1f).of(screen.width)
        repeat(3) { move(Offset(-0.2f * screen.width, 40f)) }

        assertThat(page(6).topLeft.getDistance()).isWithin(1f).of(0f)

        lift()
        compose.mainClock.advanceTimeBy(SettleWait)
        assertThat(page(6).topLeft.getDistance()).isWithin(1f).of(0f)
        assertThat(haptics.ticks).hasSize(2)
        assertThat(navigation.calls).isEmpty()
        assertThat(toggles).isEqualTo(0)
    }

    @Test
    fun `a second finger coming down after a long press pinches, and the gesture ends when both are up`() {
        show(album(spreadIndex = 5))
        compose.pressLong(screen.center)
        move(Offset(20f, 20f))

        spread.performTouchInput {
            down(1, Offset(centerX + width * 0.1f, centerY + 20f))
            repeat(4) {
                moveBy(0, Offset(-width * 0.05f, 0f))
                moveBy(1, Offset(width * 0.05f, 0f))
            }
        }

        assertThat(page(6).width).isGreaterThan(2.5f * screen.width)
        val pinched = page(6)

        // One finger left: it still moves the page; then none.
        spread.performTouchInput {
            up(1)
            moveBy(0, Offset(0f, 30f))
        }
        assertThat(page(6).width).isWithin(1f).of(pinched.width)
        assertThat(page(6).top).isWithin(1f).of(pinched.top + 30f)
        lift()
        compose.mainClock.advanceTimeBy(SettleWait)

        assertThat(page(6).width).isWithin(1f).of(pinched.width)
        assertNothingElse()
    }

    @Test
    fun `a finger that moves before the time of a long press still slides to the next spread`() {
        show(album(spreadIndex = 5))
        var timeout = 0L
        spread.performTouchInput {
            timeout = viewConfiguration.longPressTimeoutMillis
            down(center)
        }
        compose.mainClock.advanceTimeBy(timeout - LongPressMargin)

        repeat(4) { move(Offset(-0.15f * screen.width, 0f)) }
        assertThat(page(6).left).isLessThan(-0.4f * screen.width)
        assertThat(page(6).width).isWithin(1f).of(screen.width)
        lift()
        compose.mainClock.advanceTimeBy(SettleWait)

        assertThat(navigation.calls).containsExactly("next")
        assertThat(haptics.ticks).isEmpty()
        assertThat(toggles).isEqualTo(0)
    }

    @Test
    fun `a finger that moves before the time of a long press still moves a zoomed page`() {
        show(album(spreadIndex = 5))
        compose.longPressAt(screen.center)
        val zoomed = page(6)
        var timeout = 0L
        spread.performTouchInput {
            timeout = viewConfiguration.longPressTimeoutMillis
            down(center)
        }
        compose.mainClock.advanceTimeBy(timeout - LongPressMargin)

        move(Offset(0f, 60f))
        compose.mainClock.advanceTimeBy(SettleWait)
        lift()

        assertThat(page(6).top).isWithin(1f).of(zoomed.top + 60f)
        assertThat(page(6).width).isWithin(1f).of(2 * screen.width)
        assertThat(haptics.ticks).hasSize(1)
        assertThat(navigation.calls).isEmpty()
    }

    private fun show(state: ComicReaderUiState) {
        compose.setContent {
            images.Provide {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    LirelleTheme {
                        ComicSpreadView(state, navigation, onToggleBars = { toggles++ }, onHideBars = { hidden++ })
                    }
                }
            }
        }
    }

    /** One tick, that of the long press, and nothing asked of the reader nor of its bars. */
    private fun assertNothingElse() {
        assertThat(haptics.ticks).containsExactly(HapticFeedbackType.LongPress)
        assertThat(navigation.calls).isEmpty()
        assertThat(toggles).isEqualTo(0)
        assertThat(hidden).isEqualTo(0)
    }

    /** Where the page of that number (from 1) is drawn. */
    private fun page(number: Int): Rect = compose.onNodeWithContentDescription("Page $number").drawn()

    /** The finger that is down moves [by] that much, at once. */
    private fun move(by: Offset) {
        spread.performTouchInput { moveBy(by) }
        compose.waitForIdle()
    }

    private fun lift() = spread.performTouchInput { up() }
}
