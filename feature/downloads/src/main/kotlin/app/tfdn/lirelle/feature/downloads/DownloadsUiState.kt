package app.tfdn.lirelle.feature.downloads

import app.tfdn.lirelle.core.downloads.QueueEntry
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.downloads.model.hasUpdate
import app.tfdn.lirelle.core.downloads.model.isUpdating
import app.tfdn.lirelle.core.downloads.model.updateBytes
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.CoverShape

/**
 * The Downloads destination.
 *
 * @property loaded false until the device said what it keeps: nothing is shown before, not even "empty".
 * @property inProgress the downloads that are not complete: the one being fetched first.
 * @property wifi the banner of a queue waiting for Wi-Fi; null when it does not.
 * @property waitingForNetwork the queue waits for any network.
 * @property updates the books with a newer version on the server that waits for the user, in the order of [books].
 * @property books the other books with something complete, the most recently opened or downloaded first; a book
 *   that is only being updated is in [inProgress] alone.
 * @property refreshing the check asked by a pull is running.
 * @property deleteSheet the choice of what to delete of a book that keeps several formats.
 */
data class DownloadsUiState(
    val storage: StorageUi = StorageUi(0, 0, 0, 0),
    val inProgress: List<ProgressRow> = emptyList(),
    val wifi: WifiBannerUi? = null,
    val updates: List<UpdateRow> = emptyList(),
    val books: List<DownloadedRow> = emptyList(),
    val snackbar: DownloadsSnackbar? = null,
    val waitingForNetwork: Boolean = false,
    val deleteSheet: DeleteSheetUi? = null,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
) {
    /** Nothing kept and nothing asked. */
    val isEmpty: Boolean get() = loaded && books.isEmpty() && inProgress.isEmpty() && updates.isEmpty()

    /** What all the updates would fetch. */
    val updatesBytes: Long get() = updates.sumOf { it.updateBytes }

    /** The downloaded books are told apart from the other sections only when there are some. */
    val downloadedHeading: Boolean get() = books.isNotEmpty() && (inProgress.isNotEmpty() || updates.isNotEmpty())
}

/**
 * @property downloadsBytes what the downloads take, those not complete too.
 * @property bookCount the books with something complete.
 * @property totalBytes the size of the device's storage.
 * @property queued something is still asked: being fetched, waiting or failed.
 */
data class StorageUi(
    val downloadsBytes: Long,
    val bookCount: Int,
    val freeBytes: Long,
    val totalBytes: Long,
    val queued: Boolean = false,
)

/**
 * @property title null when the device does not have the book's page: the row then says "Book".
 * @property percent null for a book never started as far as the device knows.
 * @property note what the row says besides, null for most.
 * @property textName what the text is named by, when it is among [formats].
 */
data class DownloadedRow(
    val bookId: BookId,
    val title: String?,
    val authors: String,
    val cover: String?,
    val formats: List<DownloadFormat>,
    val bytes: Long,
    val percent: Int?,
    val coverShape: CoverShape = CoverShape.Portrait,
    val note: RowNote? = null,
    val textName: TextName = TextName.Text,
)

enum class RowNote {
    /** The server no longer has the book: what is kept of it stays. */
    Gone,

    /** An update is fetched and waits for the book, which is open, to close. */
    UpdateReady,
}

/**
 * A downloaded book with an update to ask for.
 *
 * @property updateBytes what the update would fetch.
 */
data class UpdateRow(val book: DownloadedRow, val updateBytes: Long)

/**
 * One book of the queue.
 *
 * @property title null when the device does not have the book's page: the row then says "Book".
 * @property update it is the update of a downloaded book, not a download.
 * @property textName what the text is named by, when it is among [formats].
 * @property pages the pages it has and those it is to have, when what is fetched is made of pages: it then
 *   says how far it is in pages, its bar following them.
 */
data class ProgressRow(
    val bookId: BookId,
    val title: String?,
    val cover: String?,
    val formats: List<DownloadFormat>,
    val status: ProgressStatus,
    val coverShape: CoverShape = CoverShape.Portrait,
    val update: Boolean = false,
    val textName: TextName = TextName.Text,
    val pages: PageCount? = null,
)

sealed interface ProgressStatus {
    /** @property totalBytes null while it is not known. */
    data class Active(val receivedBytes: Long, val totalBytes: Long?) : ProgressStatus

    /**
     * At the head of a queue that waits for a network: what it received stays.
     *
     * @property totalBytes null while it is not known.
     */
    data class Waiting(val reason: WaitReason, val receivedBytes: Long, val totalBytes: Long?) : ProgressStatus

    /** Behind another book. */
    data class Queued(val totalBytes: Long?) : ProgressStatus

    data class Failed(val reason: DownloadFailure) : ProgressStatus
}

/** @property leftBytes null when no size is known. */
data class WifiBannerUi(val bookCount: Int, val leftBytes: Long?)

/** @property serial tells one message from the same one asked again. */
data class DownloadsSnackbar(val kind: Kind, val serial: Int, val bookId: BookId? = null) {
    enum class Kind { MobileAllowed, Deleted, InUse }
}

/** @property bytes what the book takes on the device. */
data class DeleteSheetUi(val bookId: BookId, val title: String, val bytes: Long, val options: List<DeleteOption>)

