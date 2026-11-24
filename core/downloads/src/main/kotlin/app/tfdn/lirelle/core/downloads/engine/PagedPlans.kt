package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.comics.ServedPages
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import javax.inject.Inject

/**
 * What the text of a book the server serves by pages, a comic or a DjVu, is made of: its pages, one file
 * each, as many as the server counts.
 */
internal class PagedPlans @Inject constructor(
    private val served: ServedPages,
    private val registry: DownloadRegistry,
) {
    /**
     * The pages of [file], whose pages are of this [kind]: the count is asked of the server, and each page is
     * a file to fetch, named by the file, the kind and its index. Every page is of the file's version, and the
     * file's size is about what they take together, no page saying its own.
     *
     * A check, [purpose], of an album the device keeps in the file's version counts nothing: its pages are
     * those kept. And a count the server refuses says nothing of the book, which it has just answered for:
     * a check gets no answer from it, where a download fails as the server said.
     *
     * [ApiError.NotFound] for a kind the server serves no pages of.
     */
    suspend fun plan(bookId: BookId, file: BookFile, kind: PagedKind, purpose: PlanFor): ApiResult<FormatPlan> {
        if (!served.servesByPages(kind)) return ApiResult.Failure(ApiError.NotFound)
        val check = purpose == PlanFor.Check
        if (check) kept(bookId, file)?.let { return ApiResult.Success(it) }
        return when (val count = served.count(kind, file.id)) {
            is ApiResult.Failure -> count.takeUnless { check && it.error == ApiError.NotFound }
                ?: ApiResult.Failure(ApiError.Unexpected)
            is ApiResult.Success -> pages(file, kind, count.value)
        }
    }

    /** The pages the device keeps whole of [file] in the version the server has now, as a plan; null otherwise. */
    private suspend fun kept(bookId: BookId, file: BookFile): FormatPlan? {
        val record = registry.book(bookId)?.formats?.firstOrNull { it.format == DownloadFormat.Text }
        val same = record != null && record.state is FormatState.Complete && record.pages != null &&
            record.fileId == file.id && record.version == file.contentVersion
        if (record == null || !same) return null
        return FormatPlan(file.id, record.version, record.files, TextName.of(file.format), file.sizeBytes)
    }

    private fun pages(file: BookFile, kind: PagedKind, count: Int): ApiResult<FormatPlan> {
        // An album of no page is nothing to keep, and nothing to read.
        if (count <= 0) return ApiResult.Failure(ApiError.Unexpected)
        val plan = FormatPlan(
            fileId = file.id,
            version = file.contentVersion,
            files = pageFiles(file.id, kind, count, file.contentVersion),
            textName = TextName.of(file.format),
            expectedBytes = file.sizeBytes,
        )
        return ApiResult.Success(plan)
    }
}
