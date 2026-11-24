package app.tfdn.lirelle.feature.reader

/**
 * The widths of the two slots of a line [available] wide, from the widths they would like. Both
 * are kept when they fit; otherwise the shorter one keeps its own, up to half the line, and the
 * longer one takes what is left.
 */
internal fun shareWidth(available: Int, left: Int, right: Int): Pair<Int, Int> {
    if (left + right <= available) return left to right
    val half = available / 2
    return if (left <= right) {
        val kept = minOf(left, half)
        kept to available - kept
    } else {
        val kept = minOf(right, half)
        available - kept to kept
    }
}

/**
 * The width of each slot of a line [available] wide with something [centre] wide in its middle,
 * [gap] away from it on each side: the two slots get the same, so that the middle stays in the middle.
 */
internal fun sideWidth(available: Int, centre: Int, gap: Int): Int = ((available - centre) / 2 - gap).coerceAtLeast(0)
