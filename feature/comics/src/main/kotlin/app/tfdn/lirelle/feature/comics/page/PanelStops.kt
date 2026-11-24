package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.settings.Direction
import kotlin.math.ceil
import kotlin.math.min

/*
 * From the zones of a page to the stops of a reader that reads panel by panel: the parts of the spread shown one
 * after the other, each fitted to the screen. Lengths are pixels of the spread laid out at the fit, as everywhere
 * here. Nothing is remembered: the same zones, rooms and screen give the same stops.
 */

/** Whether a zone too large to be read once fitted is walked in several stops; false, every zone is one stop. */
internal const val WalkLargeZones = true

/** A zone is too large when, fitted, it is shown less than this many times larger than its page fitted. */
internal const val LargeZone = 1.5f

/** The stops of a zone that is walked are never shown more than this many times larger than the zone fitted. */
internal const val LargeZoneZoom = 2f

/** A zone is walked only when that shows it at least this many times larger than fitted as one stop. */
internal const val WalkGain = 1.75f

/** The least two neighbouring stops of a zone that is walked share, as a share of their length. */
internal const val StopOverlap = 0.25f

/** How far past a whole number the count of steps of a walk may be before a stop is added: what rounding leaves. */
private const val CountSlack = 0.001f

/** A page of the spread shown, for its stops: its room in the spread's content, and what is known of its zones. */
internal class StopPage(val room: Rect, val zones: PageZones)

/** Where a stop puts the spread: its [zoom], and the [offset] of its top left corner from the screen's. */
internal data class StopPlace(val zoom: Float, val offset: Offset)

/**
 * The stops of a spread read panel by panel, in the order they are read: each is the part of the spread's content
 * to show. Empty when no page of the spread is cut: the spread has only its whole view. When a page is cut and the
 * one beside it is not, the page not cut is one stop, its room, in its place: without it nothing would show it.
 *
 * Null while the zones of a page are not known: the reader is to wait. It must not wait for ever: a page whose
 * picture never comes stays unknown, and the caller is the one that gives up and shows the spread whole.
 *
 * @param pages the pages of the spread in reading order.
 */
internal fun spreadStops(pages: List<StopPage>, viewport: Size, direction: Direction): List<Rect>? = when {
    pages.any { it.zones == PageZones.Unknown } -> null
    pages.none { it.zones is PageZones.Cut } -> emptyList()
    else -> pages.flatMap { page ->
        val cut = page.zones as? PageZones.Cut
        cut?.let { pageStops(it.zones, page.room, viewport, direction) } ?: listOfNotNull(page.room.takeIf(::shows))
    }
}

/**
 * The stops of one page: its zones, each widened by the margin, up to the middle of the gutter where another zone
 * is nearer than that, and a zone too large walked in several when that shows it much larger. A zone that is
 * empty, outside the page or not a number gives no stop.
 *
 * @param room where the page is in the spread's content.
 * @param walkLarge whether a zone too large is walked: the rule on trial, see [WalkLargeZones].
 */
internal fun pageStops(
    zones: List<Zone>,
    room: Rect,
    viewport: Size,
    direction: Direction,
    walkLarge: Boolean = WalkLargeZones,
): List<Rect> = zones.flatMap { zone ->
    val stop = widened(zone, zones, room)
    val walk = if (walkLarge && stop != null) walkOf(stop, room, viewport) else null
    when {
        stop == null -> emptyList()
        walk == null -> listOf(stop)
        else -> walked(stop, walk, viewport, direction)
    }
}

/**
 * The zoom and the place of the spread that show [stop] whole, as large as the [viewport] lets it be, in its
 * middle. The zoom may be under the fit's: a spread fitted to the width can be higher than the screen, and so can
 * a stop of it, which is shown smaller rather than cropped. A stop or a screen with no size leaves the spread at
 * the fit, where it is laid out.
 */
internal fun stopPlace(stop: Rect, viewport: Size): StopPlace {
    if (!shows(stop) || !hasRoom(viewport)) return StopPlace(MinZoom, Offset.Zero)
    val zoom = min(fittedZoom(stop.size, viewport), MaxZoom)
    // Not kept inside what a finger could reach: what shows beside the stop is hidden.
    return StopPlace(zoom, viewport.center - stop.center * zoom)
}

