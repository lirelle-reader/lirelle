package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.model.AuthorRef
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.RelatedBook
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.ReadingListItemDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesMetadataDto

/** The series a book is shown in: its Kavita id and its name. */
internal data class SeriesRefInfo(val id: Int, val name: String)

/**
 * Turns Kavita's answers into the app's models, against the profile's [serverUrl], so the screens never build a
 * URL; the books Kavita knows no title of take one of [titles]. A cover is one of the server's image routes, given
 * without a key: the authenticated client adds it to the address when it asks. A book's people are its writers
 * and pencillers, Kavita having no artist role; its read state and progress come from its pages. The server's
 * paths are never read.
 */
internal class KavitaMapper(private val serverUrl: String, private val titles: KavitaTitles) {

    /**
     * Kavita's title of the book, else one of the app's: a special's own title, a volume's "Tome N", a chapter's
     * "Chapitre N". A special has no file name to stand for its title: the server's paths are not read. Another
     * book's Kavita `title` is a number or English words ("Book Dune"), never shown: without a number it is untitled.
     */
    fun title(book: KavitaBook): String {
        val chapter = book.chapter
        chapter.titleName.orNullIfBlank()?.let { return it }
        if (book.special) {
            return chapter.title.orNullIfBlank()
                ?: titles.numbered(chapter.range.orIndex() ?: chapter.number.orIndex(), titles::chapter)
        }
        val numbered = if (book.item is KavitaItem.Volume) titles::volume else titles::chapter
        return titles.numbered(book.index, numbered)
    }

    /**
     * A book in a list. [series] is the one it is shown in, null for a lone book; [seriesFormat] the series'
     * format, which tells what the book is read in when neither its file nor its chapter says.
     */
    fun summary(book: KavitaBook, series: SeriesRefInfo?, seriesFormat: Int? = null): BookSummary {
        val chapter = book.chapter
        val title = title(book)
        return BookSummary(
            id = book.item.bookId,
            title = title,
            authors = authorNames(chapter.writers, chapter.pencillers),
            series = seriesRefOf(book, title, series),
            coverUrl = cover(book.item),
            coverShape = CoverShape.Portrait,
            media = MediaProfile.TextOnly,
            readState = readStateOf(chapter.pagesRead, chapter.pages),
            progressPercent = percentOf(chapter.pagesRead, chapter.pages),
            lastActivityAt = utcInstant(chapter.lastReadingProgressUtc),
            pagedKind = pagedKindOf(chapter.formatOr(seriesFormat)),
        )
    }

    /**
     * A book's page. What its chapter does not say — summary, genres, publisher, language — the series' [metadata]
     * does, when it came. An EPUB's Kavita pages are its spine items, a number that means nothing to a reader: the
     * page count is given for a book read by pages only. No file is offered yet: reading a Kavita book is #51's.
     * A [lone] book, the only special of its series, has no series line.
     */
    fun detail(book: KavitaBook, series: SeriesDto, metadata: SeriesMetadataDto?, lone: Boolean = false): BookDetail {
        val chapter = book.chapter
        val title = title(book)
        return BookDetail(
            id = book.item.bookId,
            title = title,
            subtitle = null,
            // A Kavita person is asked by name, unique on the server: the name stands for the id.
            authors = authorNames(chapter.writers, chapter.pencillers).map { AuthorRef(AuthorId(it), it) },
            series = seriesRefOf(book, title, SeriesRefInfo(series.id, series.name).takeUnless { lone }),
            coverUrl = cover(book.item),
            coverShape = CoverShape.Portrait,
            media = MediaProfile.TextOnly,
            description = chapter.summary.orNullIfBlank() ?: metadata?.summary.orNullIfBlank(),
            genres = chapter.genres.titles().ifEmpty { metadata?.genres.orEmpty().titles() },
            rating = null,
            pageCount = chapter.pages.takeIf { it > 0 && pagedKindOf(chapter.formatOr(series.format)) != null },
            publishedYear = yearOf(chapter.releaseDate),
            publisher = chapter.publishers.firstName() ?: metadata?.publishers.orEmpty().firstName(),
            language = chapter.language.orNullIfBlank() ?: metadata?.language.orNullIfBlank(),
            isbn = chapter.isbn.orNullIfBlank(),
            libraryName = series.libraryName.orEmpty(),
            addedAt = utcInstant(chapter.createdUtc),
            narrators = emptyList(),
            collections = emptyList(),
            files = emptyList(),
            readState = readStateOf(chapter.pagesRead, chapter.pages),
            startedAt = null,
        )
    }

