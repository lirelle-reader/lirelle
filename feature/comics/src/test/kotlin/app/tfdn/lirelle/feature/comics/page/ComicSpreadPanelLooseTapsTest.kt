package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.WholePage
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
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
 * A series read panel by panel, under a thumb that rolls as it taps: a finger that moves a little and is soon
 * lifted is a tap where it came down, whatever way it moved, and nothing leaves the stop under it. A finger that
 * goes further is what it always was: a swipe sideways, a hand that moves the page any other way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadPanelLooseTapsTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val reader = PanelReader(compose, images)
    private val screen get() = reader.screen

    @Test
    fun `at a stop, a tap on the forward edge that moves a little upright steps forward, the stop never left`() {
        val white = pageImage(folder.root, 5, width = 411, height = 891, colour = android.graphics.Color.WHITE)
        val onBlack = ComicSettings(background = Background.Black)
        reader.show(album(spreadIndex = 5, settings = onBlack, pages = mapOf(5 to white)).copy(panels = readPanels(5)))
        compose.tapAt(Forward)
        reader.assertAtStop(6, index = 0)
        assertThat(isBlack(corner())).isTrue()

        roll(from = Forward, by = Offset(4f, -19f))

        // Under the finger: the stop still frames the page, and the rest of it is hidden.
        reader.assertAtStop(6, index = 0)
        assertThat(isBlack(corner())).isTrue()

        lift()

        reader.assertAtStop(6, index = 1)
        // Back from there is the first stop: no hand ever took the page.
        compose.tapAt(Back)
        reader.assertAtStop(6, index = 0)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `at a stop, a tap on the forward edge that rolls the way of a swipe back still steps forward`() {
        reader.show()
        compose.tapAt(Forward)

        roll(from = Forward, by = Offset(20f, 0f))
        reader.assertAtStop(6, index = 0)
        lift()

        reader.assertAtStop(6, index = 1)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `at a stop, a tap on the back edge that rolls into the middle steps back - where it came down decides`() {
        reader.show()
        repeat(2) { compose.tapAt(Forward) }

        // Down within the quarter at the edge, lifted out of it.
        roll(from = 0.24f, by = Offset(16f, -10f))
        reader.assertAtStop(6, index = 1)
        lift()

        reader.assertAtStop(6, index = 0)
        assertThat(reader.toggles).isEqualTo(0)
    }

    @Test
    fun `at a stop, a finger that goes far upright takes the page - not before it went further than a tap may`() {
        reader.show()
        compose.tapAt(Forward)
        val framed = reader.page(6)

        roll(from = 0.5f, by = Offset(0f, -20f))
        assertThat(reader.page(6).top).isWithin(StopSlack).of(framed.top)

        // On to 60 dp: the page follows the finger from where it left the reach of a tap, with no jump.
        moveOn(by = Offset(0f, -40f))

        val moved = reader.page(6)
        assertThat(moved.top).isLessThan(framed.top - 30.dp.px)
        assertThat(moved.top).isAtLeast(framed.top - 60.dp.px)
        assertThat(moved.width).isWithin(StopSlack).of(framed.width)

        lift()
        // A hand left the stop: a step back goes to it again.
        compose.tapAt(Back)
        reader.assertAtStop(6, index = 0)
    }

    @Test
    fun `at a stop, a swipe of 60 dp sideways is a step, as ever`() {
        reader.show()
        compose.tapAt(Forward)

        roll(from = 0.6f, by = Offset(-20f, 0f))
        moveOn(by = Offset(-40f, 0f))
        reader.assertAtStop(6, index = 0)
        lift()

        reader.assertAtStop(6, index = 1)
        assertThat(reader.toggles).isEqualTo(0)
    }

    @Test
    fun `a finger that rolls a little sideways in the middle is a tap in the middle, not a step`() {
        reader.show()

        roll(from = 0.5f, by = Offset(-20f, 0f))
        lift()

        assertThat(reader.toggles).isEqualTo(1)
        reader.assertWhole(6)
        assertThat(reader.hides).isEqualTo(0)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `at the whole page, a tap on the forward edge that moves a little goes to the first stop`() {
        reader.show()

        roll(from = Forward, by = Offset(4f, -19f))
        reader.assertWhole(6)
        lift()

        reader.assertAtStop(6, index = 0)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `at a stop, a finger that moves a little and stays as long as a long press is no tap, and takes nothing`() {
        reader.show()
        compose.tapAt(Forward)

        roll(from = Forward, by = Offset(4f, -19f))
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + LongPressMargin)
            up()
        }
        compose.mainClock.advanceTimeBy(SettleWait)
        compose.waitForIdle()

        reader.assertAtStop(6, index = 0)
        // No hand took the page: a step back is the whole page, not the stop again.
        compose.tapAt(Back)
        reader.assertWhole(6)
    }

    @Test
    fun `a page with no panel takes a finger that moves a little as without the mode - it is no tap`() {
        val notCut = readPanels().copy(zones = mapOf(5 to PageZones.Whole(WholePage.NoPanels)))
        reader.show(reader.state.copy(panels = notCut))

        roll(from = Forward, by = Offset(4f, -19f))
        lift()

        reader.assertWhole(6)
        assertThat(reader.recording.calls).isEmpty()
        assertThat(reader.toggles).isEqualTo(0)
    }

    /**
     * A finger comes down at [from] (a share of the width), at mid-height, moves [by] that many dp in two moves,
     * and stays. It goes further than a tap may without the mode: the screen of these tests allows a tap 16 dp.
     */
    private fun roll(from: Float, by: Offset) {
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            val travel = Offset(by.x.dp.toPx(), by.y.dp.toPx())
            check(travel.getDistance() > viewConfiguration.touchSlop) { "a plain tap: it shows nothing" }
            down(Offset(width * from, height / 2f))
            repeat(RollMoves) { moveBy(travel / RollMoves.toFloat()) }
        }
        compose.waitForIdle()
    }

    /** The finger that stayed down goes on [by] that many dp, slowly. */
    private fun moveOn(by: Offset) {
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            repeat(OnMoves) { moveBy(Offset(by.x.dp.toPx(), by.y.dp.toPx()) / OnMoves.toFloat()) }
        }
        compose.waitForIdle()
    }

    /** The finger that stayed down is lifted, and what it started ends. */
    private fun lift() {
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(SettleWait)
        compose.waitForIdle()
    }

    /** What is drawn in a corner of the page view: the page when it is whole, the background around a stop. */
    private fun corner(): Color = compose.onNodeWithTag(ComicSpreadTag).captureToImage().toPixelMap()[Corner, Corner]

    private fun isBlack(colour: Color): Boolean = colour.red < Dark && colour.green < Dark && colour.blue < Dark

    private val Dp.px: Float get() = value * compose.density.density

    private companion object {
        const val RollMoves = 2
        const val OnMoves = 4
        const val Corner = 5
        const val Dark = 0.1f
    }
}
