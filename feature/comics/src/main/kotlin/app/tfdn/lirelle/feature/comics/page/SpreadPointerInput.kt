package app.tfdn.lirelle.feature.comics.page

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * How far a finger may go from where it came down and still be a tap, where the reader steps from panel to panel:
 * a thumb that taps a tablet held in both hands rolls further than a tap may elsewhere, and such a tap is not to
 * take the page out of its panel, nor to step the way the thumb rolled. To be tuned on devices.
 */
private val LooseTapReach = 24.dp

/**
 * The gestures of the page: a tap, a long press, and fingers that move the spread, zoom it or slide it to the
 * spread beside it. A tap acts when the finger is lifted: nothing waits for a second one. [gestures] gives what
 * they do now: the spread and its size change under a finger that stays down. A long press gives a tick through
 * [haptics], and the finger that stays down after it holds the page: it moves it, and slides to no other spread.
 * Where the reader steps from panel to panel, a finger that moves a little is still a tap ([LooseTap]).
 */
internal fun Modifier.spreadGestures(haptics: HapticFeedback, gestures: () -> SpreadGestures): Modifier =
    pointerInput(haptics) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val ways = gestures().ways()
            // A press something on the page took (a button) is not the page's: it is never a loose tap.
            val loose = if (down.isConsumed || !gestures().steps) null else looseTap(down)
            val fingers = Fingers(ways, viewConfiguration.touchSlop, loose)
            // A finger a child took as it came down (a button) is neither a tap nor a long press of the page.
            when (if (down.isConsumed) Press.Moved else press(fingers, gestures)) {
                Press.Tap -> gestures().tap(fingers.at)
                Press.Long -> {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    gestures().longPress(down.position)
                    holdPage(Fingers(HeldWays, viewConfiguration.touchSlop), gestures)
                }
                Press.Moved -> followFingers(fingers, gestures)
                Press.Taken -> Unit
            }
        }
    }

/** The tap that the finger that came [down] may still be once it moved a little. */
private fun AwaitPointerEventScope.looseTap(down: PointerInputChange) = LooseTap(
    at = down.position,
    since = down.uptimeMillis,
    reach = LooseTapReach.toPx(),
    within = viewConfiguration.longPressTimeoutMillis,
)

/**
 * A tap that moves a little, where the reader steps from panel to panel: one finger that never goes further than
 * the [reach] from where it came down, and is lifted before a long press would be one, is a tap there, whatever
 * way it moved.
 *
 * @property at where the finger came down: the place of the tap, wherever the finger is lifted.
 * @property since when it came down, in milliseconds, and [within] how long after that it is to be lifted.
 * @property reach how far from [at] it may go, in pixels.
 */
private class LooseTap(val at: Offset, val since: Long, val reach: Float, val within: Long) {
    /** Whether the [event] is the finger lifted soon enough to be a tap. */
    fun lifted(event: PointerEvent): Boolean =
        event.changes.none { it.pressed } && event.changes.all { it.uptimeMillis - since < within }
}

/** What a finger that came down turned out to be. */
private enum class Press {
    /** It was lifted where it came down, before a long press. */
    Tap,

    /** It stayed where it came down for as long as a long press takes. */
    Long,

    /** It went further than a tap may, or a second finger came down. */
    Moved,

    /** A child took its move. */
    Taken,
}

/**
 * Waits for what the finger that came down is: nothing moves before it went further than a tap may, or a second
 * one came down, and it is a long press if neither happened, nor was it lifted, within the time of one.
 */
private suspend fun AwaitPointerEventScope.press(fingers: Fingers, gestures: () -> SpreadGestures): Press {
    val press = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var press: Press? = null
        while (press == null) {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) return@withTimeoutOrNull Press.Taken
            fingers.moved(event, gestures())
            press = when {
                !fingers.waiting -> Press.Moved
                event.changes.none { it.pressed } -> Press.Tap.also { event.changes.forEach { it.consume() } }
                else -> null
            }
        }
        press
    }
    return press ?: Press.Long
}

/**
 * Follows the fingers that moved until the last one is up. A move a child took (the scrolling of a failed page)
 * is left to it.
 */
private suspend fun AwaitPointerEventScope.followFingers(fingers: Fingers, gestures: () -> SpreadGestures) {
    while (currentEvent.changes.any { it.pressed }) {
        val event = awaitPointerEvent()
        if (event.changes.any { it.isConsumed }) break
        fingers.moved(event, gestures())
    }
    fingers.lifted(gestures())
}

/**
 * What a finger that holds the page after a long press finds at each side: no spread to slide to, whatever
 * edge the page is at. It only moves the page, as far as the page is larger than the screen.
 */
