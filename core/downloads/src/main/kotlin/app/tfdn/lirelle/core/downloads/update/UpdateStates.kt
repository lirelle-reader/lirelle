package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.FormatPlan
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.engine.isUpdatePending
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import kotlinx.coroutines.flow.Flow

/**
 * Where the run of an update writes how far it is: in the update of the record, whose own state stays complete.
 * An update is only changed while it is the one the run was given and still to fetch: what was cancelled, deleted
 * or asked again meanwhile is left as the user put it.
 */
internal class UpdateStates(private val registry: DownloadRegistry) {
    val books: Flow<List<DownloadedBook>> get() = registry.books

    suspend fun book(bookId: BookId): DownloadedBook? = registry.book(bookId)

    /** The record of [format] when its update is still to fetch. */
    suspend fun pending(bookId: BookId, format: DownloadFormat): FormatRecord? = book(bookId)?.pending(format)

    /** Applies [change] to the update of [record]; null takes the update away: there is nothing newer. */
    suspend fun change(bookId: BookId, record: FormatRecord, change: (FormatUpdate) -> FormatUpdate?) =
        registry.update(bookId) { book ->
            book?.copy(
                formats = book.formats.map {
                    if (it.isRunOf(record)) it.copy(update = it.update?.let(change)) else it
                },
            )
        }

    suspend fun set(bookId: BookId, record: FormatRecord, state: FormatState) =
        change(bookId, record) { it.copy(state = state) }

    /** Where the update is, and how many of its pages wait beside the kept ones: none for other files. */
    suspend fun progress(bookId: BookId, record: FormatRecord, state: FormatState, pages: Int) =
        change(bookId, record) { it.copy(state = state, keptPages = pages) }

    /** What the newer version is made of, as the server says now, and what is to fetch of it. */
    suspend fun adopt(bookId: BookId, record: FormatRecord, plan: FormatPlan) = registry.update(bookId) { book ->
        book?.copy(
            formats = book.formats.map {
                val bytes = it.bytesToFetch(plan, book)
                if (it.isRunOf(record)) it.copy(update = it.update?.adopting(plan, bytes, it.nameIn(plan))) else it
            },
        )
    }
}

/** The record of [format] when its update is still to fetch. */
internal fun DownloadedBook.pending(format: DownloadFormat): FormatRecord? =
    formats.firstOrNull { it.format == format && it.isUpdatePending }

private fun FormatRecord.isRunOf(record: FormatRecord): Boolean =
    format == record.format && askedAt == record.askedAt && isUpdatePending

private fun FormatUpdate.adopting(plan: FormatPlan, bytes: Long, textName: TextName?): FormatUpdate =
    copy(version = plan.version, files = plan.files, bytes = bytes, textName = textName)
