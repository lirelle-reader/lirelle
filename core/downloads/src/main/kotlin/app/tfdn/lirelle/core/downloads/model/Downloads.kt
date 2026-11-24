package app.tfdn.lirelle.core.downloads.model

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiError
import java.time.Instant

/**
 * What can be kept of a book. The text is what is read of it: its file to read when that is a single file, an
 * EPUB, a Kindle book, a FictionBook or a PDF, or its pages when the server serves it by pages, a comic or a
 * DjVu. Narration needs the text: the two may use the same EPUB, kept once.
 */
enum class DownloadFormat { Text, Audiobook, Narration }

/**
 * One file to keep.
 *
 * @property name its name on the device, unique within its format; for the book's text the file name of its
 *   file's id and its own extension: `"<fileId>.epub"` for an EPUB, `"<fileId>.pdf"` for a PDF. A name is only
 *   where the file lies: what the file is, [source] says.
 * @property source what it is on the server: where it is served is asked from it when it is fetched.
 * @property version what the server called this content when it was planned.
 * @property expectedBytes null when the server does not say.
 * @property resumable whether the server answers ranges for it.
 * @property cacheKey the key under which the player's cache may hold the same content; null when it holds it
 *   under no key of its own (an EPUB, a narration track).
 */
data class PlannedFile(
    val name: String,
    val source: FileSource,
    val version: String?,
    val expectedBytes: Long?,
    val resumable: Boolean,
    val kind: FileKind,
    val cacheKey: String? = null,
)

/**
 * What a kept file is, which says how it is checked when it comes and who opens it. [Epub], [Pdf], [Book] and
 * [Page] are the book's text: an EPUB is checked as one, a PDF by its first bytes, and a [Book] (MOBI, AZW,
 * AZW3, FB2) is taken as it is, the reader saying whether it reads it. A [Page] is one page of a book the
 * server serves by pages, a comic or a DjVu: a picture, named by its index from 0.
 */
enum class FileKind {
    Epub,
    Audio,
    Pdf,
    Book,
    Page,
    ;

    /** The file is the book's text, in whatever format. */
    val isText: Boolean get() = this != Audio
}

/** Where the download of one format of a book is. */
sealed interface FormatState {
    data object Queued : FormatState

    /** @property totalBytes null when it is not known. */
    data class Downloading(val receivedBytes: Long, val totalBytes: Long?) : FormatState

    data class Waiting(val reason: WaitReason, val receivedBytes: Long, val totalBytes: Long?) : FormatState

    data class Failed(val reason: DownloadFailure, val receivedBytes: Long) : FormatState

    /** @property bytes its size on the device. */
    data class Complete(val bytes: Long) : FormatState
}

/** What a stopped download waits for: any network, or one the settings allow. */
enum class WaitReason { NoNetwork, UnmeteredOnly }

sealed interface DownloadFailure {
    data class NoSpace(val missingBytes: Long) : DownloadFailure

    data class Server(val error: ApiError) : DownloadFailure

    /** The server refused a file because the account may not keep books on the device, as its source reads it. */
    data object NotAllowed : DownloadFailure

    /** The file received is not what it should be: not an EPUB, or not a PDF. The name is the one stored. */
    data object NotAnEpub : DownloadFailure

    /** A kept file is missing or cut short. */
    data object Damaged : DownloadFailure
}

/**
 * A newer version of a kept format, known from the server. [files] are the format's files in that version;
 * [bytes] what must be fetched (the files whose version differs from the kept ones). [state] is null while the
 * update only waits for the user, and a download state once asked: [FormatState.Complete], with the bytes
 * fetched, once its files are whole beside the kept ones and wait for the book to close.
 *
 * @property anyNetwork true when the user let this update use a metered network.
 * @property keptPages how many of its pages wait on the device, for an update made of pages: see [pages].
 * @property textName what the text is named by in that version, whose file may be of another format: the
 *   record's once the update has taken its place. Null when it was not said: the record keeps its own.
 */
data class FormatUpdate(
    val version: String?,
    val files: List<PlannedFile>,
    val bytes: Long,
    val state: FormatState? = null,
    val anyNetwork: Boolean = false,
    val keptPages: Int = 0,
    val textName: TextName? = null,
) {
    /** The pages it has and those it is to have, when its files are pages; null otherwise. */
    val pages: PageCount? get() = pageCount(files, keptPages, whole = state is FormatState.Complete)
}

/**
 * One format of a book, as asked.
 *
 * @property fileId the file of the text, for the text and the narration; null for an audiobook.
 * @property version what the server called the content when it was planned.
 * @property anyNetwork true when the user let this one use a metered network.
 * @property removing true while it is being deleted: hidden, its files still there until the deletion can no
 *   longer be undone.
 * @property expectedBytes about what it will take, as announced when it was asked: what a download that has not
 *   started says of its size. What it is made of, once planned, says it better.
 * @property update the newer version the server has of it, null when none is known.
 * @property textName what the text is named by, from its file's format; of no use to the other formats.
 * @property keptPages how many of its pages the device has, for a format made of pages, as the download says
 *   it when a page arrives: see [pages].
 */
data class FormatRecord(
    val format: DownloadFormat,
    val state: FormatState,
    val files: List<PlannedFile>,
    val fileId: FileId?,
    val version: String?,
    val askedAt: Instant,
    val anyNetwork: Boolean = false,
    val removing: Boolean = false,
    val expectedBytes: Long? = null,
    val update: FormatUpdate? = null,
    val textName: TextName = TextName.Text,
    val keptPages: Int = 0,
) {
    /** The pages it has and those it is to have, when its files are pages; null otherwise. */
    val pages: PageCount? get() = pageCount(files, keptPages, whole = state is FormatState.Complete)
}

/**
 * What the device keeps, or is fetching, of one book.
 *
 * @property gone true when the server no longer has the book, or no longer shows it to this account: what is
 *   kept of it stays.
 * @property deleteAtClose true when its deletion was asked while it was open: it is deleted once the reader or
 *   the player lets go of it.
 */
data class DownloadedBook(
    val bookId: BookId,
    val formats: List<FormatRecord>,
    val downloadedAt: Instant,
    val lastOpenedAt: Instant?,
    val gone: Boolean = false,
    val deleteAtClose: Boolean = false,
) {
    /** At least one format is complete. */
    val isDownloaded: Boolean get() = formats.any { it.state is FormatState.Complete }

    /** What its formats have on the device, complete or not, as each of them counts it. */
    val bytesOnDevice: Long get() = formats.sumOf { it.state.bytesOnDevice }

    val lastActivity: Instant get() = lastOpenedAt ?: downloadedAt
}

/** What the updates that still wait for the user would fetch. */
val DownloadedBook.updateBytes: Long get() = formats.filter { it.hasPendingUpdate }.sumOf { it.update?.bytes ?: 0 }

/** A complete format has a newer version that waits for the user. */
val DownloadedBook.hasUpdate: Boolean get() = formats.any { it.hasPendingUpdate }

/** An update was asked and has not failed. */
val DownloadedBook.isUpdating: Boolean
    get() = formats.any { it.update?.state != null && it.update.state !is FormatState.Failed }

private val FormatRecord.hasPendingUpdate: Boolean
    get() = state is FormatState.Complete && update != null && update.state == null

private val FormatState.bytesOnDevice: Long
    get() = when (this) {
        FormatState.Queued -> 0
        is FormatState.Downloading -> receivedBytes
        is FormatState.Waiting -> receivedBytes
        is FormatState.Failed -> receivedBytes
        is FormatState.Complete -> bytes
    }
