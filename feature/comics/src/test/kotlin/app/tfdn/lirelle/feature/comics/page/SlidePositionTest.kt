package app.tfdn.lirelle.feature.comics.page

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Where the spread shown stands while the reader catches up with the turns the page view asked for. */
class SlidePositionTest {

    private val step = 1040f

    @Test
    fun `the spread the slide started on stands where the slide put it`() {
        assertThat(SlidePosition(anchor = 5, offset = -300f).seenFrom(5, step)).isEqualTo(-300f)
    }

    @Test
    fun `the spread a finished slide asked for stands in its place, without a jump`() {
        val forward = SlidePosition(anchor = 5, offset = -1040f).asked(Turn.Forward)
        val back = SlidePosition(anchor = 5, offset = 1040f).asked(Turn.Back)

        assertThat(forward.seenFrom(6, step)).isEqualTo(0f)
        assertThat(back.seenFrom(4, step)).isEqualTo(0f)
        // Until the reader shows it, the one before is still the one shown, away from its place.
        assertThat(forward.seenFrom(5, step)).isEqualTo(-1040f)
    }

    @Test
    fun `right to left, the next spread comes from the left`() {
        val forward = SlidePosition(anchor = 5, offset = 1040f).asked(Turn.Forward)

        assertThat(forward.seenFrom(6, -step)).isEqualTo(0f)
    }

    @Test
    fun `a finger that went on meanwhile keeps what it moved`() {
        val forward = SlidePosition(anchor = 5, offset = -1100f).asked(Turn.Forward)

        assertThat(forward.seenFrom(6, step)).isEqualTo(-60f)
    }

    @Test
    fun `a spread nobody asked for shows in its place`() {
        val sliding = SlidePosition(anchor = 5, offset = -300f)
        val forward = SlidePosition(anchor = 5, offset = -1040f).asked(Turn.Forward)

        assertThat(sliding.seenFrom(6, step)).isEqualTo(0f)
        assertThat(sliding.seenFrom(20, step)).isEqualTo(0f)
        assertThat(forward.seenFrom(4, step)).isEqualTo(0f)
        assertThat(forward.seenFrom(7, step)).isEqualTo(0f)
        assertThat(forward.expects(6)).isTrue()
        assertThat(forward.expects(7)).isFalse()
        assertThat(forward.expects(4)).isFalse()
        assertThat(sliding.expects(6)).isFalse()
    }

    @Test
    fun `once the reader shows the spread asked for, the slide is counted from it`() {
        val followed = SlidePosition(anchor = 5, offset = -1100f).asked(Turn.Forward).followed(6, step)

        assertThat(followed).isEqualTo(SlidePosition(anchor = 6, offset = -60f))
    }

    @Test
    fun `two turns asked in a row are followed one by one, or both at once`() {
        val twice = SlidePosition(anchor = 5, offset = -2080f).asked(Turn.Forward).asked(Turn.Forward)

        assertThat(twice.followed(6, step)).isEqualTo(SlidePosition(anchor = 6, offset = -1040f, asked = 1))
        assertThat(twice.followed(6, step).followed(7, step)).isEqualTo(SlidePosition(anchor = 7))
        assertThat(twice.followed(7, step)).isEqualTo(SlidePosition(anchor = 7))
    }

    @Test
    fun `a spread shown for another reason forgets the slide`() {
        val forward = SlidePosition(anchor = 5, offset = -1040f).asked(Turn.Forward)

        assertThat(forward.followed(30, step)).isEqualTo(SlidePosition(anchor = 30))
        assertThat(SlidePosition(anchor = 5, offset = -300f).followed(6, step)).isEqualTo(SlidePosition(anchor = 6))
    }

    @Test
    fun `a turn the reader did not take leaves the spread shown in its place`() {
        val forward = SlidePosition(anchor = 5, offset = -1040f).asked(Turn.Forward)

        assertThat(forward.unfollowed(step)).isEqualTo(SlidePosition(anchor = 5))
        assertThat(forward.copy(offset = -1100f).unfollowed(step)).isEqualTo(SlidePosition(anchor = 5, offset = -60f))
    }
}
