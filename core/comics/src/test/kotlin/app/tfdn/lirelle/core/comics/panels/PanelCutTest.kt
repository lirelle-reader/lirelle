package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.settings.Direction
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Random
import org.junit.Test

private const val Close = 0.0005f
private const val Opaque = 0xFF shl 24
private const val Colours = 1 shl 24
private const val Square = 8
private const val Seed = 44L

/** The sizes the cut must stand, the least it takes and long thin ones: width to height. */
private val OddSizes = listOf(64 to 64, 64 to 1600, 1600 to 64)

private val Pages = listOf(
    "grid", "tall_left", "tall_right", "inset", "black_page", "tilted", "shared_lines", "caption", "balloon",
    "footnote", "full_bleed", "loose_pieces", "panel_and_pieces", "title_page", "dark_shadows",
)

class PanelCutTest {

    @Test
    fun `every test page is cut as the reference cuts it, both ways`() {
        for (name in Pages) {
            for (direction in Direction.entries) {
                val zones = cutPage(testPage(name), direction)
                val expected = expectedZones(name, direction)
                assertWithMessage("$name $direction: kinds").that(zones.map { it.kind })
                    .containsExactlyElementsIn(expected.map { it.kind }).inOrder()
                zones.zip(expected).forEachIndexed { i, (got, want) ->
                    val zone = "$name $direction zone $i"
                    assertWithMessage("$zone left").that(got.left).isWithin(Close).of(want.left)
                    assertWithMessage("$zone top").that(got.top).isWithin(Close).of(want.top)
                    assertWithMessage("$zone right").that(got.right).isWithin(Close).of(want.right)
                    assertWithMessage("$zone bottom").that(got.bottom).isWithin(Close).of(want.bottom)
                }
            }
        }
    }

    @Test
    fun `a grid is read row by row, from the left`() {
        val zones = cutPage(testPage("grid"), Direction.LeftToRight)
        assertThat(zones).hasSize(8)
        assertThat(zones.all { it.kind == ZoneKind.Filled }).isTrue()
        // First row from the left, then the second.
        assertThat(zones[0].left).isLessThan(zones[1].left)
        assertThat(zones[1].left).isLessThan(zones[2].left)
        assertThat(zones[3].top).isGreaterThan(zones[0].bottom)
    }

    @Test
    fun `read from the right, each row starts at the right`() {
        val zones = cutPage(testPage("grid"), Direction.RightToLeft)
        assertThat(zones[0].left).isGreaterThan(zones[1].left)
        assertThat(zones[1].left).isGreaterThan(zones[2].left)
        assertThat(zones[3].top).isGreaterThan(zones[0].bottom)
    }

    @Test
    fun `a tall panel is read before the stack beside it when it comes first`() {
        val zones = cutPage(testPage("tall_left"), Direction.LeftToRight)
        assertThat(zones).hasSize(9)
        val tall = zones[3]
        assertThat(tall.bottom - tall.top).isGreaterThan(0.5f)
        // The five after it are the stack: two, then three, all to its right.
        assertThat(zones.drop(4).all { it.left > tall.right - 0.01f }).isTrue()
    }

    @Test
    fun `a tall panel after the stack is read after it`() {
        val zones = cutPage(testPage("tall_right"), Direction.LeftToRight)
        assertThat(zones).hasSize(9)
        assertThat(zones.last().bottom - zones.last().top).isGreaterThan(0.5f)
    }

    @Test
    fun `a row an inset lies across stays whole, the rows around it are cut`() {
        val zones = cutPage(testPage("inset"), Direction.LeftToRight)
        assertThat(zones).hasSize(8)
        // The rows around it: three panels each, none as wide as the page.
        assertThat(zones.subList(1, 4).all { it.right - it.left < 0.4f }).isTrue()
        assertThat(zones.subList(5, 8).all { it.right - it.left < 0.4f }).isTrue()
        assertThat(zones[4].right - zones[4].left).isGreaterThan(0.8f)
    }

    @Test
    fun `a black page, a tilted one, panels sharing a line are cut`() {
        assertThat(cutPage(testPage("black_page"), Direction.LeftToRight)).hasSize(7)
        assertThat(cutPage(testPage("tilted"), Direction.LeftToRight)).hasSize(6)
        assertThat(cutPage(testPage("shared_lines"), Direction.LeftToRight)).hasSize(12)
    }

