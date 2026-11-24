package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import app.tfdn.lirelle.core.comics.layout.Corner
import app.tfdn.lirelle.core.comics.settings.Fit
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Where a spread stands in the screen: its size for each fit, and how far it can be moved at a zoom. */
class SpreadGeometryTest {

    private val portrait = Size(1000f, 2000f)
    private val landscape = Size(2000f, 1000f)

    /** A page of 1650 by 2400: 0.6875 as wide as it is high. */
    private val page = Size(1650f, 2400f)
    private val wide = Size(3300f, 2400f)

    @Test
    fun `a page that is ready has its own shape`() {
        assertThat(pageShapes(listOf(page), portrait)).containsExactly(0.6875f)
    }

    @Test
    fun `a page alone that is not there yet takes the shape of the screen`() {
        assertThat(pageShapes(listOf(null), portrait)).containsExactly(0.5f)
    }

    @Test
    fun `in a pair, a page that is not there yet takes the shape of the other`() {
        assertThat(pageShapes(listOf(null, page), landscape)).containsExactly(0.6875f, 0.6875f).inOrder()
        assertThat(pageShapes(listOf(page, null), landscape)).containsExactly(0.6875f, 0.6875f).inOrder()
    }

    @Test
    fun `a pair of which nothing is there yet shares the screen in two halves`() {
        assertThat(pageShapes(listOf(null, null), landscape)).containsExactly(1f, 1f).inOrder()
    }

    @Test
    fun `a whole page is as high as a screen in landscape`() {
        assertThat(fittedSize(listOf(0.5f), landscape, Fit.Page)).isEqualTo(Size(500f, 1000f))
    }

    @Test
    fun `a whole page is as wide as a narrow screen`() {
        // A page of 3 by 4 in a screen of 1 by 2: the width is what stops it.
        assertThat(fittedSize(listOf(0.75f), portrait, Fit.Page)).isEqualTo(Size(1000f, 1000f / 0.75f))
    }

    @Test
    fun `two pages are fitted together, as one image`() {
        // Two pages of 3 by 4 side by side are 3 by 2: in a screen of 2 by 1, the height stops them.
        assertThat(fittedSize(listOf(0.75f, 0.75f), landscape, Fit.Page)).isEqualTo(Size(1500f, 1000f))
        // In a screen of 1 by 2, the width does.
        assertThat(fittedSize(listOf(0.75f, 0.75f), portrait, Fit.Page)).isEqualTo(Size(1000f, 1000f / 1.5f))
    }

    @Test
    fun `a wide page is as wide as the screen and lower than it`() {
        val shape = pageShapes(listOf(wide), portrait).single()

        assertThat(fittedSize(listOf(shape), portrait, Fit.Page)).isEqualTo(Size(1000f, 1000f / 1.375f))
    }

    @Test
    fun `fitted to the width, a page fills the width and goes past the bottom`() {
        assertThat(fittedSize(listOf(0.5f), landscape, Fit.Width)).isEqualTo(Size(2000f, 4000f))
    }

    @Test
    fun `fitted to the width, two pages are still fitted whole`() {
        assertThat(fittedSize(listOf(0.75f, 0.75f), landscape, Fit.Width)).isEqualTo(Size(1500f, 1000f))
    }

    @Test
    fun `the fit to the width is for a reader in one page`() {
        assertThat(spreadFit(Fit.Width, twoPages = false)).isEqualTo(Fit.Width)
        assertThat(spreadFit(Fit.Page, twoPages = false)).isEqualTo(Fit.Page)
    }

    @Test
    fun `with two pages the fit is the whole pages, for a page that stands alone too`() {
        assertThat(spreadFit(Fit.Width, twoPages = true)).isEqualTo(Fit.Page)
        assertThat(spreadFit(Fit.Page, twoPages = true)).isEqualTo(Fit.Page)
    }

    @Test
    fun `a spread smaller than the screen stays in its middle`() {
        val bounds = panBounds(Size(500f, 1000f), landscape, zoom = 1f)

        assertThat(bounds.min).isEqualTo(Offset(750f, 0f))
        assertThat(bounds.max).isEqualTo(Offset(750f, 0f))
    }

    @Test
    fun `a zoomed spread moves until its edges reach those of the screen`() {
        // 500 by 1000 at twice its size is 1000 by 2000, in a screen of 2000 by 1000.
        val bounds = panBounds(Size(500f, 1000f), landscape, zoom = 2f)

        // Still narrower than the screen: centred. Higher than it: from its top to its bottom.
        assertThat(bounds.min).isEqualTo(Offset(500f, -1000f))
        assertThat(bounds.max).isEqualTo(Offset(500f, 0f))
    }

    @Test
    fun `at four times, the spread goes past the screen both ways`() {
        val bounds = panBounds(Size(500f, 1000f), landscape, zoom = 4f)

        assertThat(bounds.min).isEqualTo(Offset(0f, -3000f))
        assertThat(bounds.max).isEqualTo(Offset(0f, 0f))
    }

    @Test
    fun `a place outside the bounds is brought back to the nearest one inside`() {
        val bounds = PanBounds(min = Offset(-100f, -300f), max = Offset(0f, 0f))

        assertThat(bounds.clamp(Offset(40f, -500f))).isEqualTo(Offset(0f, -300f))
        assertThat(bounds.clamp(Offset(-150f, 10f))).isEqualTo(Offset(-100f, 0f))
        assertThat(bounds.clamp(Offset(-50f, -20f))).isEqualTo(Offset(-50f, -20f))
    }

