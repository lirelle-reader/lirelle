package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import app.tfdn.lirelle.core.comics.layout.Edge
import app.tfdn.lirelle.core.comics.layout.forwardEdge
import app.tfdn.lirelle.core.comics.settings.Direction
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * The slide from a spread to the one beside it. The spread before and the one after stand at the sides of the
 * one shown, a gap away; a slide is how far the three are from their places, in pixels: under zero they went to
 * the left, and the spread at the right comes in.
 */

/** What a way that resists gives of the finger's move. */
private const val Resistance = 1f / 3f

/** The part of the width a spread is pulled by before it goes on by itself when let go. */
private const val CompleteShare = 1f / 3f

/** How long a slide over a whole spread lasts, in milliseconds, and the shortest a slide lasts. */
private const val SlideMillis = 280
private const val ShortestSlideMillis = 120

/** Nearer than this to its edge, in pixels, a page is at its edge. */
private const val AtEdge = 0.5f

/** What a spread pulled to a side finds there. */
internal enum class Way {
    /** A spread to go to: it comes in with the finger. */
    Open,

    /** Nothing to go to: the spread shown gives a little, and comes back. */
    Resisting,

    /** More of the page shown: the pull moved the page, it brings no spread in. */
    Closed,
}

/** Whether there is a spread before the one shown, and one after it. */
internal data class SlideRoom(val back: Boolean, val forward: Boolean)

/**
 * What a pull finds at each side of the screen: at the [left], where a finger going right brings from; at the
 * [right], where a finger going left brings from.
 */
internal data class SlideWays(val left: Way, val right: Way) {
    /** The way at the side a slide by [offset] brings in. */
    fun at(offset: Float): Way = when {
        offset > 0f -> left
        offset < 0f -> right
        else -> Way.Open
    }

    /** The way a [turn] goes through. */
    fun of(turn: Turn, direction: Direction): Way {
        val forward = if (direction.forwardEdge() == Edge.Right) right else left
        val back = if (direction.forwardEdge() == Edge.Right) left else right
        return if (turn == Turn.Forward) forward else back
    }
}

/**
 * Where the spread after the one shown stands, from the place of the one shown: a screen and a [gap] to the
 * right, or to the left in an album read right to left. The spread before stands as far at the other side.
 */
internal fun nextPlace(width: Float, gap: Float, direction: Direction): Float =
    if (direction.forwardEdge() == Edge.Right) width + gap else -(width + gap)

/** The slide that ends a [turn]: the spread it goes to is where the one shown was. [step] is [nextPlace]. */
internal fun slideTarget(turn: Turn, step: Float): Float = if (turn == Turn.Forward) -step else step

/**
 * The ways of a finger that came down on a spread standing at [from]. A side where the page has more to show
 * is closed: there the finger moves the page. Past the edge of the page is the spread beside it, when there is
 * [room] for one.
 */
internal fun slideWays(from: Offset, bounds: PanBounds, direction: Direction, room: SlideRoom): SlideWays {
    fun way(edge: Edge, moreOfThePage: Boolean): Way = when {
        moreOfThePage -> Way.Closed
        if (edge == direction.forwardEdge()) room.forward else room.back -> Way.Open
        else -> Way.Resisting
    }
    return SlideWays(
        left = way(Edge.Left, moreOfThePage = bounds.max.x - from.x > AtEdge),
        right = way(Edge.Right, moreOfThePage = from.x - bounds.min.x > AtEdge),
    )
}

/** How far the finger went to slide the spread by [offset]: more than it, where the way resists. */
internal fun pulled(offset: Float, ways: SlideWays): Float =
    if (ways.at(offset) == Way.Resisting) offset / Resistance else offset

/**
 * The slide after the finger moved by [delta], from a slide of [offset]. The spread follows the finger where
 * the way is open, by a part of its move where it resists, and stays in its place where it is closed. It goes
 * no further than the place of the spread beside it, a [stride] away.
 */
internal fun dragged(offset: Float, delta: Float, ways: SlideWays, stride: Float): Float {
    val pull = pulled(offset, ways) + delta
    val slide = when (ways.at(pull)) {
        Way.Open -> pull
        Way.Resisting -> pull * Resistance
        Way.Closed -> 0f
    }
    return slide.coerceIn(-stride, stride)
}

/**
 * What decides of a release.
 *
 * @property width the width of the screen, in pixels.
 * @property flingVelocity the speed, in pixels a second, from which a finger flung the spread.
 */
internal data class Release(val width: Float, val flingVelocity: Float, val direction: Direction)

/**
 * The turn a finger let go asks for, null to go back in place. [pull] is how far it pulled the spread
 * ([pulled]), [velocity] its speed sideways when it left. Pulled by a third of the width, the spread goes on;
 * flung, it goes the way it was flung: on from a short pull, back in place from a long one flung backwards.
 */
internal fun releaseTurn(pull: Float, velocity: Float, release: Release): Turn? {
    if (pull == 0f) return null
    val flung = abs(velocity) >= release.flingVelocity
    val completes = if (flung) velocity.sign == pull.sign else abs(pull) >= release.width * CompleteShare
    // A spread pulled to the left brings in the one at its right.
    val from = if (pull < 0f) Edge.Right else Edge.Left
    return when {
        !completes -> null
        from == release.direction.forwardEdge() -> Turn.Forward
        else -> Turn.Back
    }
}

/** How long a slide over [distance] lasts, in milliseconds: in proportion to a whole [stride], and not too short. */
internal fun slideMillis(distance: Float, stride: Float): Int {
    if (stride <= 0f) return ShortestSlideMillis
    return (SlideMillis * abs(distance) / stride).roundToInt().coerceIn(ShortestSlideMillis, SlideMillis)
}
