package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

// How Kavita's values are read into the app's: its pages into a read state, its times, its formats.

private const val FullPercent = 100

/** What Kavita writes for a time that is none: the first day of year 1. */
private const val NoYear = 1

/** Kavita's `MangaFormat` values: images 0, an archive 1, unknown 2, EPUB 3, PDF 4. */
internal const val ImageFormat = 0
internal const val ArchiveFormat = 1
internal const val EpubFormat = 3
internal const val PdfFormat = 4

/** Unread at no page read, read when every page is, reading in between. */
internal fun readStateOf(pagesRead: Int, pages: Int): ReadState = when {
    pagesRead <= 0 -> ReadState.Unread
    pages in 1..pagesRead -> ReadState.Read
    else -> ReadState.Reading
}

/** The pages read over the pages, 0 to 100; none for a book never opened, or one without pages. */
internal fun percentOf(pagesRead: Int, pages: Int): Int? =
    if (pagesRead <= 0 || pages <= 0) null else (pagesRead * FullPercent / pages).coerceIn(0, FullPercent)

/**
 * A time Kavita writes in UTC without a zone (`2026-11-26T07:53:46.7812659`); null when absent, unreadable, or
 * year 1, which Kavita writes for none.
 */
internal fun utcInstant(text: String?): Instant? = timeOf(text)?.toInstant()

/** The year of a date Kavita writes (`1961-07-31T00:00:00`); null when absent, unreadable or none. */
internal fun yearOf(text: String?): Int? = timeOf(text)?.year

/** A time without a zone is UTC, as Kavita's `…Utc` fields are; one with its zone (`Z`, `+01:00`) keeps it. */
private fun timeOf(text: String?): OffsetDateTime? {
    val time = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val read = parsed { LocalDateTime.parse(time).atOffset(ZoneOffset.UTC) } ?: parsed { OffsetDateTime.parse(time) }
    return read?.takeIf { it.year > NoYear }
}

private inline fun parsed(parse: () -> OffsetDateTime): OffsetDateTime? = try {
    parse()
} catch (_: DateTimeParseException) {
    null
}

/** What a book of this Kavita format is read in, by pages; null for one that is not read by pages. */
internal fun pagedKindOf(format: Int?): PagedKind? = when (format) {
    ImageFormat, ArchiveFormat -> PagedKind.Comic
    PdfFormat -> PagedKind.Pdf
    else -> null
}

/** The chapter's format: its first file's, else its own, else its series' ([seriesFormat]). */
internal fun ChapterDto.formatOr(seriesFormat: Int?): Int? = files.firstOrNull()?.format ?: format ?: seriesFormat
