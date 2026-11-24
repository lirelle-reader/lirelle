package app.tfdn.lirelle.core.comics

import app.tfdn.lirelle.core.comics.store.ComicStore
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.io.File

/**
 * The books the server serves by pages, for who keeps them on the device: whether a book is, how many pages there
 * are, and the pages the page reader already has. Where a page is served, the profile's source says
 * ([PageSource.pageUrl]).
 */
interface ServedPages {
    /** Whether the server serves the file of a book of this [kind] by pages; a PDF, served whole, is not. */
    fun servesByPages(kind: PagedKind): Boolean

    /** How many pages the server has of the file; [ApiError.NotFound] for a kind it serves no pages of. */
    suspend fun count(kind: PagedKind, fileId: FileId): ApiResult<Int>

    /**
     * The page [index], from 0, of that version of the file, when the page reader has it among the pages
     * seen; null otherwise. Nothing is held: the file may go at any moment, the cache being the reader's. It
     * reads the disk: call it off the main thread.
     */
    fun cached(fileId: FileId, contentVersion: String?, index: Int): File?
}

/** @param source the profile's source, which serves the pages. */
internal class DefaultServedPages(private val store: ComicStore, private val source: PageSource) : ServedPages {
    override fun servesByPages(kind: PagedKind): Boolean = source.servesByPages(kind)

    override suspend fun count(kind: PagedKind, fileId: FileId): ApiResult<Int> = source.pageCount(kind, fileId)

    override fun cached(fileId: FileId, contentVersion: String?, index: Int): File? =
        store.cached(fileId, contentVersion, index)
}
