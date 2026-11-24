package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.WholePage
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.feature.comics.page.PanelReader.Companion.Back
import app.tfdn.lirelle.feature.comics.page.PanelReader.Companion.Forward
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A series read panel by panel, under fingers that move: a swipe is a step, and a hand that moves or zooms the
 * page leaves the stop, the next step going on from it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadPanelHandsTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val reader = PanelReader(compose, images)
    private val screen get() = reader.screen

    @Test
    fun `a finger going sideways moves nothing, and is a step forward when lifted`() {
        reader.show()

        pull(from = 0.8f, by = Offset(-0.3f, 0f))

        reader.assertWhole(6)
        assertThat(compose.onNodeWithTag(ComicNextSpreadTag).drawn().left).isAtLeast(screen.width)

        letGo()

        reader.assertAtStop(6, index = 0)
        assertThat(reader.recording.calls).isEmpty()
        assertThat(reader.hides).isEqualTo(1)
    }

    @Test
    fun `at a stop a swipe goes to the next one, the other way to the one before, and nothing slides`() {
        reader.show()
        compose.tapAt(Forward)

        pull(from = 0.8f, by = Offset(-0.3f, 0f))
        reader.assertAtStop(6, index = 0)
        letGo()
        reader.assertAtStop(6, index = 1)

        swipe(from = 0.3f, to = 0.7f)
        reader.assertAtStop(6, index = 0)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `a swipe after the last stop turns, and a swipe back at the whole page turns back`() {
        reader.show()
        repeat(3) { compose.tapAt(Forward) }

        swipe(from = 0.8f, to = 0.3f)
        assertThat(reader.recording.calls).containsExactly("next")
        reader.assertWhole(7)

        swipe(from = 0.3f, to = 0.8f)
        assertThat(reader.recording.calls).containsExactly("next", "previous").inOrder()
        reader.assertAtStop(6, index = 2)
    }

    @Test
    fun `right to left, a finger going right steps forward`() {
        val settings = ComicSettings(direction = Direction.RightToLeft)
        reader.show(album(spreadIndex = 5, settings = settings).copy(panels = readPanels(5)))
        val first = stopPlace(reader.stops(Direction.RightToLeft).first(), screen.size)

        swipe(from = 0.3f, to = 0.7f)

        assertThat(reader.page(6).left).isWithin(StopSlack).of(first.offset.x)
        assertThat(reader.page(6).top).isWithin(StopSlack).of(first.offset.y)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `a finger that comes back to where it came down is no step`() {
        reader.show()

        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            down(Offset(width * 0.6f, height / 2f))
            repeat(PullMoves) { moveBy(Offset(-width * 0.2f / PullMoves, 0f)) }
            repeat(PullMoves) { moveBy(Offset(width * 0.2f / PullMoves, 0f)) }
        }
        letGo()

        reader.assertWhole(6)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `a finger that ends upright, hardly to the side of where it came down, is no step`() {
        reader.show()

        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            down(Offset(width * 0.6f, height / 2f))
            repeat(PullMoves) { moveBy(Offset(-width * 0.2f / PullMoves, 0f)) }
            repeat(PullMoves) { moveBy(Offset((width * 0.2f - Hair) / PullMoves, -height * 0.2f / PullMoves)) }
        }
        letGo()

        reader.assertWhole(6)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `a step that waits is not done under a finger that slides the page`() {
        reader.show(reader.state.copy(panels = readPanels()))
        compose.tapAt(Forward)

        pull(from = 0.8f, by = Offset(-0.3f, 0f))
        reader.state = reader.state.copy(panels = readPanels(5))
        compose.waitForIdle()

        assertThat(reader.page(6).width).isWithin(StopSlack).of(screen.width)
        assertThat(reader.page(6).left).isLessThan(-0.2f * screen.width)

        // Nor once the finger is gone: a hand moved the page, nothing waits any more.
        letGo()
        reader.assertWhole(6)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `a page that was to open at its first stop stays whole under a finger that slides it`() {
        reader.show(reader.state.copy(panels = readPanels(wholePageFirst = false)))

        pull(from = 0.8f, by = Offset(-0.3f, 0f))
        reader.state = reader.state.copy(panels = readPanels(5, wholePageFirst = false))
        compose.waitForIdle()

        assertThat(reader.page(6).width).isWithin(StopSlack).of(screen.width)
        assertThat(reader.page(6).left).isLessThan(-0.2f * screen.width)

        letGo()
        reader.assertWhole(6)
    }

    @Test
    fun `a page with no panel is slid by a finger going sideways, and turned by a tap, as without the mode`() {
        val notCut = readPanels().copy(zones = mapOf(5 to PageZones.Whole(WholePage.NoPanels)))
        reader.show(reader.state.copy(panels = notCut))

        pull(from = 0.8f, by = Offset(-0.3f, 0f))

        // Under the finger: as far as it went, the next page coming in beside it.
        assertThat(reader.page(6).left).isWithin(screen.width * 0.05f).of(-0.3f * screen.width)
        assertThat(compose.onNodeWithTag(ComicNextSpreadTag).drawn().left).isLessThan(screen.width)

        letGo()
        reader.assertWhole(6)
        assertThat(reader.recording.calls).isEmpty()

        compose.tapAt(Forward)
        assertThat(reader.recording.calls).containsExactly("next")
        reader.assertWhole(7)
    }

    @Test
    fun `zones not known, a finger going sideways slides the page as ever`() {
        reader.show(reader.state.copy(panels = readPanels()))

        pull(from = 0.8f, by = Offset(-0.3f, 0f))

        assertThat(reader.page(6).left).isLessThan(-0.2f * screen.width)
        assertThat(compose.onNodeWithTag(ComicNextSpreadTag).drawn().left).isLessThan(screen.width)
    }

    @Test
    fun `a finger going upright at a stop moves the page, which is then within its edges and at no stop`() {
        reader.show()
        compose.tapAt(Forward)
        val framed = reader.page(6)

        pull(from = 0.5f, by = Offset(0f, -0.1f))
        letGo()

        val moved = reader.page(6)
        assertThat(moved.top).isLessThan(framed.top - 0.05f * screen.height)
        assertThat(moved.width).isWithin(StopSlack).of(framed.width)
        assertThat(moved.left).isAtMost(0f)
        assertThat(moved.right).isAtLeast(screen.width)
        assertThat(reader.recording.calls).isEmpty()

        // The reader goes on from the stop left: forward to the one after it.
        compose.tapAt(Forward)
        reader.assertAtStop(6, index = 1)
    }

    @Test
    fun `after a finger moved the page, a step back goes to the stop it left`() {
        reader.show()
        repeat(2) { compose.tapAt(Forward) }

        pull(from = 0.5f, by = Offset(0f, -0.1f))
        letGo()
        compose.tapAt(Back)

        reader.assertAtStop(6, index = 1)
    }

    @Test
    fun `a pinch leaves the stop for a zoom the reader allows, and the next step goes to the next stop`() {
        reader.show()
        compose.tapAt(Forward)
        val framed = reader.page(6)

        pinch(fromApart = 0.6f, toApart = 0.3f)

        val pinched = reader.page(6)
        assertThat(pinched.width).isLessThan(framed.width - 10f)
        assertThat(pinched.width).isAtLeast(screen.width - StopSlack)
        assertThat(pinched.left).isAtMost(StopSlack)
        assertThat(pinched.right).isAtLeast(screen.width - StopSlack)

        compose.tapAt(Forward)
        reader.assertAtStop(6, index = 1)
    }

    @Test
    fun `a long press at a stop goes back to the fit as on any zoomed page, and the next step to the next stop`() {
        reader.show()
        compose.tapAt(Forward)

        compose.longPressAt(screen.center)

        reader.assertWhole(6)
        compose.tapAt(Forward)
        reader.assertAtStop(6, index = 1)
    }

    @Test
    fun `a stop higher than the screen is shown under the fit, and a hand that takes it is back at the fit`() {
        // Five times as high as it is wide: fitted to the width, its panel is higher than the screen.
        val tall = pageImage(folder.root, index = 5, width = 100, height = 500)
        val high = PageZones.Cut(listOf(Zone(0.1f, 0.1f, 0.9f, 0.6f, ZoneKind.Filled)), millis = 0)
        val settings = ComicSettings(fit = Fit.Width)
        val panels = readPanels().copy(zones = mapOf(5 to high))
        reader.show(album(spreadIndex = 5, settings = settings, pages = mapOf(5 to tall)).copy(panels = panels))
        val room = Rect(Offset.Zero, reader.page(6).size)
        val stop = checkNotNull(spreadStops(listOf(StopPage(room, high)), screen.size, Direction.LeftToRight)).single()
        val place = stopPlace(stop, screen.size)
        assertThat(place.zoom).isLessThan(MinZoom)

        compose.tapAt(Forward)

        assertThat(reader.page(6).width).isWithin(StopSlack).of(screen.width * place.zoom)
        assertThat(reader.page(6).left).isWithin(StopSlack).of(place.offset.x)
        assertThat(reader.page(6).top).isWithin(StopSlack).of(place.offset.y)

        pull(from = 0.5f, by = Offset(0f, -0.1f))
        letGo()

        assertThat(reader.page(6).width).isWithin(StopSlack).of(screen.width)
        assertThat(reader.page(6).left).isWithin(StopSlack).of(0f)
        assertThat(reader.page(6).top).isAtMost(0f)
    }

    /** A finger comes down at [from] (a share of the width), moves slowly [by] shares of the screen, and stays. */
    private fun pull(from: Float, by: Offset) {
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            down(Offset(width * from, height / 2f))
            repeat(PullMoves) { moveBy(Offset(width * by.x / PullMoves, height * by.y / PullMoves)) }
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

    /** A finger across the screen at mid-height, [from] one share of the width [to] another. */
    private fun swipe(from: Float, to: Float) {
        pull(from, Offset(to - from, 0f))
        letGo()
    }

    /** Two fingers around the middle of the screen, their distance going from one share of the width to another. */
    private fun pinch(fromApart: Float, toApart: Float) {
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            pinch(
                start0 = Offset(centerX - width * fromApart / 2, centerY),
                end0 = Offset(centerX - width * toApart / 2, centerY),
                start1 = Offset(centerX + width * fromApart / 2, centerY),
                end1 = Offset(centerX + width * toApart / 2, centerY),
            )
        }
        compose.waitForIdle()
    }

    private companion object {
        const val PullMoves = 10
        const val Still = 300L

        /** Less than a finger may move and still be a tap, in pixels. */
        const val Hair = 3f
    }
}
