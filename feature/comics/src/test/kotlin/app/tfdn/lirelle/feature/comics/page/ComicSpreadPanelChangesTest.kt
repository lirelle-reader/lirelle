package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
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
 * A series read panel by panel, when what the reader knows of the spread shown changes under a stop, or when a
 * turn is not taken: unsure, the page is shown whole, and the walk starts again from there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicSpreadPanelChangesTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val reader = PanelReader(compose, images)
    private val screen get() = reader.screen

    @Test
    fun `a stop whose zones are no longer known gives way to the two whole pages, and the walk starts again`() {
        val white = listOf(11, 12).associateWith {
            pageImage(folder.root, it, width = 411, height = 891, colour = android.graphics.Color.WHITE)
        }
        val onBlack = ComicSettings(background = Background.Black)
        val pair = album(spreadIndex = 6, twoPages = true, settings = onBlack, pages = white)
        reader.show(pair.copy(panels = readPanels(11, 12)))
        val whole = reader.page(12)
        val rooms = listOf(12, 13).map { reader.page(it).translate(-whole.left, -whole.top) }
        val stops = spreadStops(rooms.map { StopPage(it, ThreePanels) }, screen.size, Direction.LeftToRight)
        val third = stopPlace(checkNotNull(stops)[2], screen.size)
        val window = Rect(stops[2].topLeft * third.zoom + third.offset, stops[2].size * third.zoom)
        // Beside the stop, where the page shown whole is.
        val beside = Offset(window.left - 6f, whole.center.y)
        assertThat(beside.x).isGreaterThan(whole.left + 2f)
        assertThat(isWhite(pixel(beside))).isTrue()

        repeat(3) { compose.tapAt(Forward) }
        assertThat(reader.page(12).left).isWithin(StopSlack).of(third.offset.x)
        assertThat(reader.page(12).width).isWithin(StopSlack).of(whole.width * third.zoom)
        assertThat(isWhite(pixel(beside))).isFalse()

        // The zones of a page are cut again: nothing is known of the stops.
        reader.state = reader.state.copy(panels = readPanels(11))
        compose.waitForIdle()

        assertThat(reader.page(12)).isEqualTo(whole)
        assertThat(isWhite(pixel(beside))).isTrue()

        // The walk is at the whole view: a step waits for the stops, then goes to the first.
        compose.tapAt(Forward)
        assertThat(reader.page(12)).isEqualTo(whole)
        reader.state = reader.state.copy(panels = readPanels(11, 12))
        compose.waitForIdle()

        val first = stopPlace(stops[0], screen.size)
        assertThat(reader.page(12).left).isWithin(StopSlack).of(first.offset.x)
        assertThat(reader.page(12).top).isWithin(StopSlack).of(first.offset.y)
        assertThat(reader.recording.calls).isEmpty()
    }

    @Test
    fun `stops that became fewer than the one shown give way to the whole page, and the walk starts again`() {
        reader.show(reader.state.copy(panels = readPanels().copy(zones = mapOf(5 to grid(6)))))
        repeat(5) { compose.tapAt(Forward) }
        reader.assertAt(6, stops(grid(6))[4])

        reader.state = reader.state.copy(panels = readPanels().copy(zones = mapOf(5 to grid(4))))
        compose.waitForIdle()
        reader.assertWhole(6)

        compose.tapAt(Forward)

        assertThat(reader.recording.calls).isEmpty()
        reader.assertAt(6, stops(grid(4))[0])
    }

    @Test
    fun `what a turn the reader did not take asked of a page is not done when that page is jumped to`() {
        reader.takesTurns = false
        reader.show()

        // Back from the whole page: the page before would end at its last stop.
        compose.tapAt(Back)
        assertThat(reader.recording.calls).containsExactly("previous")
        reader.assertWhole(6)

        reader.state = reader.state.copy(spreadIndex = 4)
        compose.waitForIdle()

        reader.assertWhole(5)
    }

    /** A page cut in [count] panels, two a row: each is one stop on the screen of these tests. */
    private fun grid(count: Int) = PageZones.Cut(
        zones = List(count) {
            val left = 0.1f + 0.45f * (it % 2)
            val top = 0.05f + 0.32f * (it / 2)
            Zone(left, top, left + 0.35f, top + 0.25f, ZoneKind.Filled)
        },
        millis = 0,
    )

    private fun stops(zones: PageZones.Cut): List<Rect> {
        val page = StopPage(Rect(Offset.Zero, screen.size), zones)
        val stops = checkNotNull(spreadStops(listOf(page), screen.size, Direction.LeftToRight))
        assertThat(stops).hasSize(zones.zones.size)
        return stops
    }

    private fun pixel(at: Offset): Color =
        compose.onNodeWithTag(ComicSpreadTag).captureToImage().toPixelMap()[at.x.toInt(), at.y.toInt()]

    private fun isWhite(colour: Color): Boolean = colour.red > Light && colour.green > Light && colour.blue > Light

    private companion object {
        const val Light = 0.9f
    }
}
