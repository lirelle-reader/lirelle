package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.downloads.update.UpdateSwap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What is put right once when the process starts: the deletions left unfinished are finished, the books that
 * were to be deleted when they closed are, the updates fetched whole and not swapped are swapped, and a
 * complete format whose files are not all there, whole, is to download again: a text made of pages with one
 * of them missing as any other.
 */
internal class StartCheck(
    private val registry: DownloadRegistry,
    private val files: DownloadFiles,
    private val remover: DownloadRemover,
    private val flagged: FlaggedBooks,
    private val swap: UpdateSwap,
    private val io: CoroutineDispatcher,
) {
    private var done = false

    /** Only the first call does anything. The runner calls it, one run at a time. */
    suspend fun once() {
        if (done) return
        done = true
        remover.finishPending()
        // Before the swaps: nothing is swapped into a book that goes.
        flagged.deleteAll()
        // Before the kept files are looked at: a swap stopped half way left them between two versions.
        swap.settleAll()
        registry.books.first().forEach { book ->
            val damaged = withContext(io) { book.formats.filter { it.isDamaged(book.bookId) } }
            damaged.forEach { record ->
                val (received, pages) = withContext(io) {
                    sizes(book, record).received() to pagesThere(book, record)
                }
                // No longer whole: it says the pages it really has.
                val state = FormatState.Failed(DownloadFailure.Damaged, received)
                val failed = record.copy(state = state, keptPages = pages)
                registry.update(book.bookId) { current ->
                    current?.copy(formats = current.formats.map { if (it == record) failed else it })
                }
            }
        }
    }

    private fun FormatRecord.isDamaged(book: BookId): Boolean = state is FormatState.Complete && files.any { planned ->
        val file = this@StartCheck.files.file(book, format, planned.name)
        !file.isFile || (planned.expectedBytes != null && planned.expectedBytes != file.length())
    }

    private fun pagesThere(book: DownloadedBook, record: FormatRecord): Int = record.files.count {
        it.kind == FileKind.Page && files.file(book.bookId, record.format, it.name).isFile
    }

    private fun sizes(book: DownloadedBook, record: FormatRecord) =
        FormatSizes(files, book.bookId, record.format, countedFiles(book, record.format, record.files))
}
