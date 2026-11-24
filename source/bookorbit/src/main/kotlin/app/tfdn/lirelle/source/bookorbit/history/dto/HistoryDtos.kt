package app.tfdn.lirelle.source.bookorbit.history.dto

import kotlinx.serialization.Serializable

// The reading-history routes. Dates are `YYYY-MM-DD`; a month is `YYYY-MM` or `undated`.

@Serializable
internal data class ReadingHistoryPageDto(
    val items: List<ReadingHistoryItemDto>,
    val offset: Int,
    val limit: Int,
    val total: Int,
    val months: List<ReadingHistoryMonthDto>? = null,
)

@Serializable
internal data class ReadingHistoryItemDto(
    val id: Long,
    val bookId: Long? = null,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val seriesName: String? = null,
    val seriesIndex: String? = null,
    val format: String? = null,
    val pageCount: Int? = null,
    val coverVersion: String? = null,
    val startedOn: String? = null,
    val endedOn: String? = null,
    val outcome: String,
    val readNumber: Int? = null,
    val completedReads: Int = 0,
    val progressPercent: Int? = null,
    val sessions: Int? = null,
    val seconds: Long = 0,
    val rating: Int? = null,
    val highlightCount: Int = 0,
    val journalCount: Int = 0,
    val removedAt: String? = null,
)

@Serializable
internal data class ReadingHistoryMonthDto(
    val month: String,
    val reads: Int,
    val completed: Int = 0,
    val rereads: Int = 0,
    val pages: Int = 0,
    val seconds: Long = 0,
)

@Serializable
internal data class ReadingSummaryDto(
    val years: List<YearCountDto> = emptyList(),
    val reads: Int = 0,
    val books: Int = 0,
    val rereads: Int = 0,
    val pages: Int = 0,
    val seconds: Long = 0,
    val daysRead: Int = 0,
    val averageRating: Double? = null,
    val ratedReads: Int = 0,
    val goal: GoalDto? = null,
    val records: RecordsDto? = null,
)

@Serializable
internal data class YearCountDto(val year: Int, val reads: Int = 0)

@Serializable
internal data class GoalDto(val target: Int, val reached: Int, val metOn: String? = null)

@Serializable
internal data class RecordsDto(
    val longestBook: RecordDto? = null,
    val mostReread: RecordDto? = null,
    val longestRead: RecordDto? = null,
    val fastest: RecordDto? = null,
)

@Serializable
internal data class RecordDto(val item: RecordItemDto, val value: Double)

@Serializable
internal data class RecordItemDto(
    val id: Long,
    val bookId: Long? = null,
    val title: String? = null,
    val coverVersion: String? = null,
    val removedAt: String? = null,
)

@Serializable
internal data class MarksDto(
    val highlights: List<HighlightDto> = emptyList(),
    val highlightCount: Int = 0,
    val journal: List<JournalDto> = emptyList(),
    val journalCount: Int = 0,
)

@Serializable
internal data class HighlightDto(
    val id: Long,
    val text: String,
    val note: String? = null,
    val color: String? = null,
    val chapterTitle: String? = null,
    val createdAt: String? = null,
)

@Serializable
internal data class JournalDto(
    val id: Long,
    val body: String,
    val chapterTitle: String? = null,
    val createdAt: String? = null,
)

/** The answer to a rating change. */
@Serializable
internal data class RatingResultDto(val rating: Int? = null, val bookRatingChanged: Boolean = false)
