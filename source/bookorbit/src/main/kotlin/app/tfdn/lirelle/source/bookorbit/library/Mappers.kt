package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorRef
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.RelatedBook
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.library.model.Shelf
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.fileToRead
import app.tfdn.lirelle.core.library.model.isAudioFormat
import app.tfdn.lirelle.core.library.model.mediaProfileOf
import app.tfdn.lirelle.core.library.model.pagedKindOf
import app.tfdn.lirelle.source.bookorbit.library.dto.AuthorSummaryDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookCardDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookDetailDto
import app.tfdn.lirelle.source.bookorbit.library.dto.CollectionDto
import app.tfdn.lirelle.source.bookorbit.library.dto.ReadStatusDto
import app.tfdn.lirelle.source.bookorbit.library.dto.ReadingProgressListDto
import app.tfdn.lirelle.source.bookorbit.library.dto.RelatedBookDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SeriesInfoDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SeriesSummaryDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SessionStatsDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SmartScopeDto
import app.tfdn.lirelle.source.bookorbit.network.authorIdOf
import app.tfdn.lirelle.source.bookorbit.network.bookIdOf
import app.tfdn.lirelle.source.bookorbit.network.seriesIdOf
import app.tfdn.lirelle.source.bookorbit.network.shelfIdOf
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

private const val SeriesTileCovers = 3
private const val FullPercent = 100
private const val Thumbnail = "thumbnail"
private const val Cover = "cover"

/**
 * Turns wire models into models. Cover and portrait URLs are made absolute here, against
 * the session's server, so the UI never builds a URL.
 */
internal class LibraryMapper(private val serverUrl: String) {
    private val audioCovers = AudioCovers(serverUrl)

    fun summary(dto: BookCardDto) = BookSummary(
        id = bookIdOf(dto.id),
        title = dto.title.orEmpty(),
        authors = dto.authors,
        series = seriesRef(dto.seriesId, dto.seriesName, dto.seriesIndex),
        coverUrl = if (dto.hasCover) imageUrl(dto.id, Thumbnail, dto.coverVersion) else null,
        coverShape = shapeOf(dto.coverAspectRatio),
        media = mediaProfileOf(dto.files.map(::mediaFile)),
        readState = readStateOf(dto.readStatus),
        progressPercent = dto.readingProgress?.let(::percent),
        lastActivityAt = instant(dto.readStatus?.updatedAt),
        hasAudioFiles = dto.files.any { isContentRole(it.role) && isAudioFormat(it.format) },
        pagedKind = pagedKindOf(
            fileToRead(dto.files.filter { isContentRole(it.role) }, { it.format }, { isPrimaryRole(it.role) })?.format,
        ),
    )

    fun related(dto: RelatedBookDto) = RelatedBook(
        id = bookIdOf(dto.id),
        title = dto.title.orEmpty(),
        authors = dto.authors,
        coverUrl = if (dto.hasCover) imageUrl(dto.id, Thumbnail, dto.coverVersion) else null,
        coverShape = shapeOf(dto.coverAspectRatio),
        seriesIndex = dto.seriesIndex,
        readState = readStateOf(dto.readStatus),
    )

    fun series(dto: SeriesSummaryDto) = Series(
        id = seriesIdOf(dto.id),
        name = dto.name,
        authors = dto.authors,
        bookCount = seriesCounts(dto.ownedVolumeCount, dto.readVolumeCount, dto.bookCount, dto.readCount).first,
        readCount = seriesCounts(dto.ownedVolumeCount, dto.readVolumeCount, dto.bookCount, dto.readCount).second,
        coverUrls = dto.coverBookIds.take(SeriesTileCovers).map { imageUrl(it, Thumbnail, version = null) },
        lastAddedAt = instant(dto.lastAddedAt),
    )

    fun series(dto: SeriesInfoDto, coverUrls: List<String>) = Series(
        id = seriesIdOf(dto.id),
        name = dto.name,
        authors = dto.authors,
        bookCount = seriesCounts(dto.ownedVolumeCount, dto.readVolumeCount, dto.bookCount, dto.readCount).first,
        readCount = seriesCounts(dto.ownedVolumeCount, dto.readVolumeCount, dto.bookCount, dto.readCount).second,
        coverUrls = coverUrls,
        lastAddedAt = null,
    )

    fun author(dto: AuthorSummaryDto) = Author(
        id = authorIdOf(dto.id),
        name = dto.name,
        bookCount = dto.workCount ?: dto.bookCount,
        portraitUrl = dto.imageUrl?.let { if (it.startsWith("/")) serverUrl + it else it },
    )

    fun collection(dto: CollectionDto) = Shelf(ShelfKind.Collection, shelfIdOf(dto.id), dto.name, dto.bookCount)

    fun smartList(dto: SmartScopeDto) = Shelf(ShelfKind.SmartList, shelfIdOf(dto.id), dto.name, dto.bookCount)

