package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.feature.comics.page.PanelReader.Companion.Forward
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A series read panel by panel, under taps that follow one another faster than the page moves. The clock only
 * goes on when the test says so.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadPanelQuickStepsTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val reader = PanelReader(compose, images)
    private val screen get() = reader.screen

    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `the page goes to a stop over a few frames, zooming and moving together`() {
        reader.show()
        val place = stopPlace(reader.stops().first(), screen.size)

        tap()
        run(MidMove)

        // On its way: larger than the screen, not yet as large as the stop makes it.
        assertThat(reader.page(6).width).isGreaterThan(screen.width + 10f)
        assertThat(reader.page(6).width).isLessThan(screen.width * place.zoom - 10f)
        assertThat(reader.page(6).left).isLessThan(0f)
        assertThat(reader.page(6).left).isGreaterThan(place.offset.x)

        run(SettleWait)
        reader.assertAtStop(6, index = 0)
    }

    @Test
    fun `a step that comes during a move goes on from the stop that move leads to`() {
        reader.show()

        tap()
        run(MidMove)
        tap()
        run(SettleWait)

        reader.assertAtStop(6, index = 1)
    }

    @Test
    fun `a step that comes while the page turns is not taken`() {
        reader.show()
        repeat(3) {
            tap()
            run(SettleWait)
        }
        reader.assertAtStop(6, index = 2)

        tap()
        run(MidMove)
        assertThat(reader.recording.calls).isEmpty()
        tap()
        run(ShortWhile)

        // Still sliding away, whole: the second tap did not send it to a stop.
        assertThat(reader.page(6).width).isWithin(StopSlack).of(screen.width)
        assertThat(reader.page(6).height).isWithin(StopSlack).of(screen.height)
        assertThat(reader.page(6).left).isLessThan(-10f)
        assertThat(reader.recording.calls).isEmpty()

        run(SettleWait)

        assertThat(reader.recording.calls).containsExactly("next")
        reader.assertWhole(7)
    }

    @Test
    fun `fingers that pinch during a move go on from the zoom the page is drawn at`() {
        reader.show()
        val stop = screen.width * stopPlace(reader.stops().first(), screen.size).zoom

        tap()
        run(MidMove)
        val drawn = reader.page(6).width
        assertThat(drawn).isLessThan(stop / PinchedBy - 10f)
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            val apart = Offset(width * 0.2f, 0f)
            val more = apart * (PinchedBy - 1f)
            down(0, center - apart)
            down(1, center + apart)
            moveBy(0, Offset.Zero - more)
            moveBy(1, more)
            up(0)
            up(1)
        }
        run(ShortWhile)

        // No jump to the zoom of the stop it was going to.
        assertThat(reader.page(6).width).isWithin(drawn * 0.05f).of(drawn * PinchedBy)
    }

    @Test
    fun `stops that change during a move - the page ends at the stop as it now is`() {
        reader.show()
        val elsewhere = PageZones.Cut(listOf(Zone(0.5f, 0.5f, 0.9f, 0.8f, ZoneKind.Filled)), millis = 0)
        val page = StopPage(Rect(Offset.Zero, screen.size), elsewhere)
        val stop = checkNotNull(spreadStops(listOf(page), screen.size, Direction.LeftToRight)).single()

        tap()
        run(MidMove)
        reader.state = reader.state.copy(panels = readPanels().copy(zones = mapOf(5 to elsewhere)))
        run(SettleWait)

        reader.assertAt(6, stop)
    }

    @Test
    fun `a page reached under a finger that slides it does not go to its first stop`() {
        // The page shown is not known: fingers slide it. The next one is, and opens at its first stop.
        reader.show(reader.state.copy(panels = readPanels(6, wholePageFirst = false)))
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            down(Offset(width * 0.8f, height / 2f))
            repeat(Moves) { moveBy(Offset(-width * 0.1f, 0f)) }
            up()
        }
        run(ShortWhile)
        assertThat(reader.recording.calls).isEmpty()

        // The finger takes the slide on its way: the turn is asked at once, and the finger slides on.
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            down(Offset(width * 0.8f, height / 2f))
            repeat(Moves) { moveBy(Offset(-width * 0.03f, 0f)) }
        }
        run(MidMove)

        assertThat(reader.recording.calls).containsExactly("next")
        assertThat(reader.page(7).width).isWithin(StopSlack).of(screen.width)
        assertThat(reader.page(7).left).isLessThan(-10f)
    }

    @Test
    fun `leaving the last stop, the page is whole at once as the next one slides in`() {
        reader.show()
        repeat(3) {
            tap()
            run(SettleWait)
        }

        tap()
        run(MidMove)

        assertThat(reader.page(6).width).isWithin(StopSlack).of(screen.width)
        assertThat(reader.page(6).left).isLessThan(-10f)
        assertThat(compose.onNodeWithTag(ComicNextSpreadTag).drawn().width).isWithin(StopSlack).of(screen.width)
    }

    /** A tap on the forward edge: the clock goes no further than the tap lasts. */
    private fun tap() =
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput { click(Offset(width * Forward, height / 2f)) }

    private fun run(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private companion object {
        const val MidMove = 100L
        const val ShortWhile = 50L
        const val PinchedBy = 1.1f
        const val Moves = 5
    }
}
