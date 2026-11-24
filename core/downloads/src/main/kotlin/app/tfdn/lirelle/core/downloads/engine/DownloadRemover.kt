package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.DeleteResult
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.DownloadRemovals
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.library.offline.BookHolds
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Deletes what the device keeps. A deleted format is only marked at first, which hides it; its files go once
 * the deletion can no longer be undone. It also knows which books are in use, refuses those, and tells who
 * waits for a book to close.
 *
 * @param scope where a deletion waits for its undo window to close.
 * @param undoWindow longer than a snackbar stays, with the accessibility settings that keep it longer.
 */
class DownloadRemover internal constructor(
    private val registry: DownloadRegistry,
    private val disposal: Disposal,
    private val scope: CoroutineScope,
    private val undoWindow: Duration = 10.seconds,
) : DownloadRemovals, BookHolds {
    private val lock = Any()
    private val held = mutableMapOf<BookId, Int>()
    private val closing = mutableMapOf<BookId, Job>()
    private val released = CopyOnWriteArrayList<suspend (BookId) -> Unit>()

    /** Runs a block while nothing is fetched nor planned; until the runner says how, there is nothing to stop. */
    @Volatile
    private var halted: suspend (block: suspend () -> Unit) -> Unit = { it() }

    override fun hold(bookId: BookId) = synchronized(lock) { held[bookId] = (held[bookId] ?: 0) + 1 }

    override fun release(bookId: BookId) {
        val last = synchronized(lock) {
            val left = (held[bookId] ?: 0) - 1
            if (left > 0) held[bookId] = left else held.remove(bookId)
            left == 0
        }
        if (last && released.isNotEmpty()) scope.launch { released.forEach { it(bookId) } }
    }

    /**
     * Runs [block] unless the book is in use, and says whether it ran: nobody takes the book while it runs.
     * For who moves or deletes files a reader or a player may have open; [block] must be short.
     */
    internal fun unlessHeld(bookId: BookId, block: () -> Unit): Boolean = synchronized(lock) {
        val free = bookId !in held
        if (free) block()
        free
    }

    /** [listener] is told, in the remover's scope, each time the last holder of a book lets go of it. */
    internal fun whenReleased(listener: suspend (bookId: BookId) -> Unit) {
        released += listener
    }

    override suspend fun delete(bookId: BookId, formats: Set<DownloadFormat>): DeleteResult {
        if (synchronized(lock) { bookId in held }) return DeleteResult.InUse
        mark(bookId) { it in formats }
        val closes = scope.launch {
            delay(undoWindow)
            disposal.drop(bookId) { it.removing }
        }
        synchronized(lock) { closing.put(bookId, closes) }?.cancel()
        return DeleteResult.Deleted
    }

    override suspend fun undoDelete(bookId: BookId) {
        synchronized(lock) { closing.remove(bookId) }?.cancel()
        mark(bookId, removing = false) { true }
    }

    /** [halt] runs its block once what is being fetched or planned has stopped, nothing starting meanwhile. */
    internal fun haltWith(halt: suspend (block: suspend () -> Unit) -> Unit) {
        halted = halt
    }

    /** What the runner was doing is stopped first: nothing is written back into a deleted book's folder. */
    override suspend fun deleteAll() = halted {
        synchronized(lock) { closing.values.toList().also { closing.clear() } }.forEach { it.cancel() }
        disposal.everything()
    }

    /** The deletions a process that ended left unfinished: nobody can undo them any more. */
    internal suspend fun finishPending() = registry.books.first().forEach { book ->
        if (book.formats.any { it.removing }) disposal.drop(book.bookId) { it.removing }
    }

    private suspend fun mark(bookId: BookId, removing: Boolean = true, which: (DownloadFormat) -> Boolean) =
        registry.update(bookId) { book ->
            book?.copy(formats = book.formats.map { if (which(it.format)) it.copy(removing = removing) else it })
        }
}
