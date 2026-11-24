package app.tfdn.lirelle.core.audio.bookmarks

import androidx.datastore.core.DataStore
import app.tfdn.lirelle.core.ids.BookId
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.audio.store.JsonValue
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.AudioBookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A change of an audiobook bookmark made on the device, waiting to reach the server. */
sealed interface BookmarkChange {
    val bookId: BookId
    val id: String

    /** [attempted]: it may have left already (it was stamped before its first send); a delete no longer cancels it. */
    data class Add(val bookmark: AudioBookmark, val attempted: Boolean = false) : BookmarkChange {
        override val bookId: BookId get() = bookmark.bookId
        override val id: String get() = bookmark.id
    }

    /** A null [note] clears it. */
    data class Edit(override val bookId: BookId, override val id: String, val title: String, val note: String?) :
        BookmarkChange

    data class Delete(override val bookId: BookId, override val id: String) : BookmarkChange
}

/** What the server's answer to a change says of the bookmark. */
sealed interface Settled {
    /** The server holds it so: created or edited. */
    data class Holds(val bookmark: AudioBookmark) : Settled

    /** The server no longer has it: deleted, here or elsewhere. */
    data class Gone(val bookId: BookId, val id: String) : Settled

    /** Nothing is known: the change was given up. */
    data object Unknown : Settled
}

/**
 * What is kept of one book: the server's list as last read or answered, and the changes waiting.
 *
 * @property version counts the answers settled into [server]: a list read before one of them is not kept.
 */
data class KeptBook(
    val server: List<AudioBookmark> = emptyList(),
    val changes: List<BookmarkChange> = emptyList(),
    val version: Int = 0,
) {
    /** The server's list with the waiting changes laid over it, in position order. */
    val shown: List<AudioBookmark> get() = overlay(server, changes)
}

/**
 * The audiobook bookmarks kept on the device: each book's list as the server last gave it, and the changes
 * still to reach the server, oldest first, kept across restarts. Safe to use from several coroutines at once.
 */
interface BookmarkQueue {
    /** What is kept of the book, now and whenever it changes. */
    fun book(bookId: BookId): Flow<KeptBook>

    /**
     * Queues [change] after the others. An edit takes the place of the bookmark's edits waiting. A delete
     * takes the bookmark's edits out of the queue; when its add has never been attempted, it takes the add
     * out too and is not queued: nothing is sent.
     */
    suspend fun record(change: BookmarkChange)

    /** What waits, oldest first. */
    suspend fun pending(): List<BookmarkChange>

    /** Marks the add of [add]'s bookmark as attempted, and gives it back; null when it no longer waits. */
    suspend fun stamp(add: BookmarkChange.Add): BookmarkChange.Add?

    /** Takes [change] out of the queue if it still waits, and lays [answer] into the book's list. */
    suspend fun settle(change: BookmarkChange, answer: Settled)

    /** Takes every change of the bookmark out of the queue. */
    suspend fun forget(bookId: BookId, id: String)

    /** Keeps [list] as the book's server list, unless an answer was settled into it since [version]. */
    suspend fun keepServer(bookId: BookId, list: List<AudioBookmark>, version: Int)
}

/**
 * This queue for a profile whose source can do [capabilities] (spec 2c): without audio bookmarks, no change is
 * shown nor queued; the rest goes through as ever.
 */
fun BookmarkQueue.forSource(capabilities: SourceCapabilities): BookmarkQueue =
    if (capabilities.audioBookmarks) this else WithoutAudioBookmarks(this)

private class WithoutAudioBookmarks(queue: BookmarkQueue) : BookmarkQueue by queue {
    override suspend fun record(change: BookmarkChange) = Unit
}

class DataStoreBookmarkQueue(dataStore: DataStore<Preferences>) : BookmarkQueue {
    private val value =
        JsonValue(dataStore, "audio_bookmarks", StoredBookmarkState.serializer(), StoredBookmarkState())

    override fun book(bookId: BookId): Flow<KeptBook> =
        value.flow.map { stored -> stored.state().book(bookId) }.distinctUntilChanged()

    override suspend fun record(change: BookmarkChange) = update { it.recorded(change) }

    override suspend fun pending(): List<BookmarkChange> = value.read().state().changes

    override suspend fun stamp(add: BookmarkChange.Add): BookmarkChange.Add? {
        var stamped: BookmarkChange.Add? = null
        update { state -> state.stamped(add.bookId, add.id).also { stamped = it.second }.first }
        return stamped
    }

    override suspend fun settle(change: BookmarkChange, answer: Settled) = update { it.settled(change, answer) }

    override suspend fun forget(bookId: BookId, id: String) = update { it.forgot(bookId, id) }

    override suspend fun keepServer(bookId: BookId, list: List<AudioBookmark>, version: Int) =
        update { it.keptServer(bookId, list, version) }

    private suspend fun update(transform: (BookmarkState) -> BookmarkState) =
        value.change { stored -> transform(stored.state()).stored() }
}
