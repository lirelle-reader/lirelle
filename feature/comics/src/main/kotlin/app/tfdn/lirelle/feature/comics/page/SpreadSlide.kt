package app.tfdn.lirelle.feature.comics.page

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.feature.comics.ComicNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How many frames the reader is given to show a spread asked for, before the page view goes back to its own. */
private const val FollowFrames = 6

/** A slide that goes by itself to a spread, and asks the reader for it when it gets there. */
internal class Landing(val turn: Turn) {
    /** What is left of the way. */
    var left = 0f
}

/** The slide of the reader, from one spread to the next: it lasts as long as the page view. */
@Stable
internal class SpreadSlide(index: Int) {
    var position by mutableStateOf(SlidePosition(index))

    /** The slide that goes by itself, while it lasts, and the turn it ends with when it has one. */
    var gliding: Job? = null
    var landing: Landing? = null

    /** The wait for the reader to show the spread asked for. */
    var following: Job? = null
}

/**
 * Where the spreads slide.
 *
 * @property width the width of the screen, and [gap] what is left between two spreads, in pixels.
 * @property room whether there is a spread before the one shown, and one after.
 * @property flingVelocity the speed, in pixels a second, from which a finger flung the spread.
 */
internal data class SlideLane(
    val width: Float,
    val gap: Float,
    val direction: Direction,
    val room: SlideRoom,
    val flingVelocity: Float,
) {
    /** How far a spread is from the one beside it. */
    val stride: Float get() = width + gap

    /** Where the spread after the one shown stands. */
    val step: Float get() = nextPlace(width, gap, direction)
}

/**
 * What moves the slide: the finger, then the slide by itself to the spread asked for or back in place. The
 * rules are those of `SlideRules` and [SlidePosition]. A turn is asked to the reader once its slide ended, or
 * as soon as something else starts: none is lost, none is asked twice.
 *
 * @param index the spread the reader shows.
 * @param scope where the slide goes by itself.
 */
internal class SpreadSliding(
    private val slide: SpreadSlide,
    private val index: Int,
    private val lane: SlideLane,
    private val navigation: ComicNavigation,
    private val scope: CoroutineScope,
) {
    /** How far the spread shown is from its place. */
    val offset: Float get() = slide.position.seenFrom(index, lane.step)

    /** Whether there is a spread before the one shown, and one after. */
    val room: SlideRoom get() = lane.room

    /** The turns on their way, back under zero: those asked that the reader does not show yet, and the slide's. */
    val pending: Int
        get() = slide.position.asked + when (slide.landing?.turn) {
            Turn.Forward -> 1
            Turn.Back -> -1
            null -> 0
        }

    /** How far the spread in the middle is from its place: the one shown, or the one asked for in its stead. */
    private val shown: Float get() = slide.position.unfollowed(lane.step).offset

    /** The reader shows the spread [index]: after a slide that asked for it, or for a reason of its own. */
    fun follow() {
        val position = slide.position
        if (position.anchor == index) return
        if (!position.expects(index)) {
            slide.gliding?.cancel()
            slide.landing = null
        }
        slide.position = position.followed(index, lane.step)
    }

    /** The finger moved sideways by [delta]. */
    fun drag(delta: Float, ways: SlideWays) {
        settle()
        moveBy(dragged(shown, delta, ways, lane.stride) - shown)
    }

    /** The finger left, at the [velocity] given sideways. */
    fun release(velocity: Float, ways: SlideWays) {
        val turn = releaseTurn(pulled(shown, ways), velocity, Release(lane.width, lane.flingVelocity, lane.direction))
        when {
            turn != null && ways.of(turn, lane.direction) == Way.Open -> glide(slideTarget(turn, lane.step), turn)
            else -> {
                glide(to = 0f, turn = null)
                // Past the last spread is the end of the album.
                if (turn == Turn.Forward) navigation.next()
            }
        }
    }

    /** A tap asked for a [turn]: the slide goes there by itself. Past the last spread it only asks. */
    fun turn(turn: Turn) {
        settle()
        val room = if (turn == Turn.Forward) lane.room.forward else lane.room.back
        when {
            room -> glide(slideTarget(turn, lane.step), turn)
            turn == Turn.Forward -> navigation.next()
        }
    }

    /** Ends at once what goes by itself: a slide to a spread is there, and its turn is asked. */
    fun settle() {
        slide.gliding?.cancel()
        slide.landing?.let { landing ->
            slide.landing = null
            moveBy(landing.left)
            ask(landing.turn)
        }
    }

    private fun glide(to: Float, turn: Turn?) {
        settle()
        val distance = to - shown
        val landing = turn?.let(::Landing)?.also { it.left = distance }
        slide.landing = landing
        slide.gliding = scope.launch {
            var done = 0f
            val spec = tween<Float>(slideMillis(distance, lane.stride), easing = LinearOutSlowInEasing)
            animate(0f, distance, animationSpec = spec) { value, _ ->
                moveBy(value - done)
                done = value
                landing?.left = distance - done
            }
            if (landing != null && slide.landing === landing) {
                slide.landing = null
                ask(landing.turn)
            }
        }
    }

    private fun moveBy(delta: Float) {
        slide.position = slide.position.let { it.copy(offset = it.offset + delta) }
    }

    /** Asks the reader for the [turn], and waits a few frames for it: a turn it did not take is forgotten. */
    private fun ask(turn: Turn) {
        slide.position = slide.position.asked(turn)
        if (turn == Turn.Forward) navigation.next() else navigation.previous()
        slide.following?.cancel()
        slide.following = scope.launch {
            repeat(FollowFrames) { withFrameNanos { } }
            if (slide.position.asked != 0) slide.position = slide.position.unfollowed(lane.step)
        }
    }
}
