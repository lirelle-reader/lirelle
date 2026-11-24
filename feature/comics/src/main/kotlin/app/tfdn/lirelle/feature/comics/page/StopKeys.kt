package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.settings.Direction
import kotlin.math.abs

/*
 * What each stop of a spread shows, whatever the screen: the stops are counted again when the screen changes, and
 * the stop the reader is at is found again by what it shows, not by its number.
 */

/**
 * What a stop shows of its spread, whatever the screen: the zone of that number ([zone], from 0) on the page of
 * that number ([page], from 0, in reading order), or that page whole when [zone] is null; and, for a zone walked in
 * several stops, where along it the stop is ([along]: 0 its first stop, 1 its last; 0 for a zone that is one stop).
 * The stops are counted again when the screen changes, and a zone walked on one screen may be one stop on another:
 * this is how the stop the reader is at is found again.
 */
internal data class StopKey(val page: Int, val zone: Int?, val along: Float)

/** A stop of a spread, the part of its content to show, with what it shows ([key]). */
internal class KeyedStop(val rect: Rect, val key: StopKey)

/** The stops of a spread as [spreadStops] makes them, each with what it shows. */
internal fun keyedStops(pages: List<StopPage>, viewport: Size, direction: Direction): List<KeyedStop>? = when {
    pages.any { it.zones == PageZones.Unknown } -> null
    pages.none { it.zones is PageZones.Cut } -> emptyList()
    else -> pages.flatMapIndexed { place, page ->
        val cut = page.zones as? PageZones.Cut
        cut?.let { zoneStops(it.zones, page.room, viewport, direction, WalkLargeZones) }
            ?.map { KeyedStop(it.rect, StopKey(place, it.zone, it.along)) }
            ?: listOfNotNull(page.room.takeIf(::shows)?.let { KeyedStop(it, StopKey(place, null, 0f)) })
    }
}

/**
 * The number, among [keys], of the stop that shows what [key] showed: the same zone, at the place along it
 * nearest to where [key] was, the first of two as near. Null when no stop shows that zone any more.
 */
internal fun sameStop(key: StopKey, keys: List<StopKey>): Int? = keys.withIndex()
    .filter { it.value.page == key.page && it.value.zone == key.zone }
    .minByOrNull { abs(it.value.along - key.along) }
    ?.index

/**
 * What the stops of the spread shown showed when they were last counted. The screen changes them (a zone walked
 * in three stops in portrait is one in landscape): the stop the reader is at is then another number, found by
 * what it shows.
 */
internal class CountedStops {
    private var spread: Int? = null
    private var keys: List<StopKey>? = null

    /**
     * The stops of the spread [index] are now [keys] (null while not known). Returns the number among them of
     * the stop that showed what the one of number [at] showed, when that number changed; null otherwise: the same
     * number, no stop, another spread, or a zone no stop shows any more.
     */
    fun recount(index: Int, keys: List<StopKey>?, at: Int?): Int? {
        val before = this.keys.takeIf { spread == index }
        spread = index
        if (keys == null) {
            if (before == null) this.keys = null
            return null
        }
        this.keys = keys
        val key = at?.let { before?.getOrNull(it) } ?: return null
        return sameStop(key, keys)?.takeIf { it != at }
    }
}
