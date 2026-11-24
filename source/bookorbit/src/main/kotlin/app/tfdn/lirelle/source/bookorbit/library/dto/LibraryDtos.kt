package app.tfdn.lirelle.source.bookorbit.library.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Written from the upstream TypeScript types (packages/types/src/book.ts, series.ts, author.ts,
// collection.ts, smart-scope.ts, reading-progress.ts, reading-session.ts) at tag v3.8.0.
// Only the fields Lirelle reads are declared; unknown fields are ignored.

@Serializable
internal data class MediaOverlayDto(val available: Boolean = false, val durationSeconds: Double? = null)

@Serializable
internal data class BookFileDto(
    val id: Long,
    val format: String? = null,
    val role: String = "",
    val sizeBytes: Long? = null,
    val durationSeconds: Double? = null,
    val mediaOverlay: MediaOverlayDto? = null,
    /** When the server's record of the file last changed; a replaced file has another one. */
    val contentVersion: String? = null,
)

@Serializable
internal data class ReadStatusDto(
    val status: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class BookCardDto(
    val id: Long,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val seriesId: Long? = null,
    val seriesName: String? = null,
    val seriesIndex: String? = null,
    val files: List<BookFileDto> = emptyList(),
    val coverAspectRatio: String = "2/3",
    val hasCover: Boolean = false,
    val coverVersion: String = "",
    val readingProgress: Double? = null,
    val readStatus: ReadStatusDto? = null,
)

@Serializable
internal data class BooksPageDto(val items: List<BookCardDto> = emptyList(), val total: Int = 0)

@Serializable
internal data class SeriesSummaryDto(
    val id: Long,
    val name: String,
    val bookCount: Int = 0,
    val readCount: Int = 0,
    val authors: List<String> = emptyList(),
    val coverBookIds: List<Long> = emptyList(),
    val lastAddedAt: String? = null,
)

@Serializable
internal data class SeriesPageDto(val items: List<SeriesSummaryDto> = emptyList(), val total: Int = 0)

@Serializable
internal data class SeriesInfoDto(
    val id: Long,
    val name: String,
    val bookCount: Int = 0,
    val readCount: Int = 0,
    val authors: List<String> = emptyList(),
)

@Serializable
internal data class SeriesBooksPageDto(
    val items: List<BookCardDto> = emptyList(),
    val total: Int = 0,
    val seriesInfo: SeriesInfoDto,
)

@Serializable
internal data class AuthorSummaryDto(
    val id: Long,
    val name: String,
    val bookCount: Int = 0,
    val imageUrl: String? = null,
    val description: String? = null,
)

@Serializable
internal data class AuthorsPageDto(val items: List<AuthorSummaryDto> = emptyList(), val total: Int = 0)

@Serializable
internal data class CollectionDto(val id: Long, val name: String, val bookCount: Int = 0)

@Serializable
internal data class SmartScopeDto(val id: Long, val name: String, val bookCount: Int? = null)

@Serializable
internal data class NamedDto(val id: Long, val name: String)

@Serializable
internal data class AudioMetadataDto(
    val narrators: List<NamedDto> = emptyList(),
    val durationSeconds: Double? = null,
    val chapters: List<JsonElement>? = null,
)

/** One cover per medium; a medium without a cover of its own is null. */
@Serializable
internal data class CoversDto(val ebook: CoverDto? = null, val audio: CoverDto? = null)

@Serializable
internal data class CoverDto(
    val width: Int? = null,
    val height: Int? = null,
    val updatedAt: String? = null,
    val source: String? = null,
)

@Serializable
internal data class BookDetailDto(
    val id: Long,
    val libraryName: String = "",
    val addedAt: String,
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val isbn13: String? = null,
    val isbn10: String? = null,
    val publisher: String? = null,
    val publishedYear: Int? = null,
    val language: String? = null,
    val pageCount: Int? = null,
    val seriesId: Long? = null,
    val seriesName: String? = null,
    val seriesIndex: String? = null,
    val rating: Double? = null,
    val authors: List<NamedDto> = emptyList(),
    val genres: List<String> = emptyList(),
    val files: List<BookFileDto> = emptyList(),
    val coverAspectRatio: String = "2/3",
    val coverVersion: String = "",
    val coverMedia: List<String> = emptyList(),
    val covers: CoversDto? = null,
    val readStatus: ReadStatusDto? = null,
    val audioMetadata: AudioMetadataDto? = null,
    val collections: List<NamedDto> = emptyList(),
)

/** [text] is the place of the file, as in its own position: only its page is read here. */
@Serializable
internal data class FileProgressDto(
    val fileId: Long,
    val percentage: Double? = null,
    val updatedAt: String? = null,
    val text: PositionTextDto? = null,
)

@Serializable
internal data class ReadingProgressListDto(val items: List<FileProgressDto> = emptyList())

@Serializable
internal data class SessionStatsDto(
    val totalSessions: Int = 0,
    val totalSeconds: Long = 0,
    val firstSessionAt: String? = null,
    val lastSessionAt: String? = null,
)

@Serializable
internal data class SessionsDto(val stats: SessionStatsDto = SessionStatsDto())

@Serializable
internal data class RelatedBookDto(
    val id: Long,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val coverAspectRatio: String = "2/3",
    val hasCover: Boolean = false,
    val coverVersion: String? = null,
    val seriesIndex: String? = null,
    val readStatus: ReadStatusDto? = null,
)

/** One row of the server's dashboard. `total` is absent for the rows the server does not count. */
@Serializable
internal data class ScrollerDto(val books: List<BookCardDto> = emptyList(), val total: Int? = null)

@Serializable
internal data class SearchHitDto<T>(val item: T)

/** @property total how many items match; null when the server does not say. */
@Serializable
internal data class SearchSectionDto<T>(val items: List<SearchHitDto<T>> = emptyList(), val total: Int? = null)

@Serializable
internal data class SearchDto(
    val books: SearchSectionDto<BookCardDto> = SearchSectionDto(),
    val series: SearchSectionDto<SeriesSummaryDto> = SearchSectionDto(),
    val authors: SearchSectionDto<AuthorSummaryDto> = SearchSectionDto(),
)

@Serializable
internal data class GenreCountDto(val genre: String? = null, val count: Int = 0)

@Serializable
internal data class GenreDistributionDto(val items: List<GenreCountDto> = emptyList())
