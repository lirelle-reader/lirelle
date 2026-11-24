package app.tfdn.lirelle.core.comics

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * A paged book's file as the server has it: its pages are kept for that version only. Its [kind] says where
 * the pages come from.
 *
 * @param bookId the book the file is of. A PDF needs it: its whole file is taken from the reader's cache of
 * whole files, or from the copy the device keeps of the book.
 */
data class ComicKey(
    val fileId: FileId,
    val contentVersion: String?,
    val kind: PagedKind = PagedKind.Comic,
    val bookId: BookId? = null,
)

/** What came of a document fetched whole before its pages can be shown; [totalBytes] is null when not known. */
data class OpeningProgress(val receivedBytes: Long, val totalBytes: Long?)

/** Why a document that is on the device cannot be read: trying again does not help. */
enum class DocumentFailure {
    /** It asks for a password. */
    Protected,

    /** It is not a document of its format, or not a whole one. */
    Damaged,

    /**
     * The copy the device keeps of the book is not a document of its format, or not a whole one: it is opened
     * as it is each time, so that only its download can be put right.
     */
    KeptDamaged,
}

/** What asking for an album's page count gives. */
sealed interface PageCountResult {
    data class Known(val count: Int) : PageCountResult

    /** The server, or the network, did not give what was asked: the same call can succeed later. */
    data class Failed(val error: ApiError) : PageCountResult

    /** The document came, and the device cannot read it. */
    data class Unreadable(val failure: DocumentFailure) : PageCountResult
}

/** What the device has of one page. */
sealed interface PageState {
    /** Not on the device: being fetched, waiting its turn, or not asked for yet. */
    data object Loading : PageState

    /** The picture is in [file]; [width] and [height] are its size in pixels. */
    data class Ready(val file: File, val width: Int, val height: Int) : PageState

    /** The fetch failed; [ComicPages.retry] asks again. */
    data class Failed(val error: ApiError) : PageState
}

/**
 * The pages of one album, fetched as the reader moves and kept on the device. The album is held from the
 * moment it is opened until [close], or until the scope it was opened in ends: the store never drops it then.
 *
 * Pages count from 0.
 */
interface ComicPages {
    /**
     * How many pages the album has. Asked of the server once; when it does not answer, the count remembered
     * from an earlier opening is given. A failure is not remembered: the next call asks again.
     *
     * A document drawn on the device is fetched whole first, which [opening] follows, and opened: its count is
     * the document's own.
     */
    suspend fun pageCount(): PageCountResult

    /**
     * What came of the document while [pageCount] fetches it whole; null when there is nothing to wait for,
     * which is always so for an album whose pages are fetched one by one.
     */
    val opening: Flow<OpeningProgress?>

    /** What the device has of the page [index], as it changes. A page already on the device needs no request. */
    fun page(index: Int): Flow<PageState>

    /**
     * The reader arrived on [index]: it is fetched at once, ahead of everything waiting. Nothing is asked for a
     * page on the device or being fetched; a page that failed is asked for again.
     */
    fun show(index: Int)

    /** The reader stayed on [index]: the pages around it are fetched ahead, those waiting before are dropped. */
    fun settle(index: Int)

    /**
     * The reader went straight to [index], far or near: what waited is dropped, and so are the fetches of the
     * pages that are not around [index]; then as [show] and [settle].
     */
    fun jump(index: Int)

    /** Asks again for a page that failed. */
    fun retry(index: Int)

    /**
     * The page [index] is shown, at the fit or at a zoom, where its picture would need [longSidePx] pixels on its
     * long side to be sharp. A page drawn on the device is drawn again when that is larger than what is kept, up
     * to the page view's limit, and [page] then gives the new picture; a page fetched as a picture is what it
     * is, and nothing happens.
     */
    fun sharpen(index: Int, longSidePx: Int)

    /** Stops the fetches and gives the album back to the store. */
    fun close()
}

/** Opens the pages of an album, from the source its kind needs. */
fun interface ComicPagesOpener {
    /** The fetches run in [scope]; the album is held until [ComicPages.close] or the end of [scope]. */
    fun open(key: ComicKey, scope: CoroutineScope): ComicPages
}