/** Whether [rect] is a part of the spread that can be shown: it has a size, and its sides are numbers. */
private fun shows(rect: Rect): Boolean = rect.isFinite && !rect.isEmpty

/** Whether the [viewport] is a screen something can be fitted to: it has a size, and its sides are numbers. */
private fun hasRoom(viewport: Size): Boolean =
    viewport.width.isFinite() && viewport.height.isFinite() && viewport.width > 0f && viewport.height > 0f

/** The zoom at which something of that [size] is as large as the [viewport] lets it be, whole. */
private fun fittedZoom(size: Size, viewport: Size): Float =
    min(viewport.width / size.width, viewport.height / size.height)

/** How a zone is walked: along its width when it is [wide], along its height otherwise, its stops at [zoom]. */
private class Walk(val wide: Boolean, val zoom: Float)

/**
 * How [stop], a zone with its margin, is walked, or null when it is one stop. It is walked on two conditions.
 * It is too large to be read once fitted: it would be shown hardly larger than its page, whose [room] is fitted
 * the same way. And walking it shows it much larger, [WalkGain] times at least: a zone cut in parts for less
 * is a bad cut, and no cut is better. Nothing is walked on a screen with no size.
 *
 * The zone is wide when its width is what keeps it from being shown larger on this screen, tall when its height
 * is. Its stops are shown at the zoom that fits its other side to the screen, capped at [LargeZoneZoom] times the
 * zoom of the zone fitted.
 */
private fun walkOf(stop: Rect, room: Rect, viewport: Size): Walk? {
    if (!hasRoom(viewport)) return null
    val fitted = fittedZoom(stop.size, viewport)
    val wide = viewport.width / stop.width <= viewport.height / stop.height
    val shortFit = if (wide) viewport.height / stop.height else viewport.width / stop.width
    val zoom = min(shortFit, fitted * LargeZoneZoom)
    val large = fitted < LargeZone * fittedZoom(room.size, viewport)
    return if (large && zoom / fitted >= WalkGain) Walk(wide, zoom) else null
}

/**
 * The stops a zone is walked in. They are as high as a wide zone (as wide as a tall one), as long as the screen
 * shows at the zoom of the [walk], and spread evenly from one end to the other: from the side reading starts from
 * for a wide zone, from the top for a tall one. The zone itself when one stop is enough.
 *
 * @param stop the zone with its margin.
 */
private fun walked(stop: Rect, walk: Walk, viewport: Size, direction: Direction): List<Rect> {
    val whole = if (walk.wide) stop.width else stop.height
    val length = min((if (walk.wide) viewport.width else viewport.height) / walk.zoom, whole)
    val starts = stopStarts(whole, length)
    return when {
        starts.size == 1 -> listOf(stop)
        !walk.wide -> starts.map { Rect(stop.left, stop.top + it, stop.right, stop.top + it + length) }
        direction == Direction.LeftToRight ->
            starts.map { Rect(stop.left + it, stop.top, stop.left + it + length, stop.bottom) }
        else -> starts.map { Rect(stop.right - it - length, stop.top, stop.right - it, stop.bottom) }
    }
}

/**
 * Where stops of that [length] begin along a zone that is [whole] long, from its start: the first at its start,
 * the last ending at its end, evenly between, as few as leave neighbours sharing [StopOverlap] of their length.
 * What rounding adds to the count is not a stop more: no two stops are a hair apart. They are three at most: a
 * walk is capped at [LargeZoneZoom], so that a stop is never under half the zone.
 */
private fun stopStarts(whole: Float, length: Float): List<Float> {
    val steps = (whole - length) / (length * (1 - StopOverlap))
    val count = if (steps.isFinite()) 1 + ceil(steps - CountSlack).toInt() else 1
    if (count <= 1) return listOf(0f)
    val step = (whole - length) / (count - 1)
    return List(count) { it * step }
}
