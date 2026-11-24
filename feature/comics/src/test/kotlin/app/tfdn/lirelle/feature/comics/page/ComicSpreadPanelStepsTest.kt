package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.WholePage
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.Fit
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

/** A series read panel by panel: the taps on the edges step from the whole page to each of its stops. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadPanelStepsTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val reader = PanelReader(compose, images)

    @Test
    fun `the forward edge goes from the whole page to each stop, then turns to the next page shown whole`() {
        reader.show()
        reader.assertWhole(6)

        compose.tapAt(Forward)
        reader.assertAtStop(6, index = 0)
        compose.tapAt(Forward)
        reader.assertAtStop(6, index = 1)
        compose.tapAt(Forward)
        reader.assertAtStop(6, index = 2)
        assertThat(reader.recording.calls).isEmpty()
        // A step is the user reading on: the bars are asked to go. A turn is the reader's to see.
        assertThat(reader.hides).isEqualTo(3)

        compose.tapAt(Forward)

        assertThat(reader.recording.calls).containsExactly("next")
        assertThat(reader.hides).isEqualTo(3)
        reader.assertWhole(7)
    }

    @Test
    fun `the back edge goes from a stop to the one before, then to the whole page`() {
        reader.show()
        repeat(2) { compose.tapAt(Forward) }

        compose.tapAt(Back)
        reader.assertAtStop(6, index = 0)
        compose.tapAt(Back)

        reader.assertWhole(6)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `at the whole page the back edge turns back, and the page before ends at its last stop`() {
        reader.show()

        compose.tapAt(Back)

        assertThat(reader.recording.calls).containsExactly("previous")
        reader.assertAtStop(5, index = 2)

        compose.tapAt(Back)
        reader.assertAtStop(5, index = 1)
    }

    @Test
    fun `right to left, the left edge steps forward`() {
        val settings = ComicSettings(direction = Direction.RightToLeft)
        reader.show(album(spreadIndex = 5, settings = settings).copy(panels = readPanels(5)))
        val first = stopPlace(reader.stops(Direction.RightToLeft).first(), reader.screen.size)

        compose.tapAt(Back)

        assertThat(reader.page(6).left).isWithin(StopSlack).of(first.offset.x)
        assertThat(reader.page(6).top).isWithin(StopSlack).of(first.offset.y)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `without the whole page first, a page opens at its first stop and the next one goes to its own`() {
        reader.show(reader.state.copy(panels = readPanels(4, 5, 6, wholePageFirst = false)))

        reader.assertAtStop(6, index = 0)

        repeat(3) { compose.tapAt(Forward) }
        assertThat(reader.recording.calls).containsExactly("next")
        reader.assertAtStop(7, index = 0)

        // Back from a first stop is the page before, at its last one.
        compose.tapAt(Back)
        assertThat(reader.recording.calls).containsExactly("next", "previous").inOrder()
        reader.assertAtStop(6, index = 2)
    }

    @Test
    fun `a page jumped to while another was at a stop is shown whole`() {
        reader.show(reader.state.copy(panels = readPanels(5, 9)))
        compose.tapAt(Forward)
        reader.assertAtStop(6, index = 0)

        reader.state = reader.state.copy(spreadIndex = 9)
        compose.waitForIdle()

        reader.assertWhole(10)
        compose.tapAt(Forward)
        reader.assertAtStop(10, index = 0)
    }

    @Test
    fun `zones not known, a step forward waits for them and is done when they come`() {
        reader.show(reader.state.copy(panels = readPanels()))

        compose.tapAt(Forward)
        assertThat(reader.recording.calls).isEmpty()
        reader.assertWhole(6)
        // Nothing is seen to happen: the bars stay.
        assertThat(reader.hides).isEqualTo(0)

        reader.state = reader.state.copy(panels = readPanels(5))
        compose.waitForIdle()

        reader.assertAtStop(6, index = 0)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `zones still not known, a second step forward turns the page`() {
        reader.show(reader.state.copy(panels = readPanels()))

        compose.tapAt(Forward)
        compose.tapAt(Forward)

        assertThat(reader.recording.calls).containsExactly("next")
        reader.assertWhole(7)
    }

    @Test
    fun `a step that waited turns the page when it turns out not cut`() {
        reader.show(reader.state.copy(panels = readPanels()))
        compose.tapAt(Forward)
        assertThat(reader.recording.calls).isEmpty()

        val whole = mapOf(5 to PageZones.Whole(WholePage.NoPanels))
        reader.state = reader.state.copy(panels = readPanels().copy(zones = whole))
        compose.waitForIdle()

        assertThat(reader.recording.calls).containsExactly("next")
    }

    @Test
    fun `a page not cut turns at once, and one higher than the screen is first walked down as without the mode`() {
        val tall = pageImage(folder.root, index = 5, width = 100, height = 500)
        val whole = readPanels().copy(zones = mapOf(5 to PageZones.Whole(WholePage.NoPanels)))
        val settings = ComicSettings(fit = Fit.Width)
        reader.show(album(spreadIndex = 5, settings = settings, pages = mapOf(5 to tall)).copy(panels = whole))

        compose.tapAt(Forward)

        assertThat(reader.recording.calls).isEmpty()
        assertThat(reader.page(6).top).isWithin(1f).of(-0.9f * reader.screen.height)
    }

    @Test
    fun `zones never known, a page higher than the screen - one tap waits, each one after it walks the page down`() {
        val tall = pageImage(folder.root, index = 5, width = 100, height = 500)
        val settings = ComicSettings(fit = Fit.Width)
        reader.show(album(spreadIndex = 5, settings = settings, pages = mapOf(5 to tall)).copy(panels = readPanels()))

        compose.tapAt(Forward)
        assertThat(reader.page(6).top).isWithin(1f).of(0f)

        compose.tapAt(Forward)
        assertThat(reader.page(6).top).isWithin(1f).of(-0.9f * reader.screen.height)
        // The page is less than two screens further down: the next tap reaches its bottom.
        compose.tapAt(Forward)
        assertThat(reader.page(6).bottom).isWithin(1f).of(reader.screen.height)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `two pages side by side, right to left - the stops of the page at the right come first`() {
        val settings = ComicSettings(direction = Direction.RightToLeft)
        val pair = album(spreadIndex = 6, twoPages = true, settings = settings).copy(panels = readPanels(11, 12))
        reader.show(pair)
        val origin = reader.page(13).topLeft
        val rooms = listOf(12, 13).map { reader.page(it).translate(-origin.x, -origin.y) }
        val stops = spreadStops(rooms.map { StopPage(it, ThreePanels) }, reader.screen.size, Direction.RightToLeft)
        val first = checkNotNull(stops).first()
        assertThat(inside(first, rooms[0])).isTrue()

        compose.tapAt(Back)

        // The page at the left is where the spread starts.
        val place = stopPlace(first, reader.screen.size)
        assertThat(reader.page(13).left).isWithin(StopSlack).of(place.offset.x)
        assertThat(reader.page(13).top).isWithin(StopSlack).of(place.offset.y)

        repeat(checkNotNull(stops).size - 1) { compose.tapAt(Back) }
        assertThat(reader.recording.calls).isEmpty()
        compose.tapAt(Back)
        assertThat(reader.recording.calls).containsExactly("next")
    }

    @Test
    fun `a series not read panel by panel turns as ever, whatever is known of its pages`() {
        val debug = PanelReading(offered = true, on = false, zones = mapOf(5 to ThreePanels))
        reader.show(reader.state.copy(zonesShown = true, panels = debug))

        compose.tapAt(Forward)

        assertThat(reader.recording.calls).containsExactly("next")
        reader.assertWhole(7)
    }

    private fun inside(stop: Rect, room: Rect): Boolean =
        stop.left >= room.left - StopSlack && stop.right <= room.right + StopSlack
}
