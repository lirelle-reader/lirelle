package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.ClosingDeletions
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The books to delete once the reader or the player lets go of them: each goes whole, its formats, what was
 * kept with them, its folder and its record, as soon as nobody holds it.
 *
 * @param unlessHeld runs its block unless the book is in use, and says whether it ran.
 */
internal class FlaggedBooks(
    private val registry: DownloadRegistry,
    private val disposal: Disposal,
    private val unlessHeld: (bookId: BookId, block: () -> Unit) -> Boolean,
) {
    /** Deletes [bookId] when it is to be deleted at close and nobody holds it; nothing otherwise. */
    suspend fun delete(bookId: BookId) = disposal.dropBook(bookId) { it.deleteAtClose && isFree(bookId) }

    /** Every book: those a process that ended while they were open left to delete. */
    suspend fun deleteAll() = registry.books.first().filter { it.deleteAtClose }.forEach { delete(it.bookId) }

    /** Asked as the record goes: a book taken after that no longer finds its copy, and reads from the server. */
    private fun isFree(bookId: BookId): Boolean = unlessHeld(bookId) {}
}

/**
 * The deletion asked from a book that is open: see [ClosingDeletions].
 *
 * @param cancel takes the book's downloads and updates in progress out of the queue.
 */
internal class ClosingDeletion(
    private val registry: DownloadRegistry,
    private val flagged: FlaggedBooks,
    private val cancel: suspend (bookId: BookId) -> Unit,
) : ClosingDeletions {
    /** To its end once started, whatever becomes of who asked: a book is not left flagged and free. */
    override suspend fun deleteAtClose(bookId: BookId) = withContext(NonCancellable) {
        cancel(bookId)
        registry.update(bookId) { it?.copy(deleteAtClose = true) }
        flagged.delete(bookId)
    }

    override suspend fun keepAfterAll(bookId: BookId) = withContext(NonCancellable) {
        registry.update(bookId) { it?.copy(deleteAtClose = false) }
    }
}