    @Test
    fun `a caption, a balloon across a gutter and a footnote change nothing`() {
        assertThat(cutPage(testPage("caption"), Direction.LeftToRight)).hasSize(4)
        assertThat(cutPage(testPage("balloon"), Direction.LeftToRight)).hasSize(4)
        assertThat(cutPage(testPage("footnote"), Direction.LeftToRight)).hasSize(9)
    }

    @Test
    fun `a page that is one picture is not cut`() {
        // The reference's answer: the picture has no margin to tell its gutters by, and nothing is kept.
        for (direction in Direction.entries) assertThat(cutPage(testPage("full_bleed"), direction)).isEmpty()
    }

    @Test
    fun `pieces on a ground are no panels, and the page is not cut`() {
        for (direction in Direction.entries) assertThat(cutPage(testPage("loose_pieces"), direction)).isEmpty()
    }

    @Test
    fun `a panel and loose pieces do not tile the page, and it is not cut`() {
        for (direction in Direction.entries) assertThat(cutPage(testPage("panel_and_pieces"), direction)).isEmpty()
    }

    @Test
    fun `a dark page whose panels all look sparse is cut, since they tile it`() {
        for (direction in Direction.entries) {
            val kinds = cutPage(testPage("dark_shadows"), direction).map { it.kind }
            assertThat(kinds).hasSize(7)
            assertThat(kinds.toSet()).containsExactly(ZoneKind.Sparse)
        }
    }

    @Test
    fun `a title and one small picture are no panels, and the page is not cut`() {
        for (direction in Direction.entries) assertThat(cutPage(testPage("title_page"), direction)).isEmpty()
    }

    @Test
    fun `a picture too small to be a page gives nothing`() {
        assertThat(cutPage(PagePixels(8, 8, IntArray(64)), Direction.LeftToRight)).isEmpty()
    }

    @Test
    fun `a picture one pixel too narrow gives nothing, whatever is on it`() {
        for ((name, picture) in pictures(63, 200)) {
            for (direction in Direction.entries) {
                assertWithMessage("$name $direction").that(cutPage(picture, direction)).isEmpty()
            }
        }
    }

    @Test
    fun `the smallest and the thinnest pictures are cut without failing`() {
        for ((width, height) in OddSizes) {
            for ((name, picture) in pictures(width, height)) assertCutHolds(name, picture)
        }
    }

    @Test
    fun `noise is cut without failing`() {
        assertCutHolds("65 by 65 noise", noise(65, 65))
        assertCutHolds("100 by 100 noise", noise(100, 100))
    }

    /** One colour, noise and a checkerboard, [width] by [height], each with its name. */
    private fun pictures(width: Int, height: Int): List<Pair<String, PagePixels>> = listOf(
        "$width by $height white" to drawnPage(width, height, 0xFFFFFF),
        "$width by $height grey" to drawnPage(width, height, 0x7F7F7F),
        "$width by $height black" to drawnPage(width, height, 0x000000),
        "$width by $height noise" to noise(width, height),
        "$width by $height checkerboard" to checkerboard(width, height),
    )

    /** Pixels of any colour, the same at every run. */
    private fun noise(width: Int, height: Int): PagePixels {
        val random = Random(Seed)
        return PagePixels(width, height, IntArray(width * height) { random.nextInt(Colours) or Opaque })
    }

    /** Black and white squares of 8 pixels. */
    private fun checkerboard(width: Int, height: Int): PagePixels = PagePixels(
        width,
        height,
        IntArray(width * height) {
            val white = (it % width / Square + it / width / Square) % 2 == 0
            (if (white) 0xFFFFFF else 0x000000) or Opaque
        },
    )

    /** Cuts [picture] both ways: nothing is thrown, and every zone is a true box inside the page. */
    private fun assertCutHolds(name: String, picture: PagePixels) {
        for (direction in Direction.entries) {
            for (zone in cutPage(picture, direction)) {
                val told = "$name $direction $zone"
                assertWithMessage(told).that(zone.left).isAtLeast(0f)
                assertWithMessage(told).that(zone.left).isLessThan(zone.right)
                assertWithMessage(told).that(zone.right).isAtMost(1f)
                assertWithMessage(told).that(zone.top).isAtLeast(0f)
                assertWithMessage(told).that(zone.top).isLessThan(zone.bottom)
                assertWithMessage(told).that(zone.bottom).isAtMost(1f)
            }
        }
    }
}
