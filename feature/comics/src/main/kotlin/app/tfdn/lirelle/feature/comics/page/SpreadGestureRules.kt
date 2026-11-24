package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.tfdn.lirelle.core.comics.layout.Edge
import app.tfdn.lirelle.core.comics.layout.forwardEdge
import app.tfdn.lirelle.core.comics.settings.Direction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The part of the width, at each edge, where a tap turns the page. */
private const val EdgeShare = 0.25f

/** How much of the screen a tap on an edge moves a page by: a little is kept in sight to read on from. */
private const val ScrollShare = 0.9f

/** Nearer than this to its end, in pixels, a page is at its end. */
private const val AtEnd = 0.5f

/** A turn of the page: to the next spread, or to the one before. */
internal enum class Turn { Forward, Back }

/**
 * What a tap at [x] in a screen [width] wide turns: forward in the quarter at the end of the reading
 * [direction], back in the quarter at the other edge, nothing (null) in the middle.
 */
internal fun tapTurn(x: Float, width: Float, direction: Direction): Turn? {
    val edge = when {
        x < width * EdgeShare -> Edge.Left
        x > width * (1 - EdgeShare) -> Edge.Right
        else -> return null
    }
    return if (edge == direction.forwardEdge()) Turn.Forward else Turn.Back
}

/**
 * The room of a page within its spread, sideways: from its [left] edge to its [right] one, counted from the
 * left edge of the spread.
 */
internal data class PageSpan(val left: Float, val right: Float) {
    val width: Float get() = right - left

    /** The same room in a spread drawn [zoom] times as large. */
    operator fun times(zoom: Float): PageSpan = PageSpan(left * zoom, right * zoom)
}

/**
 * A spread as a tap on an edge walks it.
 *
 * @property bounds how far it can be moved, and [viewport] the screen.
 * @property pages the room of each of its pages at the zoom in use, in reading order; none, or one, is one page
 *   that is the whole spread.
 */
internal data class WalkedSpread(val bounds: PanBounds, val viewport: Size, val pages: List<PageSpan> = emptyList())

/** [edgeWalk] for a spread of one page, which can be moved within [bounds]. */
internal fun edgeWalk(turn: Turn, offset: Offset, bounds: PanBounds, viewport: Size, direction: Direction): Offset? =
    edgeWalk(turn, offset, WalkedSpread(bounds, viewport), direction)

/**
 * What a tap on an edge does to a [spread] standing at [offset] and larger than the screen: it walks it as it is
 * read, one page after the other, each page row by row. Going forward it moves by about a screen towards the
 * side where a row ends, up to the edge of the page being walked; from there it goes down by about a screen and
 * back to the side of that page where a row starts; at the bottom of a page's last row it goes to the top of
 * the next page, at its start side; at the end of the last page, null: the page turns. Going back it is the
 * same walk the other way round: towards the start of the row, then up and to the end of the row above, from
 * the top of a page to the bottom of the page before at its end side, and at the top of the first page a turn
 * back.
 *
 * A page no wider than the screen has no row to walk along: it is framed on the edge where its rows start, as
 * far as the spread can move, and only walked down or up. A spread no wider than the screen is walked down or
 * up once, as one picture, and one that fits the screen turns at once.
 *
 * The page being walked is told from where the screen stands, so that a walk goes on from wherever a finger
 * left the spread: it is the page whose own places, from the start of its rows to their end, the spread stands
 * in, or nearest to sideways. A screen left across both pages is thus on the first page when it is nearer to
 * where that page's rows end, and stands at the end of one of its rows: the walk goes on with the next row of
 * that page, never backwards along the row. It is on the second page when it is nearer to where that page's
 * rows start: the walk goes on along that row. Halfway between the two, it is on the page the walk has not
 * finished: the first going forward, the second going back.
 *
 * @param offset where the spread stands, within its bounds.
 * @return the offset to go to, or null to turn.
 */
internal fun edgeWalk(turn: Turn, offset: Offset, spread: WalkedSpread, direction: Direction): Offset? {
    val forward = turn == Turn.Forward
    // Left to right a row ends at the right of the page: going forward the page moves to the left.
    val leftwards = forward == (direction.forwardEdge() == Edge.Right)
    // How far along the walk the spread is, sideways: it grows from the first row's start to the last one's end.
    val along = if (leftwards) -offset.x else offset.x
    val rows = spread.rows(direction)
        .let { rows -> if (forward) rows else rows.reversed().map { Row(it.to, it.from) } }
        .map { if (leftwards) Row(-it.from, -it.to) else it }
    // The first of the rows nearest to it: halfway between two pages, the one the walk has not finished.
    val walked = rows.indices.minBy { max(max(rows[it].from - along, along - rows[it].to), 0f) }
    val row = rows[walked]
    val bounds = spread.bounds
    val pageStart = if (forward) bounds.max.y else bounds.min.y
    val pageEnd = if (forward) bounds.min.y else bounds.max.y
    val step = spread.viewport * ScrollShare
    // Back from how far along the walk to an offset; nothing is written as a zero with a minus sign.
    val sideways = { place: Float -> if (leftwards) 0f - place else place }
    return when {
        row.to - along > AtEnd -> Offset(sideways(min(along + step.width, row.to)), offset.y)
        abs(offset.y - pageEnd) > AtEnd -> Offset(sideways(row.from), stepped(offset.y, pageEnd, step.height))
        walked < rows.lastIndex -> Offset(sideways(rows[walked + 1].from), pageStart)
        else -> null
    }
}

/** Where a spread stands sideways along a row of one page: [from] the row's start [to] its end. */
private data class Row(val from: Float, val to: Float)

/**
 * The row of each page, in reading order: from the page's start edge at the screen's to its end edge at the
 * screen's other side, within the bounds; a page no wider than the screen stays at the first. One page, or
 * pages that are no wider than the screen together, have the one row of the whole spread.
 */
private fun WalkedSpread.rows(direction: Direction): List<Row> {
    val fromLeft = direction.forwardEdge() == Edge.Right
    if (pages.size < 2 || bounds.max.x - bounds.min.x <= AtEnd) {
        return listOf(if (fromLeft) Row(bounds.max.x, bounds.min.x) else Row(bounds.min.x, bounds.max.x))
    }
    return pages.map { page ->
        // Counted from nothing: the left edge of the spread is at 0, not at a zero with a minus sign.
        val atLeft = (0f - page.left).coerceIn(bounds.min.x, bounds.max.x)
        val atRight = (viewport.width - page.right).coerceIn(bounds.min.x, bounds.max.x)
        val start = if (fromLeft) atLeft else atRight
        val wide = page.width > viewport.width + AtEnd
        Row(from = start, to = if (wide) atLeft + atRight - start else start)
    }
}

/** [from] moved by [step] towards [to], and no further than it. */
private fun stepped(from: Float, to: Float, step: Float): Float =
    if (to < from) max(from - step, to) else min(from + step, to)
