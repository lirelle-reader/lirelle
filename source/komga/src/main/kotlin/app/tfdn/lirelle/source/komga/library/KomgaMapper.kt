package app.tfdn.lirelle.source.komga.library

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ids.ShelfId
import app.tfdn.lirelle.core.library.model.AuthorRef
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PageDirection
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.RelatedBook
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.library.model.Shelf
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.source.komga.network.dto.BookDto
import app.tfdn.lirelle.source.komga.network.dto.KomgaAuthorDto
import app.tfdn.lirelle.source.komga.network.dto.ReadListDto
import app.tfdn.lirelle.source.komga.network.dto.ReadProgressDto
import app.tfdn.lirelle.source.komga.network.dto.SeriesDto
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

private const val FullPercent = 100

/**
 * Turns Komga's answers into the app's models, against the profile's [serverUrl], so the screens never build a URL.
 * Komga's ids are its own text, used as they are. A cover is the item's thumbnail under one URL, unversioned: Komga
 * keeps an item's `lastModified` when its thumbnail changes, so the cover loader checks it with its `ETag`, as the
 * server's cache headers ask. A comic is offered to the comics reader, as one file read by pages; Komga's PDFs and
 * EPUBs are offered nothing yet.
 */
internal class KomgaMapper(private val serverUrl: String) {

    fun summary(dto: BookDto) = BookSummary(
        id = BookId(dto.id),
        title = titleOf(dto),
        authors = namesOf(dto.metadata.authors),
        series = seriesRefOf(dto),
        coverUrl = thumbnail("books", dto.id),
        coverShape = CoverShape.Portrait,
        media = MediaProfile.TextOnly,
        readState = readStateOf(dto.readProgress),
        progressPercent = percentOf(dto.readProgress, dto.media.pagesCount),
        lastActivityAt = instant(dto.readProgress?.readDate),
        // Ready or not: a card that opened the reader of EPUBs would say the book has none.
        pagedKind = PagedKind.Comic.takeIf { readsAsPictures(dto) },
    )

    /**
     * [series] is the book's series, whose genres, publisher, language and reading direction are the book's; null
     * when unknown.
     */
    fun detail(dto: BookDto, series: SeriesDto?) = BookDetail(
        id = BookId(dto.id),
        title = titleOf(dto),
        subtitle = null,
        // Komga has no author page: the name is all there is, and stands for the id.
        authors = namesOf(dto.metadata.authors).map { AuthorRef(AuthorId(it), it) },
        series = seriesRefOf(dto),
        coverUrl = thumbnail("books", dto.id),
        coverShape = CoverShape.Portrait,
        media = MediaProfile.TextOnly,
        description = dto.metadata.summary.orNullIfBlank(),
        genres = series?.metadata?.genres.orEmpty().filter { it.isNotBlank() },
        rating = null,
        pageCount = dto.media.pagesCount.takeIf { it > 0 },
        publishedYear = yearOf(dto.metadata.releaseDate),
        publisher = series?.metadata?.publisher.orNullIfBlank(),
        language = series?.metadata?.language.orNullIfBlank(),
        isbn = dto.metadata.isbn.orNullIfBlank(),
        libraryName = "",
        addedAt = instant(dto.created),
        narrators = emptyList(),
        collections = emptyList(),
        files = listOfNotNull(comicFileOf(dto)),
        readState = readStateOf(dto.readProgress),
        startedAt = instant(dto.readProgress?.created),
        pageDirection = pageDirectionOf(series?.metadata?.readingDirection),
        // A kept copy then stands for the book, which is neither gone nor updated.
        filesUnavailable = filesUnavailable(dto),
    )

    /** Komga keeps one reading per book: no session, no time spent. */
    fun reading(dto: BookDto): BookReading {
        val progress = dto.readProgress
        val readAt = instant(progress?.readDate)
        return BookReading(
            progressPercent = percentOf(progress, dto.media.pagesCount),
            lastReadAt = readAt,
            totalSeconds = 0,
            sessionCount = 0,
            page = progress?.page,
            positionAt = instant(progress?.lastModified) ?: readAt,
        )
    }

    /** A book of the same series as the one shown, with its volume number. */
    fun related(dto: BookDto) = RelatedBook(
        id = BookId(dto.id),
        title = titleOf(dto),
        authors = namesOf(dto.metadata.authors),
        coverUrl = thumbnail("books", dto.id),
        coverShape = CoverShape.Portrait,
        seriesIndex = dto.metadata.number.orNullIfBlank(),
        readState = readStateOf(dto.readProgress),
    )

    fun series(dto: SeriesDto) = Series(
        id = SeriesId(dto.id),
        name = dto.metadata.title.orNullIfBlank() ?: dto.name,
        authors = namesOf(dto.booksMetadata.authors),
        bookCount = dto.booksCount,
        readCount = dto.booksReadCount,
        coverUrls = listOf(thumbnail("series", dto.id)),
        lastAddedAt = instant(dto.lastModified),
    )

    fun readList(dto: ReadListDto) = Shelf(ShelfKind.Collection, ShelfId(dto.id), dto.name, dto.bookIds.size)

    private fun thumbnail(kind: String, id: String): String = "$serverUrl/api/v1/$kind/$id/thumbnail"
}

/** The metadata's title, else the file's name. */
internal fun titleOf(dto: BookDto): String = dto.metadata.title.orNullIfBlank() ?: dto.name

/** Each person once, in Komga's order, whatever their parts in the book. */
private fun namesOf(authors: List<KomgaAuthorDto>): List<String> =
    authors.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()

/** A one-shot is a series of its own in Komga, which the app does not show as a series. */
private fun seriesRefOf(dto: BookDto): SeriesRef? {
    val name = dto.seriesTitle.orNullIfBlank()
    if (dto.oneshot || name == null) return null
    return SeriesRef(dto.seriesId?.let(::SeriesId), name, dto.metadata.number.orNullIfBlank())
}

/**
 * The way a series' pages turn: a manga's right to left. Vertical and webtoon series turn left to right until the
 * reader scrolls. None set, or a way the app does not know, says nothing.
 */
private fun pageDirectionOf(readingDirection: String?): PageDirection? = when (readingDirection?.trim()) {
    "RIGHT_TO_LEFT" -> PageDirection.RightToLeft
    "LEFT_TO_RIGHT", "VERTICAL", "WEBTOON" -> PageDirection.LeftToRight
    else -> null
}

private fun readStateOf(progress: ReadProgressDto?): ReadState = when {
    progress == null -> ReadState.Unread
    progress.completed -> ReadState.Read
    else -> ReadState.Reading
}

/** The page reached over the page count; a finished book is at 100 %, one never opened has none. */
private fun percentOf(progress: ReadProgressDto?, pages: Int): Int? = when {
    progress == null -> null
    progress.completed -> FullPercent
    pages <= 0 -> 0
    else -> (progress.page * FullPercent.toDouble() / pages).roundToInt().coerceIn(0, FullPercent)
}

private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** A time Komga sends (UTC, `Z`); null when absent or unreadable. */
private fun instant(text: String?): Instant? = text?.let {
    try {
        Instant.parse(it)
    } catch (_: DateTimeParseException) {
        null
    }
}

/** The year of a release date (`2019-04-12`); null when absent or unreadable. */
private fun yearOf(date: String?): Int? = date?.let {
    try {
        LocalDate.parse(it).year
    } catch (_: DateTimeParseException) {
        null
    }
}
