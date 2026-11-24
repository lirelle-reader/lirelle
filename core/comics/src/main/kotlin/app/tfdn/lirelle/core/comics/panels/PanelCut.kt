package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.settings.Direction
import java.util.Collections

/** Under this side, in pixels, a picture is no page to cut. */
private const val LeastSide = 64

/**
 * The zones of a page, in the order they are read in [direction]: panels, and zones left whole where no clean cut
 * remains. Empty, or one zone, for a page that cannot be cut. The picture is expected at the working size
 * ([WorkingSide] on its long side) or under.
 *
 * The panels of a story page tile it: they nearly touch, and fill the box around them all. A page whose zones
 * leave wide gaps between them is not cut, and has none: they are pieces on a ground (a cover, a title and a
 * picture, art without frames), and showing them one by one means nothing. Nor is a page whose zones together
 * take little of it. The kind of a zone decides nothing here: it only says how filled the zone's box is, and on a
 * dark page, or where balloons open on the gutter, true panels look sparsely filled.
 *
 * The page is cut again and again along the gutters that cross a zone from side to side — across it first, then
 * down it — and, where no gutter is left, along a line two panels share.
 */
internal fun cutPage(picture: PagePixels, direction: Direction): List<Zone> {
    if (picture.width < LeastSide || picture.height < LeastSide) return emptyList()
    return PageCut(picture, direction == Direction.RightToLeft).zones
}

/**
 * The cut of one page. What it keeps while it cuts is the counts and the widened gutters only: the planes and the
 * greys they are made from are let go as soon as they are counted.
 */
private class PageCut(picture: PagePixels, private val fromRight: Boolean) {
    private val width = picture.width
    private val height = picture.height
    private val wander = Math.rint(Cut.Wander * maxOf(width, height)).toInt()

    /** The gutters, counted. */
    private val outerSums: Sums

    /** The gutters widened across the lines looked for: up and down for cuts across, sideways for cuts down. */
    private val acrossGutters: Plane
    private val acrossCounts: LineCounts
    private val downGutters: Plane
    private val downCounts: LineCounts

    /** The strokes that run across the page, then down it, hairlines of gutter colour included. */
    private val acrossStrokes: LineCounts
    private val downStrokes: LineCounts

    private val found = mutableListOf<Zone>()

    /** The share of the page the zones found take together. */
    private var cover = 0.0

    /** The box around all the zones found, in the same proportions of the page as [cover] is summed from. */
    private var aroundLeft = Double.MAX_VALUE
    private var aroundTop = Double.MAX_VALUE
    private var aroundRight = -Double.MAX_VALUE
    private var aroundBottom = -Double.MAX_VALUE

    init {
        val near = nearColour(picture, gutterColour(picture))
        // The strokes first: their planes and the greys are gone before the gutters are looked for.
        val strokes = strokeCounts(picture, near)
        acrossStrokes = strokes.first
        downStrokes = strokes.second
        val outer = outerBackground(near, width, height)
        outerSums = Sums(outer)
        acrossGutters = outer.boxed(1, 2 * wander + 1, erode = false)
        acrossCounts = LineCounts(acrossGutters, rows = true)
        downGutters = outer.boxed(2 * wander + 1, 1, erode = false)
        downCounts = LineCounts(downGutters, rows = false)
    }

    /** The zones of the page, in the order they are read: the page is cut once, at the first call. */
    val zones: List<Zone> by lazy {
        cut(Box(0, 0, width, height), 0)
        // Panels tile their page. Zones with wide gaps between them are pieces on a ground, and zones that take
        // little of the page are a picture and a title on a title page: the page is not cut.
        if (found.isNotEmpty()) {
            val around = (aroundRight - aroundLeft) * (aroundBottom - aroundTop)
            if (cover < Cut.LeastTiling * around || cover < Cut.LeastCover) found.clear()
        }
        Collections.unmodifiableList(found)
    }

