package app.tfdn.lirelle.core.history.model

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.ids.ReadId
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth

/** How a read ended. */
enum class ReadOutcome { Completed, Skimmed, Abandoned }

/** The series a read's book belongs to, with its position in it. */
data class SeriesRef(val name: String, val index: String?)

/** One read of a book: a row of the reading history. */
data class ReadRecord(
    val id: ReadId,
    /** Null when the book is gone. */
    val bookId: BookId?,
    val title: String,
    val authors: List<String>,
    val series: SeriesRef?,
    /** Null for a removed or coverless book. */
    val coverUrl: String?,
    /** The full-size cover of the detail header; null for a removed book. */
    val detailCoverUrl: String?,
    val startedOn: LocalDate?,
    val endedOn: LocalDate?,
    val outcome: ReadOutcome,
    /** Null unless a completed read; 1-based. */
    val readNumber: Int?,
    /** How far a skimmed or abandoned read got. */
    val progressPercent: Int?,
    val sessions: Int?,
    val duration: Duration?,
    val rating: Int?,
    val highlightCount: Int,
    val journalCount: Int,
    val removed: Boolean,
)

/** The reads of one month; a null [month] is the bucket of undated reads. */
data class MonthBucket(val month: YearMonth?, val reads: Int, val pages: Int, val duration: Duration)

/** A page of reads. [months] is only sent with the first page. */
data class ReadPage(
    val records: List<ReadRecord>,
    val months: List<MonthBucket>?,
    val total: Int,
    val offset: Int,
    val limit: Int,
)

/** A yearly reading goal. */
data class ReadingGoal(val target: Int, val reached: Int, val metOn: LocalDate?)

/** The personal records the summary can show. */
enum class RecordKind { LongestBook, MostReread, LongestRead, Fastest }

/** One personal record. */
data class ReadingRecord(val kind: RecordKind, val bookId: BookId?, val title: String, val value: Double)

/** The totals of a period. */
data class ReadingTotals(
    val reads: Int,
    val books: Int,
    val pages: Int,
    val daysRead: Int,
    val duration: Duration,
    val averageRating: Double?,
)

/** The summary of a period. [records] holds only the records the server sent, in [RecordKind] order. */
data class ReadingSummary(
    val years: List<Int>,
    val totals: ReadingTotals,
    val goal: ReadingGoal?,
    val records: List<ReadingRecord>,
)

/** A highlight made during a read. */
data class Highlight(val id: MarkId, val text: String, val note: String?, val chapterTitle: String?)

/** A journal note written during a read. */
data class JournalNote(val id: MarkId, val body: String, val chapterTitle: String?)

/** The highlights and journal of a read, with their full counts. */
data class ReadMarks(
    val highlights: List<Highlight>,
    val highlightCount: Int,
    val journal: List<JournalNote>,
    val journalCount: Int,
)

/** The answer to a rating change. */
data class RatingResult(val rating: Int?, val bookRatingChanged: Boolean)

/** What a history source can provide. */
data class HistoryCapabilities(
    val summary: Boolean,
    val outcomes: Boolean,
    val rating: Boolean,
    val marks: Boolean,
    val durations: Boolean,
    val sessions: Boolean,
    val rereads: Boolean,
) {
    companion object {
        val All = HistoryCapabilities(true, true, true, true, true, true, true)
    }
}

/** The period a history is shown for; a null [year] is all time. */
data class HistoryPeriod(val year: Int?)
