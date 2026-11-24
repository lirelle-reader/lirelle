package app.tfdn.lirelle.core.comics.panels

/**
 * How many cells are set on the line [index] of [zone]: a row of it when [across], a column otherwise. The counts
 * are those of rows for the first, of columns for the second.
 */
private fun LineCounts.onLine(zone: Box, index: Int, across: Boolean): Int = if (across) {
    onRow(zone.top + index, zone.left, zone.right)
} else {
    onColumn(zone.left + index, zone.top, zone.bottom)
}

/**
 * The gutters that cross [zone]: across it (lines from left to right) or down it. Each is given as where it starts
 * and ends, counted from the zone's top or left, and lies strictly inside the zone.
 *
 * @param gutters the gutters widened by [wander] pixels across the lines looked for, so that a gutter slightly
 * tilted or stepped still makes whole lines.
 * @param counts the counts of [gutters] along the lines looked for: its rows when [across], its columns otherwise.
 */
internal fun gutterRuns(
    gutters: Plane,
    counts: LineCounts,
    zone: Box,
    across: Boolean,
    wander: Int,
): List<Pair<Int, Int>> {
    val lines = if (across) zone.height else zone.width
    val length = if (across) zone.width else zone.height
    val ok = BooleanArray(lines) { i ->
        counts.onLine(zone, i, across).toDouble() / length >= Cut.GutterShare &&
            longestBlockedRun(gutters, zone, i, across) <= Cut.Blocked * length
    }
    val runs = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i < lines) {
        var j = i
        while (j < lines && ok[j]) j++
        if (i > 0 && j < lines && j - i >= Cut.GutterThickness) runs += unwidened(i, j, wander)
        i = maxOf(j, i + 1)
    }
    return runs
}

/** The gutter in the lines from [start] to [end]: the widening taken back, or its middle when no wider than it. */
private fun unwidened(start: Int, end: Int, wander: Int): Pair<Int, Int> {
    if (end - start > 2 * wander + 1) return start + wander to end - wander
    val middle = (start + end) / 2
    return middle to middle + 1
}

/** The longest stretch of the line [index] of [zone] that is no gutter. */
internal fun longestBlockedRun(gutters: Plane, zone: Box, index: Int, across: Boolean): Int {
    val length = if (across) zone.width else zone.height
    val step = if (across) 1 else gutters.width
    var at = zone.top * gutters.width + zone.left + if (across) index * gutters.width else index
    var longest = 0
    var run = 0
    repeat(length) {
        if (gutters.bits[at]) {
            run = 0
        } else {
            run++
            if (run > longest) longest = run
        }
        at += step
    }
    return longest
}

/**
 * The thin separators that cross a [zone] with no gutter left: lines of strokes over [Cut.LineShare] of their
 * length, grouped when close, thin as a whole, with nothing like them a few pixels further on each side, and far
 * enough from the zone's edges. Given as [gutterRuns] gives gutters.
 *
 * @param strokes the counts of the strokes that run the way looked for, hairlines of gutter colour included: along
 * the rows when [across], along the columns otherwise.
 */
internal fun lineRuns(
    strokes: LineCounts,
    pageWidth: Int,
    pageHeight: Int,
    zone: Box,
    across: Boolean,
): List<Pair<Int, Int>> {
    val lines = if (across) zone.height else zone.width
    val length = if (across) zone.width else zone.height
    val share = DoubleArray(lines) { strokes.onLine(zone, it, across).toDouble() / length }
    val margin = (if (across) Cut.LineEdgeAcross * pageHeight else Cut.LineEdgeAlong * pageWidth).toInt()
    val near = Cut.StrokeReach + 2
    val thick = Cut.LineThickness * maxOf(pageWidth, pageHeight)
    val merged = mutableListOf<Pair<Int, Int>>()
    var first = margin
    while (first < lines - margin) {
        if (share[first] < Cut.LineShare) {
            first++
            continue
        }
        // The group: the lines of strokes from this one on, each at most [near] after the one before.
        var last = first
        for (i in first + 1 until lines - margin) {
            if (i - last > near) break
            if (share[i] >= Cut.LineShare) last = i
        }
        // A line before the zone's first is read from its end, as the reference does.
        val thin = last - first <= thick &&
            share[Math.floorMod(first - near, lines)] < Cut.LineFlank &&
            share[minOf(lines - 1, last + near)] < Cut.LineFlank
        if (thin) merged.addLines(first, last, margin)
        first = last + 1
    }
    return merged
}

/** Adds the lines [first] to [last] one by one, each joining the run before when nearer to it than [margin]. */
private fun MutableList<Pair<Int, Int>>.addLines(first: Int, last: Int, margin: Int) {
    for (i in first..last) {
        val before = lastOrNull()
        if (before != null && i - before.second < margin) this[lastIndex] = before.first to i + 1 else add(i to i + 1)
    }
}