    @Test
    fun `each corner of the spread is an end of the bounds`() {
        val bounds = PanBounds(min = Offset(-100f, -300f), max = Offset(0f, 0f))

        assertThat(bounds.at(Corner.TopLeft)).isEqualTo(Offset(0f, 0f))
        assertThat(bounds.at(Corner.TopRight)).isEqualTo(Offset(-100f, 0f))
        assertThat(bounds.at(Corner.BottomLeft)).isEqualTo(Offset(0f, -300f))
        assertThat(bounds.at(Corner.BottomRight)).isEqualTo(Offset(-100f, -300f))
    }

    @Test
    fun `a centred spread is at the same place whatever the corner`() {
        val bounds = panBounds(Size(500f, 1000f), landscape, zoom = 1f)

        Corner.entries.forEach { assertThat(bounds.at(it)).isEqualTo(Offset(750f, 0f)) }
    }

    @Test
    fun `a spread nobody moved stands at its opening corner, a moved one where it was left, within the bounds`() {
        val bounds = PanBounds(min = Offset(-100f, -300f), max = Offset(0f, 0f))

        assertThat(bounds.place(pan = null, Corner.BottomRight)).isEqualTo(Offset(-100f, -300f))
        assertThat(bounds.place(pan = Offset(-30f, -40f), Corner.BottomRight)).isEqualTo(Offset(-30f, -40f))
        assertThat(bounds.place(pan = Offset(-30f, -900f), Corner.TopLeft)).isEqualTo(Offset(-30f, -300f))
    }

    @Test
    fun `zooming keeps the point under the finger where it is`() {
        // The spread starts at (100, 50); the finger is at (300, 250), that is (200, 200) inside it.
        val moved = zoomedAround(Offset(300f, 250f), offset = Offset(100f, 50f), from = 1f, to = 2f)

        // Twice as large, that point is (400, 400) inside the spread: it must still be under the finger.
        assertThat(moved).isEqualTo(Offset(-100f, -150f))
    }

    @Test
    fun `the image is asked no larger than the screen at the highest zoom`() {
        // A slot of 500 by 1000 at four times is 2000 by 4000: the image of 6000 by 12000 is asked at that size.
        assertThat(decodeSize(IntSize(6000, 12_000), Size(500f, 1000f))).isEqualTo(IntSize(2000, 4000))
    }

    @Test
    fun `the image is never asked larger than it is`() {
        assertThat(decodeSize(IntSize(1650, 2400), Size(1000f, 1454f))).isEqualTo(IntSize(1650, 2400))
    }

    @Test
    fun `the image is never asked past the longest side a screen can draw`() {
        // A slot of 2000 by 4000 at four times would be 8000 by 16000.
        assertThat(decodeSize(IntSize(10_000, 20_000), Size(2000f, 4000f))).isEqualTo(IntSize(2048, 4096))
    }

    @Test
    fun `a page is sharp at a zoom with the long side of its room at that zoom`() {
        // A page drawn for its room of 500 by 1000: at twice the fit that room is 1000 by 2000.
        assertThat(sharpSide(IntSize(500, 1000), Size(500f, 1000f), zoom = 2f)).isEqualTo(2000)
        assertThat(sharpSide(IntSize(1000, 500), Size(800f, 400f), zoom = 3f)).isEqualTo(2400)
    }

    @Test
    fun `the sharp side is that of the room whatever the size of the picture there`() {
        assertThat(sharpSide(IntSize(250, 500), Size(500f, 1000f), zoom = 2f)).isEqualTo(2000)
        assertThat(sharpSide(IntSize(2000, 4000), Size(500f, 1000f), zoom = 2f)).isEqualTo(2000)
    }

    @Test
    fun `the sharp side never goes past the longest side a screen can draw`() {
        // A room of 2000 by 4000 at four times would be 16000 high.
        assertThat(sharpSide(IntSize(2000, 4000), Size(2000f, 4000f), zoom = 4f)).isEqualTo(4096)
    }

    @Test
    fun `a picture without a size has no sharp side`() {
        assertThat(sharpSide(IntSize(0, 0), Size(500f, 1000f), zoom = 2f)).isEqualTo(0)
    }

    @Test
    fun `a picture is too small for a side longer than its own`() {
        assertThat(tooSmallFor(IntSize(1448, 2048), side = 3394)).isTrue()
        assertThat(tooSmallFor(IntSize(1448, 2048), side = 2048)).isFalse()
        assertThat(tooSmallFor(IntSize(1448, 2048), side = 1000)).isFalse()
    }

    @Test
    fun `a picture short of a side by what the rounding of its sides leaves is not too small`() {
        // Two pixels for a page whose long side is under twice its short one, more for a longer one.
        assertThat(tooSmallFor(IntSize(1448, 2048), side = 2050)).isFalse()
        assertThat(tooSmallFor(IntSize(1448, 2048), side = 2051)).isTrue()
        assertThat(tooSmallFor(IntSize(4000, 1000), side = 4004)).isFalse()
        assertThat(tooSmallFor(IntSize(4000, 1000), side = 4005)).isTrue()
    }

    @Test
    fun `a picture without a size is never too small`() {
        assertThat(tooSmallFor(IntSize(0, 0), side = 2000)).isFalse()
    }
}
