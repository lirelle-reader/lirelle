package app.tfdn.lirelle.feature.comics.page

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Where a reader is among the stops of a spread, and what each step does from there. */
class PanelWalkTest {

    private val walk = PanelWalk()

    @Test
    fun `forward goes from the whole view to each stop in turn, then to the next spread at its whole view`() {
        walk.shown(4, wholePageFirst = true)

        assertThat(forward(stops = 3)).isEqualTo(Step.To(0))
        assertThat(forward(stops = 3)).isEqualTo(Step.To(1))
        assertThat(forward(stops = 3)).isEqualTo(Step.To(2))
        assertThat(walk.at).isEqualTo(2)
        assertThat(forward(stops = 3)).isEqualTo(Step.Leave(Turn.Forward, arriving = null))
        assertThat(walk.at).isNull()
    }

    @Test
    fun `without the whole page first, the spread after the last stop is to go to its first stop`() {
        walk.shown(4, wholePageFirst = false)
        walk.arrive(stops = 1, wholePageFirst = false)

        assertThat(forward(stops = 1, wholePageFirst = false)).isEqualTo(Step.Leave(Turn.Forward, Arriving.First))
    }

    @Test
    fun `a spread with no stop turns at once, both ways`() {
        walk.shown(4, wholePageFirst = true)

        assertThat(forward(stops = 0)).isEqualTo(Step.Leave(Turn.Forward, arriving = null))
        assertThat(back(stops = 0)).isEqualTo(Step.Leave(Turn.Back, Arriving.Last))
    }

    @Test
    fun `back goes from a stop to the one before, then to the whole view, then to the spread before at its end`() {
        walk.shown(4, wholePageFirst = true)
        repeat(3) { forward(stops = 3) }

        assertThat(back(stops = 3)).isEqualTo(Step.To(1))
        assertThat(back(stops = 3)).isEqualTo(Step.To(0))
        assertThat(back(stops = 3)).isEqualTo(Step.ToWhole)
        assertThat(walk.at).isNull()
        assertThat(back(stops = 3)).isEqualTo(Step.Leave(Turn.Back, Arriving.Last))
    }

    @Test
    fun `without the whole page first, back from the first stop is the spread before at its end`() {
        walk.shown(4, wholePageFirst = false)
        walk.arrive(stops = 3, wholePageFirst = false)
        assertThat(walk.at).isEqualTo(0)

        assertThat(back(stops = 3, wholePageFirst = false)).isEqualTo(Step.Leave(Turn.Back, Arriving.Last))
    }

    @Test
    fun `a spread opened, or jumped to, stays whole when the whole page comes first`() {
        walk.shown(4, wholePageFirst = true)

        assertThat(walk.arrive(stops = 3, wholePageFirst = true)).isNull()
        assertThat(walk.at).isNull()
    }

    @Test
    fun `a spread opened, or jumped to, goes to its first stop when the whole page does not come first`() {
        walk.shown(4, wholePageFirst = false)

        assertThat(walk.arrive(stops = 3, wholePageFirst = false)).isEqualTo(Step.To(0))
        assertThat(walk.at).isEqualTo(0)
        // Once: the reader who went back to the whole view by hand is not sent to the stop again.
        assertThat(walk.arrive(stops = 3, wholePageFirst = false)).isNull()
    }

    @Test
    fun `a spread opened without the whole page first stays whole when it has no stop`() {
        walk.shown(4, wholePageFirst = false)

        assertThat(walk.arrive(stops = 0, wholePageFirst = false)).isNull()
        assertThat(walk.at).isNull()
    }

    @Test
    fun `a spread turned back to goes to its last stop, whatever comes first`() {
        walk.shown(4, wholePageFirst = true)
        val leave = back(stops = 2) as Step.Leave
        walk.turning(3, leave.arriving)

        walk.shown(3, wholePageFirst = true)

        assertThat(walk.arrive(stops = 5, wholePageFirst = true)).isEqualTo(Step.To(4))
        assertThat(walk.at).isEqualTo(4)
        assertThat(back(stops = 5)).isEqualTo(Step.To(3))
    }

    @Test
    fun `a spread turned back to that has no stop stays whole`() {
        walk.shown(4, wholePageFirst = true)
        walk.turning(3, Arriving.Last)
        walk.shown(3, wholePageFirst = true)

        assertThat(walk.arrive(stops = 0, wholePageFirst = true)).isNull()
        assertThat(forward(stops = 0)).isEqualTo(Step.Leave(Turn.Forward, arriving = null))
    }

