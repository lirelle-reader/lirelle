package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.reader.store.JsonValue
import java.time.Clock
import java.time.Instant

/** The [MarkOutbox] side of [DataStoreMarks], over the one value it shares with the store. */
internal class StateOutbox(private val state: JsonValue<StoredMarksState>, private val wall: Clock) : MarkOutbox {
    override suspend fun record(change: MarkChange) {
        val now = wall.instant()
        state.change { it.applied(change, now).enqueued(change) }
    }

    override suspend fun enqueue(change: MarkChange) = state.change { it.enqueued(change) }

    override suspend fun pending(): List<MarkChange> = state.read().queue().changes

    override suspend fun replace(old: MarkChange, new: MarkChange) =
        state.change { it.withQueue(it.queue().replaced(old, new)) }

    override suspend fun markSent(change: MarkChange) = state.change { it.withQueue(it.queue().sent(change)) }

    override suspend fun remake(bookId: BookId, id: MarkId, noteTyped: Boolean, restored: Boolean) {
        val now = wall.instant()
        state.change { it.remade(bookId, id, noteTyped, restored, now) }
    }
}

/** See [MarkOutbox.remake]. */
internal fun StoredMarksState.remade(
    bookId: BookId,
    id: MarkId,
    noteTyped: Boolean,
    restored: Boolean,
    now: Instant,
): StoredMarksState {
    val queue = queue()
    val waiting = queue.changes.filter { it.bookId == bookId && it.id == id }
    val item = book(bookId).highlights.firstOrNull { it.id == id.value }
    val typed = noteTyped || waiting.any { it is MarkChange.ChangeHighlight && it.note is NoteEdit.Set }
    val wanted = restored || (typed && !item?.note.isNullOrBlank())
    val cleared = withQueue(queue.copy(changes = queue.changes - waiting.toSet())).withBook(bookId) { book ->
        book.copy(highlights = book.highlights.map { if (it.id == id.value) it.copy(serverId = null) else it })
    }
    val shown = item != null && !item.deleted
    val add = if (shown && wanted) cleared.book(bookId).addOf(id) else null
    return when {
        add != null -> cleared.applied(add, now).enqueued(add)
        shown && !wanted -> cleared.applied(MarkChange.DeleteHighlight(bookId, id), now)
        else -> cleared
    }
}

/** The highlight [id] as an add of the values held; null when it cannot be placed. */
private fun StoredBook.addOf(id: MarkId): MarkChange.AddHighlight? {
    val item = highlights.firstOrNull { it.id == id.value }
    val cfi = item?.cfi
    val file = fileId
    if (item == null || cfi == null || file == null) return null
    val color = HighlightColour.display(item.color)
    val highlight = NewHighlight(cfi, file, item.text, color, item.note, item.chapterTitle)
    return MarkChange.AddHighlight(bookId, id, file, highlight)
}
