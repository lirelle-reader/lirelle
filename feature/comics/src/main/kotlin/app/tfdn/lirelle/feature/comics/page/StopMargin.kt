package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Rect
import app.tfdn.lirelle.core.comics.panels.Zone
import kotlin.math.max
import kotlin.math.min

/*
 * From a zone of a page to its stop: the zone and a margin around it, which the zones beside it limit. Lengths are
 * pixels of the spread laid out at the fit, as for the stops.
 */

/**
 * What is added on each side of a zone, as a share of its page's width: a balloon may stick out of its panel. Less
 * beside another zone: never past the middle of the gutter between them.
 */
internal const val StopMargin = 0.02f

/**
 * The stop of [zone]: where it is in the [room] of its page, with the margin on each side, never past the room.
 * Beside another of the [zones] of the page the margin ends at the middle of what is between the two: what shows
 * around a panel is the gutter, not a sliver of the panel across from it (one at its corner may still show).
 * Null for a zone that is no part of the page: empty, outside it, or not a number.
 */
internal fun widened(zone: Zone, zones: List<Zone>, room: Rect): Rect? {
    val box = placed(zone, room) ?: return null
    val others = zones.mapNotNull { placed(it, room) }
    // Only a zone across from this one is its neighbour: one above or below it is not beside it.
    val beside = others.filter { it.top < box.bottom && it.bottom > box.top }
    val inLine = others.filter { it.left < box.right && it.right > box.left }
    val margin = room.width * StopMargin
    val stop = Rect(
        left = max(-past(-box.left, margin, beside.filter { it.left < box.left }.map { -it.right }), room.left),
        top = max(-past(-box.top, margin, inLine.filter { it.top < box.top }.map { -it.bottom }), room.top),
        right = min(past(box.right, margin, beside.filter { it.right > box.right }.map { it.left }), room.right),
        bottom = min(past(box.bottom, margin, inLine.filter { it.bottom > box.bottom }.map { it.top }), room.bottom),
    )
    return stop.takeIf { it.isFinite && !it.isEmpty }
}

/** Where [zone] is in the [room] of its page, or null when its sides are not numbers or leave it no size. */
private fun placed(zone: Zone, room: Rect): Rect? {
    val sides = listOf(zone.left, zone.top, zone.right, zone.bottom)
    if (!sides.all { it.isFinite() } || zone.left >= zone.right || zone.top >= zone.bottom) return null
    return Rect(
        left = room.left + zone.left * room.width,
        top = room.top + zone.top * room.height,
        right = room.left + zone.right * room.width,
        bottom = room.top + zone.bottom * room.height,
    )
}

/**
 * Where the side of a stop is, past the [edge] of its zone: the [margin] further, but no further than the middle
 * of what separates it from the nearest zone on that side, and at the edge itself when that zone overlaps it.
 * The middle is the same number for the two zones: their stops touch, and never overlap.
 *
 * @param facing the sides that face the edge, of the zones on that side.
 */
private fun past(edge: Float, margin: Float, facing: List<Float>): Float {
    val middle = facing.minOfOrNull { (edge + it) / 2 } ?: return edge + margin
    return max(edge, min(edge + margin, middle))
}
