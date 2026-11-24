package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.tfdn.lirelle.core.comics.layout.openingCorner
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.Direction.LeftToRight
import app.tfdn.lirelle.core.comics.settings.Direction.RightToLeft
import app.tfdn.lirelle.core.comics.settings.Fit
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The walk of two pages side by side: one page after the other. The screen is 1000 by 800. Two portrait pages
 * of 500 by 800 fill it at the fit; a pair at the fit is never wider than the screen, so that at twice the fit
 * a page of it is at most as wide as the screen, and it takes more for a page to have rows to walk along.
 */
class SpreadPairWalkTest {

    private val screen = Size(1000f, 800f)

    /** At three times the fit: each page is 1500 wide and 2400 high. */
    private val wide = pair(pageWidth = 500f, zoom = 3f)

    /** At twice the fit: each page is exactly as wide as the screen, and 1600 high. */
    private val twice = pair(pageWidth = 500f, zoom = 2f)

    /** Narrower pages at twice the fit: each is 800 wide, the pair 1600. */
    private val narrow = pair(pageWidth = 400f, zoom = 2f)

    @Test
    fun `the rooms of the pages share the width of the spread by their shapes, from the left`() {
        assertThat(pageSpans(listOf(0.5f, 1.5f), width = 1000f))
            .containsExactly(PageSpan(0f, 250f), PageSpan(250f, 1000f)).inOrder()
        assertThat(pageSpans(listOf(0.7f), width = 600f)).containsExactly(PageSpan(0f, 600f))
    }

    @Test
    fun `forward, the first page is walked row by row to its bottom, then the second from its top`() {
        assertThat(walk(Turn.Forward, from = Offset.Zero, wide, LeftToRight)).containsExactly(
            // The first page: a row ends at that page's right edge, not at the spread's.
            Offset(-500f, 0f),
            Offset(0f, -720f),
            Offset(-500f, -720f),
            Offset(0f, -1440f),
            Offset(-500f, -1440f),
            Offset(0f, -1600f),
            Offset(-500f, -1600f),
            // The second page, from its top left.
            Offset(-1500f, 0f),
            Offset(-2000f, 0f),
            Offset(-1500f, -720f),
            Offset(-2000f, -720f),
            Offset(-1500f, -1440f),
            Offset(-2000f, -1440f),
            Offset(-1500f, -1600f),
            Offset(-2000f, -1600f),
        ).inOrder()
    }

    @Test
    fun `back, the second page is walked up from its end, then the first from its end to its top`() {
        assertThat(walk(Turn.Back, from = Offset(-2000f, -1600f), wide, LeftToRight)).containsExactly(
            Offset(-1500f, -1600f),
            Offset(-2000f, -880f),
            Offset(-1500f, -880f),
            Offset(-2000f, -160f),
            Offset(-1500f, -160f),
            Offset(-2000f, 0f),
            Offset(-1500f, 0f),
            // The first page, from its bottom right.
            Offset(-500f, -1600f),
            Offset(0f, -1600f),
            Offset(-500f, -880f),
            Offset(0f, -880f),
            Offset(-500f, -160f),
            Offset(0f, -160f),
            Offset(-500f, 0f),
            Offset(0f, 0f),
        ).inOrder()
    }

    @Test
    fun `right to left, the first page is the one at the right, and a row runs from its right edge to its left`() {
        assertThat(walk(Turn.Forward, from = Offset(-2000f, 0f), wide, RightToLeft)).containsExactly(
            Offset(-1500f, 0f),
            Offset(-2000f, -720f),
            Offset(-1500f, -720f),
            Offset(-2000f, -1440f),
            Offset(-1500f, -1440f),
            Offset(-2000f, -1600f),
            Offset(-1500f, -1600f),
            // The second page is the one at the left, from its top right.
            Offset(-500f, 0f),
            Offset(0f, 0f),
            Offset(-500f, -720f),
            Offset(0f, -720f),
            Offset(-500f, -1440f),
            Offset(0f, -1440f),
            Offset(-500f, -1600f),
            Offset(0f, -1600f),
        ).inOrder()
    }

    @Test
    fun `right to left, the walk back starts at the bottom left of the page at the left`() {
        assertThat(walk(Turn.Back, from = Offset(0f, -1600f), wide, RightToLeft)).containsExactly(
            Offset(-500f, -1600f),
            Offset(0f, -880f),
            Offset(-500f, -880f),
            Offset(0f, -160f),
            Offset(-500f, -160f),
            Offset(0f, 0f),
            Offset(-500f, 0f),
            // The page at the right, from its bottom left.
            Offset(-1500f, -1600f),
            Offset(-2000f, -1600f),
            Offset(-1500f, -880f),
            Offset(-2000f, -880f),
            Offset(-1500f, -160f),
            Offset(-2000f, -160f),
            Offset(-1500f, 0f),
            Offset(-2000f, 0f),
        ).inOrder()
    }

