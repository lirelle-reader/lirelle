package app.tfdn.lirelle.feature.history

import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.Highlight
import app.tfdn.lirelle.core.history.model.JournalNote
import app.tfdn.lirelle.core.history.model.ReadMarks
import app.tfdn.lirelle.core.history.model.MonthBucket
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadRecord
import app.tfdn.lirelle.core.history.model.ReadingGoal
import app.tfdn.lirelle.core.history.model.ReadingRecord
import app.tfdn.lirelle.core.history.model.ReadingSummary
import app.tfdn.lirelle.core.history.model.ReadingTotals
import app.tfdn.lirelle.core.history.model.RecordKind
import app.tfdn.lirelle.core.history.model.SeriesRef
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.ids.ReadId
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth

internal fun readRecord(
    id: Long,
    title: String,
    outcome: ReadOutcome = ReadOutcome.Completed,
    rating: Int? = 4,
    readNumber: Int? = 2,
) = ReadRecord(
    id = ReadId("$id"),
    bookId = BookId("book-$id"),
    title = title,
    authors = listOf("Ursula K. Le Guin", "Marie-Antoinette de la Bibliothèque"),
    series = SeriesRef("Earthsea", "1"),
    coverUrl = null,
    detailCoverUrl = null,
    startedOn = LocalDate.of(2026, 11, 1),
    endedOn = LocalDate.of(2026, 11, 12),
    outcome = outcome,
    readNumber = readNumber,
    progressPercent = null,
    sessions = 7,
    duration = Duration.ofHours(9),
    rating = rating,
    highlightCount = 12,
    journalCount = 3,
    removed = false,
)

/** What a source with one reading per book offers (Komga): the books read and their dates, nothing more. */
internal val FinishedBooksOnly = HistoryCapabilities(
    summary = false,
    outcomes = false,
    rating = false,
    marks = false,
    durations = false,
    sessions = false,
    rereads = false,
)

/** A book read to the end, as such a source gives it: its day, no start, time, sessions, rating nor marks. */
internal fun finishedBook(id: String, title: String, endedOn: LocalDate) = ReadRecord(
    id = ReadId(id),
    bookId = BookId(id),
    title = title,
    authors = listOf("Writer One"),
    series = SeriesRef("Tidewater", "1"),
    coverUrl = null,
    detailCoverUrl = null,
    startedOn = null,
    endedOn = endedOn,
    outcome = ReadOutcome.Completed,
    readNumber = null,
    progressPercent = null,
    sessions = null,
    duration = null,
    rating = null,
    highlightCount = 0,
    journalCount = 0,
    removed = false,
)

internal fun historyState(
    capabilities: HistoryCapabilities = HistoryCapabilities.All,
    summary: SummaryUi = SummaryUi.Shown(
        ReadingSummary(
            years = listOf(2026, 2025),
            totals = ReadingTotals(
                reads = 42,
                books = 40,
                pages = 12_345,
                daysRead = 210,
                duration = Duration.ofHours(418),
                averageRating = 4.25,
            ),
            goal = ReadingGoal(target = 50, reached = 42, metOn = null),
            records = listOf(
                ReadingRecord(RecordKind.LongestBook, BookId("1"), "Les Misérables, tome premier", 1_488.0),
                ReadingRecord(RecordKind.MostReread, BookId("2"), "Le Seigneur des anneaux", 5.0),
                ReadingRecord(RecordKind.LongestRead, BookId("3"), "Guerre et paix", 412.0),
                ReadingRecord(RecordKind.Fastest, BookId("4"), "Le Petit Prince", 85.0),
            ),
        ),
    ),
    log: LogUi = LogUi.Loaded(
        sections = listOf(
            MonthSection(
                month = YearMonth.of(2026, 11),
                totals = MonthBucket(YearMonth.of(2026, 11), reads = 2, pages = 900, duration = Duration.ofHours(30)),
                rows = listOf(
                    readRecord(1, "A Wizard of Earthsea"),
                    readRecord(2, "The Tombs of Atuan", ReadOutcome.Skimmed),
                ),
            ),
            MonthSection(
                month = null,
                totals = MonthBucket(null, reads = 1, pages = 0, duration = Duration.ZERO),
                rows = listOf(readRecord(3, "Untitled")),
            ),
        ),
        canLoadMore = false,
    ),
) = HistoryUiState(
    capabilities = capabilities,
    years = listOf(2026, 2025),
    period = HistoryPeriod(null),
    outcomes = setOf(ReadOutcome.Completed),
    summary = summary,
    log = log,
    loadingMore = false,
)

internal fun detailMarks() = ReadMarks(
    highlights = listOf(
        Highlight(MarkId("1"), "It is only in the dark that one can see the stars.", null, "Chapter one"),
        Highlight(MarkId("2"), "To light a candle is to cast a shadow.", "Remember this", "Chapter two"),
        Highlight(MarkId("3"), "Third", null, null),
        Highlight(MarkId("4"), "Fourth, not shown", null, null),
    ),
    highlightCount = 12,
    journal = listOf(
        JournalNote(MarkId("1"), "A slow start, then it caught me.", "Chapter one"),
        JournalNote(MarkId("2"), "The ending changed everything.", null),
        JournalNote(MarkId("3"), "Third, not shown", null),
    ),
    journalCount = 3,
)

internal fun detailState(
    record: ReadRecord? = readRecord(1, "A Wizard of Earthsea"),
    rating: RatingUi = RatingUi.Editable(stars = 3),
    marks: MarksUi = MarksUi.Loaded(detailMarks()),
    capabilities: HistoryCapabilities = HistoryCapabilities.All,
) = ReadDetailUiState(
    capabilities = capabilities,
    record = record,
    openBookId = record?.bookId,
    rating = rating, marks = marks)
