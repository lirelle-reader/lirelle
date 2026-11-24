package app.tfdn.lirelle.source.bookorbit.library.dto

import kotlinx.serialization.Serializable

// Written from the upstream sources at tag v3.8.0 (reading-progress.dto.ts, reading-progress.ts,
// save-reading-session.dto.ts, the series and reading-attempt types). The server refuses a request
// body with a field it does not know: a request type declares only what is sent.

/** [page] is that of a file read by pages (a comic); a book has a [cfi] instead. */
@Serializable
internal data class PositionTextDto(val cfi: String? = null, val updatedAt: String? = null, val page: Int? = null)

@Serializable
internal data class PositionDto(
    val fileId: Long,
    /** From 0 to 100. */
    val percentage: Double = 0.0,
    val updatedAt: String? = null,
    val text: PositionTextDto? = null,
    val narration: PositionNarrationDto? = null,
)

/** Every field is null on a file that was never narrated. */
@Serializable
internal data class PositionNarrationDto(
    val seconds: Double? = null,
    val fragment: String? = null,
    val sectionIndex: Int? = null,
    val percentage: Double? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class PositionsDto(val items: List<PositionDto> = emptyList())

/**
 * A book's place is its [cfi], a comic's its [page]. A null one is left out of the body, which the server
 * stores as "no place": a text write replaces both.
 */
@Serializable
internal data class PositionTextWriteDto(val cfi: String?, val page: Int? = null)

/** The sentence a narration is at; [fragment] is `textHref#id`. */
@Serializable
internal data class PositionNarrationWriteDto(val seconds: Double, val fragment: String, val sectionIndex: Int)

/** A write carries the part its [kind] names and not the other: the null one is left out of the body. */
@Serializable
internal data class PositionWriteDto(
    val fileId: Long,
    val percentage: Double,
    val text: PositionTextWriteDto? = null,
    val kind: String = "text",
    val narration: PositionNarrationWriteDto? = null,
    val editedAt: String? = null,
)

@Serializable
internal data class PositionWritesDto(val writes: List<PositionWriteDto>)

/** [progress] is the position the server holds after the write: the one sent, or the one it kept. */
@Serializable
internal data class PositionWriteResultDto(val fileId: Long, val status: String = "", val progress: PositionDto? = null)

@Serializable
internal data class PositionWriteResultsDto(val results: List<PositionWriteResultDto> = emptyList())

/** No `sessionType`: the server's default is a reading session. */
@Serializable
internal data class ReadingSessionWriteDto(
    val sessionId: String,
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Int,
    val progressDelta: Double? = null,
    val endProgress: Double? = null,
    val source: String = "android",
)

/**
 * One read-through of a book. [outcome] is null while it is still going on. [rating] is the one given to
 * this reading. [updatedAt] is a full time; [endedOn] a day only.
 */
@Serializable
internal data class ReadingAttemptDto(
    val id: Long,
    val outcome: String? = null,
    val rating: Double? = null,
    val endedOn: String? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class ReadingAttemptsDto(val items: List<ReadingAttemptDto> = emptyList())

@Serializable
internal data class NextVolumeDto(val bookId: Long, val title: String? = null, val seriesIndex: String? = null)

@Serializable
internal data class NextVolumeAnswerDto(val next: NextVolumeDto? = null)
