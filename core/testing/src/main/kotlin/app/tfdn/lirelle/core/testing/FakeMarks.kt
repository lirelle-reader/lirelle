package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.HighlightStyle
import app.tfdn.lirelle.core.library.model.MarkOrigin
import app.tfdn.lirelle.core.library.model.NewHighlight
import app.tfdn.lirelle.core.library.model.PositionStatus
import app.tfdn.lirelle.core.reader.marks.BookMarks
import app.tfdn.lirelle.core.reader.marks.Bookmark
import app.tfdn.lirelle.core.reader.marks.CfiOrder
import app.tfdn.lirelle.core.reader.marks.Highlight
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.marks.Marks
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [Marks]: each book's marks in [books], changed by every call, in reading order. Every
 * call is noted in [calls] as `name bookId arguments`, then waits at [gate] when it is set, as a
 * slow disk would, before it changes anything. New items get the ids `local-1`, `local-2`…; a
 * deleted item is kept for its undo.
 */
class FakeMarks : Marks {
    val books = MutableStateFlow<Map<BookId, BookMarks>>(emptyMap())
    val calls = mutableListOf<String>()

    /** The clock of the created items. */
    var now: Instant = Instant.parse("2026-11-14T12:00:00Z")

    /** When set, every write waits for it once noted. */
    var gate: CompletableDeferred<Unit>? = null

    /** When set, every write fails with it once noted. */
    var failure: Exception? = null

    private val deletedHighlights = mutableMapOf<MarkId, Highlight>()
    private val deletedBookmarks = mutableMapOf<MarkId, Bookmark>()
    private var nextId = 1

    override fun of(bookId: BookId): Flow<BookMarks> = books.map { it[bookId] ?: BookMarks() }.distinctUntilChanged()

    override suspend fun refresh(bookId: BookId, fileId: FileId, force: Boolean) {
        calls += "refresh $bookId $fileId force=$force"
    }

    override suspend fun addHighlight(bookId: BookId, highlight: NewHighlight): MarkId {
        calls += "addHighlight $bookId $highlight"
        hold()
        val made = sampleHighlight(
            id = "local-${nextId++}",
            cfi = highlight.cfi,
            text = highlight.text,
            color = highlight.color,
            note = highlight.note?.takeUnless { it.isBlank() },
        ).copy(chapterTitle = highlight.chapterTitle, createdAt = now)
        books.change(bookId) { it.copy(highlights = it.highlights + made) }
        return made.id
    }

    override suspend fun recolour(bookId: BookId, id: MarkId, color: String) {
        calls += "recolour $bookId ${id.value} $color"
        hold()
        books.changeHighlight(bookId, id) { it.copy(color = color) }
    }

    override suspend fun setNote(bookId: BookId, id: MarkId, note: String?) {
        calls += "setNote $bookId ${id.value} $note"
        hold()
        books.changeHighlight(bookId, id) { it.copy(note = note?.takeUnless { text -> text.isBlank() }) }
    }

    override suspend fun deleteHighlight(bookId: BookId, id: MarkId) {
        calls += "deleteHighlight $bookId ${id.value}"
        hold()
        books.value[bookId]?.highlights?.firstOrNull { it.id == id }?.let { deletedHighlights[id] = it }
        books.change(bookId) { marks -> marks.copy(highlights = marks.highlights.filterNot { it.id == id }) }
    }

    override suspend fun undoDeleteHighlight(bookId: BookId, id: MarkId) {
        calls += "undoDeleteHighlight $bookId ${id.value}"
        hold()
        val back = deletedHighlights.remove(id) ?: return
        books.change(bookId) { it.copy(highlights = it.highlights + back) }
    }

    override suspend fun addBookmark(bookId: BookId, fileId: FileId, cfi: String, title: String): MarkId {
        calls += "addBookmark $bookId $fileId $cfi $title"
        hold()
        val made = sampleBookmark(id = "local-${nextId++}", cfi = cfi, title = title, createdAt = now)
        books.change(bookId) { it.copy(bookmarks = it.bookmarks + made) }
        return made.id
    }

    override suspend fun deleteBookmarks(bookId: BookId, ids: List<MarkId>) {
        calls += "deleteBookmarks $bookId ${ids.map { it.value }}"
        hold()
        books.value[bookId]?.bookmarks?.filter { it.id in ids }?.forEach { deletedBookmarks[it.id] = it }
        books.change(bookId) { marks -> marks.copy(bookmarks = marks.bookmarks.filterNot { it.id in ids }) }
    }

    override suspend fun undoDeleteBookmarks(bookId: BookId, ids: List<MarkId>) {
        calls += "undoDeleteBookmarks $bookId ${ids.map { it.value }}"
        hold()
        val back = ids.mapNotNull { deletedBookmarks.remove(it) }
        books.change(bookId) { it.copy(bookmarks = it.bookmarks + back) }
    }
}

/** A write noted waits at the gate, then fails if it is to. */
private suspend fun FakeMarks.hold() {
    gate?.await()
    failure?.let { throw it }
}

/** Replaces a book's marks, as a refresh from the server would. */
fun FakeMarks.set(bookId: BookId, marks: BookMarks) = books.change(bookId) { marks }

private fun MutableStateFlow<Map<BookId, BookMarks>>.changeHighlight(
    bookId: BookId,
    id: MarkId,
    transform: (Highlight) -> Highlight,
) = change(bookId) { marks ->
    marks.copy(highlights = marks.highlights.map { if (it.id == id) transform(it) else it })
}

/** Changes a book's marks, kept in reading order. */
private fun MutableStateFlow<Map<BookId, BookMarks>>.change(bookId: BookId, transform: (BookMarks) -> BookMarks) =
    update { all ->
        val changed = transform(all[bookId] ?: BookMarks())
        val sorted = BookMarks(
            bookmarks = changed.bookmarks.sortedWith(compareBy(CfiOrder) { it.cfi }),
            highlights = changed.highlights.sortedWith(compareBy(CfiOrder) { it.cfi }),
        )
        all + (bookId to sorted)
    }

private val SampleDate: Instant = Instant.parse("2026-11-01T09:00:00Z")

/** A highlight made in the app, with invented text; the rest by `copy`. */
fun sampleHighlight(
    id: String,
    cfi: String?,
    text: String = "A sentence somebody liked.",
    color: String = "#FACC15",
    note: String? = null,
) = Highlight(
    id = MarkId(id),
    serverId = null,
    cfi = cfi,
    text = text,
    color = color,
    style = HighlightStyle.Highlight,
    note = note,
    chapterTitle = null,
    chapterIndex = null,
    origin = MarkOrigin.Web,
    positionStatus = if (cfi == null) PositionStatus.Failed else PositionStatus.Exact,
    createdAt = SampleDate,
)

/** A bookmark made in the app. */
fun sampleBookmark(id: String, cfi: String?, title: String = "A chapter", createdAt: Instant = SampleDate) = Bookmark(
    id = MarkId(id),
    serverId = null,
    cfi = cfi,
    title = title,
    note = null,
    origin = MarkOrigin.Web,
    createdAt = createdAt,
)
