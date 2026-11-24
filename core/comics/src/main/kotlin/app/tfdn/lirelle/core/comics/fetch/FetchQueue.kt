package app.tfdn.lirelle.core.comics.fetch

/** How many pages of the window are fetched at a time. */
internal const val WindowSlots = 2

/**
 * The pages of the window that are [waiting], in their order, and those [inFlight]: being fetched, whether
 * for the window or for the page shown.
 */
internal data class FetchQueue(val waiting: List<Int> = emptyList(), val inFlight: Set<Int> = emptySet()) {

    /**
     * The page whose fetch starts now, or null. The page [shown] the reader just arrived on never waits: it
     * starts whatever is going, unless it is being fetched already. Without one, the window goes on while
     * fewer than [WindowSlots] fetches are going.
     */
    fun next(shown: Int? = null): Int? = when {
        shown != null -> shown.takeIf { it !in inFlight }
        inFlight.size >= WindowSlots -> null
        else -> waiting.firstOrNull { it !in inFlight }
    }

    fun started(index: Int): FetchQueue = copy(waiting = waiting - index, inFlight = inFlight + index)

    fun finished(index: Int): FetchQueue = copy(inFlight = inFlight - index)

    /** After a jump to a page whose [window] is given: nothing waits, and only the fetches of that window go on. */
    fun jumped(window: Set<Int>): Jumped = Jumped(FetchQueue(inFlight = inFlight intersect window), inFlight - window)
}

/** The [queue] after a jump, and the pages whose fetches are [dropped]. */
internal data class Jumped(val queue: FetchQueue, val dropped: Set<Int>)
