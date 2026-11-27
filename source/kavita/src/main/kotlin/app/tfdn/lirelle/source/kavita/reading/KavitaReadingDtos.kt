package app.tfdn.lirelle.source.kavita.reading

import kotlinx.serialization.Serializable

/** A chapter's place as Kavita keeps it (`GET api/Reader/get-progress`). */
@Serializable
internal data class ProgressDto(
    val volumeId: Int = 0,
    val chapterId: Int = 0,
    val pageNum: Int = 0,
    val seriesId: Int = 0,
    val libraryId: Int = 0,
    val bookScrollId: String? = null,
    val lastModifiedUtc: String? = null,
)

/**
 * A chapter's place as the app writes it (`POST api/Reader/progress`). Every field is always sent, a null
 * [bookScrollId] too: Kavita requires the page and the four ids, and refuses a place without them (400), even one
 * at page 0. No date: Kavita dates the place itself.
 */
@Serializable
internal data class ProgressWriteDto(
    val volumeId: Int,
    val chapterId: Int,
    val pageNum: Int,
    val seriesId: Int,
    val libraryId: Int,
    val bookScrollId: String?,
)

/** What `GET api/Reader/chapter-info` says of a chapter, of which the page count is read. */
@Serializable
internal data class ChapterInfoDto(val pages: Int = 0)

/**
 * A series' effective reading profile: whether it is Kavita's default one ([kind] 0; 1 the user's, 2 implicit),
 * and its [readingDirection] (0 left to right, 1 right to left).
 */
@Serializable
internal data class ReadingProfileDto(val kind: Int = 0, val readingDirection: Int = 0)

/**
 * One session's reading of a series on one day (`GET api/Stats/reading-history`), with its chapters. A server that
 * makes no generated session (0.8.9) does not tell [isGeneratedSession]: it is false then.
 */
@Serializable
internal data class HistoryItemDto(
    val sessionId: Int = 0,
    val isGeneratedSession: Boolean = false,
    val startTimeUtc: String? = null,
    val localDate: String? = null,
    val seriesId: Int = 0,
    val seriesName: String? = null,
    val libraryId: Int = 0,
    val chapters: List<HistoryChapterDto> = emptyList(),
)

/** A chapter read in a session: when, for how long, and whether it was finished then. */
@Serializable
internal data class HistoryChapterDto(
    val chapterId: Int = 0,
    val startTimeUtc: String? = null,
    val durationSeconds: Long = 0,
    val completed: Boolean = false,
)