    /** [audioChapterCount] comes from the book's audio metadata and is shown on audio files. */
    fun detail(dto: BookDetailDto, audioChapterCount: Int?) = BookDetail(
        id = bookIdOf(dto.id),
        title = dto.title.orEmpty(),
        subtitle = dto.subtitle,
        authors = dto.authors.map { AuthorRef(authorIdOf(it.id), it.name) },
        series = seriesRef(dto.seriesId, dto.seriesName, dto.seriesIndex),
        coverUrl = if (dto.coverMedia.isNotEmpty()) imageUrl(dto.id, Cover, dto.coverVersion) else null,
        coverShape = shapeOf(dto.coverAspectRatio),
        media = mediaProfileOf(dto.files.map(::mediaFile)),
        description = dto.description?.takeIf { it.isNotBlank() },
        // A genre is a link to its books: one without a name would lead nowhere.
        genres = dto.genres.filter { it.isNotBlank() },
        rating = dto.rating?.toFloat(),
        pageCount = dto.pageCount,
        publishedYear = dto.publishedYear,
        publisher = dto.publisher,
        language = dto.language,
        isbn = dto.isbn13 ?: dto.isbn10,
        libraryName = dto.libraryName,
        addedAt = instant(dto.addedAt),
        narrators = dto.audioMetadata?.narrators.orEmpty().map { it.name },
        collections = dto.collections.map { it.name },
        files = dto.files.filter { isContentRole(it.role) }.map { bookFile(it, audioChapterCount) },
        readState = readStateOf(dto.readStatus),
        startedAt = instant(dto.readStatus?.startedAt),
        audioCover = dto.covers?.audio?.let { audioCovers.of(dto.id, it.updatedAt) },
    ).withReaderFileWithoutDownload()

    /**
     * The server lists every file of the book, those never opened included: they carry a
     * percentage of 0 and no date. Only a dated item is reading, and the latest one counts.
     */
    fun reading(progress: ReadingProgressListDto, stats: SessionStatsDto): BookReading {
        val latest = progress.items
            .mapNotNull { item -> instant(item.updatedAt)?.let { it to item } }
            .maxByOrNull { it.first }
        return BookReading(
            progressPercent = latest?.second?.percentage?.let(::percent),
            lastReadAt = listOfNotNull(latest?.first, instant(stats.lastSessionAt)).maxOrNull(),
            totalSeconds = stats.totalSeconds,
            sessionCount = stats.totalSessions,
            page = latest?.second?.text?.page,
            positionAt = latest?.first,
        )
    }

    /** The version is part of the URL, so a changed cover is a new cache entry. */
    private fun imageUrl(bookNumber: Long, image: String, version: String?): String =
        "$serverUrl/api/v1/books/$bookNumber/$image" + versionQuery(version)
}

private fun seriesRef(id: Long?, name: String?, index: String?): SeriesRef? =
    name?.takeIf { it.isNotBlank() }?.let { SeriesRef(id?.let(::seriesIdOf), it, index) }

private fun shapeOf(ratio: String) = if (ratio == "1/1") CoverShape.Square else CoverShape.Portrait

private fun readStateOf(dto: ReadStatusDto?): ReadState? = when (dto?.status) {
    "unread" -> ReadState.Unread
    "want_to_read" -> ReadState.WantToRead
    "reading", "rereading", "on_hold" -> ReadState.Reading
    "read", "skimmed" -> ReadState.Read
    "abandoned" -> ReadState.Abandoned
    else -> null
}

/**
 * How many a series holds and how many of them are read: by volume, as the server's own pages, where it counts
 * both (an edition of a volume is not one more book to read), by book otherwise. Never one of each.
 */
private fun seriesCounts(ownedVolumes: Int?, readVolumes: Int?, books: Int, read: Int): Pair<Int, Int> =
    if (ownedVolumes != null && readVolumes != null) ownedVolumes to readVolumes else books to read

private fun percent(value: Double): Int = value.roundToInt().coerceIn(0, FullPercent)

private fun versionQuery(version: String?): String =
    if (version.isNullOrEmpty()) "" else "?t=" + URLEncoder.encode(version, Charsets.UTF_8)

/**
 * "2017-08-20 10:05:17.785287+00": what the series list sends instead of an ISO instant. The
 * offset is read in the three forms a database writes it: `+05:30`, `+0530` and `+05`.
 */
private val DatabaseTimestamp: DateTimeFormatter = DateTimeFormatterBuilder()
    .append(DateTimeFormatter.ISO_LOCAL_DATE)
    .appendLiteral(' ')
    .append(DateTimeFormatter.ISO_LOCAL_TIME)
    .appendPattern("[xxx][xx][x]")
    .toFormatter()

/** A time sent by the server, in either of its two forms; null when absent or unreadable. */
internal fun instant(text: String?): Instant? = text?.let { isoInstant(it) ?: databaseInstant(it) }

private fun isoInstant(text: String): Instant? = try {
    Instant.parse(text)
} catch (_: DateTimeParseException) {
    null
}

private fun databaseInstant(text: String): Instant? = try {
    OffsetDateTime.parse(text, DatabaseTimestamp).toInstant()
} catch (_: DateTimeParseException) {
    null
}
