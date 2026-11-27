package app.tfdn.lirelle.source.kavita.library.dto

import kotlinx.serialization.Serializable

// What the book page and History read of a series' reading on Kavita: its reading profile and its reading
// sessions. In the library's package, as the book page asks them; reading's routes give the sessions too.

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