    /** Kavita keeps the pages read of a chapter and when they last moved: no session, no time spent. */
    fun reading(chapter: ChapterDto) = BookReading(
        progressPercent = percentOf(chapter.pagesRead, chapter.pages),
        lastReadAt = utcInstant(chapter.lastReadingProgressUtc),
        totalSeconds = 0,
        sessionCount = 0,
        page = chapter.pagesRead.takeIf { it > 0 },
    )

    /** Another book of the series of the one shown, with its place in it. */
    fun related(book: KavitaBook) = RelatedBook(
        id = book.item.bookId,
        title = title(book),
        authors = authorNames(book.chapter.writers, book.chapter.pencillers),
        coverUrl = cover(book.item),
        coverShape = CoverShape.Portrait,
        seriesIndex = book.index,
        readState = readStateOf(book.chapter.pagesRead, book.chapter.pages),
    )

    /**
     * A series in a list. Kavita counts chapters, not the app's books: the counts stay at 0 until the series'
     * books are known ([seriesWithCounts]).
     */
    fun series(dto: SeriesDto) = Series(
        id = dto.id.asSeriesId(),
        name = dto.name,
        authors = emptyList(),
        bookCount = 0,
        readCount = 0,
        coverUrls = listOf(seriesCover(dto.id)),
        lastAddedAt = utcInstant(dto.lastChapterAddedUtc),
    )

    /** A series whose [books] are known: they are what it counts. */
    fun seriesWithCounts(dto: SeriesDto, books: List<BookSummary>) = series(dto).copy(
        bookCount = books.size,
        readCount = books.count { it.readState == ReadState.Read },
    )

    /**
     * A reading list's item, from what the item says alone: the default chapter of its volume is the volume, any
     * other chapter itself. Its people are named on Kavita 0.9 only.
     */
    fun readingListItem(item: ReadingListItemDto): BookSummary {
        // A default chapter is its volume's book, unless that volume is the loose-leaf one of the chapters outside
        // a volume: its id would stand for all of them.
        val looseLeaf = item.volumeNumber?.trim()?.toFloatOrNull()?.isSentinelVolume() == true
        val volume = item.chapterNumber == DefaultChapter && !item.isSpecial && !looseLeaf
        val kavitaItem = if (volume) KavitaItem.Volume(item.volumeId) else KavitaItem.Chapter(item.chapterId)
        val index = when {
            volume -> item.volumeNumber.orNullIfBlank()
            item.isSpecial -> null
            else -> item.chapterNumber.orIndex()
        }
        // Kavita's other title of a chapter is its own English words ("Issue #1"): a special's alone is read.
        val title = item.chapterTitleName.orNullIfBlank()
            ?: item.title.orNullIfBlank()?.takeIf { item.isSpecial }
            ?: titles.numbered(index, if (volume) titles::volume else titles::chapter)
        val people = item.chapter
        val authors = listOfNotNull(people?.writerName.orNullIfBlank(), people?.pencillerName.orNullIfBlank())
        val seriesName = item.seriesName.orNullIfBlank()
        val dropSeries = seriesName == null || item.isSpecial && title.equals(seriesName, ignoreCase = true)
        return BookSummary(
            id = kavitaItem.bookId,
            title = title,
            authors = authors.distinct(),
            series = if (dropSeries) null else SeriesRef(item.seriesId.asSeriesId(), seriesName, index),
            coverUrl = cover(kavitaItem),
            coverShape = CoverShape.Portrait,
            media = MediaProfile.TextOnly,
            readState = readStateOf(item.pagesRead, item.pagesTotal),
            progressPercent = percentOf(item.pagesRead, item.pagesTotal),
            lastActivityAt = utcInstant(item.lastReadingProgressUtc),
            pagedKind = pagedKindOf(item.seriesFormat),
        )
    }

    private fun cover(item: KavitaItem): String = when (item) {
        is KavitaItem.Volume -> "$serverUrl/api/Image/volume-cover?volumeId=${item.id}"
        is KavitaItem.Chapter -> "$serverUrl/api/Image/chapter-cover?chapterId=${item.id}"
    }

    private fun seriesCover(id: Int): String = "$serverUrl/api/Image/series-cover?seriesId=$id"
}

/**
 * The series line of a book: none for a lone book ([series] null), nor for a special named as its series, which
 * would only say its title again.
 */
private fun seriesRefOf(book: KavitaBook, title: String, series: SeriesRefInfo?): SeriesRef? {
    if (series == null) return null
    val name = series.name.orNullIfBlank() ?: return null
    if (book.special && title.equals(name, ignoreCase = true)) return null
    return SeriesRef(series.id.asSeriesId(), name, book.index)
}

/** The title [numbered] gives a book of this [index]; untitled for a book without one. */
private fun KavitaTitles.numbered(index: String?, numbered: (String) -> String): String =
    index.orNullIfBlank()?.let(numbered) ?: untitled()
