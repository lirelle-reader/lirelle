package app.tfdn.lirelle.feature.comics.page

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import app.tfdn.lirelle.core.comics.layout.Corner
import app.tfdn.lirelle.core.comics.layout.Edge
import app.tfdn.lirelle.core.comics.layout.forwardEdge
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.feature.comics.ComicNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The zoom of the reader, from [MinZoom] (the fit) to [MaxZoom]. It is the reader's, not a spread's. */
@Stable
internal class SpreadZoom {
    var scale by mutableFloatStateOf(MinZoom)
}

/** Where the user moved the spread shown to; it is that spread's alone. */
@Stable
internal class SpreadPan {
    /** Null while nobody moved the spread: it stands at its opening corner, whatever its size becomes. */
    var offset: Offset? by mutableStateOf(null)

    /** The stop that frames the spread; null when none does: the spread is then where [offset] says. */
    var framed: StopFrame? by mutableStateOf(null)

    /** The move a tap on an edge started, while it lasts, and where it leads to. */
    var scrolling: Job? = null
    var walkingTo: Offset? = null
}

/**
 * The spread in the screen, in pixels.
 *
 * @property content the size of the spread at the fit.
 * @property corner the corner the spread opens at: going forward the start of its first page, going back the end
 *   of its last one, which are corners of the spread however many pages it has.
 * @property pages the room of each page within [content], in reading order.
 */
internal data class SpreadFrame(
    val viewport: Size,
    val content: Size,
    val corner: Corner,
    val direction: Direction,
    val pages: List<PageSpan> = emptyList(),
)

/** What the gestures of the page end in. */
internal class SpreadTurns(
    val navigation: ComicNavigation,
    val onToggleBars: () -> Unit,
    val onHideBars: () -> Unit,
)

/**
 * What each gesture on the spread does: the rules are those of `SpreadGeometry` and `SpreadGestureRules`, this
 * only applies them to the zoom and to the place of the spread.
 *
 * Taps on an edge are taken one after the other, each from where the one before leads to, never from where a
 * move that still goes on has got to: a step of the walk counts from the end of the step before, and after a
 * turn on its way a tap goes to the spread that turn leads to, as it will open.
 *
 * @param scope where a tap on an edge moves the page, over a few frames.
 * @property sliding what a finger that pulls the spread sideways moves, and what turns the page.
 * @property panels what steps from panel to panel; null when the series is not read so: every gesture is then
 *   what it is without it.
 */
internal class SpreadGestures(
    private val zoom: SpreadZoom,
    private val places: SpreadPlaces,
    private val turns: SpreadTurns,
    private val scope: CoroutineScope,
    val sliding: SpreadSliding,
    val panels: PanelSteps? = null,
) {
    private val pan: SpreadPan get() = places.shown.pan
    private val frame: SpreadFrame get() = places.shown.frame
    private val bounds: PanBounds get() = bounds(frame)

    /** Where the spread stands now: where a stop puts it, while one frames it. */
    val offset: Offset get() = pan.framed?.place?.offset ?: bounds.place(pan.offset, frame.corner)

    /** The zoom the spread shown is drawn at: a stop's while one frames it, else the reader's. */
    val scale: Float get() = pan.framed?.place?.zoom ?: zoom.scale

    /**
     * Whether a finger that goes sideways is a step from panel to panel, and moves nothing; there too, a finger
     * that moves a little is still a tap.
     */
    val steps: Boolean get() = panels?.steps == true

    /**
     * What a finger that comes down now and pulls the spread sideways finds at each side. With no turn on its
     * way, nothing is kept of what the taps before prepared for one.
     */
    fun ways(): SlideWays {
        if (sliding.pending == 0) places.forget()
        return slideWays(offset, bounds, frame.direction, sliding.room)
    }

    fun tap(at: Offset) {
        val turn = tapTurn(at.x, frame.viewport.width, frame.direction)
        when {
            turn == null -> turns.onToggleBars()
            panels == null -> edgeTap(turn)
            else -> panels.step(turn, ::edgeTap)
        }
    }

    /**
     * A finger that was a step ([steps]) is lifted, having gone sideways by [dx]: towards the side reading starts
     * from it goes forward, as a page is pulled in, and the other way back.
     */
    fun swiped(dx: Float) {
        val from = if (dx < 0f) Edge.Right else Edge.Left
        val turn = if (from == frame.direction.forwardEdge()) Turn.Forward else Turn.Back
        if (dx != 0f) panels?.step(turn, ::edgeTap)
    }

    /** The reader shows the spread, or more is known of it: what it is to do by itself, read panel by panel. */
    fun arrive() {
        panels?.arrive(::edgeTap)
    }

    /**
     * To twice the fit around the place pressed, or back to the fit. A stop is first left for the zoom the spread
     * is drawn at: the press goes on from what is seen.
     */
    fun longPress(at: Offset) {
        panels?.byHand()
        zoomTo(if (zoom.scale > MinZoom) MinZoom else LongPressZoom, at)
    }

    /**
     * Fingers moved: by [moved], and apart by [zoomed] around [centroid]. A stop is first left for the zoom the
     * spread is drawn at: the fingers go on from what is seen.
     */
    fun transform(centroid: Offset, moved: Offset, zoomed: Float) {
        panels?.byHand()
        zoomTo((zoom.scale * zoomed).coerceIn(MinZoom, MaxZoom), centroid, moved)
    }

    private fun zoomTo(scale: Float, around: Offset, moved: Offset = Offset.Zero) {
        pan.scrolling?.cancel()
        pan.walkingTo = null
        // Fingers that only move say nothing of where they are: the spread just follows them.
        val zoomed = scale != zoom.scale && around.isSpecified
        val place = (if (zoomed) zoomedAround(around, offset, from = zoom.scale, to = scale) else offset) + moved
        zoom.scale = scale
        pan.offset = bounds.clamp(place)
    }

    private fun bounds(frame: SpreadFrame): PanBounds = panBounds(frame.content, frame.viewport, zoom.scale)

    /** The tap goes to the spread the turns on their way lead to, and to the end of the step that still goes on. */
    private fun edgeTap(turn: Turn) {
        val pending = sliding.pending
        val spread = if (pending == 0) places.shown else places.ahead(pending)
        val bounds = spread?.let { bounds(it.frame) }
        val standing = bounds?.place(spread.pan.offset, spread.frame.corner)
        val to = standing?.let {
            val walked = WalkedSpread(bounds, spread.frame.viewport, spread.frame.pages.map { it * zoom.scale })
            edgeWalk(turn, spread.pan.walkingTo ?: it, walked, spread.frame.direction)
        }
        if (to == null) {
            sliding.turn(turn)
        } else {
            turns.onHideBars()
            walk(spread.pan, standing, to)
        }
    }

    private fun walk(pan: SpreadPan, from: Offset, to: Offset) {
        pan.scrolling?.cancel()
        pan.walkingTo = to
        pan.scrolling = scope.launch {
            animate(0f, 1f) { part, _ -> pan.offset = lerp(from, to, part) }
            pan.walkingTo = null
        }
    }
}