    @Test
    fun `what a turn asked of a spread is not done by another spread`() {
        walk.shown(4, wholePageFirst = true)
        walk.turning(3, Arriving.Last)

        // The slider went elsewhere.
        walk.shown(9, wholePageFirst = true)

        assertThat(walk.arrive(stops = 5, wholePageFirst = true)).isNull()
    }

    @Test
    fun `what a turn asked of a spread is forgotten by the step after it`() {
        walk.shown(4, wholePageFirst = true)
        walk.turning(3, Arriving.Last)
        // The reader did not take the turn, and the user went on.
        forward(stops = 2)
        back(stops = 2)

        walk.shown(3, wholePageFirst = true)

        assertThat(walk.arrive(stops = 5, wholePageFirst = true)).isNull()
    }

    @Test
    fun `the spread shown again is where it was`() {
        walk.shown(4, wholePageFirst = true)
        forward(stops = 3)
        forward(stops = 3)

        walk.shown(4, wholePageFirst = true)

        assertThat(walk.at).isEqualTo(1)
    }

    @Test
    fun `another spread starts at its whole view`() {
        walk.shown(4, wholePageFirst = true)
        forward(stops = 3)

        walk.shown(5, wholePageFirst = true)

        assertThat(walk.at).isNull()
        assertThat(forward(stops = 3)).isEqualTo(Step.To(0))
    }

    @Test
    fun `stops not known, a step forward waits, and is done when they come`() {
        walk.shown(4, wholePageFirst = true)

        assertThat(forward(stops = null)).isEqualTo(Step.Wait)
        assertThat(walk.at).isNull()

        assertThat(walk.arrive(stops = 3, wholePageFirst = true)).isEqualTo(Step.To(0))
        assertThat(walk.at).isEqualTo(0)
        assertThat(walk.arrive(stops = 3, wholePageFirst = true)).isNull()
    }

    @Test
    fun `a step that waited goes to the next spread when the page turns out not cut`() {
        walk.shown(4, wholePageFirst = true)
        forward(stops = null)

        assertThat(walk.arrive(stops = 0, wholePageFirst = true)).isEqualTo(Step.Leave(Turn.Forward, arriving = null))
    }

    @Test
    fun `stops still not known, a second step forward turns - no page blocks the reader`() {
        walk.shown(4, wholePageFirst = false)

        assertThat(forward(stops = null, wholePageFirst = false)).isEqualTo(Step.Wait)
        assertThat(forward(stops = null, wholePageFirst = false)).isEqualTo(Step.Leave(Turn.Forward, Arriving.First))
    }

    @Test
    fun `stops still not known, only the first step forward waits, until they are known`() {
        walk.shown(4, wholePageFirst = true)
        val leave = Step.Leave(Turn.Forward, arriving = null)

        assertThat(forward(stops = null)).isEqualTo(Step.Wait)
        // The spread is left, and what leaves it may only move it: it is still the one shown.
        assertThat(forward(stops = null)).isEqualTo(leave)
        assertThat(forward(stops = null)).isEqualTo(leave)
        walk.moved()
        assertThat(forward(stops = null)).isEqualTo(leave)

        // Known, then not known any more: a step waits again.
        assertThat(forward(stops = 3)).isEqualTo(Step.To(0))
        assertThat(forward(stops = null)).isEqualTo(Step.Wait)
    }

    @Test
    fun `another spread whose stops are not known is waited for in its turn`() {
        walk.shown(4, wholePageFirst = true)
        forward(stops = null)
        forward(stops = null)

        walk.shown(5, wholePageFirst = true)

        assertThat(forward(stops = null)).isEqualTo(Step.Wait)
    }

    @Test
    fun `stops not known, a step back turns to the spread before at its end, and no step waits any more`() {
        walk.shown(4, wholePageFirst = true)
        forward(stops = null)

        assertThat(back(stops = null)).isEqualTo(Step.Leave(Turn.Back, Arriving.Last))
        assertThat(walk.arrive(stops = 3, wholePageFirst = true)).isNull()
    }

    @Test
    fun `a step that waits is forgotten once a hand moved the spread`() {
        walk.shown(4, wholePageFirst = true)
        forward(stops = null)

        walk.moved()

        assertThat(walk.arrive(stops = 3, wholePageFirst = true)).isNull()
        assertThat(walk.at).isNull()
    }

