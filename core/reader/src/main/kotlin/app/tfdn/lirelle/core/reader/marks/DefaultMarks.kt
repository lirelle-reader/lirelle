package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NewHighlight
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * [Marks] over the store and the outbox: each change is shown and queued in one write
 * ([MarkOutbox.record]), then the flusher is kicked.
 *
 * Undo relies on what they keep: a deleted highlight stays in the store, hidden, so that a restore
 * shows it as it was; the outbox cancels a delete not yet sent, and sends a restore otherwise. A
 * bookmark deleted is added again at the same place with the same title: the server gives back
 * the one it had, or makes it again.
 */
class DefaultMarks(
    private val outbox: MarkOutbox,
    private val store: MarksStore,
    private val refresher: MarksRefresher,
    private val flusher: MarksFlusher,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : Marks {
    override fun of(bookId: BookId): Flow<BookMarks> = store.book(bookId)

    override suspend fun refresh(bookId: BookId, fileId: FileId, force: Boolean) =
        refresher.refresh(bookId, fileId, force)

    override suspend fun addHighlight(bookId: BookId, highlight: NewHighlight): MarkId {
        val id = MarkId(newId())
        val note = highlight.note?.takeUnless { it.isBlank() }
        val add = MarkChange.AddHighlight(bookId, id, highlight.fileId, highlight.copy(note = note))
        outbox.recordAndSend(flusher, add)
        return id
    }

    override suspend fun recolour(bookId: BookId, id: MarkId, color: String) =
        outbox.recordAndSend(flusher, MarkChange.ChangeHighlight(bookId, id, color = color, note = null))

    override suspend fun setNote(bookId: BookId, id: MarkId, note: String?) {
        val edit = note?.takeUnless { it.isBlank() }?.let { NoteEdit.Set(it) } ?: NoteEdit.Clear
        outbox.recordAndSend(flusher, MarkChange.ChangeHighlight(bookId, id, color = null, note = edit))
    }

    override suspend fun deleteHighlight(bookId: BookId, id: MarkId) =
        outbox.recordAndSend(flusher, MarkChange.DeleteHighlight(bookId, id))

    override suspend fun undoDeleteHighlight(bookId: BookId, id: MarkId) =
        outbox.recordAndSend(flusher, MarkChange.RestoreHighlight(bookId, id))

    override suspend fun addBookmark(bookId: BookId, fileId: FileId, cfi: String, title: String): MarkId {
        val id = MarkId(newId())
        outbox.recordAndSend(flusher, MarkChange.AddBookmark(bookId, id, fileId, cfi, title))
        return id
    }

    override suspend fun deleteBookmarks(bookId: BookId, ids: List<MarkId>) =
        outbox.recordAndSend(flusher, ids.map { MarkChange.DeleteBookmark(bookId, it) })

    /** Those no longer held, or that cannot be placed, are left out. */
    override suspend fun undoDeleteBookmarks(bookId: BookId, ids: List<MarkId>) {
        val held = store.held(bookId)
        val fileId = held.fileId ?: return
        val again = ids.mapNotNull { id ->
            val bookmark = held.bookmarks.firstOrNull { it.id == id }
            bookmark?.cfi?.let { MarkChange.AddBookmark(bookId, id, fileId, it, bookmark.title) }
        }
        outbox.recordAndSend(flusher, again)
    }
}

private suspend fun MarkOutbox.recordAndSend(flusher: MarksFlusher, change: MarkChange) =
    recordAndSend(flusher, listOf(change))

/** Shows and queues each of [changes], then has [flusher] send them. */
private suspend fun MarkOutbox.recordAndSend(flusher: MarksFlusher, changes: List<MarkChange>) {
    changes.forEach { record(it) }
    flusher.kick()
}
