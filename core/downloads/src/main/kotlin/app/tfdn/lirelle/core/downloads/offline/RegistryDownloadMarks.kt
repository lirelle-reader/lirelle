package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.hasUpdate
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.isUpdating
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.library.offline.DownloadMark
import app.tfdn.lirelle.core.library.offline.DownloadMarks
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The marks, from the registry. Something being fetched comes first, then an update that waits for the user,
 * then what is kept; a book with only failures has no mark. A format being deleted does not count, nor does a
 * book that is deleted once it is closed.
 */
@ProfileScoped
class RegistryDownloadMarks @Inject constructor(registry: DownloadRegistry) : DownloadMarks {
    override val marks: Flow<Map<BookId, DownloadMark>> = registry.books
        .map { books -> books.mapNotNull { book -> book.mark()?.let { book.bookId to it } }.toMap() }
        .distinctUntilChanged()
}

private fun DownloadedBook.mark(): DownloadMark? {
    if (deleteAtClose) return null
    val shown = copy(formats = formats.filterNot { it.removing })
    return when {
        shown.isFetching || shown.isUpdating -> DownloadMark.InProgress
        shown.hasUpdate -> DownloadMark.Update
        shown.isDownloaded -> DownloadMark.Kept
        else -> null
    }
}

private val DownloadedBook.isFetching: Boolean
    get() = formats.any {
        it.state is FormatState.Queued || it.state is FormatState.Downloading || it.state is FormatState.Waiting
    }
