package app.tfdn.lirelle.core.downloads

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** What the UI and the features use: what the device keeps, what it is fetching, and asking for more. */
interface Downloads {
    /** Every book kept or being fetched, without the formats being deleted; in no particular order. */
    val books: Flow<List<DownloadedBook>>

    val queue: Flow<QueueState>

    /** Null while nothing of this book is kept or asked. */
    fun book(bookId: BookId): Flow<DownloadedBook?>

    /** Null when it may start; otherwise why not. Narration takes the text with it. */
    suspend fun ask(bookId: BookId, formats: Set<DownloadFormat>): AskRefusal?

    /**
     * [ask], with about what each format will take when the page knows ([sizes], in bytes): a download that
     * waits before its first byte then says how much is left.
     */
    suspend fun ask(bookId: BookId, formats: Set<DownloadFormat>, sizes: Map<DownloadFormat, Long>): AskRefusal? =
        ask(bookId, formats)

    /**
     * [ask], with what the page knows of the book's text besides: what it is named by ([textName], from its
     * file's format, as the offer says it). A text that waits before the server said what it is made of is
     * then named as it will be: a comic is not shown as a text meanwhile.
     */
    suspend fun ask(
        bookId: BookId,
        formats: Set<DownloadFormat>,
        sizes: Map<DownloadFormat, Long>,
        textName: TextName,
    ): AskRefusal? = ask(bookId, formats, sizes)

    /**
     * Takes what is not complete out of the queue and deletes what was received: [format], or every format of
     * the book. The text goes with a narration that is not complete, and stays while a complete one needs it.
     * An update asked of a complete format is taken back: what it fetched goes, the kept copy stays, and the
     * update waits for the user again.
     */
    suspend fun cancel(bookId: BookId, format: DownloadFormat? = null)

    /** Queues the failed formats of this book again, and its failed updates. */
    suspend fun retry(bookId: BookId)

    /** The downloads and the updates now queued may use any network; those asked later follow the rule again. */
    suspend fun allowAnyNetworkNow()

    suspend fun markOpened(bookId: BookId)
}

/** Deleting what the device keeps. */
interface DownloadRemovals {
    /**
     * Removes formats: they are hidden at once, and their files go after the undo window unless [undoDelete] is
     * called. Refused for a book in use.
     */
    suspend fun delete(bookId: BookId, formats: Set<DownloadFormat>): DeleteResult

    /** Brings back what [delete] hid of this book, while its files are still there. */
    suspend fun undoDelete(bookId: BookId)

    /** Everything, at once, in use or not: the queue too. */
    suspend fun deleteAll()
}

/** Deleting a book from where it is open: the end panels of the reader and of the player. */
interface ClosingDeletions {
    /**
     * Everything the device keeps of [bookId] goes when the book is no longer held; at once when it is not.
     * Its downloads and its updates in progress are cancelled. Until it goes, the book opens from its copy, and
     * says [app.tfdn.lirelle.core.downloads.model.DownloadedBook.deleteAtClose].
     */
    suspend fun deleteAtClose(bookId: BookId)

    /** Takes [deleteAtClose] back, when the book is still there. What was cancelled is not started again. */
    suspend fun keepAfterAll(bookId: BookId)
}

enum class AskRefusal { NotAllowed }

enum class DeleteResult { Deleted, InUse }

/**
 * The downloads that are not complete.
 *
 * @property active the book being fetched.
 * @property waiting the others in the order they will be fetched, the failed ones among them.
 * @property waitReason what the queue waits for when nothing is being fetched.
 * @property leftBytes what is left to fetch of the sizes known; null when none is.
 */
data class QueueState(
    val active: QueueEntry?,
    val waiting: List<QueueEntry>,
    val waitReason: WaitReason?,
    val leftBytes: Long?,
)

/**
 * One book of the queue.
 *
 * @property formats those not complete, in the order they are fetched.
 * @property totalBytes null while the size of one of them is not known.
 * @property failure why one of them failed, if one did.
 * @property update true when the book is whole on the device and [formats] are being fetched again in a newer
 *   version: the kept copy stays usable meanwhile.
 * @property pages the pages the device has and those it is to have of the text being fetched, when that text
 *   is made of pages: its bytes are then counted against an estimate. Null otherwise.
 * @property textName what the text is named by, when it is among [formats].
 */
data class QueueEntry(
    val bookId: BookId,
    val formats: List<DownloadFormat>,
    val receivedBytes: Long,
    val totalBytes: Long?,
    val failure: DownloadFailure?,
    val update: Boolean = false,
    val pages: PageCount? = null,
    val textName: TextName = TextName.Text,
)

/**
 * Whether the device has [format] of the book whole, and is not deleting it: the reader and the player then
 * use what the download kept.
 */
suspend fun Downloads.keeps(bookId: BookId, format: DownloadFormat): Boolean =
    book(bookId).first()?.formats.orEmpty().any {
        it.format == format && !it.removing && it.state is FormatState.Complete
    }
