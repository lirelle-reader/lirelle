package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.audio.timeline.BookTimeline
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.ReadingWrites

/**
 * What the [PlaybackFlusher] makes of the server's state after a conflict. Only touched by the one flush
 * running.
 *
 * - **Ours**: at one of the places this device last wrote ([PlaybackStore.writtenPlaces]), whatever its
 *   date says (rewritten, missing, clocks apart); or captured no later than this device's last write, the
 *   position itself when none ever was. A state without a date at another place is not ours. Or what the
 *   server made of this device's own reading that waited, when that reading is older than the position
 *   ([ReadingWrites]): the server moves the listening to each text or narration position it stores.
 * - **The same place**: within 5 s of the position on the book's timeline ([NewerListeningRule]'s notion),
 *   read from the manifest the position was made with: written elsewhere, but where the device is anyway.
 */
internal class StateJudge(
    private val store: PlaybackStore,
    private val repository: AudioRepository,
    private val own: OwnWrites,
    private val reading: () -> ReadingWrites? = { null },
) {
    private val rule = NewerListeningRule()

    /** By book, the timeline of the content version last read. */
    private val timelines = mutableMapOf<BookId, Pair<String, BookTimeline>>()

    suspend fun isOurs(position: LocalPlayback, state: PlaybackState): Boolean {
        val id = position.bookId
        if (WrittenPlace(state.assetId, state.positionMs) in store.writtenPlaces(id)) return true
        val at = state.capturedAt ?: return false
        val ourLast = listOfNotNull(own.lastWritten(id), store.lastWritten(id)).maxOrNull() ?: position.at
        return !at.isAfter(ourLast) || reading()?.movedListeningBefore(at, position.at) == true
    }

    suspend fun isSamePlace(position: LocalPlayback, state: PlaybackState): Boolean {
        val timeline = timelineOf(position) ?: return false
        return !rule.isElsewhere(state, timeline, position.bookTimeMs)
    }

    /** The timeline [position] was made on; null when its manifest cannot be read, or changed since. */
    private suspend fun timelineOf(position: LocalPlayback): BookTimeline? {
        timelines[position.bookId]?.takeIf { it.first == position.contentVersion }?.let { return it.second }
        val manifest = (repository.manifest(position.bookId) as? ApiResult.Success)?.value ?: return null
        if (manifest.contentVersion != position.contentVersion) return null
        return BookTimeline(manifest).also { timelines[position.bookId] = manifest.contentVersion to it }
    }
}