private val HeldWays = SlideWays(left = Way.Closed, right = Way.Closed)

/**
 * After a long press, follows the [fingers] that hold the page until the last one is up: the finger that
 * pressed moves the page once it went further than a tap may, counted from where the long press found it, and
 * a second finger zooms. Lifting them is not a tap, and what they did is no one else's to take. A move a child
 * took is left to it.
 */
private suspend fun AwaitPointerEventScope.holdPage(fingers: Fingers, gestures: () -> SpreadGestures) {
    do {
        val event = awaitPointerEvent()
        if (event.changes.none { it.isConsumed }) fingers.moved(event, gestures())
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

/** What the fingers that are down do. */
private enum class Following {
    /** Nothing yet: they may still be a tap, or a long press. */
    Waiting,

    /** One finger pulls the spread sideways, towards the spread beside it. */
    Sliding,

    /** They move the page and zoom it. */
    Moving,

    /** One finger goes sideways over a reader that reads panel by panel: nothing moves, it is a step when lifted. */
    Stepping,

    /**
     * One finger went further than a tap may, but no further than a tap that moves a little may, over a reader
     * that reads panel by panel: nothing moves, and it is a tap where it came down when lifted soon enough.
     */
    Loose,
}

/**
 * The fingers of one gesture. What they do is settled when they first move further than the [slop]: one finger
 * going more sideways than upright slides the spread, unless the page had more to show that way when it came
 * down ([ways]); anything else moves the page, and so does every move where both ways are closed. A second finger
 * ends a slide: from then on they zoom.
 *
 * Where the reader stepped from panel to panel when the finger came down ([loose] is then given), what one finger
 * does is settled later: when it first goes further than a tap that moves a little may. Until then nothing moves,
 * no stop is left, and the finger lifted soon enough is a tap where it came down. From then on, going more
 * sideways than upright it moves nothing: lifted further than that reach to a side of where it came down, it is a
 * step the way it went; going any other way it moves the page, from where it then is. A second finger ends all
 * that: they zoom. A finger that slides the spread is a hand for a reader that reads panel by panel: what the
 * spread was still to do by itself is forgotten.
 */
private class Fingers(private val ways: SlideWays, private val slop: Float, private val loose: LooseTap? = null) {
    private val velocity = VelocityTracker()
    private var drag = Offset.Zero
    private var following = Following.Waiting

    /** Whether the finger was just lifted as a tap that moved a little. */
    private var tapped = false

    /** Whether they may still be a tap, or a long press. */
    val waiting: Boolean get() = following == Following.Waiting

    /** Where the first of them last was. */
    var at = Offset.Unspecified
        private set

    fun moved(event: PointerEvent, gestures: SpreadGestures) {
        val moved = event.calculatePan()
        drag += moved
        at = event.changes.first().position
        event.changes.forEach(velocity::addPointerInputChange)
        val pinched = event.changes.count { it.pressed } > 1
        if (pinched && following == Following.Sliding) gestures.sliding.release(velocity = 0f, ways)
        following = if (pinched) Following.Moving else settled()
        when (following) {
            Following.Waiting -> return
            Following.Stepping, Following.Loose -> Unit
            Following.Sliding -> {
                gestures.panels?.byHand()
                gestures.sliding.drag(moved.x, ways)
            }
            Following.Moving ->
                gestures.transform(event.calculateCentroid(useCurrent = false), moved, event.calculateZoom())
        }
        tapped = following == Following.Loose && loose?.lifted(event) == true
        // The lift of a tap is the page's, as that of a tap that did not move.
        event.changes.filter { tapped || it.positionChanged() }.forEach { it.consume() }
    }

    /** The fingers are lifted, or a child took their move. */
    fun lifted(gestures: SpreadGestures) {
        when (following) {
            Following.Sliding -> gestures.sliding.release(velocity.calculateVelocity().x, ways)
            Following.Stepping -> if (abs(drag.x) > (loose?.reach ?: slop)) gestures.swiped(drag.x)
            Following.Loose -> if (tapped) loose?.let { gestures.tap(it.at) }
            Following.Waiting, Following.Moving -> Unit
        }
    }

    private fun settled(): Following = when {
        following != Following.Waiting && following != Following.Loose -> following
        drag.getDistance() <= slop -> following
        loose != null -> loose.settled()
        abs(drag.x) > abs(drag.y) && ways.at(drag.x) != Way.Closed -> Following.Sliding
        else -> Following.Moving
    }

    /** What one finger that went further than a tap may is, where the reader steps from panel to panel. */
    private fun LooseTap.settled(): Following = when {
        drag.getDistance() <= reach -> Following.Loose
        abs(drag.x) > abs(drag.y) -> Following.Stepping
        else -> Following.Moving
    }
}