    @Test
    fun `at twice the fit each page is as wide as the screen - it is framed and only walked down, then up`() {
        assertThat(walk(Turn.Forward, from = Offset.Zero, twice, LeftToRight)).containsExactly(
            Offset(0f, -720f),
            Offset(0f, -800f),
            Offset(-1000f, 0f),
            Offset(-1000f, -720f),
            Offset(-1000f, -800f),
        ).inOrder()
        assertThat(walk(Turn.Back, from = Offset(-1000f, -800f), twice, LeftToRight)).containsExactly(
            Offset(-1000f, -80f),
            Offset(-1000f, 0f),
            Offset(0f, -800f),
            Offset(0f, -80f),
            Offset(0f, 0f),
        ).inOrder()
        assertThat(walk(Turn.Forward, from = Offset(-1000f, 0f), twice, RightToLeft)).containsExactly(
            Offset(-1000f, -720f),
            Offset(-1000f, -800f),
            Offset(0f, 0f),
            Offset(0f, -720f),
            Offset(0f, -800f),
        ).inOrder()
    }

    @Test
    fun `a page narrower than the screen is framed on its start edge, as far as the spread can move`() {
        // Left to right the first page has its left edge at the screen's; the second cannot: the spread ends.
        assertThat(walk(Turn.Forward, from = Offset.Zero, narrow, LeftToRight)).containsExactly(
            Offset(0f, -720f),
            Offset(0f, -800f),
            Offset(-600f, 0f),
            Offset(-600f, -720f),
            Offset(-600f, -800f),
        ).inOrder()
        // Right to left the start edge is the right one.
        assertThat(walk(Turn.Forward, from = Offset(-600f, 0f), narrow, RightToLeft)).containsExactly(
            Offset(-600f, -720f),
            Offset(-600f, -800f),
            Offset(0f, 0f),
            Offset(0f, -720f),
            Offset(0f, -800f),
        ).inOrder()
        assertThat(walk(Turn.Back, from = Offset(-600f, -800f), narrow, LeftToRight)).containsExactly(
            Offset(-600f, -80f),
            Offset(-600f, 0f),
            Offset(0f, -800f),
            Offset(0f, -80f),
            Offset(0f, 0f),
        ).inOrder()
    }

    @Test
    fun `a pair no wider than the screen is walked down once, as one picture`() {
        // 1.25 times the fit of pages 400 wide: the pair is as wide as the screen, and 200 higher.
        val fits = pair(pageWidth = 400f, zoom = 1.25f)

        assertThat(walk(Turn.Forward, from = Offset.Zero, fits, LeftToRight)).containsExactly(Offset(0f, -200f))
        assertThat(walk(Turn.Back, from = Offset(0f, -200f), fits, RightToLeft)).containsExactly(Offset.Zero)
    }

    @Test
    fun `a pair that fits the screen turns at once`() {
        val fits = pair(pageWidth = 400f, zoom = 1f)
        val at = fits.bounds.at(LeftToRight.openingCorner(forward = true))

        assertThat(step(Turn.Forward, at, fits, LeftToRight)).isNull()
        assertThat(step(Turn.Back, at, fits, RightToLeft)).isNull()
    }

    @Test
    fun `the corner a pair opens at going forward is the start of its first page, going back the end of its last`() {
        for (spread in listOf(wide, twice, narrow)) {
            for (direction in Direction.entries) {
                val start = spread.bounds.at(direction.openingCorner(forward = true))
                val end = spread.bounds.at(direction.openingCorner(forward = false))

                // Nothing is before the start nor after the end, and each walk leads from one to the other.
                assertThat(step(Turn.Back, start, spread, direction)).isNull()
                assertThat(step(Turn.Forward, end, spread, direction)).isNull()
                assertThat(walk(Turn.Forward, start, spread, direction).last()).isEqualTo(end)
                assertThat(walk(Turn.Back, end, spread, direction).last()).isEqualTo(start)
            }
        }
        // The first page of a pair read right to left is the one at the right.
        assertThat(wide.bounds.at(RightToLeft.openingCorner(forward = true))).isEqualTo(Offset(-2000f, 0f))
        assertThat(wide.bounds.at(LeftToRight.openingCorner(forward = false))).isEqualTo(Offset(-2000f, -1600f))
    }

    @Test
    fun `a page a finger left in its middle is walked on from there`() {
        // On the first page: along its row to its edge.
        assertThat(step(Turn.Forward, Offset(-200f, -300f), wide, LeftToRight)).isEqualTo(Offset(-500f, -300f))
        // On the second page: along its row, and back no further than its own left edge.
        assertThat(step(Turn.Forward, Offset(-1700f, -300f), wide, LeftToRight)).isEqualTo(Offset(-2000f, -300f))
        assertThat(step(Turn.Back, Offset(-1700f, -300f), wide, LeftToRight)).isEqualTo(Offset(-1500f, -300f))
        assertThat(step(Turn.Back, Offset(-200f, -300f), wide, LeftToRight)).isEqualTo(Offset(0f, -300f))
    }

