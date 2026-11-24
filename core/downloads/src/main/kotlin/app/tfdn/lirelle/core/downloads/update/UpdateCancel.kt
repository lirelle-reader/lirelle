package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.askedUpdate
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.engine.isUpdatePending
import app.tfdn.lirelle.core.downloads.engine.updateTakenBack
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import kotlinx.coroutines.flow.first

/**
 * Takes updates back: what they fetched goes, the update waits for the user again, and the kept copy is not
 * touched.
 */
internal class UpdateCancel(private val registry: DownloadRegistry, private val swap: UpdateSwap) {
    /** The update asked of [format], or of every format of the book: on its way, failed, or whole and waiting. */
    suspend fun cancel(bookId: BookId, format: DownloadFormat? = null) =
        takeBack(bookId) { (format == null || it.format == format) && it.askedUpdate != null }

    /** The account may no longer download: the updates still to fetch are taken back. */
    suspend fun cancelPending() = registry.books.first().forEach { book ->
        if (book.formats.any { it.isUpdatePending }) takeBack(book.bookId) { it.isUpdatePending }
    }

    private suspend fun takeBack(bookId: BookId, which: (FormatRecord) -> Boolean) {
        registry.update(bookId) { book ->
            book?.copy(formats = book.formats.map { if (which(it)) it.updateTakenBack() else it })
        }
        // The files of an update that is not asked are removed there.
        swap.settle(bookId)
    }
}
