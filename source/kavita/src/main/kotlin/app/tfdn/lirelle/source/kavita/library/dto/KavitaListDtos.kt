package app.tfdn.lirelle.source.kavita.library.dto

import kotlinx.serialization.Serializable

/** A reading list of the account, or one promoted by the server. */
@Serializable
internal data class ReadingListDto(val id: Int, val title: String = "", val itemCount: Int = 0)

/** The people of a reading list's chapter, by name: Kavita 0.9 gives them, 0.8.9 gives no chapter at all. */
@Serializable
internal data class ReadingListChapterDto(
    val writerName: String? = null,
    val pencillerName: String? = null,
)

/**
 * A chapter of a reading list, with enough of its volume and its series to be shown without another call. Its
 * chapter number is `-100000` when it is the default chapter of its volume.
 */
@Serializable
internal data class ReadingListItemDto(
    val order: Int = 0,
    val chapterId: Int,
    val seriesId: Int = 0,
    val seriesName: String? = null,
    val volumeId: Int = 0,
    val volumeNumber: String? = null,
    val chapterNumber: String? = null,
    val chapterTitleName: String? = null,
    val title: String? = null,
    val pagesRead: Int = 0,
    val pagesTotal: Int = 0,
    val seriesFormat: Int = 2,
    val isSpecial: Boolean = false,
    val lastReadingProgressUtc: String? = null,
    val chapter: ReadingListChapterDto? = null,
)

/**
 * One group of `recently-updated-series`: its chapter and volume ids are 0 on 0.8.9 and 0.9 alike.
 *
 * @property format its series' format, as a series' own.
 */
@Serializable
internal data class RecentlyUpdatedDto(
    val seriesId: Int,
    val seriesName: String? = null,
    val format: Int? = null,
    val created: String? = null,
    val count: Int = 1,
)

/** A series the search found. */
@Serializable
internal data class SearchSeriesDto(val seriesId: Int, val name: String = "", val libraryId: Int = 0)

/** What the search found, of which the series, the chapters and the people are read; up to 15 in each group. */
@Serializable
internal data class SearchResultDto(
    val series: List<SearchSeriesDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
    val persons: List<PersonDto> = emptyList(),
)