    @Test
    fun `a spread that was to go to a stop stays where a hand moved it`() {
        walk.shown(4, wholePageFirst = false)

        walk.moved()

        assertThat(walk.arrive(stops = 3, wholePageFirst = false)).isNull()
        // The next step goes on from the whole view.
        assertThat(forward(stops = 3, wholePageFirst = false)).isEqualTo(Step.To(0))
    }

    @Test
    fun `after a hand moved the spread, forward goes to the stop after the one left, back to that stop itself`() {
        walk.shown(4, wholePageFirst = true)
        forward(stops = 3)
        forward(stops = 3)

        walk.moved()
        assertThat(walk.at).isEqualTo(1)
        assertThat(forward(stops = 3)).isEqualTo(Step.To(2))

        walk.moved()
        assertThat(back(stops = 3)).isEqualTo(Step.To(2))
        // Back at the stop, a step back is a step again.
        assertThat(back(stops = 3)).isEqualTo(Step.To(1))
    }

    @Test
    fun `the reader is at a stop until a hand moves the spread, or a step leaves it`() {
        assertThat(walk.spread).isNull()
        walk.shown(4, wholePageFirst = true)
        assertThat(walk.spread).isEqualTo(4)
        assertThat(walk.atStop).isFalse()

        forward(stops = 1)
        assertThat(walk.atStop).isTrue()
        walk.moved()
        assertThat(walk.atStop).isFalse()

        back(stops = 1)
        assertThat(walk.atStop).isTrue()
        forward(stops = 1)
        assertThat(walk.atStop).isFalse()
    }

    @Test
    fun `after a hand moved the spread at its last stop, forward turns`() {
        walk.shown(4, wholePageFirst = true)
        repeat(2) { forward(stops = 2) }

        walk.moved()

        assertThat(forward(stops = 2)).isEqualTo(Step.Leave(Turn.Forward, arriving = null))
    }

    @Test
    fun `a hand that moved the whole view changes nothing to the steps`() {
        walk.shown(4, wholePageFirst = true)

        walk.moved()

        assertThat(back(stops = 3)).isEqualTo(Step.Leave(Turn.Back, Arriving.Last))
        assertThat(forward(stops = 3)).isEqualTo(Step.To(0))
    }

    @Test
    fun `stops that became fewer than the one the reader is at are walked from the last of them`() {
        walk.shown(4, wholePageFirst = true)
        repeat(4) { forward(stops = 4) }

        assertThat(back(stops = 2)).isEqualTo(Step.To(0))

        repeat(3) { forward(stops = 4) }
        assertThat(forward(stops = 2)).isEqualTo(Step.Leave(Turn.Forward, arriving = null))
    }

    @Test
    fun `a stop that is no longer there is the whole view`() {
        walk.shown(4, wholePageFirst = true)
        forward(stops = 3)

        assertThat(back(stops = 0)).isEqualTo(Step.Leave(Turn.Back, Arriving.Last))
    }

    @Test
    fun `a spread put back at its whole view is walked again from there, whatever stop it was at`() {
        walk.shown(4, wholePageFirst = true)
        repeat(5) { forward(stops = 6) }

        walk.whole()

        assertThat(walk.at).isNull()
        assertThat(forward(stops = 4)).isEqualTo(Step.To(0))
    }

    @Test
    fun `a spread put back at its whole view after a hand left a stop is at no stop a step back goes to`() {
        walk.shown(4, wholePageFirst = true)
        repeat(2) { forward(stops = 3) }
        walk.moved()

        walk.whole()

        assertThat(walk.atStop).isFalse()
        assertThat(back(stops = 3)).isEqualTo(Step.Leave(Turn.Back, Arriving.Last))
    }

    @Test
    fun `what a turn that was not taken asked of a spread is forgotten once no turn is on its way`() {
        walk.shown(4, wholePageFirst = true)
        walk.turning(3, Arriving.Last)

        walk.unturned()
        // Later, the slider goes to that spread.
        walk.shown(3, wholePageFirst = true)

        assertThat(walk.arrive(stops = 5, wholePageFirst = true)).isNull()
    }

    private fun forward(stops: Int?, wholePageFirst: Boolean = true): Step =
        walk.step(Turn.Forward, stops, wholePageFirst)

    private fun back(stops: Int?, wholePageFirst: Boolean = true): Step = walk.step(Turn.Back, stops, wholePageFirst)
}
