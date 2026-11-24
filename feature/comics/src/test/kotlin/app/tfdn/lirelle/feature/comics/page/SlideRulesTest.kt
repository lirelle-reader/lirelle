package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import app.tfdn.lirelle.core.comics.settings.Direction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The slide from a spread to the one beside it: where the spreads stand, what a pull and a release do. */
class SlideRulesTest {

    /** A page that can move by 100 sideways. */
    private val bounds = PanBounds(min = Offset(-100f, -1500f), max = Offset(0f, 0f))
    private val still = PanBounds(min = Offset(40f, 0f), max = Offset(40f, 0f))
    private val both = SlideRoom(back = true, forward = true)
    private val open = SlideWays(left = Way.Open, right = Way.Open)

    @Test
    fun `the next spread stands at the right of the one shown, a gap away, and at its left right to left`() {
        assertThat(nextPlace(width = 1000f, gap = 40f, Direction.LeftToRight)).isEqualTo(1040f)
        assertThat(nextPlace(width = 1000f, gap = 40f, Direction.RightToLeft)).isEqualTo(-1040f)
    }

    @Test
    fun `a slide ends where the spread it goes to takes the place of the one shown`() {
        assertThat(slideTarget(Turn.Forward, step = 1040f)).isEqualTo(-1040f)
        assertThat(slideTarget(Turn.Back, step = 1040f)).isEqualTo(1040f)
        assertThat(slideTarget(Turn.Forward, step = -1040f)).isEqualTo(1040f)
        assertThat(slideTarget(Turn.Back, step = -1040f)).isEqualTo(-1040f)
    }

    @Test
    fun `a page that fits lets both spreads beside it in`() {
        assertThat(slideWays(Offset(40f, 0f), still, Direction.LeftToRight, both)).isEqualTo(open)
    }

    @Test
    fun `where there is no spread to go to, the way resists`() {
        val first = SlideRoom(back = false, forward = true)
        val last = SlideRoom(back = true, forward = false)

        assertThat(slideWays(Offset(40f, 0f), still, Direction.LeftToRight, first))
            .isEqualTo(SlideWays(left = Way.Resisting, right = Way.Open))
        assertThat(slideWays(Offset(40f, 0f), still, Direction.LeftToRight, last))
            .isEqualTo(SlideWays(left = Way.Open, right = Way.Resisting))
        // Right to left the spread before is at the right.
        assertThat(slideWays(Offset(40f, 0f), still, Direction.RightToLeft, first))
            .isEqualTo(SlideWays(left = Way.Open, right = Way.Resisting))
    }

    @Test
    fun `a page with more to show at a side keeps that way closed`() {
        // In the middle of the page: it can still move both ways.
        assertThat(slideWays(Offset(-50f, 0f), bounds, Direction.LeftToRight, both))
            .isEqualTo(SlideWays(left = Way.Closed, right = Way.Closed))
        // The right edge of the page is at the right edge of the screen.
        assertThat(slideWays(Offset(-100f, 0f), bounds, Direction.LeftToRight, both))
            .isEqualTo(SlideWays(left = Way.Closed, right = Way.Open))
        assertThat(slideWays(Offset(0f, 0f), bounds, Direction.LeftToRight, both))
            .isEqualTo(SlideWays(left = Way.Open, right = Way.Closed))
    }

    @Test
    fun `the way of a turn is the one at the side its spread comes from`() {
        val ways = SlideWays(left = Way.Resisting, right = Way.Open)

        assertThat(ways.of(Turn.Forward, Direction.LeftToRight)).isEqualTo(Way.Open)
        assertThat(ways.of(Turn.Back, Direction.LeftToRight)).isEqualTo(Way.Resisting)
        assertThat(ways.of(Turn.Forward, Direction.RightToLeft)).isEqualTo(Way.Resisting)
        assertThat(ways.of(Turn.Back, Direction.RightToLeft)).isEqualTo(Way.Open)
    }

    @Test
    fun `an open way follows the finger, no further than the spread beside`() {
        assertThat(dragged(offset = 0f, delta = -30f, open, stride = 1040f)).isEqualTo(-30f)
        assertThat(dragged(offset = -30f, delta = 80f, open, stride = 1040f)).isEqualTo(50f)
        assertThat(dragged(offset = -1000f, delta = -500f, open, stride = 1040f)).isEqualTo(-1040f)
        assertThat(dragged(offset = 1000f, delta = 500f, open, stride = 1040f)).isEqualTo(1040f)
    }

