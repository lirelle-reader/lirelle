package app.tfdn.lirelle.feature.player.service

import android.net.Uri
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.keeps
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the device keeps of the books, for the player: whether a format is there whole, what was kept with it
 * (the manifest, the map, the page), and the hold that keeps a book playing from being deleted.
 */
class KeptBooks @Inject constructor(
    private val downloads: Downloads,
    private val data: KeptBookData,
    private val holds: BookHolds,
) {
    /** What was kept with [format] of the book, when the device has it whole and is not deleting it. */
    suspend fun copyOf(bookId: BookId, format: DownloadFormat): KeptBookData? =
        data.takeIf { downloads.keeps(bookId, format) }

    suspend fun opened(bookId: BookId) = downloads.markOpened(bookId)

    fun hold(bookId: BookId) = holds.hold(bookId)

    fun release(bookId: BookId) = holds.release(bookId)
}

/**
 * The server cannot say: it cannot be reached, or it no longer has the book. What a download kept then stands
 * in; any other failure is the server's answer.
 */
internal val ApiResult<*>.isMissing: Boolean
    get() = (this as? ApiResult.Failure)?.error.let { it == ApiError.ServerUnreachable || it == ApiError.NotFound }

/**
 * The address of the cover kept with [bookId], a file on the device that the screens and the notification read
 * without the server; null when no cover was kept.
 */
internal fun KeptBookData.coverAddress(bookId: BookId): String? =
    coverFile(bookId)?.let { Uri.fromFile(it).toString() }

/**
 * The address of the cover the audiobook of [bookId] shows from the device: its own cover when one was kept,
 * else the book's; null when neither was kept.
 */
internal fun KeptBookData.audiobookCoverAddress(bookId: BookId): String? =
    audioCoverFile(bookId)?.let { Uri.fromFile(it).toString() } ?: coverAddress(bookId)

/**
 * Holds the downloaded book loaded in the service, so that it is not deleted while it plays, and tells each
 * book's download that it was opened. Each hold is released once: when another book takes its place, when the
 * book is closed, when the service ends ([closed]).
 *
 * @param lasting for what must be written even as the service ends.
 */
class DownloadHold(private val kept: KeptBooks, private val lasting: CoroutineScope) {
    private var held: BookId? = null

    fun opened(book: LoadedBook) {
        val before = held
        // The new one first: the same book loaded again is never let go in between.
        held = book.bookId.takeIf { book.kept }?.also(kept::hold)
        before?.let(kept::release)
        lasting.launch { kept.opened(book.bookId) }
    }

    /** The book was closed, or the service ends; nothing more is held. */
    fun closed() {
        held?.let(kept::release)
        held = null
    }

    /** Whether the book loaded is [bookId], played from its download. */
    fun holds(bookId: BookId): Boolean = held == bookId
}
