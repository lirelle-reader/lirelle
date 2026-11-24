package app.tfdn.lirelle.core.audio.bookmarks

import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange.Add
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange.Delete
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange.Edit
import app.tfdn.lirelle.core.library.model.AudioBookmark

/** A book's list as the server last gave it; [version] counts the answers settled into it. */
internal data class ServerBook(val bookmarks: List<AudioBookmark> = emptyList(), val version: Int = 0)

/** Everything kept: each book's server list and the changes waiting, oldest first. */
internal data class BookmarkState(
    val books: Map<BookId, ServerBook> = emptyMap(),
    val changes: List<BookmarkChange> = emptyList(),
) {
    fun book(bookId: BookId): KeptBook {
        val server = books[bookId] ?: ServerBook()
        return KeptBook(server.bookmarks, changes.filter { it.bookId == bookId }, server.version)
    }

    /**
     * A new edit takes the place of the edits of the bookmark still waiting: each sends the whole title and
     * note, so only the latest matters, and two equal changes never wait side by side.
     */
    fun recorded(change: BookmarkChange): BookmarkState = when (change) {
        is Add -> copy(changes = changes + change)
        is Edit -> copy(changes = changes.filterNot { it is Edit && it.isOf(change.bookId, change.id) } + change)
        is Delete -> deleted(change)
    }

    /** The waiting add of the bookmark, stamped, with the state that keeps it so; null when none waits. */
    fun stamped(bookId: BookId, id: String): Pair<BookmarkState, Add?> {
        val waiting = changes.firstOrNull { it is Add && it.isOf(bookId, id) } as Add? ?: return this to null
        val stamped = waiting.copy(attempted = true)
        return copy(changes = changes.map { if (it === waiting) stamped else it }) to stamped
    }

    fun settled(change: BookmarkChange, answer: Settled): BookmarkState {
        val left = copy(changes = changes - change)
        return when (answer) {
            is Settled.Holds -> left.withBook(change.bookId) { list ->
                list.filterNot { it.id == answer.bookmark.id } + answer.bookmark
            }
            is Settled.Gone -> left.withBook(answer.bookId) { list -> list.filterNot { it.id == answer.id } }
            Settled.Unknown -> left
        }
    }

    fun forgot(bookId: BookId, id: String) = copy(changes = changes.filterNot { it.isOf(bookId, id) })

    fun keptServer(bookId: BookId, list: List<AudioBookmark>, version: Int): BookmarkState {
        val server = books[bookId] ?: ServerBook()
        return if (server.version != version) this else copy(books = books + (bookId to server.copy(bookmarks = list)))
    }

    /** Edits wait no more; an add never attempted goes with them, and then nothing is left to send. */
    private fun deleted(change: Delete): BookmarkState {
        val left = changes.filterNot { it is Edit && it.isOf(change.bookId, change.id) }
        val mine = left.filter { it.isOf(change.bookId, change.id) }
        return when {
            mine.any { it is Add && !it.attempted } -> copy(changes = left - mine.toSet())
            mine.any { it is Delete } -> copy(changes = left)
            else -> copy(changes = left + change)
        }
    }

    private fun withBook(bookId: BookId, transform: (List<AudioBookmark>) -> List<AudioBookmark>): BookmarkState {
        val server = books[bookId] ?: ServerBook()
        val list = transform(server.bookmarks).sortedWith(AudioBookmark.PositionOrder)
        val changed = ServerBook(list, version = server.version + 1)
        return copy(books = books + (bookId to changed))
    }
}

private fun BookmarkChange.isOf(bookId: BookId, id: String) = this.bookId == bookId && this.id == id

/** Waiting adds shown with what the device has, waiting edits over the title and note, waiting deletes hidden. */
internal fun overlay(server: List<AudioBookmark>, changes: List<BookmarkChange>): List<AudioBookmark> {
    val shown = LinkedHashMap<String, AudioBookmark>()
    server.forEach { shown[it.id] = it }
    for (change in changes) {
        when (change) {
            is Add -> if (change.id !in shown) shown[change.id] = change.bookmark
            is Edit -> shown[change.id]?.let { shown[change.id] = it.copy(title = change.title, note = change.note) }
            is Delete -> shown -= change.id
        }
    }
    return shown.values.sortedWith(AudioBookmark.PositionOrder)
}
