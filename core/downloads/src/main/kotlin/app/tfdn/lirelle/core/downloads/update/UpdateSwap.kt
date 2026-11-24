package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.FormatSizes
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.engine.askedUpdate
import app.tfdn.lirelle.core.downloads.engine.countedFiles
import app.tfdn.lirelle.core.downloads.engine.textFileOf
import app.tfdn.lirelle.core.downloads.engine.isUpdateWhole
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.offline.takeWaitingData
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Puts an update fetched whole in the place of what is kept: its files are moved into the kept places, and
 * with them what the newer version opens with (its manifest, its map), the kept files the newer version no
 * longer has are deleted (the pages past its count, for a text made of pages), and then the record says the
 * newer version and has no update any more. In that order, and an update says it is whole before any of it:
 * stopped in between, the swap is done again from where it was, the files already moved left where they are.
 *
 * A book in use is not touched: its update waits, whole, until [settle] is called again, which who knows the
 * holds does when the book is let go of; [settleAll] does it for what an earlier process left.
 *
 * @param unlessHeld runs its block unless the book is in use, nobody taking the book meanwhile, and says
 *   whether it ran.
 * @param io where the disk is read and written.
 */
internal class UpdateSwap(
    private val registry: DownloadRegistry,
    private val files: DownloadFiles,
    private val unlessHeld: (bookId: BookId, block: () -> Unit) -> Boolean,
    private val io: CoroutineDispatcher,
) {
    /** One swap at a time, whoever asks: the runner, a book that closes, the start. */
    private val mutex = Mutex()

    /** Every book: what a process that ended left whole and not swapped. */
    suspend fun settleAll() = registry.books.first().forEach { settle(it.bookId) }

    /**
     * Swaps the updates of [bookId] once all those asked are whole, unless the book is in use, and removes what
     * waits beside the kept files for an update that is no longer asked.
     */
    suspend fun settle(bookId: BookId) = mutex.withLock {
        swap(bookId)
        sweep(bookId)
    }

    /** The updates of the book take their place in one step; a book in use is not touched. */
    private suspend fun swap(bookId: BookId) {
        val book = registry.book(bookId) ?: return
        val whole = together(book)
        if (whole.isEmpty()) return
        val bytes = try {
            withContext(io) { if (unlessHeld(bookId) { move(book, whole) }) sizes(book, whole) else null }
        } catch (_: IOException) {
            // The device's side: what was moved stays, and the rest is tried again at the next occasion.
            null
        } ?: return
        registry.update(bookId) { current ->
            whole.fold(current) { now, record ->
                val update = record.update
                if (update == null) now else now?.swapped(record.format, update, bytes.getValue(record.format))
            }
        }
    }

    /**
     * The records whose updates take their place now, the text before the narration: every update asked, once
     * they are all whole. None while one of them is still to fetch or failed: the text and the narration share
     * an EPUB, and the kept copy opens as it was until all of it can change. None either when a file would
     * change under a narration that is not among them: its kept map and tracks are those of the kept EPUB.
     */
    private fun together(book: DownloadedBook): List<FormatRecord> {
        val asked = book.formats.filter { it.askedUpdate != null }.sortedBy { it.format }
        val narration = book.formats.firstOrNull {
            it.format == DownloadFormat.Narration && it.state is FormatState.Complete && !it.removing && it !in asked
        }
        val kept = narration?.files.orEmpty().associateBy { files.file(book.bookId, DownloadFormat.Narration, it.name) }
        val leftBehind = asked.any { record ->
            record.update?.files.orEmpty().any { file ->
                kept[files.file(book.bookId, record.format, file.name)]?.let { it.version != file.version } == true
            }
        }
        return asked.takeIf { all -> all.all { it.isUpdateWhole } && !leftBehind }.orEmpty()
    }

    /**
     * The new files of [whole] into the kept places, each in one step, then what they open with; then the kept
     * files nobody lists any more go.
     */
    private fun move(book: DownloadedBook, whole: List<FormatRecord>) {
        whole.forEach { record ->
            record.update?.files.orEmpty().forEach { file ->
                val waiting = files.updates.file(book.bookId, record.format, file.name)
                // One that is not there was moved before the swap was stopped, or with another format.
                if (waiting.isFile) {
                    val place = files.file(book.bookId, record.format, file.name)
                    // Not made once the profile closed: the move then fails, and the file still waits.
                    files.folders.make(place.parentFile)
                    Files.move(waiting.toPath(), place.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
            }
            files.takeWaitingData(book.bookId, record.format)
        }
        val staying = book.formats.filter { it !in whole }.flatMap { places(book.bookId, it.format, it.files) }
        val listed =
            (whole.flatMap { places(book.bookId, it.format, it.update?.files.orEmpty()) } + staying).toSet()
        whole.flatMap { places(book.bookId, it.format, it.files) }.filterNot { it in listed }.forEach { it.delete() }
    }

    /** What each of [whole] has on the device once swapped, each counted on the book as the one before left it. */
    private fun sizes(book: DownloadedBook, whole: List<FormatRecord>): Map<DownloadFormat, Long> {
        var now = book
        return whole.associate { record ->
            val update = checkNotNull(record.update)
            val counted = countedFiles(now, record.format, update.files)
            val bytes = FormatSizes(files, book.bookId, record.format, counted).received()
            now = now.swapped(record.format, update, bytes)
            record.format to bytes
        }
    }

    private fun places(bookId: BookId, format: DownloadFormat, planned: List<PlannedFile>): List<File> =
        planned.map { files.file(bookId, format, it.name) }

    /**
     * The record of [format] with the files and the version of [update], whole, [bytes] on the device, its text
     * named as the newer file is: a CBZ replaced by a CBR is a CBR from then on. The
     * other formats that keep one of its files, the EPUB the text and the narration share, say it of its new
     * version too. Nothing changes when the record is no longer the one whose files were moved.
     */
    private fun DownloadedBook.swapped(format: DownloadFormat, update: FormatUpdate, bytes: Long): DownloadedBook {
        val current = formats.firstOrNull { it.format == format }
        val same = current != null && !current.removing && current.state is FormatState.Complete &&
            current.update?.version == update.version && current.update?.files == update.files
        if (!same) return this
        val moved = update.files.associateBy { files.file(bookId, format, it.name) }
        return copy(
            formats = formats.map { record ->
                if (record.format == format) {
                    record.copy(
                        files = update.files,
                        version = update.version,
                        fileId = textFileOf(update.files) ?: record.fileId,
                        state = FormatState.Complete(bytes),
                        update = null,
                        keptPages = update.files.count { it.kind == FileKind.Page },
                        textName = update.textName ?: record.textName,
                    )
                } else {
                    record.copy(files = record.files.map { moved[files.file(bookId, record.format, it.name)] ?: it })
                }
            },
        )
    }

    /** What waits beside the kept files of [bookId] for an update that is not asked goes. */
    private suspend fun sweep(bookId: BookId) {
        val asked = registry.book(bookId)?.formats.orEmpty().filter { it.askedUpdate != null }
        withContext(io) {
            DownloadFormat.entries.filter { format -> asked.none { it.format == format } }
                .forEach { files.deleteUpdate(bookId, it, asked) }
        }
    }
}
