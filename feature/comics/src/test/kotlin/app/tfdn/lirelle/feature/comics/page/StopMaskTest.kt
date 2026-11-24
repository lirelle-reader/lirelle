package app.tfdn.lirelle.feature.comics.page

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Constraints
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import app.tfdn.lirelle.feature.comics.PanelReading
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
 * What is around a panel: the reader's background, over everything a stop leaves out. The page here is white, on
 * a black background, and has the shape of the screen: whole, it is all there is to see.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class StopMaskTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val reader = PanelReader(compose, images)
    private val screen get() = reader.screen

    @Test
    fun `what the mask leaves to see is the window, widened to whole pixels`() {
        val size = Size(400f, 800f)

        assertThat(seenWindow(Rect(100f, 200f, 300f, 500f), size)).isEqualTo(Rect(100f, 200f, 300f, 500f))
        assertThat(seenWindow(Rect(100.4f, 200.6f, 300.2f, 500.5f), size)).isEqualTo(Rect(100f, 200f, 301f, 501f))
    }

    @Test
    fun `a window that is the screen, or larger, leaves all of the screen to see`() {
        val size = Size(400f, 800f)

        assertThat(seenWindow(Rect(0f, 0f, 400f, 800f), size)).isEqualTo(Rect(0f, 0f, 400f, 800f))
        assertThat(seenWindow(Rect(-50.5f, -10f, 500f, 900.5f), size)).isEqualTo(Rect(0f, 0f, 400f, 800f))
    }

    @Test
    fun `a window that is nowhere on the screen leaves nothing to see`() {
        val size = Size(400f, 800f)

        assertThat(seenWindow(Rect(450f, 200f, 600f, 500f), size).isEmpty).isTrue()
        assertThat(seenWindow(Rect(100f, -300f, 300f, -10f), size).isEmpty).isTrue()
    }

    @Test
    fun `a window between pixels has no seam - it is seen whole, and all around it is the background`() {
        compose.setContent {
            Box(
                Modifier
                    .testTag(MaskedTag)
                    .layout { measurable, _ ->
                        val box = measurable.measure(Constraints.fixed(MaskedSide, MaskedSide))
                        layout(MaskedSide, MaskedSide) { box.place(0, 0) }
                    }
                    .background(Color.White)
                    .stopMask(Color.Black) { Rect(20.5f, 30.5f, 60.5f, 70.5f) },
            )
        }
        val pixels = compose.onNodeWithTag(MaskedTag).captureToImage().toPixelMap()

        // The window, widened to whole pixels: its corners are all seen.
        val seen = listOf(20 to 30, 60 to 30, 20 to 70, 60 to 70, 40 to 50)
        assertThat(seen.map { (x, y) -> pixels[x, y] }.distinct()).containsExactly(Color.White)
        // Around it, and where what is drawn over and under it meets what is drawn at its sides.
        val beside = listOf(19 to 50, 61 to 50, 40 to 29, 40 to 71, 19 to 29, 61 to 71)
        val hidden = beside + listOf(5 to 30, 5 to 70, 90 to 30, 90 to 70)
        assertThat(hidden.map { (x, y) -> pixels[x, y] }.distinct()).containsExactly(Color.Black)
    }

    @Test
    fun `at a stop, what is outside it is the reader's background`() {
        reader.show(whitePage(ThreePanels))
        val window = window(ThreePanels, index = 0)
        // The stop is as wide as the screen: the page goes on over and under it.
        assertThat(window.top).isGreaterThan(40f)

        compose.tapAt(Forward)

        assertThat(reader.page(12).top).isLessThan(0f)
        assertThat(isWhite(pixel(window.center))).isTrue()
        assertThat(isWhite(pixel(Offset(window.center.x, window.top + 6f)))).isTrue()
        assertThat(isBlack(pixel(Offset(window.center.x, window.top - 6f)))).isTrue()
        assertThat(isBlack(pixel(Offset(window.center.x, window.bottom + 6f)))).isTrue()
        assertThat(isBlack(pixel(Offset(5f, 5f)))).isTrue()
    }

    @Test
    fun `a stop that the highest zoom leaves smaller than the screen has the background on every side`() {
        val small = PageZones.Cut(listOf(Zone(0.4f, 0.4f, 0.5f, 0.5f, ZoneKind.Filled)), millis = 0)
        reader.show(whitePage(small))
        val window = window(small, index = 0)
        assertThat(window.left).isGreaterThan(40f)

        compose.tapAt(Forward)

        assertThat(reader.page(12).width).isWithin(StopSlack).of(MaxZoom * screen.width)
        assertThat(isWhite(pixel(window.center))).isTrue()
        assertThat(isBlack(pixel(Offset(window.left - 6f, window.center.y)))).isTrue()
        assertThat(isBlack(pixel(Offset(window.right + 6f, window.center.y)))).isTrue()
        assertThat(isBlack(pixel(Offset(window.center.x, window.top - 6f)))).isTrue()
        assertThat(isBlack(pixel(Offset(window.center.x, window.bottom + 6f)))).isTrue()
    }

    @Test
    fun `the whole page has no mask`() {
        reader.show(whitePage(ThreePanels))

        assertAllPage()

        // Nor when it is gone back to from a stop.
        compose.tapAt(Forward)
        compose.tapAt(PanelReader.Back)
        assertAllPage()
    }

    @Test
    fun `a series not read panel by panel has no mask, zoomed or not`() {
        val debug = PanelReading(offered = true, on = false, zones = mapOf(11 to ThreePanels))
        reader.show(whitePage(ThreePanels).copy(panels = debug))

        assertAllPage()
        compose.longPressAt(screen.center)
        assertAllPage()
    }

    @Test
    fun `once a hand moved the page, the mask is gone`() {
        reader.show(whitePage(ThreePanels))
        compose.tapAt(Forward)
        assertThat(isBlack(pixel(Offset(5f, 5f)))).isTrue()

        compose.onNodeWithTag(ComicSpreadTag).performTouchInput {
            down(center)
            repeat(Moves) { moveBy(Offset(0f, -height * 0.05f / Moves)) }
            up()
        }
        compose.waitForIdle()

        assertAllPage()
    }

    @Test
    fun `the zones of the debug view are still drawn, under the mask`() {
        reader.show(whitePage(ThreePanels).copy(zonesShown = true))
        val window = window(ThreePanels, index = 0)
        val place = stopPlace(stop(ThreePanels, index = 0), screen.size)

        compose.tapAt(Forward)

        // The left side of the first zone's frame, a margin inside the stop.
        val frame = pixel(Offset(place.offset.x + 0.1f * screen.width * place.zoom, window.center.y))
        assertThat(frame.green).isGreaterThan(0.5f)
        assertThat(frame.red).isLessThan(0.3f)
        assertThat(isBlack(pixel(Offset(window.center.x, window.top - 6f)))).isTrue()
    }

    @Test
    fun `on the way to a stop the mask closes in with the page`() {
        reader.show(whitePage(ThreePanels))
        val window = window(ThreePanels, index = 0)
        compose.mainClock.autoAdvance = false

        tap()
        compose.mainClock.advanceTimeBy(MidMove)
        compose.waitForIdle()

        // The top of the screen is already hidden, not yet all that the stop hides.
        assertThat(isBlack(pixel(Offset(window.center.x, 2f)))).isTrue()
        assertThat(isWhite(pixel(Offset(window.center.x, window.top - 4f)))).isTrue()
    }

    @Test
    fun `with the system's animations off, the stop is reached at once`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        reader.show(whitePage(ThreePanels))
        val window = window(ThreePanels, index = 0)
        compose.mainClock.autoAdvance = false

        tap()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val place = stopPlace(stop(ThreePanels, index = 0), screen.size)
        assertThat(reader.page(12).width).isWithin(StopSlack).of(screen.width * place.zoom)
        assertThat(reader.page(12).top).isWithin(StopSlack).of(place.offset.y)
        assertThat(isBlack(pixel(Offset(window.center.x, window.top - 6f)))).isTrue()
    }

    /** The page 11 (from 0), white and of the screen's shape, on black, in a series read panel by panel. */
    private fun whitePage(zones: PageZones): ComicReaderUiState {
        val white = pageImage(folder.root, 11, width = 411, height = 891, colour = android.graphics.Color.WHITE)
        val onBlack = ComicSettings(background = Background.Black)
        return album(spreadIndex = 11, settings = onBlack, pages = mapOf(11 to white))
            .copy(panels = readPanels().copy(zones = mapOf(11 to zones)))
    }

    private fun stop(zones: PageZones, index: Int): Rect {
        val page = StopPage(Rect(Offset.Zero, screen.size), zones)
        return checkNotNull(spreadStops(listOf(page), screen.size, Direction.LeftToRight))[index]
    }

    /** Where the stop of that [index] is on the screen once the page is at it. */
    private fun window(zones: PageZones, index: Int): Rect {
        val stop = stop(zones, index)
        val place = stopPlace(stop, screen.size)
        return Rect(stop.topLeft * place.zoom + place.offset, stop.size * place.zoom)
    }

    /** The page is everywhere on the screen: nothing hides any of it. */
    private fun assertAllPage() {
        val pixels = compose.onNodeWithTag(ComicSpreadTag).captureToImage().toPixelMap()
        val spots = listOf(0.02f, 0.5f, 0.98f)
        val seen = spots.flatMap { x ->
            spots.map { y -> pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()] }
        }
        assertThat(seen.all(::isWhite)).isTrue()
    }

    /** What is drawn [at] a place of the page view. */
    private fun pixel(at: Offset): Color =
        compose.onNodeWithTag(ComicSpreadTag).captureToImage().toPixelMap()[at.x.toInt(), at.y.toInt()]

    private fun isWhite(colour: Color): Boolean = colour.red > Light && colour.green > Light && colour.blue > Light

    private fun isBlack(colour: Color): Boolean = colour.red < Dark && colour.green < Dark && colour.blue < Dark

    /** A tap on the forward edge: the clock goes no further than the tap lasts. */
    private fun tap() =
        compose.onNodeWithTag(ComicSpreadTag).performTouchInput { click(Offset(width * Forward, height / 2f)) }

    private companion object {
        const val Light = 0.9f
        const val Dark = 0.1f
        const val Moves = 10
        const val MidMove = 100L
        const val MaskedTag = "masked"
        const val MaskedSide = 100
    }
}
