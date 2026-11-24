package app.tfdn.lirelle.feature.downloads

import app.tfdn.lirelle.core.downloads.QueueEntry
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.CoverShape
import java.time.Instant

// The builders below take the book's id as its text, as the samples of core/testing do.

internal const val Mb = 1_000_000L
internal const val Gb = 1_000_000_000L

internal val Day: Instant = Instant.parse("2026-11-01T10:00:00Z")

internal fun record(format: DownloadFormat, state: FormatState = FormatState.Complete(4 * Mb)) = FormatRecord(
    format = format,
    state = state,
    files = emptyList(),
    fileId = null,
    version = null,
    askedAt = Day,
)

/** A book whose [formats] are complete, each with its size. */
internal fun downloaded(
    bookId: String,
    vararg formats: Pair<DownloadFormat, Long> = arrayOf(DownloadFormat.Text to 4 * Mb),
    downloadedAt: Instant = Day,
    lastOpenedAt: Instant? = null,
) = DownloadedBook(
    bookId = BookId(bookId),
    formats = formats.map { (format, bytes) -> record(format, FormatState.Complete(bytes)) },
    downloadedAt = downloadedAt,
    lastOpenedAt = lastOpenedAt,
)

/** A book nothing of which is complete yet. */
internal fun fetching(bookId: String, format: DownloadFormat = DownloadFormat.Audiobook) =
    DownloadedBook(BookId(bookId), listOf(record(format, FormatState.Queued)), downloadedAt = Day, lastOpenedAt = null)

internal fun entry(
    bookId: String,
    vararg formats: DownloadFormat = arrayOf(DownloadFormat.Audiobook),
    receivedBytes: Long = 0,
    totalBytes: Long? = 412 * Mb,
) = QueueEntry(BookId(bookId), formats.toList(), receivedBytes, totalBytes, failure = null)

/** The queue's entry of an album of [total] pages, [kept] of them on the device, its bytes an estimate. */
internal fun albumEntry(bookId: String, kept: Int, total: Int, name: TextName = TextName.Cbz, update: Boolean = false) =
    QueueEntry(
        bookId = BookId(bookId),
        formats = listOf(DownloadFormat.Text),
        receivedBytes = kept * Mb,
        totalBytes = 9 * Mb,
        failure = null,
        update = update,
        pages = PageCount(kept, total),
        textName = name,
    )

/** A downloaded book whose text has a newer version on the server: [state] is where its update is. */
internal fun updatable(bookId: String, bytes: Long = 4 * Mb, state: FormatState? = null): DownloadedBook =
    downloaded(bookId).let { book ->
        book.copy(formats = book.formats.map { it.copy(update = FormatUpdate("v2", emptyList(), bytes, state)) })
    }

/** The queue's entry of a book that has nothing but an update there. */
internal fun updateEntry(bookId: String, receivedBytes: Long = 0, totalBytes: Long? = 4 * Mb) =
    QueueEntry(BookId(bookId), listOf(DownloadFormat.Text), receivedBytes, totalBytes, failure = null, update = true)

internal val IdleQueue = QueueState(active = null, waiting = emptyList(), waitReason = null, leftBytes = null)

internal fun keptBook(title: String, authors: String = "Ursula Writer", percent: Int? = null) =
    KeptBook(title = title, authors = authors, cover = null, coverShape = CoverShape.Portrait, percent = percent)

internal val Storage = DeviceStorage(freeBytes = 41 * Gb, totalBytes = 128 * Gb)
