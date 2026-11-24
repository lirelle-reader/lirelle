package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Takes formats off the device: their records, their files, what was kept for them. An EPUB a format that
 * stays still uses is left; the book's last format takes the book's page and its directory with it.
 */
internal class Disposal(
    private val registry: DownloadRegistry,
    private val files: DownloadFiles,
    private val kept: KeptBookData,
    private val io: CoroutineDispatcher,
) {
    /** Drops the formats of [bookId] that [which] names. */
    suspend fun drop(bookId: BookId, which: (FormatRecord) -> Boolean) = dropThose(bookId) { it.formats.filter(which) }

    /** Drops [bookId] whole, when [which] says so of the book as it is recorded then. */
    suspend fun dropBook(bookId: BookId, which: (DownloadedBook) -> Boolean) =
        dropThose(bookId) { if (which(it)) it.formats else emptyList() }

    private suspend fun dropThose(bookId: BookId, named: (DownloadedBook) -> List<FormatRecord>) {
        var dropped = emptyList<FormatRecord>()
        var left = emptyList<FormatRecord>()
        registry.update(bookId) { book ->
            dropped = book?.let(named).orEmpty()
            left = book?.formats.orEmpty() - dropped.toSet()
            if (dropped.isEmpty()) book else book?.copy(formats = left)?.takeIf { left.isNotEmpty() }
        }
        if (dropped.isEmpty()) return
        withContext(io) {
            dropped.forEach { files.delete(bookId, it.format, left) }
            if (left.isEmpty()) files.deleteBook(bookId)
        }
        dropped.forEach { kept.remove(bookId, it.format) }
        if (left.isEmpty()) kept.remove(bookId, null)
    }

    /** Removes what a fetch stopped late may have left of a format that is no longer recorded. */
    suspend fun sweep(bookId: BookId, format: DownloadFormat) {
        val recorded = registry.book(bookId)?.formats.orEmpty()
        if (recorded.any { it.format == format }) return
        withContext(io) {
            files.delete(bookId, format, recorded)
            if (recorded.isEmpty()) files.deleteBook(bookId)
        }
    }

    /** Every book, at once. */
    suspend fun everything() {
        registry.clear()
        withContext(io) { files.deleteAll() }
    }
}
