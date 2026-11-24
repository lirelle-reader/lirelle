package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.engine.OfferedFormat
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.net.NetworkState
import java.time.Instant

internal const val Mb = 1_000_000L
internal const val Gb = 1_000_000_000L

internal val Asked: Instant = Instant.parse("2026-11-10T10:00:00Z")
internal val Wifi = NetworkState(online = true, unmetered = true)
internal val Mobile = NetworkState(online = true, unmetered = false)
internal val NoNetwork = NetworkState(online = false, unmetered = false)
internal val EmptyQueue = QueueState(active = null, waiting = emptyList(), waitReason = null, leftBytes = null)

/** A book with a text of its own and an audiobook. */
internal val TextAndAudiobook = listOf(
    OfferedFormat(DownloadFormat.Text, fileId = FileId("1"), estimatedBytes = 3 * Mb),
    OfferedFormat(DownloadFormat.Audiobook, fileId = null, estimatedBytes = 412 * Mb, chapterCount = 139),
)

/** A read-along book with an audiobook: the text comes inside the narration's file, without a size of its own. */
internal val ThreeFormats = listOf(
    OfferedFormat(DownloadFormat.Text, fileId = FileId("1"), estimatedBytes = null),
    OfferedFormat(DownloadFormat.Audiobook, fileId = null, estimatedBytes = 1_070 * Mb, chapterCount = 139),
    OfferedFormat(DownloadFormat.Narration, fileId = FileId("1"), estimatedBytes = 1_080 * Mb),
)

/** The text of a read-along file, about as large as the plain EPUB of the same book. */
internal val EstimatedText =
    OfferedFormat(DownloadFormat.Text, fileId = FileId("1"), estimatedBytes = 3 * Mb, estimated = true)

/** A comic, whose size is that of its file: about what its pages take. */
internal val Comic = listOf(
    OfferedFormat(
        DownloadFormat.Text,
        fileId = FileId("31"),
        estimatedBytes = 9 * Mb,
        estimated = true,
        textName = TextName.Cbz,
    ),
)

/** A PDF and an audiobook. */
internal val PdfAndAudiobook = listOf(
    OfferedFormat(DownloadFormat.Text, fileId = FileId("32"), estimatedBytes = 9 * Mb, textName = TextName.Pdf),
    TextAndAudiobook[1],
)

/** The pages of an album of [total] pages. */
internal fun pages(total: Int) = List(total) { index ->
    val source = FileSource.Page(FileId("12"), PagedKind.Comic, index)
    PlannedFile("$index", source, "v1", null, resumable = false, FileKind.Page)
}

/** The text of an album of 48 pages, [kept] of them on the device, where [state] says. */
internal fun album(state: FormatState, kept: Int, name: TextName = TextName.Cbz) =
    record(DownloadFormat.Text, state)
        .copy(files = pages(total = 48), keptPages = kept, textName = name, expectedBytes = 9 * Mb)

/** A kept album whose newer version, of 48 pages too, has [kept] of them fetched, where [state] says. */
internal fun albumUpdated(state: FormatState?, kept: Int) = album(FormatState.Complete(8 * Mb), kept = 48)
    .copy(update = FormatUpdate("new", pages(total = 48), bytes = 9 * Mb, state = state, keptPages = kept))

internal fun record(format: DownloadFormat, state: FormatState, removing: Boolean = false) =
    FormatRecord(format, state, files = emptyList(), fileId = null, version = null, Asked, removing = removing)

internal fun complete(format: DownloadFormat, bytes: Long = 3 * Mb) = record(format, FormatState.Complete(bytes))

/** A whole format whose newer version, of [bytes] to fetch, is where [state] says: null while it waits for the user. */
internal fun updatable(format: DownloadFormat, state: FormatState? = null, bytes: Long = 1_080 * Mb) =
    complete(format).copy(update = FormatUpdate(version = "new", files = emptyList(), bytes = bytes, state = state))

internal fun downloaded(vararg formats: FormatRecord, bookId: String = "12") =
    DownloadedBook(BookId(bookId), formats.toList(), downloadedAt = Asked, lastOpenedAt = null)

internal fun situation(
    offered: List<OfferedFormat> = TextAndAudiobook,
    book: DownloadedBook? = null,
    queue: QueueState = EmptyQueue,
    network: NetworkState = Wifi,
    canDownload: Boolean = true,
) = DownloadSituation(offered, book, queue, network, canDownload)
