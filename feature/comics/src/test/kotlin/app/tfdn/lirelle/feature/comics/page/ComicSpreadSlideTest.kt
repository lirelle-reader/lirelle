package app.tfdn.lirelle.feature.comics.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.ComicNavigation
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The slide from a spread to the one beside it, with fingers made by the test. The reader here takes the turns
 * it is asked for, as the real one does. But where they are said to be on the device, the pages are loading:
 * such a page has the shape of the screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadSlideTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val recording = RecordingNavigation()
    private var state by mutableStateOf(album(spreadIndex = 5))

    /** Whether the reader takes the turns: it does not while it is not ready. */
    private var follows = true

    private val navigation = object : ComicNavigation by recording {
        override fun next() {
            recording.next()
            if (follows && state.spreadIndex < state.spreads.lastIndex) {
                state = state.copy(spreadIndex = state.spreadIndex + 1, forward = true)
            }
        }

        override fun previous() {
            recording.previous()
            if (follows && state.spreadIndex > 0) {
                state = state.copy(spreadIndex = state.spreadIndex - 1, forward = false)
            }
        }
    }
    private val screen: Rect get() = compose.viewport()
    private val slop: Float by lazy {
        var slop = 0f
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput { slop = viewConfiguration.touchSlop }
        slop
    }

    @Test
    fun `the page follows the finger before it is let go, the next one coming in beside it`() {
        show()

        pull(from = 0.8f, by = -0.3f)

        assertThat(page(6).left).isWithin(slop + 1f).of(-0.3f * screen.width)
        val next = compose.onNodeWithTag(ComicNextSpreadTag).drawn()
        assertThat(next.left).isWithin(1f).of(page(6).right + 16f * compose.density.density)
        assertThat(next.left).isLessThan(screen.width)
        assertThat(next.width).isWithin(1f).of(screen.width)
        assertThat(recording.calls).isEmpty()
    }

    @Test
    fun `the pages of both spreads are drawn while the finger holds them`() {
        val red = pageImage(folder.root, 5, width = 411, height = 891, colour = android.graphics.Color.RED)
        val blue = pageImage(folder.root, 6, width = 411, height = 891, colour = android.graphics.Color.BLUE)
        val white = ComicSettings(background = Background.White)
        state = album(spreadIndex = 5, settings = white, pages = mapOf(5 to red, 6 to blue))
        show()

        pull(from = 0.8f, by = -0.5f)

        val pixels = compose.onNodeWithTag(ComicSpreadTag).captureToImage().toPixelMap()
        val leaving = pixels[pixels.width / 4, pixels.height / 2]
        val coming = pixels[pixels.width * 3 / 4, pixels.height / 2]
        assertThat(leaving.red).isGreaterThan(0.8f)
        assertThat(leaving.blue).isLessThan(0.2f)
        assertThat(coming.blue).isGreaterThan(0.8f)
        assertThat(coming.red).isLessThan(0.2f)
    }

    @Test
    fun `let go past a third of the width, the slide goes on and the reader is asked for the next spread once`() {
        show()

        pull(from = 0.8f, by = -0.4f)
        letGo()

        assertThat(recording.calls).containsExactly("next")
        assertThat(page(7).left).isWithin(1f).of(0f)
        assertThat(compose.onNodeWithTag(ComicPreviousSpreadTag).drawn().right).isLessThan(0f)
    }

    @Test
    fun `let go before a third of the width, the page comes back and nothing is asked`() {
        show()

        pull(from = 0.8f, by = -0.25f)
        letGo()

        assertThat(recording.calls).isEmpty()
        assertThat(page(6).left).isWithin(1f).of(0f)
    }

    @Test
    fun `a short fling completes the slide`() {
        show()

        fling(from = 0.6f, to = 0.45f)

        assertThat(recording.calls).containsExactly("next")
        assertThat(page(7).left).isWithin(1f).of(0f)
    }

    @Test
    fun `pulled the other way, the spread before comes in from the left`() {
        show()

        pull(from = 0.2f, by = 0.4f)

        val previous = compose.onNodeWithTag(ComicPreviousSpreadTag).drawn()
        assertThat(previous.right).isWithin(1f).of(page(6).left - 16f * compose.density.density)
        assertThat(previous.right).isGreaterThan(0f)

        letGo()

        assertThat(recording.calls).containsExactly("previous")
        assertThat(page(5).left).isWithin(1f).of(0f)
    }

    @Test
    fun `right to left, the next spread comes in from the left`() {
        state = album(spreadIndex = 5, settings = ComicSettings(direction = Direction.RightToLeft))
        show()

        pull(from = 0.2f, by = 0.4f)

        assertThat(compose.onNodeWithTag(ComicNextSpreadTag).drawn().right).isGreaterThan(0f)
        assertThat(compose.onNodeWithTag(ComicNextSpreadTag).drawn().right).isLessThan(page(6).left)

        letGo()

        assertThat(recording.calls).containsExactly("next")
        assertThat(page(7).left).isWithin(1f).of(0f)
    }

    @Test
    fun `right to left, a fling to the left goes back`() {
        state = album(spreadIndex = 5, settings = ComicSettings(direction = Direction.RightToLeft))
        show()

        fling(from = 0.6f, to = 0.45f)

        assertThat(recording.calls).containsExactly("previous")
        assertThat(page(5).left).isWithin(1f).of(0f)
    }

    @Test
    fun `the first spread resists a pull back, shows nothing beside it and comes back`() {
        state = album(spreadIndex = 0)
        show()

        pull(from = 0.2f, by = 0.6f)

        assertThat(page(1).left).isWithin(slop / 3 + 1f).of(0.2f * screen.width)
        compose.onAllNodesWithTag(ComicPreviousSpreadTag).assertCountEquals(0)

        letGo()

        assertThat(recording.calls).isEmpty()
        assertThat(page(1).left).isWithin(1f).of(0f)
    }

    @Test
    fun `the last spread resists a pull forward, and a pull long enough asks for what is past it`() {
        state = album(spreadIndex = AlbumPages - 1)
        show()

        pull(from = 0.9f, by = -0.2f)
        letGo()
        assertThat(recording.calls).isEmpty()

        pull(from = 0.9f, by = -0.6f)
        assertThat(page(AlbumPages).left).isWithin(slop / 3 + 1f).of(-0.2f * screen.width)
        letGo()

        assertThat(recording.calls).containsExactly("next")
        assertThat(page(AlbumPages).left).isWithin(1f).of(0f)
    }

    @Test
    fun `a tap on an edge slides to the next spread, and asks for it once the slide is over`() {
        show()
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag(ComicSpreadTag).performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        var frames = 0
        while (page(6).left > -0.2f * screen.width && frames++ < 60) compose.mainClock.advanceTimeByFrame()

        // On its way: the page left its place, the next one is coming in, and the reader was not asked yet.
        assertThat(page(6).left).isLessThan(-0.2f * screen.width)
        assertThat(page(6).left).isGreaterThan(-screen.width)
        assertThat(compose.onNodeWithTag(ComicNextSpreadTag).drawn().left).isLessThan(screen.width)
        assertThat(recording.calls).isEmpty()

        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(SettleWait)
        compose.waitForIdle()

        assertThat(recording.calls).containsExactly("next")
        assertThat(page(7).left).isWithin(1f).of(0f)
    }

    @Test
    fun `two taps in a row turn twice`() {
        show()
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag(ComicSpreadTag).performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        // The second one comes while the first slide is on its way.
        compose.mainClock.advanceTimeBy(SecondTapAfter)
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(SettleWait)
        compose.waitForIdle()

        assertThat(recording.calls).containsExactly("next", "next").inOrder()
        assertThat(page(8).left).isWithin(1f).of(0f)
    }

    @Test
    fun `a fling during the slide of another ends that one at once, and each turn is asked once`() {
        show()
        compose.mainClock.autoAdvance = false

        repeat(3) {
            compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
                swipe(Offset(width * 0.6f, height / 2f), Offset(width * 0.45f, height / 2f), durationMillis = 60)
            }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(SettleWait)
        compose.waitForIdle()

        assertThat(recording.calls).containsExactly("next", "next", "next").inOrder()
        assertThat(page(9).left).isWithin(1f).of(0f)
    }

    @Test
    fun `a turn the reader does not take leaves the page shown in its place`() {
        follows = false
        show()

        fling(from = 0.6f, to = 0.45f)
        fling(from = 0.6f, to = 0.45f)

        assertThat(recording.calls).containsExactly("next", "next").inOrder()
        assertThat(page(6).left).isWithin(1f).of(0f)
    }

    @Test
    fun `a spread shown for another reason is in its place at once`() {
        show()
        pull(from = 0.8f, by = -0.25f)

        state = album(spreadIndex = 20)
        compose.waitForIdle()

        assertThat(page(21).left).isWithin(1f).of(0f)
        assertThat(recording.calls).isEmpty()
    }

    private fun show() {
        compose.setContent {
            images.Provide {
                LirelleTheme { ComicSpreadView(state, navigation, onToggleBars = {}) }
            }
        }
    }

    /** Where the page of that number (from 1) is drawn. */
    private fun page(number: Int): Rect = compose.onNodeWithContentDescription("Page $number").drawn()

    /** A finger comes down at [from] (a share of the width), moves slowly [by] a share of it, and stays down. */
    private fun pull(from: Float, by: Float) {
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            down(Offset(width * from, height / 2f))
            repeat(PullMoves) { moveBy(Offset(width * by / PullMoves, 0f)) }
        }
        compose.waitForIdle()
    }

    /** The finger that stayed down leaves, without a move: it did not fling. */
    private fun letGo() {
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            advanceEventTime(Still)
            up()
        }
        compose.mainClock.advanceTimeBy(SettleWait)
        compose.waitForIdle()
    }

    /** A quick finger across a small part of the width. */
    private fun fling(from: Float, to: Float) {
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            swipe(Offset(width * from, height / 2f), Offset(width * to, height / 2f), durationMillis = 60)
        }
        compose.mainClock.advanceTimeBy(SettleWait)
        compose.waitForIdle()
    }

    private companion object {
        const val PullMoves = 10
        const val Still = 300L
        const val SecondTapAfter = 100L
    }
}
