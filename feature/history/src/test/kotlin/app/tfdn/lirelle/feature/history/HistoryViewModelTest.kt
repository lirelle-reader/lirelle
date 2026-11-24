package app.tfdn.lirelle.feature.history

import app.cash.turbine.test
import app.tfdn.lirelle.core.history.HistoryRepository
import app.tfdn.lirelle.core.history.ReadRecordCache
import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.MonthBucket
import app.tfdn.lirelle.core.history.model.RatingResult
import app.tfdn.lirelle.core.history.model.ReadMarks
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadPage
import app.tfdn.lirelle.core.history.model.ReadRecord
import app.tfdn.lirelle.core.history.model.ReadingSummary
import app.tfdn.lirelle.core.history.model.ReadingTotals
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private data class LogCall(val period: HistoryPeriod, val outcomes: Set<ReadOutcome>, val offset: Int)

private class FakeHistory(override var capabilities: HistoryCapabilities = HistoryCapabilities.All) :
    HistoryRepository {
    val logCalls = mutableListOf<LogCall>()
    val summaryCalls = mutableListOf<HistoryPeriod>()
    var failLog = false
    var failSummary = false
    var pages: (Int) -> ReadPage = { offset -> page(offset, listOf(record(1, LocalDate.of(2026, 3, 5))), total = 1) }
    var summary = summary(listOf(2026, 2025))

    override suspend fun log(
        period: HistoryPeriod,
        outcomes: Set<ReadOutcome>,
        newestFirst: Boolean,
        offset: Int,
        limit: Int,
    ): ApiResult<ReadPage> {
        logCalls += LogCall(period, outcomes, offset)
        return if (failLog) ApiResult.Failure(ApiError.ServerFailed) else ApiResult.Success(pages(offset))
    }

    override suspend fun summary(period: HistoryPeriod): ApiResult<ReadingSummary> {
        summaryCalls += period
        return if (failSummary) ApiResult.Failure(ApiError.ServerFailed) else ApiResult.Success(summary)
    }

    override suspend fun marks(readId: ReadId): ApiResult<ReadMarks> = error("unused")

    override suspend fun setRating(readId: ReadId, stars: Int?): ApiResult<RatingResult> = error("unused")
}

private fun record(id: Long, endedOn: LocalDate?) = ReadRecord(
    id = ReadId("$id"),
    bookId = BookId("book-$id"),
    title = "Book $id",
    authors = emptyList(),
    series = null,
    coverUrl = null,
    detailCoverUrl = null,
    startedOn = null,
    endedOn = endedOn,
    outcome = ReadOutcome.Completed,
    readNumber = null,
    progressPercent = null,
    sessions = null,
    duration = Duration.ofMinutes(10),
    rating = null,
    highlightCount = 0,
    journalCount = 0,
    removed = false,
)

private fun page(offset: Int, records: List<ReadRecord>, total: Int, months: List<MonthBucket>? = null) =
    ReadPage(records, months, total, offset, 30)

private fun summary(years: List<Int>) =
    ReadingSummary(years, ReadingTotals(3, 3, 900, 20, Duration.ofHours(30), null), null, emptyList())

class HistoryViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repo = FakeHistory()

    @Test
    fun `the first load shows the summary and the month sections`() = runTest {
        val state = HistoryViewModel(repo, ReadRecordCache()).state.value

        assertThat(state.summary).isEqualTo(SummaryUi.Shown(repo.summary))
        assertThat(state.years).containsExactly(2026, 2025).inOrder()
        assertThat(state.outcomes).containsExactly(ReadOutcome.Completed)
        val log = state.log as LogUi.Loaded
        assertThat(log.sections.map { it.month }).containsExactly(YearMonth.of(2026, 3))
        assertThat(log.canLoadMore).isFalse()
    }

    @Test
    fun `choosing a year reloads the summary and the log from the start`() = runTest {
        val viewModel = HistoryViewModel(repo, ReadRecordCache())

        viewModel.selectPeriod(HistoryPeriod(2026))

        assertThat(repo.summaryCalls.last()).isEqualTo(HistoryPeriod(2026))
        assertThat(repo.logCalls.last()).isEqualTo(LogCall(HistoryPeriod(2026), setOf(ReadOutcome.Completed), 0))
        assertThat(viewModel.state.value.period).isEqualTo(HistoryPeriod(2026))
    }

    @Test
    fun `toggling an outcome reloads the log only`() = runTest {
        val viewModel = HistoryViewModel(repo, ReadRecordCache())

        viewModel.toggleOutcome(ReadOutcome.Skimmed)

        assertThat(repo.logCalls.last().outcomes).containsExactly(ReadOutcome.Completed, ReadOutcome.Skimmed)
        assertThat(repo.logCalls.last().offset).isEqualTo(0)
        assertThat(repo.summaryCalls).hasSize(1)
    }

    @Test
    fun `removing the last outcome keeps Completed`() = runTest {
        val viewModel = HistoryViewModel(repo, ReadRecordCache())

        viewModel.toggleOutcome(ReadOutcome.Completed)

        assertThat(viewModel.state.value.outcomes).containsExactly(ReadOutcome.Completed)
    }

    @Test
    fun `an empty first page is the empty state`() = runTest {
        repo.pages = { offset -> page(offset, emptyList(), total = 0) }

        assertThat(HistoryViewModel(repo, ReadRecordCache()).state.value.log).isEqualTo(LogUi.Empty)
    }

    @Test
    fun `loading more keeps a month that spans two pages as one section`() = runTest {
        val march = YearMonth.of(2026, 3)
        val first = listOf(record(1, LocalDate.of(2026, 4, 2)), record(2, LocalDate.of(2026, 3, 30)))
        val second = listOf(record(3, LocalDate.of(2026, 3, 1)), record(4, LocalDate.of(2026, 2, 9)))
        repo.pages = { offset ->
            if (offset == 0) {
                page(0, first, total = 4, months = listOf(MonthBucket(march, 3, 500, Duration.ofHours(2))))
            } else {
                page(offset, second, total = 4)
            }
        }
        val viewModel = HistoryViewModel(repo, ReadRecordCache())
        assertThat((viewModel.state.value.log as LogUi.Loaded).canLoadMore).isTrue()

        viewModel.loadMore()

        val log = viewModel.state.value.log as LogUi.Loaded
        assertThat(repo.logCalls.last().offset).isEqualTo(2)
        assertThat(log.sections.map { it.month })
            .containsExactly(YearMonth.of(2026, 4), march, YearMonth.of(2026, 2)).inOrder()
        assertThat(log.sections[1].rows.map { it.id }).containsExactly(ReadId("2"), ReadId("3")).inOrder()
        assertThat(log.sections[1].totals.pages).isEqualTo(500)
        assertThat(log.canLoadMore).isFalse()
        assertThat(viewModel.state.value.loadingMore).isFalse()
    }

    @Test
    fun `a failing log is an error until a retry works`() = runTest {
        repo.failLog = true
        val viewModel = HistoryViewModel(repo, ReadRecordCache())
        viewModel.state.test {
            assertThat(awaitItem().log).isEqualTo(LogUi.Error)
            repo.failLog = false

            viewModel.retryLog()

            assertThat(awaitItem().log).isEqualTo(LogUi.Loading)
            assertThat(awaitItem().log).isInstanceOf(LogUi.Loaded::class.java)
        }
    }

    @Test
    fun `a source without a summary never asks for one`() = runTest {
        repo.capabilities = HistoryCapabilities.All.copy(summary = false)
        val viewModel = HistoryViewModel(repo, ReadRecordCache())

        viewModel.selectPeriod(HistoryPeriod(2026))

        assertThat(repo.summaryCalls).isEmpty()
        assertThat(viewModel.state.value.summary).isEqualTo(SummaryUi.Hidden)
    }

    @Test
    fun `a rating change of a visible read updates its row`() = runTest {
        val cache = ReadRecordCache().apply { put(listOf(record(1, LocalDate.of(2026, 3, 5)))) }
        val viewModel = HistoryViewModel(repo, cache)

        cache.updateRating(ReadId("1"), 5)

        val log = viewModel.state.value.log as LogUi.Loaded
        assertThat(log.sections.single().rows.single().rating).isEqualTo(5)
    }

    @Test
    fun `a rating change of a read that is not listed changes nothing`() = runTest {
        val cache = ReadRecordCache().apply { put(listOf(record(99, null))) }
        val viewModel = HistoryViewModel(repo, cache)
        val before = viewModel.state.value

        cache.updateRating(ReadId("99"), 3)

        assertThat(viewModel.state.value).isEqualTo(before)
    }

    @Test
    fun `a failed load more flags the error, keeps the rows and clears on a success`() = runTest {
        val first = listOf(record(1, LocalDate.of(2026, 4, 2)))
        val second = listOf(record(2, LocalDate.of(2026, 3, 1)))
        repo.pages = { offset -> if (offset == 0) page(0, first, total = 2) else page(offset, second, total = 2) }
        val viewModel = HistoryViewModel(repo, ReadRecordCache())
        repo.failLog = true

        viewModel.loadMore()

        var state = viewModel.state.value
        assertThat(state.loadMoreError).isTrue()
        assertThat(state.loadingMore).isFalse()
        val kept = state.log as LogUi.Loaded
        assertThat(kept.sections.flatMap { it.rows }.map { it.id }).containsExactly(ReadId("1"))
        assertThat(kept.canLoadMore).isTrue()

        repo.failLog = false
        viewModel.loadMore()

        state = viewModel.state.value
        assertThat(state.loadMoreError).isFalse()
        assertThat((state.log as LogUi.Loaded).sections.flatMap { it.rows }.map { it.id })
            .containsExactly(ReadId("1"), ReadId("2")).inOrder()
    }

    @Test
    fun `a read shifted onto the next page by a book finished meanwhile is shown once`() = runTest {
        // A book finished elsewhere between two loads pushes the last row of the first page onto the second.
        val first = listOf(record(1, LocalDate.of(2026, 4, 2)), record(2, LocalDate.of(2026, 3, 30)))
        val second = listOf(record(2, LocalDate.of(2026, 3, 30)), record(3, LocalDate.of(2026, 3, 1)))
        val third = listOf(record(4, LocalDate.of(2026, 2, 9)))
        repo.pages = { offset ->
            when (offset) {
                0 -> page(0, first, total = 5)
                2 -> page(2, second, total = 5)
                else -> page(offset, third, total = 5)
            }
        }
        val viewModel = HistoryViewModel(repo, ReadRecordCache())

        viewModel.loadMore()

        var log = viewModel.state.value.log as LogUi.Loaded
        // Each row once: the list's keys are the reads' ids.
        assertThat(log.sections.flatMap { it.rows }.map { it.id })
            .containsExactly(ReadId("1"), ReadId("2"), ReadId("3")).inOrder()
        assertThat(log.canLoadMore).isTrue()

        viewModel.loadMore()

        // The next page starts after what the server sent, the repeated row counted.
        assertThat(repo.logCalls.map { it.offset }).containsExactly(0, 2, 4).inOrder()
        log = viewModel.state.value.log as LogUi.Loaded
        assertThat(log.sections.flatMap { it.rows }.map { it.id })
            .containsExactly(ReadId("1"), ReadId("2"), ReadId("3"), ReadId("4")).inOrder()
        assertThat(log.canLoadMore).isFalse()
    }

    @Test
    fun `a failing summary after a good one keeps the year chips`() = runTest {
        val viewModel = HistoryViewModel(repo, ReadRecordCache())
        repo.failSummary = true

        viewModel.selectPeriod(HistoryPeriod(2026))

        assertThat(viewModel.state.value.summary).isEqualTo(SummaryUi.Error)
        assertThat(viewModel.state.value.years).containsExactly(2026, 2025).inOrder()
    }

    @Test
    fun `a failing first summary leaves no years`() = runTest {
        repo.failSummary = true

        assertThat(HistoryViewModel(repo, ReadRecordCache()).state.value.years).isEmpty()
    }
}
