package app.tfdn.lirelle.feature.comics.page

import android.provider.Settings
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How long the spread takes to go to a stop, in milliseconds. */
internal const val StopMillis = 250

/**
 * A stop that frames the spread, for a reader that reads panel by panel, or a place on the way to one.
 *
 * @property place where it puts the spread: outside what a finger could reach, and at a zoom of its own.
 * @property window the part of the screen it leaves to see, in pixels: the rest is hidden.
 * @property towards the frame the spread is on its way to; null when it is where it is to stay.
 */
internal data class StopFrame(val place: StopPlace, val window: Rect, val towards: StopFrame? = null)

/**
 * Puts the spread shown at a stop, and back at its whole view. A stop frames the spread ([SpreadPan.framed]) at a
 * zoom and a place of its own; on the way to it, the zoom, the place and what is left to see of the screen move
 * together. The reader's zoom is set to the stop's, as far as a reader's zoom can be, as soon as the spread
 * leaves for it: it is what the pages are drawn sharper for, and what a hand that then moves the spread goes on
 * from.
 *
 * @param zoom the reader's zoom, and [shown] the spread it shows.
 * @param scope where the spread moves, over a few frames.
 * @param animated whether it does: otherwise it is at the stop at once.
 */
internal class StopMoves(
    private val zoom: SpreadZoom,
    private val shown: PlacedSpread,
    private val scope: CoroutineScope,
    private val animated: Boolean,
) {
    private val pan: SpreadPan get() = shown.pan
    private val frame: SpreadFrame get() = shown.frame

    /** Whether a stop frames the spread, or the spread is on its way to or from one. */
    val framed: Boolean get() = pan.framed != null

    /** Whether the reader's zoom is the fit. */
    val atFit: Boolean get() = zoom.scale == MinZoom

    /** The spread goes to [stop], a part of its content, from where it is drawn now. */
    fun goTo(stop: Rect) = move(framing(stop, frame.viewport), whole = false)

    /** The spread goes back to its whole view, from where it is drawn now. */
    fun toWhole() {
        val place = StopPlace(MinZoom, panBounds(frame.content, frame.viewport, MinZoom).at(frame.corner))
        move(StopFrame(place, frame.viewport.toRect()), whole = true)
    }

    /** At once: the spread at the fit, at the corner it opens at, and no stop framing it. */
    fun wholeAtOnce() {
        pan.scrolling?.cancel()
        pan.walkingTo = null
        pan.framed = null
        pan.offset = null
        zoom.scale = MinZoom
    }

    /**
     * At once: [stop], a part of the spread's content, frames the spread, with no move to it. For a spread that
     * was drawn there while a turn slid to it: the reader's zoom, which was the spread's it left, is now the stop's.
     */
    fun standAt(stop: Rect) {
        pan.scrolling?.cancel()
        pan.walkingTo = null
        val at = framing(stop, frame.viewport)
        pan.framed = at
        zoom.scale = readerZoom(at)
    }

    /**
     * The spread a turn on its way leads to ([ahead]) is framed by [stop], a part of its content, before the reader
     * shows it: it slides in as it will stand. The reader's zoom is left alone: it is still the spread shown's.
     */
    fun frameAhead(ahead: PlacedSpread, stop: Rect) {
        ahead.pan.framed = framing(stop, ahead.frame.viewport)
    }

    /**
     * The stop that frames the spread is [stop], as the screen and the zones now make it; with none, the spread
     * is whole. A spread on its way to a stop goes on to where that stop now is, from where it is drawn: its move
     * never ends at a place that is no longer the stop's. A spread that no stop frames, or that is on its way to
     * its whole view, is left alone.
     */
    fun refit(stop: Rect?) {
        val framed = pan.framed ?: return
        val moving = framed.towards != null
        val fitted = stop?.let { framing(it, frame.viewport) }
        when {
            fitted == null -> if (!moving) wholeAtOnce()
            moving -> if (fitted != framed.towards) move(fitted, whole = false)
            fitted != framed -> {
                pan.framed = fitted
                zoom.scale = readerZoom(fitted)
            }
        }
    }

    /**
     * A hand takes the spread: the stop is left, where the spread is drawn now. The reader goes on from the
     * stop's zoom, as far as a reader's zoom can be, and from its place brought back within the spread's edges.
     */
    fun release() {
        val framed = pan.framed ?: return
        pan.scrolling?.cancel()
        pan.framed = null
        zoom.scale = readerZoom(framed)
        pan.offset = panBounds(frame.content, frame.viewport, zoom.scale).clamp(framed.place.offset)
    }

    private fun move(to: StopFrame, whole: Boolean) {
        val moved = pan
        val from = moved.framed ?: StopFrame(StopPlace(zoom.scale, standing()), frame.viewport.toRect())
        moved.scrolling?.cancel()
        moved.walkingTo = null
        zoom.scale = readerZoom(to)
        if (!animated) {
            arrived(moved, to, whole)
            return
        }
        moved.framed = from.copy(towards = to)
        moved.scrolling = scope.launch {
            animate(0f, 1f, animationSpec = tween(StopMillis)) { part, _ -> moved.framed = between(from, to, part) }
            arrived(moved, to, whole)
        }
    }

    /** The move is over: the stop frames the spread, or nothing does at its [whole] view. */
    private fun arrived(moved: SpreadPan, at: StopFrame, whole: Boolean) {
        moved.framed = at.takeUnless { whole }
        if (whole) moved.offset = null
    }

    /** Where the spread stands when no stop frames it. */
    private fun standing(): Offset =
        panBounds(frame.content, frame.viewport, zoom.scale).place(pan.offset, frame.corner)
}

/**
 * Whether the system animates what moves: false when the user turned its animations off, for a spread that then
 * goes to its stops at once. Read once, when the page view is first shown.
 */
@Composable
internal fun systemAnimates(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/** The reader's zoom for a spread [framed] by a stop: the stop's, kept within what a reader's zoom can be. */
private fun readerZoom(framed: StopFrame): Float = framed.place.zoom.coerceIn(MinZoom, MaxZoom)

/** The frame that shows [stop], a part of the spread's content, in the [viewport]. */
private fun framing(stop: Rect, viewport: Size): StopFrame {
    val place = stopPlace(stop, viewport)
    return StopFrame(place, Rect(stop.topLeft * place.zoom + place.offset, stop.size * place.zoom))
}

/** The frame a [part] of the way [from] one [to] another: the zoom, the place and the window move together. */
private fun between(from: StopFrame, to: StopFrame, part: Float): StopFrame = StopFrame(
    place = StopPlace(lerp(from.place.zoom, to.place.zoom, part), lerp(from.place.offset, to.place.offset, part)),
    window = lerp(from.window, to.window, part),
    towards = to,
)