    @Test
    fun `a way that resists gives a third of the finger's move, and gives it back the same`() {
        val ways = SlideWays(left = Way.Resisting, right = Way.Open)

        assertThat(dragged(offset = 0f, delta = 300f, ways, stride = 1040f)).isWithin(0.01f).of(100f)
        assertThat(dragged(offset = 100f, delta = 300f, ways, stride = 1040f)).isWithin(0.01f).of(200f)
        assertThat(dragged(offset = 100f, delta = -150f, ways, stride = 1040f)).isWithin(0.01f).of(50f)
        // Back past its place, the finger has the page again.
        assertThat(dragged(offset = 100f, delta = -400f, ways, stride = 1040f)).isWithin(0.01f).of(-100f)
        assertThat(dragged(offset = -100f, delta = 400f, ways, stride = 1040f)).isWithin(0.01f).of(100f)
    }

    @Test
    fun `a closed way does not move`() {
        val ways = SlideWays(left = Way.Closed, right = Way.Open)

        assertThat(dragged(offset = 0f, delta = 300f, ways, stride = 1040f)).isEqualTo(0f)
        assertThat(dragged(offset = -100f, delta = 300f, ways, stride = 1040f)).isEqualTo(0f)
        assertThat(dragged(offset = 0f, delta = -300f, ways, stride = 1040f)).isEqualTo(-300f)
    }

    @Test
    fun `the pull is what the finger did, whatever the way gave of it`() {
        val ways = SlideWays(left = Way.Resisting, right = Way.Open)

        assertThat(pulled(offset = 100f, ways)).isWithin(0.01f).of(300f)
        assertThat(pulled(offset = -100f, ways)).isEqualTo(-100f)
        assertThat(pulled(offset = 0f, ways)).isEqualTo(0f)
    }

    @Test
    fun `let go past a third of the width, the slide completes, as a book's page is pulled`() {
        val release = Release(width = 900f, flingVelocity = 1000f, Direction.LeftToRight)

        assertThat(releaseTurn(pull = -300f, velocity = 0f, release)).isEqualTo(Turn.Forward)
        assertThat(releaseTurn(pull = 300f, velocity = 0f, release)).isEqualTo(Turn.Back)
        assertThat(releaseTurn(pull = -299f, velocity = 0f, release)).isNull()
        assertThat(releaseTurn(pull = 299f, velocity = -999f, release)).isNull()
        assertThat(releaseTurn(pull = 0f, velocity = 0f, release)).isNull()
    }

    @Test
    fun `right to left, the release turns the other way`() {
        val release = Release(width = 900f, flingVelocity = 1000f, Direction.RightToLeft)

        assertThat(releaseTurn(pull = -300f, velocity = 0f, release)).isEqualTo(Turn.Back)
        assertThat(releaseTurn(pull = 300f, velocity = 0f, release)).isEqualTo(Turn.Forward)
    }

    @Test
    fun `a fling completes a short slide its way, and takes back a long one the other way`() {
        val release = Release(width = 900f, flingVelocity = 1000f, Direction.LeftToRight)

        assertThat(releaseTurn(pull = -20f, velocity = -1000f, release)).isEqualTo(Turn.Forward)
        assertThat(releaseTurn(pull = 20f, velocity = 1500f, release)).isEqualTo(Turn.Back)
        assertThat(releaseTurn(pull = -600f, velocity = 1000f, release)).isNull()
        assertThat(releaseTurn(pull = 600f, velocity = -1000f, release)).isNull()
        // A fling says nothing of a page that did not leave its place.
        assertThat(releaseTurn(pull = 0f, velocity = -5000f, release)).isNull()
    }

    @Test
    fun `a whole slide lasts 280 ms, a shorter one less, never under 120 ms`() {
        assertThat(slideMillis(distance = 1040f, stride = 1040f)).isEqualTo(280)
        assertThat(slideMillis(distance = -1040f, stride = 1040f)).isEqualTo(280)
        assertThat(slideMillis(distance = 520f, stride = 1040f)).isEqualTo(140)
        assertThat(slideMillis(distance = 10f, stride = 1040f)).isEqualTo(120)
        assertThat(slideMillis(distance = 10f, stride = 0f)).isEqualTo(120)
    }
}
