package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.WholePage
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import app.tfdn.lirelle.feature.comics.PanelReading
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
 * A series read panel by panel, when a step turns the page: the page turned to slides in at the stop it is to
 * arrive at, never whole first, and is there when the slide ends. The pages here are white, on a black background,
 * and have the shape of the screen; the clock only goes on when the test says so.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadPanelArrivalsTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val reader = PanelReader(compose, images)
    private val screen get() = reader.screen

    @Test
    fun `without the whole page first, the next page slides in at its first stop, and is there when the slide ends`() {
        reader.show(whitePages(readPanels(4, 5, 6, wholePageFirst = false)).copy(zonesShown = true))
        repeat(2) { compose.tapAt(Forward) }
        reader.assertAtStop(6, index = 2)
        val window = window(index = 0)
        val place = stopPlace(reader.stops()[0], screen.size)
        holdTheClock()

        tap(Forward)
        run(MidSlide)

        val slot = compose.onNodeWithTag(ComicNextSpreadTag).drawn()
        assertThat(slot.left).isGreaterThan(Margin)
        assertThat(slot.left).isLessThan(screen.width - 2 * Margin)
        val shot = shot()
        // What is seen of the page coming in: its first panel, the rest of it under the mask.
        val coming = (slot.left + screen.width) / 2
        assertThat(isBlack(shot.at(coming, Top))).isTrue()
        assertThat(isBlack(shot.at(coming, window.top - Beside))).isTrue()
        assertThat(isWhite(shot.at(coming, window.center.y))).isTrue()
        // The left side of the first zone's frame is where the stop puts it, not where the whole page would.
        val frame = shot.at(slot.left + place.offset.x + 0.1f * screen.width * place.zoom, window.center.y)
        assertThat(frame.green).isGreaterThan(0.5f)
        assertThat(frame.red).isLessThan(0.3f)
        // The page going out is whole, and the mask of the other is not over it.
        assertThat(reader.page(6).width).isWithin(StopSlack).of(screen.width)
        assertThat(isWhite(shot.at(reader.page(6).right / 2, Top))).isTrue()

        endSlide()

        reader.assertAtStop(7, index = 0)
        assertThat(isBlack(shot().at(window.center.x, window.top - Beside))).isTrue()
        run(SettleWait)
        reader.assertAtStop(7, index = 0)
        assertThat(reader.recording.calls).containsExactly("next")

        tap(Forward)
        run(SettleWait)
        reader.assertAtStop(7, index = 1)
    }

    @Test
    fun `a step back from the whole page slides the page before in at its last stop, and it is there at once`() {
        reader.show(whitePages(readPanels(4, 5, 6)))
        val window = window(index = 2)
        holdTheClock()

        tap(Back)
        run(MidSlide)

        val slot = compose.onNodeWithTag(ComicPreviousSpreadTag).drawn()
        assertThat(slot.right).isGreaterThan(2 * Margin)
        assertThat(slot.right).isLessThan(screen.width - Margin)
        val shot = shot()
        assertThat(isBlack(shot.at(slot.right / 2, Top))).isTrue()
        assertThat(isBlack(shot.at(slot.right / 2, window.top - Beside))).isTrue()
        assertThat(isWhite(shot.at(slot.right / 2, window.center.y))).isTrue()
        assertThat(isWhite(shot.at((reader.page(6).left + screen.width) / 2, Top))).isTrue()

        endSlide()

        reader.assertAtStop(5, index = 2)
        run(SettleWait)
        reader.assertAtStop(5, index = 2)
        assertThat(reader.recording.calls).containsExactly("previous")

        tap(Back)
        run(SettleWait)
        reader.assertAtStop(5, index = 1)
    }

    @Test
    fun `with the whole page first, the next page slides in whole and stays so`() {
        reader.show(whitePages(readPanels(4, 5, 6)))
        repeat(3) { compose.tapAt(Forward) }
        reader.assertAtStop(6, index = 2)
        holdTheClock()

        tap(Forward)
        run(MidSlide)

        assertThat(isWhite(shot().at(comingIn(), Top))).isTrue()

        endSlide()
        reader.assertWhole(7)
        run(SettleWait)
        reader.assertWhole(7)
    }

    @Test
    fun `a next page whose stops are not known slides in whole, and goes to its first stop when they come`() {
        reader.show(whitePages(readPanels(5, wholePageFirst = false)))
        repeat(2) { compose.tapAt(Forward) }
        reader.assertAtStop(6, index = 2)
        holdTheClock()

        tap(Forward)
        run(MidSlide)

        assertThat(isWhite(shot().at(comingIn(), Top))).isTrue()

        endSlide()
        reader.assertWhole(7)
        run(SettleWait)
        reader.assertWhole(7)

        reader.state = reader.state.copy(panels = readPanels(5, 6, wholePageFirst = false))
        run(MidMove)

        // On its way: it moves there, as a page does that is shown before its stops are known.
        val stop = screen.width * stopPlace(reader.stops()[0], screen.size).zoom
        assertThat(reader.page(7).width).isGreaterThan(screen.width + 10f)
        assertThat(reader.page(7).width).isLessThan(stop - 10f)
        run(SettleWait)
        reader.assertAtStop(7, index = 0)
    }

    @Test
    fun `a next page that has no stop slides in whole and stays whole`() {
        val zones = mapOf(5 to ThreePanels, 6 to PageZones.Whole(WholePage.NoPanels))
        reader.show(whitePages(readPanels(wholePageFirst = false).copy(zones = zones)))
        repeat(2) { compose.tapAt(Forward) }
        reader.assertAtStop(6, index = 2)
        holdTheClock()

        tap(Forward)
        run(MidSlide)

        assertThat(isWhite(shot().at(comingIn(), Top))).isTrue()

        endSlide()
        reader.assertWhole(7)
        run(SettleWait)
        reader.assertWhole(7)
    }

    @Test
    fun `a turn the reader does not take leaves nothing framed, and the page it led to opens as any other`() {
        reader.takesTurns = false
        reader.show(whitePages(readPanels(4, 5, 6, wholePageFirst = false)))
        repeat(2) { compose.tapAt(Forward) }
        holdTheClock()

        tap(Forward)
        run(SettleWait)

        assertThat(reader.recording.calls).containsExactly("next")
        reader.assertWhole(6)
        assertAllPage()

        // Jumped to, the page opens whole and moves to its first stop: nothing is kept of the turn not taken.
        reader.state = reader.state.copy(spreadIndex = 6)
        compose.waitForIdle()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        assertThat(reader.page(7).width).isLessThan(screen.width * 1.3f)
        run(SettleWait)
        reader.assertAtStop(7, index = 0)
    }

    @Test
    fun `a finger that takes the slide of a step finds the next page whole under it`() {
        // The page shown has no panel: a finger slides it. The next one has, and was to arrive at its first stop.
        val zones = mapOf(5 to PageZones.Whole(WholePage.NoPanels), 6 to ThreePanels)
        reader.show(whitePages(readPanels(wholePageFirst = false).copy(zones = zones)))
        holdTheClock()

        tap(Forward)
        run(MidSlide)
        assertThat(isBlack(shot().at(comingIn(), Top))).isTrue()

        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            down(Offset(width * 0.8f, height / 2f))
            repeat(Moves) { moveBy(Offset(-width * 0.03f, 0f)) }
        }
        run(ShortWhile)

        assertThat(reader.recording.calls).containsExactly("next")
        assertThat(reader.page(7).width).isWithin(StopSlack).of(screen.width)
        assertThat(reader.page(7).left).isLessThan(-10f)
        assertThat(isWhite(shot().at(reader.page(7).right / 2, Top))).isTrue()
    }

    @Test
    fun `two pages side by side, right to left - the next pages arrive at their first stop in reading order`() {
        val settings = ComicSettings(direction = Direction.RightToLeft)
        val pair = album(spreadIndex = 6, twoPages = true, settings = settings)
        reader.show(pair.copy(panels = readPanels(11, 12, 13, 14)))
        val origin = reader.page(13).topLeft
        val rooms = listOf(12, 13).map { reader.page(it).translate(-origin.x, -origin.y) }
        val stops = spreadStops(rooms.map { StopPage(it, ThreePanels) }, screen.size, Direction.RightToLeft)
        val first = stopPlace(checkNotNull(stops)[0], screen.size)
        // The first stop is of the page at the right, which is read first.
        assertThat(stops[0].left).isAtLeast(rooms[0].left - StopSlack)
        reader.state = reader.state.copy(panels = readPanels(11, 12, 13, 14, wholePageFirst = false))
        compose.waitForIdle()
        repeat(stops.size) { compose.tapAt(Back) }
        assertThat(reader.recording.calls).isEmpty()
        holdTheClock()

        tap(Back)
        endSlide()

        // The page at the left is where the spread starts.
        assertThat(reader.page(15).left).isWithin(StopSlack).of(first.offset.x)
        assertThat(reader.page(15).top).isWithin(StopSlack).of(first.offset.y)
        assertThat(reader.page(15).width).isWithin(StopSlack).of(rooms[1].width * first.zoom)
        run(SettleWait)
        assertThat(reader.page(15).left).isWithin(StopSlack).of(first.offset.x)
        assertThat(reader.page(15).top).isWithin(StopSlack).of(first.offset.y)

        tap(Back)
        run(SettleWait)
        val second = stopPlace(stops[1], screen.size)
        assertThat(reader.page(15).left).isWithin(StopSlack).of(second.offset.x)
        assertThat(reader.page(15).top).isWithin(StopSlack).of(second.offset.y)
    }

    @Test
    fun `the page left for one that arrived at a stop is whole beside it, and has no mask`() {
        reader.show(whitePages(readPanels(4, 5, 6, wholePageFirst = false)))
        repeat(3) { compose.tapAt(Forward) }
        reader.assertAtStop(7, index = 0)
        val window = window(index = 2)

        // The page shown turns out to have no panel: a finger slides it, and brings the one before in as it stands.
        val zones = reader.state.panels.zones + (6 to PageZones.Whole(WholePage.NoPanels))
        reader.state = reader.state.copy(panels = reader.state.panels.copy(zones = zones))
        compose.waitForIdle()
        reader.assertWhole(7)
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            down(Offset(width * 0.3f, height / 2f))
            repeat(PullMoves) { moveBy(Offset(width * 0.4f / PullMoves, 0f)) }
        }
        compose.waitForIdle()

        val slot = compose.onNodeWithTag(ComicPreviousSpreadTag).drawn()
        assertThat(slot.right).isGreaterThan(2 * Margin)
        val shot = shot()
        val heights = listOf(Top, window.top - Beside, window.center.y, screen.height - Top)
        assertThat(heights.all { isWhite(shot.at(slot.right / 2, it)) }).isTrue()
        assertThat(isWhite(shot.at(Top, Top))).isTrue()
    }

    /** The pages 5 to 7 (from 1), white and of the screen's shape, on black, the page 6 shown. */
    private fun whitePages(panels: PanelReading): ComicReaderUiState {
        val white = listOf(4, 5, 6).associateWith {
            pageImage(folder.root, it, width = 411, height = 891, colour = android.graphics.Color.WHITE)
        }
        val onBlack = ComicSettings(background = Background.Black)
        return album(spreadIndex = 5, settings = onBlack, pages = white).copy(panels = panels)
    }

    /** Where the stop of that [index] is on the screen once a page is at it. */
    private fun window(index: Int): Rect {
        val stop = reader.stops()[index]
        val place = stopPlace(stop, screen.size)
        return Rect(stop.topLeft * place.zoom + place.offset, stop.size * place.zoom)
    }

    /** The middle of what is seen of the next page while it slides in from the right. */
    private fun comingIn(): Float {
        val slot = compose.onNodeWithTag(ComicNextSpreadTag).drawn()
        assertThat(slot.left).isGreaterThan(Margin)
        assertThat(slot.left).isLessThan(screen.width - 2 * Margin)
        return (slot.left + screen.width) / 2
    }

    /** What has to end first ends, then the clock only goes on when the test says so. */
    private fun holdTheClock() {
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    /** A tap on an edge: the clock goes no further than the tap lasts. */
    private fun tap(x: Float) =
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput { click(Offset(width * x, height / 2f)) }

    private fun run(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    /**
     * The clock goes on a frame at a time until the slide asked the reader for its turn, then for the two frames
     * the reader needs to show the page turned to: a page that only then left for a stop would be far from it.
     */
    private fun endSlide() {
        repeat(SlideFrames) { if (reader.recording.calls.isEmpty()) compose.mainClock.advanceTimeByFrame() }
        assertThat(reader.recording.calls).hasSize(1)
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }

    /** The page is everywhere on the screen: nothing hides any of it. */
    private fun assertAllPage() {
        val shot = shot()
        val spots = listOf(0.02f, 0.5f, 0.98f)
        val seen = spots.flatMap { x -> spots.map { y -> shot.at(screen.width * x, screen.height * y) } }
        assertThat(seen.all(::isWhite)).isTrue()
    }

    private fun shot(): PixelMap = compose.onNodeWithTag(ComicSpreadTag).captureToImage().toPixelMap()

    private fun PixelMap.at(x: Float, y: Float): Color = this[x.toInt(), y.toInt()]

    private fun isWhite(colour: Color): Boolean = colour.red > Light && colour.green > Light && colour.blue > Light

    private fun isBlack(colour: Color): Boolean = colour.red < Dark && colour.green < Dark && colour.blue < Dark

    private companion object {
        const val Light = 0.9f
        const val Dark = 0.1f

        /** How far from an edge of the screen, or of a stop, a pixel is looked at. */
        const val Top = 5f
        const val Beside = 20f

        /** The least that is to be seen of a page for a pixel of it to be looked at. */
        const val Margin = 40f
        const val MidSlide = 100L
        const val MidMove = 100L
        const val ShortWhile = 50L
        const val SlideFrames = 40
        const val Moves = 5
        const val PullMoves = 10
    }
}
