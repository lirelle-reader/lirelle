package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.audio.timeline.BookTimeline
import app.tfdn.lirelle.core.library.model.PlaybackState
import java.time.Instant
import kotlin.math.abs

/** Two places in a book this close are the same place. */
private const val SamePlaceMs = 5_000L

/** How many of our own last writes are recognised when the server gives them back. */
private const val OwnWritesRemembered = 16

/**
 * Decides whether the listening state found on the server is worth offering to the user: it must
 * be more recent than what this player last read from the server, ignored or wrote, and more than
 * 5 s away from where the player is on the whole book's timeline. The server does not say who
 * wrote a state, so our own last writes are also recognised by their place, whatever their date.
 *
 * Not thread-safe: call it from one thread.
 */
class NewerListeningRule {
    private var stamp: Instant? = null
    private val ownPlaces = ArrayDeque<Pair<String, Long>>()

    /** [server] was read and followed, or need not be offered again: at opening, after "Go there". */
    fun onRead(server: PlaybackState) {
        server.capturedAt?.let(::stampAt)
    }

    /** The user dismissed [server]: only a still newer one will be offered. */
    fun ignore(server: PlaybackState) = onRead(server)

    /** The player wrote [position], which is on its way to the server. */
    fun onOwnWrite(position: LocalPlayback) {
        stampAt(position.at)
        ownPlaces.addLast(position.assetId to position.positionMs)
        if (ownPlaces.size > OwnWritesRemembered) ownPlaces.removeFirst()
    }

    /**
     * Whether [server] is to be offered to a player at [currentBookTimeMs] (null: at the very
     * start) of the book [timeline] describes. A state in a file the timeline does not have is not.
     * A state without a date cannot be older than our last write: it is someone else's, unless it is
     * at one of our own places, as the flusher judges it.
     */
    fun isOffered(server: PlaybackState, timeline: BookTimeline, currentBookTimeMs: Long?): Boolean {
        val serverTime = timeline.bookTimeOf(server.assetId, server.positionMs) ?: return false
        val newer = server.capturedAt?.let { at -> stamp?.let(at::isAfter) ?: true } ?: true
        val elsewhere = abs(serverTime - (currentBookTimeMs ?: 0)) > SamePlaceMs
        val own = (server.assetId to server.positionMs) in ownPlaces
        return newer && elsewhere && !own
    }

    /**
     * Whether [server] is more than 5 s away from a player at [currentBookTimeMs] (null: at the very
     * start), whatever its date: for a state the flusher already found written elsewhere. False for a
     * state in a file the timeline does not have.
     */
    fun isElsewhere(server: PlaybackState, timeline: BookTimeline, currentBookTimeMs: Long?): Boolean {
        val serverTime = timeline.bookTimeOf(server.assetId, server.positionMs) ?: return false
        return abs(serverTime - (currentBookTimeMs ?: 0)) > SamePlaceMs
    }

    private fun stampAt(at: Instant) {
        if (stamp?.isBefore(at) != false) stamp = at
    }
}
