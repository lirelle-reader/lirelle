package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.QueueEntry
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.TextName
import java.time.Instant

/** Without the formats being deleted, and without the books that have nothing else. */
internal fun List<DownloadedBook>.visible(): List<DownloadedBook> = mapNotNull { it.visible() }

internal fun DownloadedBook.visible(): DownloadedBook? =
    copy(formats = formats.filterNot { it.removing }).takeIf { it.formats.isNotEmpty() }

/**
 * What who asks for formats knows of them before the server is asked what they are made of.
 *
 * @property sizes about what each format will take, in bytes.
 * @property textName what the text is named by, from its file's format.
 */
internal class Known(val sizes: Map<DownloadFormat, Long> = emptyMap(), val textName: TextName = TextName.Text)

/**
 * The record of a book once [formats] are asked at [now]: the narration takes the text with it; what is complete
 * or on its way stays as it is, what failed is queued again, what was being deleted is kept after all. What is
 * queued keeps what was [known] of it: about what it will take, and, for a text asked for the first time, its
 * name. A book the server no longer has stays so; one that was to be deleted when it closes is wanted again,
 * and is not any more.
 */
internal fun asked(
    book: DownloadedBook?,
    bookId: BookId,
    formats: Set<DownloadFormat>,
    now: Instant,
    known: Known = Known(),
): DownloadedBook {
    val wanted = if (DownloadFormat.Narration in formats) formats + DownloadFormat.Text else formats
    val before = book?.formats.orEmpty()
    val kept = before.map { record ->
        when {
            record.format !in wanted -> record
            record.state is FormatState.Failed ->
                record.queuedAgain(now, known.sizes[record.format] ?: record.expectedBytes)
            else -> record.copy(removing = false)
        }
    }
    val added = DownloadFormat.entries
        .filter { format -> format in wanted && before.none { it.format == format } }
        .map { queued(it, now, known.sizes[it], known.textName.takeIf { _ -> it == DownloadFormat.Text }) }
    return DownloadedBook(
        bookId = bookId,
        formats = kept + added,
        downloadedAt = book?.downloadedAt ?: now,
        lastOpenedAt = book?.lastOpenedAt,
        gone = book?.gone ?: false,
        deleteAtClose = false,
    )
}

/**
 * @param expectedBytes about what the format will take, when the page that asked for it knew.
 * @param textName what its text is named by, as who asked for it said or as it was planned before; null when
 *   nobody said: it is the text until its plan names it.
 */
internal fun queued(
    format: DownloadFormat,
    now: Instant,
    expectedBytes: Long? = null,
    textName: TextName? = null,
) = FormatRecord(
    format = format,
    state = FormatState.Queued,
    files = emptyList(),
    fileId = null,
    version = null,
    askedAt = now,
    expectedBytes = expectedBytes,
    textName = textName ?: TextName.Text,
)

/**
 * A format that failed, queued again at [now]. One made of pages goes on from the pages the device has, each
 * checked when it arrived: it keeps what it is made of and its count, so that its run finds them in their
 * place and fetches the missing ones only. Any other starts again with nothing.
 */
internal fun FormatRecord.queuedAgain(now: Instant, expectedBytes: Long? = this.expectedBytes): FormatRecord =
    if (pages == null) {
        queued(format, now, expectedBytes, textName)
    } else {
        copy(
            state = FormatState.Queued,
            askedAt = now,
            anyNetwork = false,
            removing = false,
            expectedBytes = expectedBytes,
            update = null,
        )
    }

/** What is not complete, and the updates asked and not fetched, as the queue shows them. */
internal fun queueState(books: List<DownloadedBook>): QueueState {
    val ordered = queueOrder(books)
    val active = ordered.firstOrNull { book -> book.work.any { it is FormatState.Downloading } }
    val pending = ordered.flatMap { book -> book.work.filter { it !is FormatState.Failed } }
    return QueueState(
        active = active?.entry(),
        waiting = ordered.filter { it !== active }.map { it.entry() },
        waitReason = pending.firstNotNullOfOrNull { (it as? FormatState.Waiting)?.reason }.takeIf { active == null },
        leftBytes = pending.mapNotNull { it.leftBytes }.takeIf { it.isNotEmpty() }?.sum(),
    )
}

/** The formats the queue has something to do for, in the order they are shown. */
private val DownloadedBook.unfinished: List<FormatRecord>
    get() = formats.filter { it.work != null }.sortedBy { it.format }

/** Where each of them is. */
private val DownloadedBook.work: List<FormatState> get() = unfinished.mapNotNull { it.work }

private fun DownloadedBook.entry(): QueueEntry {
    val records = unfinished
    val states = work
    val totals = states.map { it.totalBytes }
    val text = records.firstOrNull { it.format == DownloadFormat.Text }
    return QueueEntry(
        bookId = bookId,
        formats = records.map { it.format },
        receivedBytes = states.sumOf { it.received },
        totalBytes = totals.filterNotNull().sum().takeIf { null !in totals },
        failure = states.firstNotNullOfOrNull { (it as? FormatState.Failed)?.reason },
        update = records.all { it.state is FormatState.Complete },
        // Of what is being fetched: the text itself, or the update of a text that is kept.
        pages = text?.run { if (state is FormatState.Complete) update?.pages else pages },
        textName = text?.textName ?: TextName.Text,
    )
}

private val FormatState.totalBytes: Long?
    get() = when (this) {
        is FormatState.Downloading -> totalBytes
        is FormatState.Waiting -> totalBytes
        else -> null
    }

private val FormatState.leftBytes: Long?
    get() = when (this) {
        is FormatState.Downloading -> totalBytes?.minus(receivedBytes)
        is FormatState.Waiting -> totalBytes?.minus(receivedBytes)
        else -> null
    }?.coerceAtLeast(0)
