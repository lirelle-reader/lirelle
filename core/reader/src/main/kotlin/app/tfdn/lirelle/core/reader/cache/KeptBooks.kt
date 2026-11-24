package app.tfdn.lirelle.core.reader.cache

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The books the device keeps, as the cache sees them: which keys the downloads answer for, and the book each
 * hold of such a key was taken on. A hold is given back where it was taken, even when the book was downloaded,
 * or deleted, in between.
 */
internal class KeptBooks(
    private val files: DownloadedFiles,
    private val holds: BookHolds,
    private val io: CoroutineDispatcher,
) {
    private val books = mutableMapOf<CachedBookKey, BookId>()
    private val held = mutableMapOf<CachedBookKey, MutableList<BookId>>()

    /**
     * Hands the copy kept of this book's file to [take], when there is one; false when there is none. The book
     * is in use from before the look until [take] returns, so that it is not deleted in between.
     */
    suspend fun offer(bookId: BookId, key: CachedBookKey, take: suspend (File) -> Unit): Boolean {
        holds.hold(bookId)
        try {
            val file = withContext(io) { files.text(bookId, key.fileId) }
            synchronized(this) { if (file != null) books[key] = bookId else books.remove(key) }
            file?.let { take(it) }
            return file != null
        } finally {
            holds.release(bookId)
        }
    }

    /** Whether the last opening of [key] was answered by the downloads. */
    @Synchronized
    fun answersFor(key: CachedBookKey): Boolean = key in books

    /** False when the key is the cache's to hold. */
    fun hold(key: CachedBookKey): Boolean {
        val bookId = synchronized(this) { books[key]?.also { held.getOrPut(key, ::mutableListOf) += it } }
        bookId?.let(holds::hold)
        return bookId != null
    }

    /** False when no hold of a book is left for [key]: the hold to give back is the cache's. */
    fun release(key: CachedBookKey): Boolean {
        val bookId = synchronized(this) {
            val taken = held[key] ?: return false
            taken.removeAt(taken.lastIndex).also { if (taken.isEmpty()) held.remove(key) }
        }
        holds.release(bookId)
        return true
    }
}