/** @property textName what the text is named by, for the text. */
data class DeleteOption(val format: DownloadFormat, val bytes: Long, val textName: TextName = TextName.Text)

/** What the device kept of a book's page, and how far the user is in it. */
data class KeptBook(
    val title: String,
    val authors: String,
    val cover: String?,
    val coverShape: CoverShape,
    val percent: Int?,
) {
    companion object {
        /** A book whose page was not kept. */
        val NoPage = KeptBook("", "", cover = null, coverShape = CoverShape.Portrait, percent = null)
    }
}

data class DeviceStorage(val freeBytes: Long, val totalBytes: Long)

/** The formats the device has whole, with their sizes and what the text is named by, in the order asked. */
internal fun DownloadedBook.complete(): List<DeleteOption> =
    formats.filterNot(FormatRecord::removing).mapNotNull { record ->
        val name = record.textName.takeIf { record.format == DownloadFormat.Text } ?: TextName.Text
        (record.state as? FormatState.Complete)?.let { DeleteOption(record.format, it.bytes, name) }
    }

/** What the screen shows of what the device keeps and is fetching; the messages and the sheet are not its business. */
internal fun downloadsUiState(
    books: List<DownloadedBook>,
    queue: QueueState,
    kept: Map<BookId, KeptBook>,
    storage: DeviceStorage,
): DownloadsUiState {
    val entries = listOfNotNull(queue.active) + queue.waiting
    val sections = books.sortedByDescending { it.lastActivity }.groupBy { it.section(entries) }
    val rows = sections[Section.Downloaded].orEmpty().mapNotNull { it.row(kept[it.bookId]) }
    val updates = sections[Section.Updates].orEmpty()
        .mapNotNull { book -> book.row(kept[book.bookId])?.let { UpdateRow(it, book.updateBytes) } }
    // A queue that waits has no active download: the one stopped is the first that may still run.
    val paused = queue.waiting.firstOrNull { it.failure == null }.takeIf { queue.active == null }
    return DownloadsUiState(
        storage = StorageUi(
            downloadsBytes = books.sumOf { it.bytesOnDevice },
            bookCount = books.count { it.complete().isNotEmpty() },
            freeBytes = storage.freeBytes,
            totalBytes = storage.totalBytes,
            queued = entries.isNotEmpty(),
        ),
        inProgress = entries.map { entry ->
            entry.row(kept[entry.bookId], entry.status(queue.active, queue.waitReason.takeIf { entry === paused }))
        },
        wifi = WifiBannerUi(entries.count { it.failure == null }, queue.leftBytes)
            .takeIf { queue.waitReason == WaitReason.UnmeteredOnly },
        updates = updates,
        books = rows,
        waitingForNetwork = queue.waitReason == WaitReason.NoNetwork,
        loaded = true,
    )
}

/** Where a book with something complete is listed. */
private enum class Section { Updates, Updating, Downloaded }

/**
 * An update that waits for the user is offered, but not of a book the server no longer has, nor of one to
 * delete when it closes; a book that has nothing in the queue but its update is there only; any other is with
 * the downloaded books.
 */
private fun DownloadedBook.section(queue: List<QueueEntry>): Section = when {
    hasUpdate && !isUpdating && !gone && !deleteAtClose -> Section.Updates
    queue.any { it.update && it.bookId == bookId } -> Section.Updating
    else -> Section.Downloaded
}

private val DownloadedBook.note: RowNote?
    get() = when {
        gone -> RowNote.Gone
        formats.any { !it.removing && it.update?.state is FormatState.Complete } -> RowNote.UpdateReady
        else -> null
    }

private fun DownloadedBook.row(kept: KeptBook?): DownloadedRow? {
    val complete = complete().takeIf { it.isNotEmpty() } ?: return null
    return DownloadedRow(
        bookId = bookId,
        title = kept?.shownTitle,
        authors = kept?.authors.orEmpty(),
        cover = kept?.cover,
        formats = complete.map { it.format },
        bytes = complete.sumOf { it.bytes },
        percent = kept?.percent,
        coverShape = kept?.coverShape ?: CoverShape.Portrait,
        note = note,
        textName = complete.firstOrNull { it.format == DownloadFormat.Text }?.textName ?: TextName.Text,
    )
}

/** @param waitReason what stopped this download, for the one the queue stopped. */
private fun QueueEntry.status(active: QueueEntry?, waitReason: WaitReason?): ProgressStatus {
    val failure = failure
    return when {
        failure != null -> ProgressStatus.Failed(failure)
        this === active -> ProgressStatus.Active(receivedBytes, totalBytes)
        waitReason != null -> ProgressStatus.Waiting(waitReason, receivedBytes, totalBytes)
        else -> ProgressStatus.Queued(totalBytes)
    }
}

private fun QueueEntry.row(kept: KeptBook?, status: ProgressStatus): ProgressRow {
    val page = kept ?: KeptBook.NoPage
    return ProgressRow(bookId, page.shownTitle, page.cover, formats, status, page.coverShape, update, textName, pages)
}

/** Null for a page that was not kept, or kept without a title: a row never shows an empty one. */
private val KeptBook.shownTitle: String? get() = title.takeIf { it.isNotBlank() }
