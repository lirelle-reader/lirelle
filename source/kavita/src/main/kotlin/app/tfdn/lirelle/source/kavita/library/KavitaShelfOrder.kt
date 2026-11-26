package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.source.kavita.library.dto.ReadingListItemDto
import java.text.Collator

// How a reading list's books are sorted and narrowed on the device: Kavita gives a list whole, in its own order.

/** Kavita's formats each group of formats stands for: comics are images and archives of them. No audio. */
private val FormatFilter.kavitaFormats: Set<Int>
    get() = when (this) {
        FormatFilter.Epub -> setOf(EpubFormat)
        FormatFilter.Pdf -> setOf(PdfFormat)
        FormatFilter.Comics -> setOf(ImageFormat, ArchiveFormat)
        FormatFilter.Audio -> emptySet()
    }

/** The read state a reading state stands for; none for those Kavita does not keep. */
private val StatusFilter.readState: ReadState?
    get() = when (this) {
        StatusFilter.Unread -> ReadState.Unread
        StatusFilter.Reading -> ReadState.Reading
        StatusFilter.Read -> ReadState.Read
        StatusFilter.WantToRead, StatusFilter.Abandoned -> null
    }

/**
 * The list's books as [request] asks: of its formats and reading states, matching its text, in its sort. The
 * list's own order is "Recently added, newest first", the default (Kavita tells no date an item was added to a
 * list), and turned around is the other way; titles are sorted as a reader would, without case nor accents. The
 * other sorts keep the list's order.
 */
internal fun List<ReadingListItemDto>.arranged(request: BookListRequest, mapper: KavitaMapper): List<BookSummary> {
    val formats = request.filters.formats.flatMap { it.kavitaFormats }.toSet()
    val states = request.filters.statuses.mapNotNull { it.readState }.toSet()
    val text = request.text.orNullIfBlank()
    val kept = filter { request.filters.formats.isEmpty() || it.seriesFormat in formats }
        .map(mapper::readingListItem)
        .filter { request.filters.statuses.isEmpty() || it.readState in states }
        .filter { text == null || it.matches(text) }
    return when (request.sort) {
        BookSort.Title -> {
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            val byTitle = Comparator<BookSummary> { a, b -> collator.compare(a.title, b.title) }
            kept.sortedWith(if (request.direction == SortDirection.Descending) byTitle.reversed() else byTitle)
        }
        BookSort.Added -> if (request.direction == SortDirection.Ascending) kept.reversed() else kept
        BookSort.Author, BookSort.Series, BookSort.Published -> kept
    }
}

/** The book's title, series or one of its authors holds [text], whatever the case. */
private fun BookSummary.matches(text: String): Boolean =
    title.contains(text, ignoreCase = true) ||
        series?.name?.contains(text, ignoreCase = true) == true ||
        authors.any { it.contains(text, ignoreCase = true) }

private const val ImageFormat = 0
private const val ArchiveFormat = 1
private const val EpubFormat = 3
private const val PdfFormat = 4
