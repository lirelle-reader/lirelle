package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

/** Nearer than this to its place, in pixels, the spread is in its place: nothing slid it. */
private const val InPlace = 0.5f

/**
 * What the page view knows of reading panel by panel, for the spread shown.
 *
 * @property walk where the reader is among the stops.
 * @property index the spread shown, in the album's spreads.
 * @property stops the parts of the spread's content shown one after the other; empty when no page of the spread
 *   is cut, null while its zones are not known.
 * @property wholePageFirst whether each spread is shown whole before its first stop.
 * @property ahead the stops of the spread that many turns from the one shown (back under zero), as [stops] are;
 *   null where the album has no spread too.
 * @property keys what each of the [stops] shows; null when they are not known.
 * @property counted the stops of the spread shown as they were counted before.
 */
internal class PanelSpread(
    val walk: PanelWalk,
    val index: Int,
    val stops: List<Rect>?,
    val wholePageFirst: Boolean,
    val ahead: (turns: Int) -> List<Rect>? = { null },
    val keys: List<StopKey>? = null,
    val counted: CountedStops = CountedStops(),
)

/**
 * The steps of a reader that reads panel by panel: the rules are those of [PanelWalk], this applies them to the
 * spread shown, which [StopMoves] puts at its stops.
 *
 * A spread with no stop takes every gesture as it does without the mode: a finger going sideways slides it, and a
 * tap on an edge first walks what a zoom or a fit to the width leaves to read of it, then turns. So does a spread
 * whose stops are not known, but for the first tap forward: that one waits for the stops, and is done when they
 * come.
 *
 * A spread a step turns to, when it is to arrive at a stop and its stops are known, is framed by that stop before
 * the slide brings it in: the reader never sees it whole first. Shown, it is at that stop at once.
 *
 * @param places where the spread a turn leads to is framed ahead of the turn.
 * @param sliding what turns the page.
 * @param onHideBars a step that moved the spread without turning the page: the user reads on.
 */
