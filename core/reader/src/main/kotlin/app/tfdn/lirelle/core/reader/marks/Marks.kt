package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NewHighlight
import kotlinx.coroutines.flow.Flow

/**
 * The bookmarks and highlights of the books, for the reader. Every change shows at once, works
 * offline and is sent when it can; an undo brings back what was deleted, before or after it was
 * sent. Ids are the app's own ([MarkId]).
 */
interface Marks {
    /** The book's marks as shown, in reading order, each time they change. */
    fun of(bookId: BookId): Flow<BookMarks>

    /**
     * Reads both lists again; not more than once every 15 s per book unless [force]. A failure
     * keeps what is stored.
     */
    suspend fun refresh(bookId: BookId, fileId: FileId, force: Boolean = false)

    /** A blank note is none. Returns the new highlight's id. */
    suspend fun addHighlight(bookId: BookId, highlight: NewHighlight): MarkId

    suspend fun recolour(bookId: BookId, id: MarkId, color: String)

    /** A null or blank [note] clears it. */
    suspend fun setNote(bookId: BookId, id: MarkId, note: String?)

    suspend fun deleteHighlight(bookId: BookId, id: MarkId)

    /** Brings back the highlight [deleteHighlight] took, as it was. */
    suspend fun undoDeleteHighlight(bookId: BookId, id: MarkId)

    /** Returns the new bookmark's id. */
    suspend fun addBookmark(bookId: BookId, fileId: FileId, cfi: String, title: String): MarkId

    suspend fun deleteBookmarks(bookId: BookId, ids: List<MarkId>)

    /** Adds the bookmarks [deleteBookmarks] took again, at the same places, with the same titles. */
    suspend fun undoDeleteBookmarks(bookId: BookId, ids: List<MarkId>)
}
