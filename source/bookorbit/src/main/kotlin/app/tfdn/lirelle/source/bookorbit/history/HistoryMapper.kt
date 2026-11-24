package app.tfdn.lirelle.source.bookorbit.history

import app.tfdn.lirelle.core.history.model.Highlight
import app.tfdn.lirelle.core.history.model.JournalNote
import app.tfdn.lirelle.core.history.model.MonthBucket
import app.tfdn.lirelle.core.history.model.ReadMarks
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadPage
import app.tfdn.lirelle.core.history.model.ReadRecord
import app.tfdn.lirelle.core.history.model.ReadingGoal
import app.tfdn.lirelle.core.history.model.ReadingRecord
import app.tfdn.lirelle.core.history.model.ReadingSummary
import app.tfdn.lirelle.core.history.model.ReadingTotals
import app.tfdn.lirelle.core.history.model.RecordKind
import app.tfdn.lirelle.core.history.model.SeriesRef
import app.tfdn.lirelle.source.bookorbit.history.dto.GoalDto
import app.tfdn.lirelle.source.bookorbit.history.dto.MarksDto
import app.tfdn.lirelle.source.bookorbit.history.dto.ReadingHistoryItemDto
import app.tfdn.lirelle.source.bookorbit.history.dto.ReadingHistoryMonthDto
import app.tfdn.lirelle.source.bookorbit.history.dto.ReadingHistoryPageDto
import app.tfdn.lirelle.source.bookorbit.history.dto.ReadingSummaryDto
import app.tfdn.lirelle.source.bookorbit.history.dto.RecordDto
import app.tfdn.lirelle.source.bookorbit.network.bookIdOf
import app.tfdn.lirelle.source.bookorbit.network.markIdOf
import app.tfdn.lirelle.source.bookorbit.network.readIdOf
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth

/** Maps the history wire types to the server-neutral models. */
internal class HistoryMapper(serverUrl: String) {

    private val covers = HistoryCovers(serverUrl)

    fun page(dto: ReadingHistoryPageDto) = ReadPage(
        records = dto.items.map(::record),
        months = dto.months?.map(::month),
        total = dto.total,
        offset = dto.offset,
        limit = dto.limit,
    )

    fun summary(dto: ReadingSummaryDto) = ReadingSummary(
        years = dto.years.map { it.year },
        totals = ReadingTotals(
            reads = dto.reads,
            books = dto.books,
            pages = dto.pages,
            daysRead = dto.daysRead,
            duration = Duration.ofSeconds(dto.seconds),
            averageRating = dto.averageRating,
        ),
        goal = dto.goal?.let(::goal),
        records = records(dto),
    )

    fun marks(dto: MarksDto) = ReadMarks(
        highlights = dto.highlights.map { Highlight(markIdOf(it.id), it.text, it.note, it.chapterTitle) },
        highlightCount = dto.highlightCount,
        journal = dto.journal.map { JournalNote(markIdOf(it.id), it.body, it.chapterTitle) },
        journalCount = dto.journalCount,
    )

    private fun record(dto: ReadingHistoryItemDto): ReadRecord {
        val outcome = outcome(dto.outcome)
        val removed = dto.removedAt != null || dto.bookId == null
        return ReadRecord(
            id = readIdOf(dto.id),
            bookId = dto.bookId?.let(::bookIdOf),
            title = dto.title.orEmpty(),
            authors = dto.authors,
            series = dto.seriesName?.let { SeriesRef(it, dto.seriesIndex) },
            coverUrl = dto.bookId?.takeUnless { removed }?.let { covers.url(it, "thumbnail", dto.coverVersion) },
            detailCoverUrl = dto.bookId?.takeUnless { removed }?.let { covers.url(it, "cover", dto.coverVersion) },
            startedOn = date(dto.startedOn),
            endedOn = date(dto.endedOn),
            outcome = outcome,
            readNumber = dto.readNumber.takeIf { outcome == ReadOutcome.Completed },
            progressPercent = dto.progressPercent,
            sessions = dto.sessions,
            duration = Duration.ofSeconds(dto.seconds),
            rating = dto.rating,
            highlightCount = dto.highlightCount,
            journalCount = dto.journalCount,
            removed = removed,
        )
    }

    private fun month(dto: ReadingHistoryMonthDto) = MonthBucket(
        month = runCatching { YearMonth.parse(dto.month) }.getOrNull(),
        reads = dto.reads,
        pages = dto.pages,
        duration = Duration.ofSeconds(dto.seconds),
    )

    private fun goal(dto: GoalDto) = ReadingGoal(dto.target, dto.reached, date(dto.metOn))

    private fun records(dto: ReadingSummaryDto): List<ReadingRecord> {
        val r = dto.records ?: return emptyList()
        return listOf(
            RecordKind.LongestBook to r.longestBook,
            RecordKind.MostReread to r.mostReread,
            RecordKind.LongestRead to r.longestRead,
            RecordKind.Fastest to r.fastest,
        ).mapNotNull { (kind, record) -> record?.let { record(kind, it) } }
    }

    private fun record(kind: RecordKind, dto: RecordDto) = ReadingRecord(
        kind = kind,
        bookId = dto.item.bookId?.takeIf { dto.item.removedAt == null }?.let(::bookIdOf),
        title = dto.item.title.orEmpty(),
        value = dto.value,
    )

    private fun outcome(value: String) = when (value) {
        "skimmed" -> ReadOutcome.Skimmed
        "abandoned" -> ReadOutcome.Abandoned
        else -> ReadOutcome.Completed
    }

    private fun date(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
