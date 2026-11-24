package app.tfdn.lirelle.feature.reader

private const val TurnsToForget = 2
private const val MostPlaces = 32

/**
 * The places jumps were made from, the last one on top. "Go back" is offered while there is one;
 * two pages turned after a jump mean the user is reading on, and every place is forgotten.
 */
class GoBackStack {
    private val places = ArrayDeque<String>()
    private var turns = 0

    val isEmpty: Boolean get() = places.isEmpty()

    /** A jump is made from [cfi]. */
    fun push(cfi: String) {
        places.addLast(cfi)
        if (places.size > MostPlaces) places.removeFirst()
        turns = 0
    }

    /** Where to go back to, which is forgotten; null when there is nowhere. */
    fun pop(): String? {
        turns = 0
        return places.removeLastOrNull()
    }

    /** A page was turned, by any means that is not a jump. */
    fun onPageTurn() {
        if (places.isEmpty()) return
        turns++
        if (turns >= TurnsToForget) clear()
    }

    fun clear() {
        places.clear()
        turns = 0
    }
}
