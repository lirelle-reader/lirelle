package app.tfdn.lirelle.feature.comics.strip

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The strip of an album read vertically: what a tap asks, the shape of a page not there yet, its slices and items. */
class StripRulesTest {

    @Test
    fun `the top quarter goes back, the bottom quarter forward, the middle shows the bars`() {
        assertThat(stripTap(y = 0f, height = 800f)).isEqualTo(StripTap.Back)
        assertThat(stripTap(y = 199f, height = 800f)).isEqualTo(StripTap.Back)
        assertThat(stripTap(y = 200f, height = 800f)).isEqualTo(StripTap.Bars)
        assertThat(stripTap(y = 599f, height = 800f)).isEqualTo(StripTap.Bars)
        assertThat(stripTap(y = 600f, height = 800f)).isEqualTo(StripTap.Forward)
        assertThat(stripTap(y = 10f, height = 0f)).isEqualTo(StripTap.Bars)
    }

    @Test
    fun `a tap moves by a screen less a tenth`() {
        assertThat(stripStep(viewportHeight = 800f)).isEqualTo(720f)
    }

    @Test
    fun `a page not there yet has the shape of the nearest page known, the one before first`() {
        val shapes = mapOf(3 to 0.5f, 8 to 0.25f)
        assertThat(provisionalShape(shapes, index = 4, stripWidth = 400f, viewportHeight = 800f)).isEqualTo(0.5f)
        assertThat(provisionalShape(shapes, index = 7, stripWidth = 400f, viewportHeight = 800f)).isEqualTo(0.25f)
        assertThat(provisionalShape(shapes, index = 5, stripWidth = 400f, viewportHeight = 800f)).isEqualTo(0.5f)
        // As far from the one as from the other.
        val around = mapOf(7 to 0.25f, 3 to 0.5f)
        assertThat(provisionalShape(around, index = 5, stripWidth = 400f, viewportHeight = 800f)).isEqualTo(0.5f)
    }

    @Test
    fun `with no page known, a page not there yet is one screen high`() {
        assertThat(provisionalShape(emptyMap(), index = 0, stripWidth = 400f, viewportHeight = 800f)).isEqualTo(0.5f)
        assertThat(provisionalShape(emptyMap(), index = 0, stripWidth = 400f, viewportHeight = 0f)).isEqualTo(1f)
    }

    @Test
    fun `a page that fits one picture is one slice, its whole height`() {
        assertThat(stripSlices(width = 800, height = 1200, drawnWidth = 1080)).containsExactly(StripSlice(0, 1200))
        // Drawn smaller than it is: 1600 x 12000 at 400 wide is 3000 high.
        assertThat(stripSlices(width = 1600, height = 12_000, drawnWidth = 400))
            .containsExactly(StripSlice(0, 12_000))
    }

    @Test
    fun `a page too tall for one picture is cut in slices no higher than the limit once drawn`() {
        // 800 x 15000 drawn 1080 wide: not scaled down, 2048 rows a slice.
        val slices = stripSlices(width = 800, height = 15_000, drawnWidth = 1080)
        assertThat(slices.first()).isEqualTo(StripSlice(0, 2048))
        assertThat(slices.last().bottom).isEqualTo(15_000)
        assertThat(slices.zipWithNext().all { (a, b) -> a.bottom == b.top }).isTrue()
        assertThat(slices.all { it.bottom - it.top <= 2048 }).isTrue()
        assertThat(slices).hasSize(8)
    }

    @Test
    fun `slices of a page drawn smaller than it is hold more rows`() {
        // 1600 x 40000 drawn 800 wide: half size, 20000 high; 4096 rows a slice.
        val slices = stripSlices(width = 1600, height = 40_000, drawnWidth = 800)
        assertThat(slices.first()).isEqualTo(StripSlice(0, 4096))
        assertThat(slices).hasSize(10)
    }

    @Test
    fun `a slice is decoded at the largest power of two that keeps it as wide as it is drawn`() {
        assertThat(sliceSample(width = 800, drawnWidth = 1080)).isEqualTo(1)
        assertThat(sliceSample(width = 1600, drawnWidth = 800)).isEqualTo(2)
        assertThat(sliceSample(width = 4000, drawnWidth = 900)).isEqualTo(4)
        assertThat(sliceSample(width = 800, drawnWidth = 0)).isEqualTo(1)
    }

    @Test
    fun `the items of the strip are the slices of its pages, in order`() {
        val layout = StripLayout(listOf(1, 3, 1, 2))
        assertThat(layout.size).isEqualTo(7)
        assertThat(layout.itemOf(page = 0)).isEqualTo(0)
        assertThat(layout.itemOf(page = 1)).isEqualTo(1)
        assertThat(layout.itemOf(page = 2)).isEqualTo(4)
        assertThat(layout.itemOf(page = 3)).isEqualTo(5)
        assertThat((0..6).map(layout::pageOf)).containsExactly(0, 1, 1, 1, 2, 3, 3).inOrder()
        assertThat((0..6).map(layout::sliceOf)).containsExactly(0, 0, 1, 2, 0, 0, 1).inOrder()
    }

    @Test
    fun `an item outside the strip is its nearest end`() {
        val layout = StripLayout(listOf(1, 3))
        assertThat(layout.pageOf(-1)).isEqualTo(0)
        assertThat(layout.pageOf(9)).isEqualTo(1)
        assertThat(layout.itemOf(page = 5)).isEqualTo(1)
        assertThat(StripLayout(emptyList()).size).isEqualTo(0)
    }
}