    @Test
    fun `a screen left across both pages is at the end of the first page's row going forward`() {
        val across = Offset(-1000f, -300f)

        // Forward the first page is not finished: its next row, from its start. Nothing moves backwards.
        assertThat(step(Turn.Forward, across, wide, LeftToRight)).isEqualTo(Offset(0f, -1020f))
        // At its bottom, the second page from its top.
        assertThat(step(Turn.Forward, Offset(-1000f, -1600f), wide, LeftToRight)).isEqualTo(Offset(-1500f, 0f))
        // The second page is walked from where the screen has its start edge.
        assertThat(step(Turn.Forward, Offset(-1500f, -300f), wide, LeftToRight)).isEqualTo(Offset(-2000f, -300f))
        assertThat(step(Turn.Forward, Offset(-1499.8f, -300f), wide, LeftToRight))
            .isEqualTo(Offset(-2000f, -300f))
    }

    @Test
    fun `a screen left across both pages is on the page whose own places it is nearest to`() {
        // Nearer to where the second page starts: on along that page's row.
        assertThat(step(Turn.Forward, Offset(-1200f, -300f), wide, LeftToRight)).isEqualTo(Offset(-2000f, -300f))
        assertThat(step(Turn.Forward, Offset(-1499f, -1600f), wide, LeftToRight)).isEqualTo(Offset(-2000f, -1600f))
        // Nearer to where the first page's rows end: its next row.
        assertThat(step(Turn.Forward, Offset(-800f, -300f), wide, LeftToRight)).isEqualTo(Offset(0f, -1020f))
        // Back, nearer to the end of the first page's rows: on along its row, towards its start.
        assertThat(step(Turn.Back, Offset(-800f, -300f), wide, LeftToRight)).isEqualTo(Offset(0f, -300f))
        assertThat(step(Turn.Back, Offset(-1200f, -300f), wide, LeftToRight)).isEqualTo(Offset(-2000f, 0f))
        // A page as wide as the screen, a pixel short of its place, is first framed.
        assertThat(step(Turn.Forward, Offset(-999f, -400f), twice, LeftToRight)).isEqualTo(Offset(-1000f, -400f))
    }

    @Test
    fun `a screen left across both pages is at the start of the second page's row going back`() {
        // Back the second page is not finished: the row above, from its end.
        assertThat(step(Turn.Back, Offset(-1000f, -300f), wide, LeftToRight)).isEqualTo(Offset(-2000f, 0f))
        // At its top, the first page from its end.
        assertThat(step(Turn.Back, Offset(-1000f, 0f), wide, LeftToRight)).isEqualTo(Offset(-500f, -1600f))
        // Right to left the pages and the rows are the other way round.
        assertThat(step(Turn.Forward, Offset(-1000f, -300f), wide, RightToLeft))
            .isEqualTo(Offset(-2000f, -1020f))
        assertThat(step(Turn.Back, Offset(-1000f, 0f), wide, RightToLeft)).isEqualTo(Offset(-1500f, -1600f))
    }

    @Test
    fun `one page given its room is walked as the spread it is`() {
        // A page at twice a screen of 600 by 1000, as in the tests of a lone page.
        val bounds = PanBounds(min = Offset(-600f, -1000f), max = Offset.Zero)
        val small = Size(600f, 1000f)
        val alone = WalkedSpread(bounds, small, listOf(PageSpan(0f, 1200f)))

        for (turn in Turn.entries) {
            for (direction in Direction.entries) {
                val from = bounds.at(direction.openingCorner(forward = turn == Turn.Forward))
                assertThat(walk(turn, from, alone, direction))
                    .isEqualTo(walk(turn, from, WalkedSpread(bounds, small), direction))
                assertThat(walk(turn, from, alone, direction)).hasSize(8)
            }
        }
    }

    /** Two pages of [pageWidth] by 800 at the fit, side by side in the screen at [zoom]: their rooms from the left. */
    private fun pair(pageWidth: Float, zoom: Float): WalkedSpread {
        val shapes = List(2) { pageWidth / screen.height }
        val content = fittedSize(shapes, screen, Fit.Page)
        val pages = pageSpans(shapes, content.width).map { it * zoom }
        return WalkedSpread(panBounds(content, screen, zoom), screen, pages)
    }

    /** Where a tap of a [turn] leads the [spread], its pages given from the left, read in the [direction]. */
    private fun step(turn: Turn, at: Offset, spread: WalkedSpread, direction: Direction): Offset? {
        val inReadingOrder = if (direction == RightToLeft) spread.pages.reversed() else spread.pages
        return edgeWalk(turn, at, spread.copy(pages = inReadingOrder), direction)
    }

    /** Every place the taps of a [turn] go through, until the page turns. */
    private fun walk(turn: Turn, from: Offset, spread: WalkedSpread, direction: Direction): List<Offset> =
        generateSequence(from) { step(turn, it, spread, direction) }.drop(1).take(MostSteps).toList()

    private companion object {
        /** More steps than any walk here has: a walk that never ends fails instead of filling the memory. */
        const val MostSteps = 40
    }
}
