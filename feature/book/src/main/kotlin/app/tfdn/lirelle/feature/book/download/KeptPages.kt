package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.offline.OwnProgress
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.ui.Loadable
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * The page of a downloaded book as the device keeps it.
 *
 * @property complete the formats the device has whole: those that open without the server.
 * @property percent the book's progress as the device knows it; null for a book never started.
 * @property listening where the listening is as the device knows it; null when it does not know.
 * @property ownAt when the user got to [percent], by a position the device keeps; null when [percent] is what the
 *   server said the last time the page was loaded, or none.
 */
data class KeptPage(
    val detail: BookDetail,
    val complete: Set<DownloadFormat>,
    val percent: Int?,
    val listening: PlaybackState? = null,
    val ownAt: Instant? = null,
)

/**
 * The reading an offline page shows: the device's progress, and none of what only the server knows (no date, no
 * time, no session); null for a book never started.
 */
internal fun KeptPage.offlineReading(): BookReading? =
    percent?.let { BookReading(it, lastReadAt = null, totalSeconds = 0, sessionCount = 0) }

/**
 * The listening an offline page shows: the one the device knows; when it knows none, it is said not to be known,
 * by the [error] that kept the server's, so that the player goes on from where it knows.
 */
internal fun KeptPage.offlineListening(error: ApiError): Loadable<PlaybackState?> =
    listening?.let { Loadable.Ready(it) } ?: Loadable.Failed(error)

/**
 * Where the device is in the book, by a position it keeps, when that is as recent as the [server]'s reading or the
 * server dates none: the places read of a kept copy the server cannot serve for now wait to be sent, and the
 * server's reading lacks them. Null otherwise, and when the device keeps no position.
 */
internal fun KeptPage.ownReadingAsRecentAs(server: BookReading?): BookReading? {
    val at = ownAt ?: return null
    val serverAt = server?.positionAt ?: server?.lastReadAt
    if (serverAt != null && at.isBefore(serverAt)) return null
    return percent?.let { BookReading(it, lastReadAt = at, totalSeconds = 0, sessionCount = 0, positionAt = at) }
}

/** The pages of the downloaded books, for when the server cannot give them. */
interface KeptPages {
    /** Null for a book that is not downloaded. */
    suspend fun page(bookId: BookId): KeptPage?

    /**
     * Keeps the page of a downloaded book as the server just gave it, with where it says the [reading] is, and
     * the positions the server holds, for the book to open there without it; nothing for a book that is not
     * downloaded.
     */
    suspend fun keep(detail: BookDetail, reading: BookReading?)
}

/**
 * What the device knows of where the user is in a downloaded book.
 *
 * @property progress the book's progress, from 0 to 100; null for a book never started.
 * @property listening where its listening is; null when the device does not know.
 * @property refresh keeps the positions the server holds of the book, when the device has nothing newer.
 * @property own the book's progress by the positions the device keeps, with its date; null when it keeps none.
 */
class KeptPlaces(
    val progress: suspend (DownloadedBook) -> Int?,
    val listening: suspend (DownloadedBook) -> PlaybackState? = { null },
    val refresh: suspend (BookDetail) -> Unit = {},
    val own: suspend (DownloadedBook) -> OwnProgress? = { null },
)

class DownloadedPages(
    private val downloads: Downloads,
    private val kept: KeptBookData,
    private val places: KeptPlaces,
) : KeptPages {

    override suspend fun page(bookId: BookId): KeptPage? {
        val book = downloads.book(bookId).first() ?: return null
        val detail = kept.detail(bookId) ?: return null
        val complete = book.formats.filter { !it.removing && it.isComplete }.mapTo(mutableSetOf()) { it.format }
        val own = places.own(book)
        return KeptPage(detail, complete, own?.percent ?: places.progress(book), places.listening(book), own?.at)
    }

    override suspend fun keep(detail: BookDetail, reading: BookReading?) {
        if (downloads.book(detail.id).first() == null) return
        kept.keep(detail, reading?.progressPercent, reading?.lastReadAt)
        places.refresh(detail)
    }
}
