package app.tfdn.lirelle.core.comics

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.io.IOException
import java.io.OutputStream

/**
 * Where the pages of the books the profile's server serves by pages come from: the profile's source, which alone
 * knows the server's routes. Of the profile's own server, never that of "whoever is signed in": a fetch that ends
 * after its profile closed has asked no other server for a page. Pages count from 0.
 */
interface PageSource {
    /** Whether the server serves the file of a book of this [kind] by pages; a file it serves whole is not. */
    fun servesByPages(kind: PagedKind): Boolean

    /**
     * How many pages the server has of the file [fileId], of this [kind]. [ApiError.NotFound] for a kind it serves
     * no pages of, [ApiError.SessionExpired] when there is no session.
     */
    suspend fun pageCount(kind: PagedKind, fileId: FileId): ApiResult<Int>

    /**
     * Fetches the page [index] of the file [fileId], of this [kind]: once the server answers with the page, its
     * bytes are written into what [sink] opens as they come, a block at a time, and it is closed. Nothing is
     * opened for a refusal; a page past the end is [ApiError.NotFound]. The fetch ends with its coroutine at once,
     * even while it waits for bytes. A failure of [sink], or of writing what it opened, is the device's:
     * [ApiError.Unexpected]. It blocks while bytes are read and written: it is called where that is allowed.
     */
    suspend fun page(kind: PagedKind, fileId: FileId, index: Int, sink: PageSink): ApiResult<Unit>

    /**
     * The address of the page [index] of the file [fileId], of this [kind], for who fetches it with the profile's
     * client; null when there is no session, or for a kind the server serves no pages of.
     */
    fun pageUrl(kind: PagedKind, fileId: FileId, index: Int): String?
}

/** Where the bytes of a page go as they come. */
fun interface PageSink {
    /** The stream the bytes are written to; an [IOException] when the device cannot take them. */
    @Throws(IOException::class)
    fun open(): OutputStream
}
