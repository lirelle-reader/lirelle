package app.tfdn.lirelle.core.audio.bookmarks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.core.library.model.BookmarkCreateResult
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

sealed interface AddOutcome {
    data class Added(val bookmark: AudioBookmark) : AddOutcome

    /** A bookmark was already at that second: this one, from the list, and none was added. */
    data class Existing(val bookmark: AudioBookmark) : AddOutcome

    /** Another bookmark is at that second, but it could not be read; none was added at [positionMs]. */
    data class Taken(val positionMs: Long) : AddOutcome
}

/** The bookmarks of the audiobooks, kept on the server, changed on the device even without a network. */
interface AudioBookmarks {
    /** The server's list with what waits on the device laid over it, in position order. */
    fun of(bookId: BookId): Flow<List<AudioBookmark>>

    suspend fun refresh(bookId: BookId)

    /** @throws IllegalArgumentException for a blank [title]: nothing is shown, queued or sent. */
    suspend fun add(bookId: BookId, positionMs: Long, chapterId: String?, title: String): AddOutcome

    /** A blank [title] keeps the one shown; a null or blank [note] clears it. A bookmark not shown is left alone. */
    suspend fun edit(bookId: BookId, id: String, title: String, note: String?)

    /** Called once the "Undo" snackbar has gone. */
    suspend fun delete(bookId: BookId, id: String)
}

/**
 * [AudioBookmarks] over the [BookmarkQueue]: each change is shown and queued in one write, then the
 * [BookmarkFlusher] sends it.
 *
 * An add is sent at once when nothing waits before it, so that a bookmark already at that second (the
 * server keeps one per second) is answered as [AddOutcome.Existing]: the one shown at that second, or the
 * one the server's list holds there once read again, or [AddOutcome.Taken] when that list cannot be read.
 * Offline, or behind other changes, it is shown at once as [AddOutcome.Added] and sent later. Each add has a
 * new id, whatever the answer. A blank title is refused: the screens never offer one.
 */
class DefaultAudioBookmarks(
    private val queue: BookmarkQueue,
    private val flusher: BookmarkFlusher,
    private val clock: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AudioBookmarks {

    override fun of(bookId: BookId): Flow<List<AudioBookmark>> =
        queue.book(bookId).map { it.shown }.distinctUntilChanged()

    override suspend fun refresh(bookId: BookId) = flusher.refresh(bookId)

    override suspend fun add(bookId: BookId, positionMs: Long, chapterId: String?, title: String): AddOutcome {
        val id = newId()
        require(title.isNotBlank()) { "A bookmark needs a title" }
        val at = positionMs.coerceAtLeast(0)
        shownAt(bookId, at)?.let { return AddOutcome.Existing(it) }
        val bookmark = AudioBookmark(id, bookId, at, chapterId, title, null, clock.instant(), null)
        val add = BookmarkChange.Add(bookmark)
        queue.record(add)
        return when (val answer = flusher.sendNow(add)) {
            is BookmarkCreateResult.Created -> AddOutcome.Added(answer.bookmark)
            // The list was read again: the one at that second shows, unless that reading failed.
            BookmarkCreateResult.SameSecond -> shownAt(bookId, at)?.let { AddOutcome.Existing(it) }
                ?: AddOutcome.Taken(at)
            null -> AddOutcome.Added(bookmark)
        }
    }

    override suspend fun edit(bookId: BookId, id: String, title: String, note: String?) {
        val kept = title.takeUnless { it.isBlank() }
            ?: of(bookId).first().firstOrNull { it.id == id }?.title
            ?: return
        queue.record(BookmarkChange.Edit(bookId, id, kept, note?.takeUnless { it.isBlank() }))
        flusher.kick()
    }

    override suspend fun delete(bookId: BookId, id: String) {
        queue.record(BookmarkChange.Delete(bookId, id))
        flusher.kick()
    }

    private suspend fun shownAt(bookId: BookId, positionMs: Long): AudioBookmark? {
        val second = AudioBookmark.secondOf(positionMs)
        return of(bookId).first().firstOrNull { AudioBookmark.secondOf(it.positionMs) == second }
    }
}
