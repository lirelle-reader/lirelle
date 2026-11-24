package app.tfdn.lirelle.feature.comics.page

import kotlin.math.abs
import kotlin.math.sign

/**
 * Where the slide is. The page view asks the reader for a turn when a slide ends, and the reader shows the new
 * spread a moment later: until then the slide is still counted from the spread it started on, and the spread
 * asked for, which stands beside it, is the one in the middle of the screen. When the reader follows, the
 * slide is counted from the new spread, and nothing moves on the screen.
 *
 * @property anchor the spread the slide is counted from, an index in the album's spreads.
 * @property offset how far that spread is from its place, in pixels.
 * @property asked the turns asked that the reader does not show yet: spreads forward, or back under zero.
 */
internal data class SlidePosition(val anchor: Int, val offset: Float = 0f, val asked: Int = 0) {
    /** Whether the spread [index] is one the turns asked lead to. */
    fun expects(index: Int): Boolean {
        val turned = index - anchor
        return turned != 0 && turned.sign == asked.sign && abs(turned) <= abs(asked)
    }

    /**
     * How far the spread [index], now the one shown, is from its place. A spread nobody asked for is in its
     * place. [step] is where a spread stands from the one before it ([nextPlace]).
     */
    fun seenFrom(index: Int, step: Float): Float = when {
        index == anchor -> offset
        expects(index) -> offset + (index - anchor) * step
        else -> 0f
    }

    /** A turn was asked. */
    fun asked(turn: Turn): SlidePosition = copy(asked = asked + if (turn == Turn.Forward) 1 else -1)

    /** The reader shows the spread [index]: the slide is counted from it. */
    fun followed(index: Int, step: Float): SlidePosition = SlidePosition(
        anchor = index,
        offset = seenFrom(index, step),
        asked = if (expects(index)) asked - (index - anchor) else 0,
    )

    /** The reader did not take the turns asked: the spread it still shows is the one in the middle. */
    fun unfollowed(step: Float): SlidePosition = SlidePosition(anchor, offset + asked * step)
}
