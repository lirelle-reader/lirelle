package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.library.model.ReadingPosition
import java.time.Instant
import kotlin.math.abs

/** Two percentages this close are the same place, when a precise place is missing on one side. */
private const val SamePlacePercent = 0.5

/** Where a position is, whoever holds it. */
private data class Place(val cfi: String?, val page: Int?, val percent: Double)

private fun ReadingPosition.place() = Place(cfi, page, percent)

private fun LocalPosition?.place() = Place(this?.cfi, this?.page, this?.percent ?: 0.0)

/** The very end of a file, in percent. */
private const val End = 100.0

/**
 * Two positions at the very end of the file are at the same place, whatever their precise places: an album
 * ending on a spread of two pages is at the first one for the reader, at the last one for a server that keeps it.
 * Otherwise two positions that both have a page are at the same place when it is the same page: the pages of a
 * long album are closer than the tolerance of the percentages. Otherwise the precise places of a book decide, and
 * the percentages when one is missing.
 */
private fun samePlace(one: Place, other: Place): Boolean = when {
    one.percent >= End && other.percent >= End -> true
    one.page != null && other.page != null -> one.page == other.page
    one.cfi != null && other.cfi != null -> one.cfi == other.cfi
    else -> abs(one.percent - other.percent) <= SamePlacePercent
}

/** How many of our own last writes are recognised when the server gives them back. */
internal const val OwnWritesRemembered = 8

/**
 * Decides whether a position found on the server is worth offering to the user: it must be more
 * recent than what this reader last read from the server or wrote, and somewhere else than where
 * the user is. The server does not say who wrote a position, so:
 *
 * - by time, a position is newer than what we knew when its date is after the last one read from
 *   the server, the last one ignored, and our own last write;
 * - by place, our own last writes are recognised whatever their date, since the server dates
 *   them with its own clock.
 *
 * Not thread-safe: call it from one thread.
 */
class NewerPositionRule {
    private var stamp: Instant? = null
    private val ownPlaces = ArrayDeque<Place>()

    /** [server] was read and followed, or need not be offered again: at opening, after "Go there". */
    fun onRead(server: ReadingPosition) {
        server.updatedAt?.let(::stampAt)
    }

    /** The user dismissed [server]: only a still newer one will be offered. */
    fun ignore(server: ReadingPosition) = onRead(server)

    /** This reader moved to [position], which is on its way to the server. */
    fun onOwnWrite(position: LocalPosition) {
        stampAt(position.at)
        ownPlaces.addLast(position.place())
        if (ownPlaces.size > OwnWritesRemembered) ownPlaces.removeFirst()
    }

    /** Whether [server] is to be offered to a user who is at [current] (null: at the very start). */
    fun isOffered(server: ReadingPosition, current: LocalPosition?): Boolean {
        val at = server.updatedAt ?: return false
        val newer = stamp?.let(at::isAfter) ?: true
        val place = server.place()
        val elsewhere = !samePlace(place, current.place())
        return newer && elsewhere && ownPlaces.none { samePlace(place, it) }
    }

    private fun stampAt(at: Instant) {
        if (stamp?.isBefore(at) != false) stamp = at
    }
}
