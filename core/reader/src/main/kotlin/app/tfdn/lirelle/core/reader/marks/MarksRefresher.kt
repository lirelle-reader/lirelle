package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.network.ApiResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The shortest time between two refreshes of a book that were not forced. */
private const val ThrottleMillis = 15_000L

/**
 * Reads a book's lists again and merges them through the [MarksStore]. One refresh at a time per
 * book, in the order they were asked: an older answer never lands after a newer one. Not more than
 * once every 15 s per book unless forced; a failure keeps what is stored, and does not count.
 *
 * As the [MarksSent] of the flusher, it reads a book's lists again, forced, once its changes went:
 * a list read while they were on their way cannot leave the old state shown.
 *
 * @param monotonic measures the time between two refreshes.
 */
class MarksRefresher(
    private val store: MarksStore,
    private val repository: MarksRepository,
    private val monotonic: MonotonicClock,
) : MarksSent {
    private val locks = ConcurrentHashMap<BookId, Mutex>()

    /** When each book was last refreshed. */
    private val refreshedAt = ConcurrentHashMap<BookId, Long>()

    suspend fun refresh(bookId: BookId, fileId: FileId, force: Boolean = false) {
        locks.computeIfAbsent(bookId) { Mutex() }.withLock {
            val now = monotonic.nowMillis()
            val last = refreshedAt[bookId]
            if (!force && last != null && now - last < ThrottleMillis) return
            val bookmarks = repository.bookmarks(bookId, fileId) as? ApiResult.Success ?: return
            val highlights = repository.highlights(bookId, fileId) as? ApiResult.Success ?: return
            store.replaceFromServer(bookId, bookmarks.value, highlights.value, fileId)
            refreshedAt[bookId] = now
        }
    }

    /** In the file the book's places were last given in; a book never read nor marked is not shown anywhere. */
    override suspend fun sent(bookId: BookId) {
        val fileId = store.held(bookId).fileId ?: return
        refresh(bookId, fileId, force = true)
    }
}
