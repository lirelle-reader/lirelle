package app.tfdn.lirelle.feature.comics.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The zoom and the swipes, with fingers made by the test. The pages here are loading: such a page has the shape
 * of the screen, so the spread at the fit is the screen itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadZoomTest {

    @get:Rule
    val compose = createComposeRule()

    private val navigation = RecordingNavigation()
    private var state by mutableStateOf(album(spreadIndex = 5))
    private val screen: Rect get() = compose.viewport()

    @Test
    fun `a long press goes to twice the fit around the place pressed, and another one back`() {
        show()
        val at = Offset(screen.width * 0.5f, screen.height * 0.25f)

        compose.longPressAt(at)

        val zoomed = page(6)
        assertThat(zoomed.width).isWithin(1f).of(2 * screen.width)
        assertThat(zoomed.height).isWithin(1f).of(2 * screen.height)
        // What was under the finger still is: a quarter down the page.
        assertThat(zoomed.left).isWithin(1f).of(-0.5f * screen.width)
        assertThat(zoomed.top).isWithin(1f).of(-0.25f * screen.height)

        compose.longPressAt(at)

        assertThat(page(6).width).isWithin(1f).of(screen.width)
        assertThat(page(6).topLeft.getDistance()).isWithin(1f).of(0f)
        assertThat(navigation.calls).isEmpty()
    }

    @Test
    fun `the next spread shows at the same zoom, on its top at the side where reading starts`() {
        show()
        compose.longPressAt(screen.center)

        state = album(spreadIndex = 6, forward = true)

        assertThat(page(7).width).isWithin(1f).of(2 * screen.width)
        assertThat(page(7).topLeft.getDistance()).isWithin(1f).of(0f)
    }

    @Test
    fun `right to left, the next spread shows on its top right`() {
        val settings = ComicSettings(direction = Direction.RightToLeft)
        state = album(spreadIndex = 5, settings = settings)
        show()
        compose.longPressAt(screen.center)

        state = album(spreadIndex = 6, settings = settings)

        assertThat(page(7).width).isWithin(1f).of(2 * screen.width)
        assertThat(page(7).right).isWithin(1f).of(screen.width)
        assertThat(page(7).top).isWithin(1f).of(0f)
    }

    @Test
    fun `the spread before shows at the same zoom, on its bottom at the other side`() {
        show()
        compose.longPressAt(screen.center)

        state = album(spreadIndex = 4, forward = false)

        assertThat(page(5).width).isWithin(1f).of(2 * screen.width)
        assertThat(page(5).right).isWithin(1f).of(screen.width)
        assertThat(page(5).bottom).isWithin(1f).of(screen.height)
    }

    @Test
    fun `changing the fit goes back to it`() {
        show()
        compose.longPressAt(screen.center)

        state = album(spreadIndex = 5, settings = ComicSettings(fit = Fit.Width))

        assertThat(page(6).width).isWithin(1f).of(screen.width)
    }

    @Test
    fun `at the fit a swipe turns, as a book's page is pulled`() {
        show()

        swipe(from = 0.8f, to = 0.3f)
        assertThat(navigation.calls).containsExactly("next")

        swipe(from = 0.2f, to = 0.7f)
        assertThat(navigation.calls).containsExactly("next", "previous").inOrder()
    }

    @Test
    fun `right to left, a swipe turns the other way`() {
        state = album(spreadIndex = 5, settings = ComicSettings(direction = Direction.RightToLeft))
        show()

        swipe(from = 0.8f, to = 0.3f)

        assertThat(navigation.calls).containsExactly("previous")
    }

    @Test
    fun `zoomed, a swipe moves the page, and turns only once it started from the page's edge`() {
        show()
        compose.longPressAt(screen.center)
        val middle = page(6).left
        assertThat(middle).isWithin(1f).of(-0.5f * screen.width)

        swipe(from = 0.6f, to = 0.4f)
        assertThat(page(6).left).isLessThan(middle - 10f)
        assertThat(page(6).left).isGreaterThan(-screen.width + 10f)

        // Further than what is left of the page: it stops at its edge and does not turn.
        swipe(from = 0.9f, to = 0.1f)
        assertThat(page(6).right).isWithin(1f).of(screen.width)
        assertThat(navigation.calls).isEmpty()

        swipe(from = 0.9f, to = 0.1f)
        assertThat(navigation.calls).containsExactly("next")
    }

    @Test
    fun `a pinch zooms, up to four times the fit and never under it`() {
        show()
        val y = screen.height / 2

        pinch(fromApart = 0.1f, toApart = 0.3f, y)
        val zoomed = page(6).width
        assertThat(zoomed).isGreaterThan(1.5f * screen.width)
        assertThat(zoomed).isLessThan(4f * screen.width)

        pinch(fromApart = 0.05f, toApart = 0.9f, y)
        assertThat(page(6).width).isWithin(1f).of(4 * screen.width)

        repeat(3) { pinch(fromApart = 0.9f, toApart = 0.05f, y) }
        assertThat(page(6).width).isWithin(1f).of(screen.width)
        assertThat(page(6).left).isWithin(1f).of(0f)
        assertThat(navigation.calls).isEmpty()
    }

    private fun show() {
        compose.setContent {
            LirelleTheme { ComicSpreadView(state, navigation, onToggleBars = {}) }
        }
    }

    /** Where the page of that number (from 1) is drawn. */
    private fun page(number: Int): Rect = compose.onNodeWithContentDescription("Page $number").drawn()

    /** A finger across the screen at mid-height, [from] one share of the width [to] another. */
    private fun swipe(from: Float, to: Float) {
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            swipe(Offset(width * from, height / 2f), Offset(width * to, height / 2f), durationMillis = 200)
        }
        compose.mainClock.advanceTimeBy(SettleWait)
        compose.waitForIdle()
    }

    /** Two fingers around the middle of the width, their distance going from one share of the width to another. */
    private fun pinch(fromApart: Float, toApart: Float, y: Float) {
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            pinch(
                start0 = Offset(centerX - width * fromApart / 2, y),
                end0 = Offset(centerX - width * toApart / 2, y),
                start1 = Offset(centerX + width * fromApart / 2, y),
                end1 = Offset(centerX + width * toApart / 2, y),
            )
        }
        compose.waitForIdle()
    }
}