    /** [zone] drawn in to what is not gutter in it; null when nothing is. */
    private fun trimmed(zone: Box): Box? {
        if (zone.width <= 0 || zone.height <= 0) return null
        var top = -1
        var bottom = -1
        for (y in zone.top until zone.bottom) {
            val ink = zone.width - outerSums.count(zone.left, y, zone.right, y + 1)
            if (ink.toDouble() / zone.width > Cut.TrimShare) {
                if (top < 0) top = y
                bottom = y + 1
            }
        }
        var left = -1
        var right = -1
        for (x in zone.left until zone.right) {
            val ink = zone.height - outerSums.count(x, zone.top, x + 1, zone.bottom)
            if (ink.toDouble() / zone.height > Cut.TrimShare) {
                if (left < 0) left = x
                right = x + 1
            }
        }
        return if (top < 0 || left < 0) null else Box(left, top, right, bottom)
    }

    /** Cuts [whole] as far as it can be, and keeps what is left of it when it is large enough. */
    private fun cut(whole: Box, depth: Int) {
        val zone = trimmed(whole) ?: return
        if (depth < Cut.Depth && split(zone, depth)) return
        val w = zone.width.toDouble() / width
        val h = zone.height.toDouble() / height
        if (w < Cut.SmallSide || h < Cut.SmallSide || w * h < Cut.SmallArea) return
        val area = zone.width.toLong() * zone.height
        val gutter = outerSums.count(zone.left, zone.top, zone.right, zone.bottom).toDouble() / area
        val left = zone.left.toDouble() / width
        val top = zone.top.toDouble() / height
        val right = zone.right.toDouble() / width
        val bottom = zone.bottom.toDouble() / height
        // Summed as the reference sums it, before the proportions are made shorter.
        cover += (right - left) * (bottom - top)
        aroundLeft = minOf(aroundLeft, left)
        aroundTop = minOf(aroundTop, top)
        aroundRight = maxOf(aroundRight, right)
        aroundBottom = maxOf(aroundBottom, bottom)
        found += Zone(
            left = left.toFloat(),
            top = top.toFloat(),
            right = right.toFloat(),
            bottom = bottom.toFloat(),
            kind = if (1.0 - gutter >= Cut.PanelFill) ZoneKind.Filled else ZoneKind.Sparse,
        )
    }

    /**
     * Cuts [zone] along the first kind of separator it has — gutters across it, gutters down it, shared lines
     * across it, shared lines down it — and each part in turn. False when it has none.
     */
    private fun split(zone: Box, depth: Int): Boolean {
        var across = true
        var runs = gutterRuns(acrossGutters, acrossCounts, zone, across = true, wander = wander)
        if (runs.isEmpty()) {
            across = false
            runs = gutterRuns(downGutters, downCounts, zone, across = false, wander = wander)
        }
        if (runs.isEmpty()) {
            across = true
            runs = lineRuns(acrossStrokes, width, height, zone, across = true)
        }
        if (runs.isEmpty()) {
            across = false
            runs = lineRuns(downStrokes, width, height, zone, across = false)
        }
        if (runs.isEmpty()) return false
        for (part in parts(zone, runs, across)) cut(part, depth + 1)
        return true
    }

    /** The parts of [zone] between its [runs]: from the top, or in the reading direction. */
    private fun parts(zone: Box, runs: List<Pair<Int, Int>>, across: Boolean): List<Box> {
        val parts = ArrayList<Box>(runs.size + 1)
        var start = 0
        for (i in 0..runs.size) {
            val end = if (i < runs.size) runs[i].first else if (across) zone.height else zone.width
            parts += if (across) {
                Box(zone.left, zone.top + start, zone.right, zone.top + end)
            } else {
                Box(zone.left + start, zone.top, zone.left + end, zone.bottom)
            }
            if (i < runs.size) start = runs[i].second
        }
        return if (!across && fromRight) parts.asReversed() else parts
    }
}

/**
 * The counts of the strokes that run across the page, along its rows, then of those that run down it, along its
 * columns. A hairline gutter, too thin to carry the flood, separates as a stroke does: what is gutter-coloured
 * ([near]) counts as a stroke both ways.
 */
private fun strokeCounts(picture: PagePixels, near: Plane): Pair<LineCounts, LineCounts> {
    val (across, along) = strokes(greys(picture), picture.width, picture.height)
    for (i in near.bits.indices) {
        if (near.bits[i]) {
            across.bits[i] = true
            along.bits[i] = true
        }
    }
    return LineCounts(across, rows = true) to LineCounts(along, rows = false)
}
