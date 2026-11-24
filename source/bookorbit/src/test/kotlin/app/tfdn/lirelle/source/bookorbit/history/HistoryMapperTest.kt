package app.tfdn.lirelle.source.bookorbit.history

import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.RecordKind
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.source.bookorbit.history.dto.GoalDto
import app.tfdn.lirelle.source.bookorbit.history.dto.HighlightDto
import app.tfdn.lirelle.source.bookorbit.history.dto.JournalDto
import app.tfdn.lirelle.source.bookorbit.history.dto.MarksDto
import app.tfdn.lirelle.source.bookorbit.history.dto.ReadingHistoryItemDto
import app.tfdn.lirelle.source.bookorbit.history.dto.ReadingHistoryMonthDto
import app.tfdn.lirelle.source.bookorbit.history.dto.ReadingHistoryPageDto
import app.tfdn.lirelle.source.bookorbit.history.dto.ReadingSummaryDto
import app.tfdn.lirelle.source.bookorbit.history.dto.RecordDto
import app.tfdn.lirelle.source.bookorbit.history.dto.RecordItemDto
import app.tfdn.lirelle.source.bookorbit.history.dto.RecordsDto
import app.tfdn.lirelle.source.bookorbit.history.dto.YearCountDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth

class HistoryMapperTest {

    private val mapper = HistoryMapper("https://srv")

    private fun item(
        bookId: Long? = 7,
        outcome: String = "completed",
        removedAt: String? = null,
    ) = ReadingHistoryItemDto(
        id = 1, bookId = bookId, title = "Dune", authors = listOf("Herbert"),
        seriesName = "Dune", seriesIndex = "1", format = "epub", pageCount = 500,
        coverVersion = "v 1", startedOn = "2026-01-02", endedOn = "2026-01-20",
        outcome = outcome, readNumber = 2, completedReads = 2, progressPercent = 40,
        sessions = 5, seconds = 3600, rating = 4, highlightCount = 3, journalCount = 1,
        removedAt = removedAt,
    )

    private fun page(vararg items: ReadingHistoryItemDto, months: List<ReadingHistoryMonthDto>? = null) =
        ReadingHistoryPageDto(items.toList(), offset = 0, limit = 20, total = items.size, months = months)

    @Test
    fun `a full item maps every field`() {
        val r = mapper.page(page(item())).records.single()
        assertThat(r.id).isEqualTo(ReadId("1"))
        assertThat(r.bookId).isEqualTo(BookId("7"))
        assertThat(r.title).isEqualTo("Dune")
        assertThat(r.authors).containsExactly("Herbert")
        assertThat(r.series?.name).isEqualTo("Dune")
        assertThat(r.series?.index).isEqualTo("1")
        assertThat(r.coverUrl).isEqualTo("https://srv/api/v1/books/7/thumbnail?t=v+1")
        assertThat(r.detailCoverUrl).isEqualTo("https://srv/api/v1/books/7/cover?t=v+1")
        assertThat(r.startedOn).isEqualTo(LocalDate.of(2026, 1, 2))
        assertThat(r.endedOn).isEqualTo(LocalDate.of(2026, 1, 20))
        assertThat(r.outcome).isEqualTo(ReadOutcome.Completed)
        assertThat(r.readNumber).isEqualTo(2)
        assertThat(r.duration).isEqualTo(Duration.ofSeconds(3600))
        assertThat(r.sessions).isEqualTo(5)
        assertThat(r.rating).isEqualTo(4)
        assertThat(r.highlightCount).isEqualTo(3)
        assertThat(r.journalCount).isEqualTo(1)
        assertThat(r.removed).isFalse()
    }

    @Test
    fun `an item without a book has no cover and is removed`() {
        val r = mapper.page(page(item(bookId = null))).records.single()
        assertThat(r.bookId).isNull()
        assertThat(r.coverUrl).isNull()
        assertThat(r.removed).isTrue()
    }

    @Test
    fun `a skimmed item keeps its progress and drops the read number`() {
        val r = mapper.page(page(item(outcome = "skimmed"))).records.single()
        assertThat(r.outcome).isEqualTo(ReadOutcome.Skimmed)
        assertThat(r.progressPercent).isEqualTo(40)
        assertThat(r.readNumber).isNull()
    }

    @Test
    fun `the first page maps its months and an undated bucket`() {
        val months = listOf(
            ReadingHistoryMonthDto("2026-01", reads = 2, pages = 300, seconds = 60),
            ReadingHistoryMonthDto("undated", reads = 1),
        )
        val mapped = mapper.page(page(item(), months = months)).months!!
        assertThat(mapped[0].month).isEqualTo(YearMonth.of(2026, 1))
        assertThat(mapped[0].duration).isEqualTo(Duration.ofSeconds(60))
        assertThat(mapped[1].month).isNull()
        assertThat(mapped[1].reads).isEqualTo(1)
    }

    @Test
    fun `a later page has no months`() {
        assertThat(mapper.page(page(item())).months).isNull()
    }

    @Test
    fun `the summary maps years totals goal and the present records in order`() {
        fun rec(v: Double) = RecordDto(RecordItemDto(id = 1, bookId = 9, title = "T"), v)
        val dto = ReadingSummaryDto(
            years = listOf(YearCountDto(2026, 5), YearCountDto(2025, 3)),
            reads = 8, books = 7, pages = 2000, seconds = 7200, daysRead = 40, averageRating = 4.5,
            goal = GoalDto(target = 10, reached = 8, metOn = "2026-06-01"),
            records = RecordsDto(longestBook = rec(900.0), fastest = rec(2.0)),
        )
        val s = mapper.summary(dto)
        assertThat(s.years).containsExactly(2026, 2025).inOrder()
        assertThat(s.totals.reads).isEqualTo(8)
        assertThat(s.totals.books).isEqualTo(7)
        assertThat(s.totals.duration).isEqualTo(Duration.ofSeconds(7200))
        assertThat(s.totals.averageRating).isEqualTo(4.5)
        assertThat(s.goal?.metOn).isEqualTo(LocalDate.of(2026, 6, 1))
        assertThat(s.goal?.target).isEqualTo(10)
        assertThat(s.records.map { it.kind }).containsExactly(RecordKind.LongestBook, RecordKind.Fastest).inOrder()
        assertThat(s.records[0].value).isEqualTo(900.0)
    }

    @Test
    fun `an empty summary has no goal and no records`() {
        val s = mapper.summary(ReadingSummaryDto(goal = null, records = RecordsDto()))
        assertThat(s.goal).isNull()
        assertThat(s.records).isEmpty()
    }

    @Test
    fun `marks map highlights and journal with their counts`() {
        val m = mapper.marks(
            MarksDto(
                highlights = listOf(HighlightDto(1, "text", note = "n", chapterTitle = "C1")),
                highlightCount = 4,
                journal = listOf(JournalDto(2, "body")),
                journalCount = 2,
            ),
        )
        assertThat(m.highlights.single().id).isEqualTo(MarkId("1"))
        assertThat(m.highlights.single().note).isEqualTo("n")
        assertThat(m.highlights.single().chapterTitle).isEqualTo("C1")
        assertThat(m.highlightCount).isEqualTo(4)
        assertThat(m.journal.single().id).isEqualTo(MarkId("2"))
        assertThat(m.journal.single().body).isEqualTo("body")
        assertThat(m.journalCount).isEqualTo(2)
    }
}
