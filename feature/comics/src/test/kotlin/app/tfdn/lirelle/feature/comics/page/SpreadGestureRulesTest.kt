package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.tfdn.lirelle.core.comics.settings.Direction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What a tap does, from where it is and from what is left of the page to see. */
class SpreadGestureRulesTest {

    /** A page as wide as the screen, that can move by 1500 down. */
    private val tall = PanBounds(min = Offset(0f, -1500f), max = Offset(0f, 0f))
    private val screen = Size(1000f, 1000f)

    /** A page at twice a screen of 600 by 1000: it can move by 600 sideways and 1000 down. */
    private val zoomed = PanBounds(min = Offset(-600f, -1000f), max = Offset(0f, 0f))
    private val small = Size(600f, 1000f)
    private val still = PanBounds(min = Offset(40f, 0f), max = Offset(40f, 0f))

    @Test
    fun `the quarter at the end of the reading direction goes forward, the other back, the middle neither`() {
        assertThat(tapTurn(x = 760f, width = 1000f, Direction.LeftToRight)).isEqualTo(Turn.Forward)
        assertThat(tapTurn(x = 240f, width = 1000f, Direction.LeftToRight)).isEqualTo(Turn.Back)
        assertThat(tapTurn(x = 260f, width = 1000f, Direction.LeftToRight)).isNull()
        assertThat(tapTurn(x = 740f, width = 1000f, Direction.LeftToRight)).isNull()
    }

    @Test
    fun `right to left, the edges are the other way round`() {
        assertThat(tapTurn(x = 760f, width = 1000f, Direction.RightToLeft)).isEqualTo(Turn.Back)
        assertThat(tapTurn(x = 240f, width = 1000f, Direction.RightToLeft)).isEqualTo(Turn.Forward)
        assertThat(tapTurn(x = 500f, width = 1000f, Direction.RightToLeft)).isNull()
    }

    @Test
    fun `a page that fits turns at once`() {
        val at = Offset(40f, 0f)

        assertThat(edgeWalk(Turn.Forward, at, still, screen, Direction.LeftToRight)).isNull()
        assertThat(edgeWalk(Turn.Back, at, still, screen, Direction.LeftToRight)).isNull()
    }

    @Test
    fun `forward, a page no wider than the screen first moves down by about a screen`() {
        assertThat(edgeWalk(Turn.Forward, Offset.Zero, tall, screen, Direction.LeftToRight))
            .isEqualTo(Offset(0f, -900f))
    }

    @Test
    fun `forward, the move stops at the bottom of the page, and from there the page turns`() {
        assertThat(edgeWalk(Turn.Forward, Offset(0f, -900f), tall, screen, Direction.LeftToRight))
            .isEqualTo(Offset(0f, -1500f))
        assertThat(edgeWalk(Turn.Forward, Offset(0f, -1500f), tall, screen, Direction.LeftToRight)).isNull()
        // A hair from the bottom is the bottom.
        assertThat(edgeWalk(Turn.Forward, Offset(0f, -1499.6f), tall, screen, Direction.LeftToRight)).isNull()
    }

    @Test
    fun `back, a page no wider than the screen moves up by about a screen, then to its top, then turns`() {
        assertThat(edgeWalk(Turn.Back, Offset(0f, -1500f), tall, screen, Direction.RightToLeft))
            .isEqualTo(Offset(0f, -600f))
        assertThat(edgeWalk(Turn.Back, Offset(0f, -600f), tall, screen, Direction.RightToLeft)).isEqualTo(Offset.Zero)
        assertThat(edgeWalk(Turn.Back, Offset.Zero, tall, screen, Direction.RightToLeft)).isNull()
    }

    @Test
    fun `forward, a page zoomed to twice the screen is walked row by row, as it is read`() {
        val seen = walk(Turn.Forward, from = Offset.Zero, Direction.LeftToRight)

        assertThat(seen).containsExactly(
            // The top row, to its right end: the last step is what is left of it.
            Offset(-540f, 0f),
            Offset(-600f, 0f),
            // Down a screen, back at the left.
            Offset(0f, -900f),
            Offset(-540f, -900f),
            Offset(-600f, -900f),
            // The bottom row is nearer than a screen.
            Offset(0f, -1000f),
            Offset(-540f, -1000f),
            Offset(-600f, -1000f),
        ).inOrder()
    }

    @Test
    fun `right to left, the rows are walked from the right`() {
        val seen = walk(Turn.Forward, from = Offset(-600f, 0f), Direction.RightToLeft)

        assertThat(seen).containsExactly(
            Offset(-60f, 0f),
            Offset(0f, 0f),
            Offset(-600f, -900f),
            Offset(-60f, -900f),
            Offset(0f, -900f),
            Offset(-600f, -1000f),
            Offset(-60f, -1000f),
            Offset(0f, -1000f),
        ).inOrder()
    }

    @Test
    fun `back, the walk is the same the other way round, from the bottom at the end of the row`() {
        val seen = walk(Turn.Back, from = Offset(-600f, -1000f), Direction.LeftToRight)

        assertThat(seen).containsExactly(
            Offset(-60f, -1000f),
            Offset(0f, -1000f),
            Offset(-600f, -100f),
            Offset(-60f, -100f),
            Offset(0f, -100f),
            Offset(-600f, 0f),
            Offset(-60f, 0f),
            Offset(0f, 0f),
        ).inOrder()
    }

    @Test
    fun `right to left, the walk back goes to the right of each row`() {
        val seen = walk(Turn.Back, from = Offset(0f, -1000f), Direction.RightToLeft)

        assertThat(seen.first()).isEqualTo(Offset(-540f, -1000f))
        assertThat(seen[2]).isEqualTo(Offset(0f, -100f))
        assertThat(seen.last()).isEqualTo(Offset(-600f, 0f))
    }

    /** Every place the taps of a [turn] go through on the zoomed page, until the page turns. */
    private fun walk(turn: Turn, from: Offset, direction: Direction): List<Offset> =
        generateSequence(from) { edgeWalk(turn, it, zoomed, small, direction) }.drop(1).toList()
}