internal class PanelSteps(
    private val reading: PanelSpread,
    private val moves: StopMoves,
    private val places: SpreadPlaces,
    private val sliding: SpreadSliding,
    private val onHideBars: () -> Unit,
) {
    private val walk: PanelWalk get() = reading.walk

    /**
     * Whether a finger that goes sideways is a step: a stop frames the spread, or the spread is at the fit and
     * has stops. There the page has nothing more to show sideways that a finger would move, and a finger that
     * moves a little is still a tap. A spread with no stop, or whose stops are not known, is the finger's to
     * slide, as without the mode.
     */
    val steps: Boolean get() = moves.framed || (!reading.stops.isNullOrEmpty() && moves.atFit)

    /**
     * A step forward or back. None is taken while a turn is on its way.
     *
     * @param today what a tap on that edge does without the mode.
     */
    fun step(turn: Turn, today: (Turn) -> Unit) {
        if (sliding.pending != 0) return
        when (val step = walk.step(turn, reading.stops?.size, reading.wholePageFirst)) {
            is Step.Leave -> leave(step, today)
            // Nothing is seen to happen: the bars stay.
            Step.Wait -> Unit
            else -> {
                onHideBars()
                take(step)
            }
        }
    }

    /**
     * The reader shows the spread, or more is known of it. A spread reached from one left at a stop is at the
     * fit: that stop's zoom is not the reader's to keep. A stop whose spread changed under it gives way to the
     * whole view: nothing is known of the stops any more, or the stop is no longer one. Then, its stops known, the
     * spread does what it is still to do by itself: the step that waited for them, or going to the stop the turn
     * that led to it asked for. It does nothing by itself while a turn is on its way, nor where a hand slid it.
     *
     * A spread that comes framed by the stop the turn that led to it asked for is at that stop at once: it slid in
     * so. One that comes framed and that no turn led to is whole: the turn was not taken, and it was jumped to.
     *
     * The stops of the spread shown counted again (the screen turned), the reader stays at the one that shows what
     * its stop showed, whatever its number now.
     */
    fun arrive(today: (Turn) -> Unit) {
        val other = walk.spread != reading.index
        if (other && walk.atStop) moves.wholeAtOnce()
        val renumbered = reading.counted.recount(reading.index, reading.keys, walk.at)
        val led = walk.shown(reading.index, reading.wholePageFirst, renumbered)
        val stops = reading.stops
        if (stops == null) {
            if (moves.framed) whole()
            return
        }
        if (other && moves.framed) land(stops.takeIf { led })
        refit(stops)
        if (!inPlace()) return
        when (val step = walk.arrive(stops.size, reading.wholePageFirst)) {
            null -> Unit
            is Step.Leave -> leave(step, today)
            else -> take(step)
        }
    }

    /**
     * A hand moves the spread, zooms it, or slides it towards the one beside it: the stop is left, and nothing
     * waits any more. The spread a turn leads to is no longer framed ahead: it comes whole under the hand.
     */
    fun byHand() {
        walk.moved()
        moves.release()
        places.forget()
    }

    /**
     * Said before the slide follows the spread the reader shows. With no turn on its way, what a turn the reader
     * did not take asked of the spread it led to is forgotten, and so is the stop that framed it ahead: that
     * spread, jumped to later, is one like any other.
     */
    fun settle() {
        if (sliding.pending != 0) return
        walk.unturned()
        places.forget()
    }

    /**
     * Whether the spread stands in its place, no turn on its way: there only does it do something by itself. Off
     * its place with no turn on its way, a finger slides it, or let it go: that is a hand.
     */
    private fun inPlace(): Boolean {
        val turning = sliding.pending != 0
        val slid = !turning && abs(sliding.offset) > InPlace
        if (slid) byHand()
        return !turning && !slid
    }

    /**
     * The stop the reader is at frames the spread as the screen and the zones now make it. A stop that is no
     * longer among them gives way to the whole view.
     */
    private fun refit(stops: List<Rect>) {
        val at = walk.at
        val stop = at?.let(stops::getOrNull)
        if (at != null && stop == null && moves.framed) whole() else moves.refit(stop)
    }

    /**
     * The spread just shown came framed, as it slid in. The walk is at the stop it was to arrive at, among its
     * [stops], and that stop frames it as the screen and the zones now make it, with no move. With no such stop,
     * or when no turn led to the spread ([stops] null), the spread is whole.
     */
    private fun land(stops: List<Rect>?) {
        val step = stops?.let { walk.arrive(it.size, reading.wholePageFirst) } as? Step.To
        val stop = step?.let { stops?.getOrNull(it.stop) }
        if (stop == null) whole() else moves.standAt(stop)
    }

    /** The spread is back at its whole view at once, and the walk with it: it starts again from there. */
    private fun whole() {
        moves.wholeAtOnce()
        walk.whole()
    }

    private fun take(step: Step) {
        when (step) {
            is Step.To -> reading.stops?.getOrNull(step.stop)?.let(moves::goTo)
            Step.ToWhole -> moves.toWhole()
            else -> Unit
        }
    }

    /**
     * The spread is left for the one beside it. Read by its stops, it is back at its whole view at once: it slides
     * out whole. The spread the turn leads to is told what it is to do once shown, and, when that is to go to a
     * stop already known, is framed by it at once: it slides in at that stop, the rest of it hidden.
     */
    private fun leave(step: Step.Leave, today: (Turn) -> Unit) {
        if (sliding.pending != 0) return
        if (!moves.framed && reading.stops.isNullOrEmpty()) {
            today(step.turn)
        } else {
            moves.wholeAtOnce()
            sliding.turn(step.turn)
        }
        val turns = if (step.turn == Turn.Forward) 1 else -1
        if (sliding.pending == 0) return
        walk.turning(reading.index + turns, step.arriving)
        val stop = arrivalStop(step.arriving, reading.ahead(turns))
        val ahead = places.ahead(turns)
        if (stop != null && ahead != null) moves.frameAhead(ahead, stop) else places.forget()
    }
}

/** The stop a spread is to arrive at among its [stops]: none when it arrives whole, or has no stop that is known. */
private fun arrivalStop(arriving: Arriving?, stops: List<Rect>?): Rect? = when (arriving) {
    Arriving.First -> stops?.firstOrNull()
    Arriving.Last -> stops?.lastOrNull()
    null -> null
}
