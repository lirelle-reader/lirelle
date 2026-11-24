package app.tfdn.lirelle.feature.library.series

import app.tfdn.lirelle.core.downloads.engine.OfferedFormat
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ui.Loadable
import java.time.Instant

// The builders below take the book's id as its text, as the samples of core/testing do.

internal const val Mb = 1_000_000L

/** In these samples, the file of a book's text has the same text as the book's id. */
private fun fileOf(bookId: String) = FileId(bookId)

internal fun text(bookId: String, bytes: Long? = 3 * Mb, estimated: Boolean = false, name: TextName = TextName.Text) =
    OfferedFormat(DownloadFormat.Text, fileOf(bookId), bytes, estimated = estimated, textName = name)

/** A series of [names], one book each, each a text named so and an audiobook. */
internal fun pagedOffers(vararg names: TextName) = names.mapIndexed { index, name ->
    val id = "paged-$index"
    offer(id, offered = listOf(text(id, bytes = 90 * Mb, estimated = true, name = name), audiobook()))
}

internal fun audiobook(bytes: Long? = 400 * Mb) =
    OfferedFormat(DownloadFormat.Audiobook, fileId = null, bytes, chapterCount = 12)

/** A text of its own and an audiobook. */
internal fun textAndAudiobook(bookId: String) = listOf(text(bookId), audiobook())

/** A read-along book: its text comes inside the narration's file, and is about as large as a plain EPUB. */
internal val ReadAlong = listOf(
    text("90", bytes = 2 * Mb, estimated = true),
    OfferedFormat(DownloadFormat.Narration, fileId = FileId("90"), estimatedBytes = 900 * Mb),
)

internal fun offer(
    bookId: String,
    finished: Boolean = false,
    offered: List<OfferedFormat> = textAndAudiobook(bookId),
    kept: Set<DownloadFormat> = emptySet(),
) = SeriesBookOffer(BookId(bookId), finished, offered, kept)

internal const val Gb = 1_000_000_000L

internal val Asked: Instant = Instant.parse("2026-11-10T10:00:00Z")

internal fun record(format: DownloadFormat, state: FormatState) =
    FormatRecord(format, state, files = emptyList(), fileId = null, version = null, Asked)

internal fun downloaded(bookId: String, vararg formats: FormatRecord) =
    DownloadedBook(BookId(bookId), formats.toList(), downloadedAt = Asked, lastOpenedAt = null)

private fun readAlong(bookId: String) = listOf(
    text(bookId, bytes = 2_400_000, estimated = true),
    audiobook(1_040 * Mb),
    OfferedFormat(DownloadFormat.Narration, fileId = fileOf(bookId), estimatedBytes = 1_020 * Mb),
)

/**
 * Seven books, three of them finished. Of the four others: every one has a text, three an audiobook, two a
 * narration.
 */
internal val MockOffers = listOf(
    offer("1", finished = true),
    offer("2", finished = true),
    offer("3", finished = true),
    offer("4", offered = readAlong("4")),
    offer("5", offered = readAlong("5")),
    offer("6", offered = listOf(text("6", bytes = 2_400_000), audiobook(1_040 * Mb))),
    offer("7", offered = listOf(text("7", bytes = 2_400_000))),
)

internal fun sampleSheet(
    offers: Loadable<List<SeriesBookOffer>> = Loadable.Ready(MockOffers),
    chosen: Set<DownloadFormat> = setOf(DownloadFormat.Text),
    freeBytes: Long = 41_200 * Mb,
    waitsForWifi: Boolean = false,
    name: String = "Dawn",
) = SeriesSheetState(name, books = 7, onDevice = 2, offers, chosen, freeBytes, waitsForWifi)
