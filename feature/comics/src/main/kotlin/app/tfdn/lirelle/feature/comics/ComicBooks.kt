package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * The books the page reader opens, as the server tells of them or, for a book the device keeps, as its
 * download kept them: a kept book needs no server, neither for its page nor for what is read of it. The whole
 * file of a PDF is handed out by the cache of whole files as the device keeps it, and the pages of a comic or
 * of a DjVu are read where the downloads keep them.
 */
class ComicBooks @Inject constructor(
    private val library: LibraryRepository,
    private val kept: KeptBookData,
    private val downloads: Downloads,
) {
    /**
     * The server's page of the book; the one kept with its download when the server cannot give it, or gives it
     * without its files for now (Komga analyses the book's new file) while the device keeps its pages whole: the
     * kept copy is read, saying nothing.
     */
    suspend fun detail(bookId: BookId): ApiResult<BookDetail> {
        val answer = library.book(bookId)
        val keptInstead = when (answer) {
            is ApiResult.Success -> answer.value.filesUnavailable && keepsText(bookId)
            is ApiResult.Failure -> answer.error == ApiError.ServerUnreachable || answer.error == ApiError.NotFound
        }
        if (!keptInstead) return answer
        return kept.detail(bookId)?.let { ApiResult.Success(it) } ?: answer
    }

    /** Whether the device has the book's text whole: not on its way, not being deleted. */
    private suspend fun keepsText(bookId: BookId): Boolean = downloads.book(bookId).first()?.formats.orEmpty()
        .any { it.format == DownloadFormat.Text && !it.removing && it.state is FormatState.Complete }

    /**
     * The book is open: its download, if it has one, is told. A date that cannot be written keeps nobody from
     * reading.
     */
    suspend fun opened(bookId: BookId) {
        try {
            downloads.markOpened(bookId)
        } catch (_: IOException) {
            // The registry's file: the book is only not counted as opened.
        }
    }
}
