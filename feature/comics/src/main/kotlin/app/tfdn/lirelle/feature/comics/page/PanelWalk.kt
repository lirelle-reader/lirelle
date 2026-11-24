package app.tfdn.lirelle.feature.comics.page

/** What a spread just turned to is to do once it is the one shown and its stops are known. */
internal enum class Arriving {
    /** Go to its first stop: the whole page is not shown first. */
    First,

    /** Go to its last stop: it was reached going back. */
    Last,
}

/** What a step of a reader that reads panel by panel does. */
internal sealed interface Step {
    /** The spread goes to its stop of that number, from 0. */
    data class To(val stop: Int) : Step

    /** The spread goes back to its whole view. */
    data object ToWhole : Step

    /** The spread is left for the one the [turn] leads to, which is then to do what [arriving] says. */
    data class Leave(val turn: Turn, val arriving: Arriving?) : Step

    /** Nothing yet: the stops are not known, and the step is done when they are. */
    data object Wait : Step
}

/**
 * Where a reader that reads panel by panel is among the stops of the spread shown, and what each step does from
 * there. It only knows how many stops the spread has, or that they are not known yet: what a stop is, and how the
 * spread gets to it, is not its business.
 *
 * A step forward goes from the whole view to the first stop, from a stop to the next, and from the last one, or
 * with no stop, to the next spread. A step back goes the other way; from the first stop it goes to the whole view
 * when the whole page is shown first, and from the whole view to the spread before, which goes to its last stop.
 * After a hand moved the spread, a step forward goes to the stop after the one left, a step back to that stop.
 *
 * While the stops are not known, a first step forward waits for them, and those after it leave the spread as one
 * that has no stop: a page whose picture never comes is never one the reader is kept on, and no step in two is
 * lost on a spread that what leaves it only moves.
 */
internal class PanelWalk {
    /**
     * The stop the reader is at, from 0, or the one a hand moved the spread away from; null at the whole view.
     */
    var at: Int? = null
        private set

    /** Whether a hand moved the spread away from the stop it was [at]. */
    private var left = false

    /** Whether a step forward waits for the stops to be known. */
    private var waited = false

    /** Whether a step forward already waited for the stops of the spread shown, still not known: no other does. */
    private var waitedOnce = false

    /** What the spread shown is still to do once its stops are known. */
    private var arriving: Arriving? = null

    /** The spread shown, null before any is; and the one a turn leads to with what it is to do there. */
    var spread: Int? = null
        private set
    private var ahead: Pair<Int, Arriving?>? = null

    /** Whether the reader is at a stop, and no hand moved the spread away from it. */
    val atStop: Boolean get() = at != null && !left

    /**
     * The reader shows the spread [index]. Another spread than before starts at its whole view: it is to do what
     * the turn that led to it asked, or, reached any other way (the album opening, the slider, a jump), to go to
     * its first stop unless the whole page is shown first.
     *
     * @return whether it is another spread than before, and the turn that was on its way led to it.
     */
    fun shown(index: Int, wholePageFirst: Boolean): Boolean {
        if (index == spread) return false
        val turned = ahead?.takeIf { it.first == index }
        spread = index
        ahead = null
        at = null
        left = false
        waited = false
        waitedOnce = false
        arriving = if (turned != null) turned.second else firstUnless(wholePageFirst)
        return turned != null
    }

    /** A turn started towards the spread [index], which is to do what [arriving] says once it is shown. */
    fun turning(index: Int, arriving: Arriving?) {
        ahead = index to arriving
    }

    /** A hand moved the spread, or zoomed it: nothing waits any more, and the stop left is remembered. */
    fun moved() {
        left = at != null
        waited = false
        arriving = null
        ahead = null
    }

    /**
     * The spread is back at its whole view by itself: the stop it was at is no longer one, or nothing is known of
     * its stops any more. Nothing is remembered of that stop: the walk starts again from the whole view.
     */
    fun whole() {
        at = null
        left = false
    }

    /** No turn is on its way: what one the reader did not take asked of the spread it led to is forgotten. */
    fun unturned() {
        ahead = null
    }

    /**
     * What a step does, and the walk is then where it leads.
     *
     * @param stops how many stops the spread shown has; null while they are not known.
     */
    fun step(turn: Turn, stops: Int?, wholePageFirst: Boolean): Step {
        ahead = null
        if (stops != null) waitedOnce = false
        val step = if (turn == Turn.Forward) forward(stops, wholePageFirst) else back(stops, wholePageFirst)
        follow(step)
        return step
    }

    /**
     * What the spread shown is to do by itself now that its [stops] are known, null when nothing: the step that
     * waited for them, or what the spread was to do when it was turned to. It is said once.
     */
    fun arrive(stops: Int, wholePageFirst: Boolean): Step? {
        val step = when {
            waited -> forward(stops, wholePageFirst)
            stops == 0 -> null
            arriving == Arriving.First -> Step.To(0)
            arriving == Arriving.Last -> Step.To(stops - 1)
            else -> null
        }
        waited = false
        waitedOnce = false
        arriving = null
        step?.let(::follow)
        return step
    }

    private fun forward(stops: Int?, wholePageFirst: Boolean): Step {
        val leave = Step.Leave(Turn.Forward, firstUnless(wholePageFirst))
        val next = at?.plus(1) ?: 0
        return when {
            stops == null -> if (waitedOnce) leave else Step.Wait
            next < stops -> Step.To(next)
            else -> leave
        }
    }

    private fun back(stops: Int?, wholePageFirst: Boolean): Step {
        // A stop that is no longer there is the last of those that are, or the whole view.
        val at = at?.takeIf { stops != null && stops > 0 }?.coerceAtMost((stops ?: 1) - 1)
        return when {
            at == null -> Step.Leave(Turn.Back, Arriving.Last)
            left -> Step.To(at)
            at > 0 -> Step.To(at - 1)
            wholePageFirst -> Step.ToWhole
            else -> Step.Leave(Turn.Back, Arriving.Last)
        }
    }

    /** The walk is where the [step] leads; only a step that waits leaves something to be done. */
    private fun follow(step: Step) {
        waited = step == Step.Wait
        waitedOnce = waitedOnce || waited
        if (step == Step.Wait) return
        at = (step as? Step.To)?.stop
        left = false
        arriving = null
    }
}

/** What a spread reached going forward, or any way but a turn back, is to do: nothing when it is shown whole first. */
private fun firstUnless(wholePageFirst: Boolean): Arriving? = Arriving.First.takeUnless { wholePageFirst }
