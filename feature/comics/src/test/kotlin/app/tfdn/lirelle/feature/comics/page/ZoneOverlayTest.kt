package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.WholePage
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import app.tfdn.lirelle.feature.comics.PanelReading
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The zones drawn over a page: where each is, what is said of them, and that they are drawn only when shown. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ZoneOverlayTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val filled = Zone(0.1f, 0.3f, 0.9f, 0.5f, ZoneKind.Filled)
    private val sparse = Zone(0.1f, 0.6f, 0.9f, 0.8f, ZoneKind.Sparse)
    private val cut = PageZones.Cut(listOf(filled, sparse), millis = 5)

    @Test
    fun `a zone is where its proportions say on the page`() {
        val page = Size(800f, 1200f)

        assertThat(zoneRect(Zone(0f, 0f, 1f, 1f, ZoneKind.Sparse), page)).isEqualTo(Rect(0f, 0f, 800f, 1200f))
        assertThat(zoneRect(Zone(0.25f, 0.5f, 0.75f, 0.875f, ZoneKind.Filled), page))
            .isEqualTo(Rect(200f, 600f, 600f, 1050f))
    }

    @Test
    fun `what is said of a page's zones is how many they are and how long the cut took`() {
        val three = PageZones.Cut(listOf(filled, sparse, filled), millis = 412)

        assertThat(zoneSummary(three)).isEqualTo("3 zones, 412 ms")
    }

    @Test
    fun `what is said of a page read whole is that it is, and why`() {
        assertThat(wholeSummary(PageZones.Whole(WholePage.Cover))).isEqualTo("whole: cover")
        assertThat(wholeSummary(PageZones.Whole(WholePage.NoPanels))).isEqualTo("whole: no panels")
        assertThat(wholeSummary(PageZones.Whole(WholePage.NoPicture))).isEqualTo("whole: no picture")
        assertThat(wholeSummary(PageZones.Whole(WholePage.Failed))).isEqualTo("whole: failed")
    }

    @Test
    fun `a page read whole has only what is said of it, at its top`() {
        val whole = PanelReading(zones = mapOf(11 to PageZones.Whole(WholePage.NoPanels)))
        show(whitePage().copy(zonesShown = true, panels = whole))

        // The backing of what is written, in the middle of the top; the page is white everywhere else.
        assertThat(pixelAt(0.5f, 0.01f).red).isLessThan(0.5f)
        assertThat(pixelAt(0.1f, 0.4f).green).isGreaterThan(0.9f)
        assertThat(pixelAt(0.1f, 0.4f).red).isGreaterThan(0.9f)
        assertThat(pixelAt(0.5f, 0.55f).blue).isGreaterThan(0.9f)
    }

    @Test
    fun `a filled zone is framed in green and a sparse one in red, over the page`() {
        show(whitePage().copy(zonesShown = true, panels = PanelReading(zones = mapOf(11 to cut))))

        // The left side of each frame, at mid-height of its zone; the page is white between the two.
        val green = pixelAt(0.1f, 0.4f)
        assertThat(green.green).isGreaterThan(0.5f)
        assertThat(green.red).isLessThan(0.3f)
        val red = pixelAt(0.1f, 0.7f)
        assertThat(red.red).isGreaterThan(0.5f)
        assertThat(red.green).isLessThan(0.3f)
        assertThat(pixelAt(0.5f, 0.55f).blue).isGreaterThan(0.9f)
    }

    @Test
    fun `zones that are not shown are not drawn`() {
        show(whitePage().copy(zonesShown = false, panels = PanelReading(zones = mapOf(11 to cut))))

        val side = pixelAt(0.1f, 0.4f)

        assertThat(side.red).isGreaterThan(0.9f)
        assertThat(side.blue).isGreaterThan(0.9f)
    }

    /** What is drawn at [x] of the width of the page and [y] of its height. */
    private fun pixelAt(x: Float, y: Float): Color {
        val pixels = compose.onNodeWithTag(ComicSpreadTag).captureToImage().toPixelMap()
        val page = compose.onNodeWithContentDescription("Page 12").drawn()
        return pixels[(page.left + page.width * x).toInt(), (page.top + page.height * y).toInt()]
    }

    /** The page 11 (from 0), white, on the device. */
    private fun whitePage(): ComicReaderUiState {
        val white = pageImage(folder.root, 11, width = 165, height = 240, colour = android.graphics.Color.WHITE)
        val onBlack = ComicSettings(background = Background.Black)
        return album(spreadIndex = 11, settings = onBlack, pages = mapOf(11 to white))
    }

    private fun show(state: ComicReaderUiState) {
        compose.setContent {
            images.Provide { LirelleTheme { ComicSpreadView(state, RecordingNavigation(), onToggleBars = {}) } }
        }
    }
}
