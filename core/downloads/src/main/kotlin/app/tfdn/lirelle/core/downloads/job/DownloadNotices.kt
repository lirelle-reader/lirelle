package app.tfdn.lirelle.core.downloads.job

import android.app.Notification
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.ids.BookId
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * What the downloads have to say in notifications, as the queue changes.
 *
 * @param title the title of a book, as its kept page has it; null when it is not kept yet.
 */
class DownloadNotices internal constructor(
    private val queue: Flow<QueueState>,
    private val title: suspend (bookId: BookId) -> String?,
    private val notifications: DownloadNotifications,
) {
    @Inject
    constructor(downloads: Downloads, kept: KeptBookData, notifications: DownloadNotifications) :
        this(downloads.queue, { kept.detail(it)?.title }, notifications)

    /** What the job shows: the book being fetched, or what the queue waits for. A title is read once per book. */
    val progress: Flow<Notification> = flow {
        var known: Pair<BookId, String>? = null
        queue.collect { state ->
            val bookId = state.active?.bookId
            if (bookId != null && known?.first != bookId) known = title(bookId)?.let { bookId to it }
            emit(notifications.of(state, known?.second?.takeIf { known?.first == bookId }))
        }
    }
}
