package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import java.time.Instant

/**
 * Where a run writes how far it is. A record is only changed while it is the one the run was given and still to
 * fetch: what was cancelled, deleted or asked again meanwhile is left as the user put it.
 */
internal class RecordStates(private val registry: DownloadRegistry, private val now: () -> Instant) {
    suspend fun book(bookId: BookId): DownloadedBook? = registry.book(bookId)

    suspend fun change(bookId: BookId, record: FormatRecord, change: (FormatRecord) -> FormatRecord) =
        registry.update(bookId) { it?.changing(record, change) }

    /** Where the download is, and how many of its pages the device has: none for a format not made of pages. */
    suspend fun progress(bookId: BookId, record: FormatRecord, state: FormatState, pages: Int) =
        change(bookId, record) { it.copy(state = state, keptPages = pages) }

    /**
     * What the format is made of, as the server says now: its files, what its text is named by, and about
     * what it takes when the plan says so better than who asked for it.
     */
    suspend fun adopt(bookId: BookId, record: FormatRecord, plan: FormatPlan) = change(bookId, record) {
        it.copy(
            files = plan.files,
            fileId = plan.fileId,
            version = plan.version,
            textName = plan.textName,
            expectedBytes = plan.expectedBytes ?: it.expectedBytes,
        )
    }

    /** The format is whole, [bytes] on the device, every page of it there: the book was downloaded now. */
    suspend fun complete(bookId: BookId, record: FormatRecord, bytes: Long) = registry.update(bookId) { book ->
        val changed = book?.changing(record) {
            val pages = it.files.count { file -> file.kind == FileKind.Page }
            it.copy(state = FormatState.Complete(bytes), keptPages = pages)
        }
        if (changed == book) book else changed?.copy(downloadedAt = now())
    }

    private fun DownloadedBook.changing(record: FormatRecord, change: (FormatRecord) -> FormatRecord) = copy(
        formats = formats.map {
            if (it.format == record.format && it.askedAt == record.askedAt && it.isPending) change(it) else it
        },
    )
}
