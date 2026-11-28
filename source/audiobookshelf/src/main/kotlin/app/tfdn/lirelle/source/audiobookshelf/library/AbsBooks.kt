package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.AuthorRef
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMetadataDto
import java.time.Instant

/**
 * Turns Audiobookshelf's items into the app's books, against the profile's server at [serverUrl], so the screens
 * never build a URL. An item is a book: its id the item's UUID, its title as the server gives it (some keep their
 * folder's "[fr] (1998)"), its read state and progress from the account's [AbsReadings]. Its cover is drawn in the
 * shape its formats say ([AbsFormats.coverShape]): square for an audiobook alone. What the server does not say is
 * left empty; its paths are never read.
 */
internal class AbsBooks(serverUrl: String) {

    private val covers = AbsCovers(serverUrl)

    /** The cover of an item in a list; null for an item without one. */
    fun cover(item: AbsItemDto): String? = covers.tile(item)

    /** An item in a list. */
    fun summary(item: AbsItemDto, readings: AbsReadings): BookSummary {
        val metadata = item.media.metadata
        val reading = readings.of(item.id)
        return BookSummary(
            id = item.id.asBookId(),
            title = metadata.title.orEmpty(),
            authors = metadata.authorNames(),
            series = metadata.seriesRef(),
            coverUrl = covers.tile(item),
            coverShape = AbsFormats.coverShape(item),
            media = AbsFormats.media(item),
            readState = readings.state(item.id),
            progressPercent = reading?.percent,
            lastActivityAt = reading?.lastUpdate,
            pagedKind = AbsFormats.pagedKind(item),
        )
    }

    /**
     * A whole item (`GET api/items/:id?expanded=1`) as a book's page, in its library [libraryName]. The description
     * is the server's HTML, which the page shows as simple text with its paragraphs. Its files are not listed yet:
     * reading and listening come with #69 and #68.
     */
    fun detail(
        item: AbsItemDto,
        readings: AbsReadings,
        libraryName: String,
    ): BookDetail {
        val metadata = item.media.metadata
        return BookDetail(
            id = item.id.asBookId(),
            title = metadata.title.orEmpty(),
            subtitle = metadata.subtitle.orNullIfBlank(),
            authors = metadata.authors.map { AuthorRef(it.id.asAuthorId(), it.name) },
            series = metadata.seriesRef(),
            coverUrl = covers.page(item),
            coverShape = AbsFormats.coverShape(item),
            media = AbsFormats.media(item),
            description = metadata.description.orNullIfBlank() ?: metadata.descriptionPlain.orNullIfBlank(),
            // Trimmed, as the genres list shows them: Audiobookshelf keeps a genre as it was typed.
            genres = metadata.genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            rating = null,
            pageCount = null,
            publishedYear = yearOf(metadata.publishedYear),
            publisher = metadata.publisher.orNullIfBlank(),
            language = metadata.language.orNullIfBlank(),
            isbn = metadata.isbn.orNullIfBlank(),
            libraryName = libraryName,
            addedAt = item.addedAt?.let(Instant::ofEpochMilli),
            narrators = metadata.narrators.ifEmpty { metadata.narratorName.names() },
            collections = emptyList(),
            files = emptyList(),
            readState = readings.state(item.id),
            startedAt = readings.of(item.id)?.startedAt,
        )
    }
}

/** The book's authors: a whole item's linked ones, else the names a list's item joins in one text. */
internal fun AbsMetadataDto.authorNames(): List<String> = authors.map { it.name }.ifEmpty { authorName.names() }

/**
 * The book's series: the first a whole item, or a series' list, links, with its place; else the first a list's item
 * names in [AbsMetadataDto.seriesName], whose id is not known there.
 */
private fun AbsMetadataDto.seriesRef(): SeriesRef? {
    series.firstOrNull { it.name.isNotBlank() }?.let {
        return SeriesRef(it.id.asSeriesId(), it.name, it.sequence.orNullIfBlank())
    }
    val named = seriesName.orNullIfBlank() ?: return null
    val numbered = NumberedSeries.matchEntire(named)
    return if (numbered != null) {
        SeriesRef(null, numbered.groupValues[1], numbered.groupValues[2])
    } else {
        SeriesRef(null, named, null)
    }
}

/**
 * A series named with its place, as a list's item writes it: "Hitchhiker's Guide to the Galaxy #0.5", and maybe
 * more series after a ", ". A place has no space, no comma and no "#".
 */
private val NumberedSeries = Regex("""^(.+?) #([^\s,#]+)(?:, .*)?$""")

/** The names a list's item joins with ", ", each once; none for no text. */
private fun String?.names(): List<String> =
    orEmpty().split(", ").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** The year of what the server keeps as a book's year: "1979", "1992-01-01"; null for anything else. */
private fun yearOf(text: String?): Int? = text?.let { Year.find(it.trim()) }?.value?.toIntOrNull()

private val Year = Regex("""^\d{4}""")

private fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
