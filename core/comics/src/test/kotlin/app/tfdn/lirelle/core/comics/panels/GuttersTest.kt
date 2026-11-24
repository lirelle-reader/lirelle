package app.tfdn.lirelle.core.comics.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuttersTest {

    @Test
    fun `the gutter colour is the colour of the page's border`() {
        assertThat(gutterColour(testPage("grid")).toList()).containsExactly(255, 255, 255).inOrder()
        assertThat(gutterColour(testPage("black_page")).toList()).containsExactly(12, 12, 12).inOrder()
    }

    @Test
    fun `the white inside a framed panel is no gutter`() {
        // A white page, a black frame 4 px thick around a white inside, far from the edges.
        val page = drawnPage(
            200, 200, 0xFFFFFF,
            listOf(40, 40, 160, 160) to 0x000000,
            listOf(44, 44, 156, 156) to 0xFFFFFF,
        )
        val outer = outerBackground(nearColour(page, gutterColour(page)), page.width, page.height)
        assertThat(outer[10, 10]).isTrue()
        assertThat(outer[100, 100]).isFalse()
        assertThat(outer[41, 100]).isFalse()
    }

    @Test
    fun `the gutter-coloured inside of a panel open at the edge of the page is gutter`() {
        // The frame is missing along the left edge of the page, and the inside is white as the page is: the flood
        // comes in from the pad there, as in the reference. Without the pad the inside would be shut in by the
        // edge of the page and stay a panel.
        val page = drawnPage(
            200, 200, 0xFFFFFF,
            listOf(0, 40, 160, 44) to 0x000000,
            listOf(0, 156, 160, 160) to 0x000000,
            listOf(156, 40, 160, 160) to 0x000000,
        )
        val outer = outerBackground(nearColour(page, gutterColour(page)), page.width, page.height)
        assertThat(outer[80, 100]).isTrue()
        assertThat(outer[2, 100]).isTrue()
        // What is left of the frame is no gutter, and the page beyond it is.
        assertThat(outer[158, 100]).isFalse()
        assertThat(outer[80, 42]).isFalse()
        assertThat(outer[180, 100]).isTrue()
    }

    @Test
    fun `the gutters a panel running off both sides of the page parts are joined around it by the pad`() {
        // A grey panel from the left edge of the page to the right one, with no frame: the white under it reaches
        // the corner the flood starts from only around the panel, through the pad.
        val page = drawnPage(200, 200, 0xFFFFFF, listOf(0, 60, 200, 120) to 0x808080)
        val outer = outerBackground(nearColour(page, gutterColour(page)), page.width, page.height)
        assertThat(outer[100, 30]).isTrue()
        assertThat(outer[100, 160]).isTrue()
        assertThat(outer[0, 199]).isTrue()
        // The panel itself is no gutter, up to the edges of the page.
        assertThat(outer[100, 90]).isFalse()
        assertThat(outer[0, 90]).isFalse()
        assertThat(outer[199, 90]).isFalse()
    }

    @Test
    fun `a small blob lying in the gutter is gutter`() {
        val page = drawnPage(
            400, 400, 0xFFFFFF,
            listOf(40, 40, 360, 180) to 0x808080,
            listOf(40, 220, 360, 360) to 0x808080,
            listOf(100, 196, 180, 204) to 0x000000,
        )
        val outer = outerBackground(nearColour(page, gutterColour(page)), page.width, page.height)
        assertThat(outer[140, 200]).isTrue()
        assertThat(outer[200, 100]).isFalse()
    }

    @Test
    fun `a speck's own cells become gutter, not what else lies in its box`() {
        // Gutter everywhere but an L 5 wide and 20 tall, small and thin enough to be a speck, and a large block
        // with a tongue that reaches into the L's box without touching it.
        val width = 200
        val outer = Plane(
            width,
            200,
            BooleanArray(width * 200) {
                val x = it % width
                val y = it / width
                val speck = x == 10 && y in 10..29 || y == 29 && x in 10..14
                val block = x in 40..139 && y in 0..99 || y == 15 && x in 12..39
                !speck && !block
            },
        )
        fillSpecks(outer)
        // The L, bar and foot.
        assertThat(outer[10, 10]).isTrue()
        assertThat(outer[10, 20]).isTrue()
        assertThat(outer[14, 29]).isTrue()
        // The tongue inside the L's box, and the block it belongs to.
        assertThat(outer[12, 15]).isFalse()
        assertThat(outer[14, 15]).isFalse()
        assertThat(outer[90, 50]).isFalse()
        // What was gutter in the box stays gutter.
        assertThat(outer[12, 20]).isTrue()
    }
}
